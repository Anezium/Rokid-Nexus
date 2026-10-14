package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit
import java.util.UUID

internal sealed interface GlassesManualPairingState {
    data object IDLE : GlassesManualPairingState
    data object WAITING_FOR_CODE : GlassesManualPairingState
    data object PAIRING : GlassesManualPairingState
    data object CONNECTING : GlassesManualPairingState
    data object ARMING : GlassesManualPairingState
    data object DONE : GlassesManualPairingState
    data class ERROR(
        val userMessage: String,
        val supportDetail: String,
    ) : GlassesManualPairingState
}

internal enum class GlassesManualControlAction(val wireValue: String) {
    ENABLE_DEVELOPER_OPTIONS("enable_developer_options"),
    OPEN_DEVELOPER_OPTIONS("open_developer_options"),
    OPEN_WIRELESS_DEBUGGING("open_wireless_debugging"),
    OPEN_PAIRING_DIALOG("open_pairing_dialog"),
    OPEN_ACCESSIBILITY_SETTINGS("open_accessibility_settings"),
    CLOSE("close"),
}

internal fun interface GlassesManualControlSender {
    /**
     * Returns null on success or a non-sensitive transport error code. [abandonPairing] rides a
     * CLOSE only when the owner left the flow, and ends a self-pairing run on the glasses too.
     */
    fun send(
        requestId: String,
        action: GlassesManualControlAction,
        armed: Boolean,
        abandonPairing: Boolean,
    ): String?
}

internal fun interface ManualPairingCancellation {
    fun cancel()
}

internal fun interface ManualPairingTaskExecutor {
    fun submit(task: () -> Unit): ManualPairingCancellation
}

internal fun interface ManualPairingTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): ManualPairingCancellation
}

/**
 * Engine-only phone fallback. It retains neither the typed pairing code nor connection endpoints,
 * and DONE is emitted only after a live glasses capabilities announcement confirms setupComplete.
 */
internal class GlassesManualPairingEngine(
    private val control: GlassesManualControlSender,
    private val backend: GlassesManualPairingBackend,
    private val worker: ManualPairingTaskExecutor,
    private val timeoutScheduler: ManualPairingTimeoutScheduler,
    private val controlAckTimeoutMs: Long = DEFAULT_CONTROL_ACK_TIMEOUT_MS,
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    private val connectPortWaitTimeoutMs: Long = DEFAULT_CONNECT_PORT_WAIT_TIMEOUT_MS,
    private val nextRequestId: () -> String = { UUID.randomUUID().toString() },
    private val logger: (String) -> Unit = {},
    private val shutdownResources: () -> Unit = {},
) : Closeable {
    private enum class WorkStage { PAIRING, CONNECTING, ARMING }
    private data class PendingControl(
        val requestId: String,
        val attempt: Long,
    )

    private val lock = Any()
    private val observers = CopyOnWriteArraySet<(GlassesManualPairingState) -> Unit>()
    private var generation = 0L
    private var activeWork: ManualPairingCancellation? = null
    private var controlAckTimeout: ManualPairingCancellation? = null
    private var pendingControl: PendingControl? = null
    private var confirmationTimeout: ManualPairingCancellation? = null
    private var awaitingGlassesConfirmation = false
    private var awaitGlassesConfirmationForAttempt = true

    @Volatile
    var state: GlassesManualPairingState = GlassesManualPairingState.IDLE
        private set

    fun observe(observer: (GlassesManualPairingState) -> Unit): Closeable {
        observers += observer
        observer(state)
        return Closeable { observers -= observer }
    }

    fun start(awaitGlassesConfirmation: Boolean = true): Boolean {
        val attempt = synchronized(lock) {
            generation += 1L
            activeWork?.cancel()
            activeWork = null
            controlAckTimeout?.cancel()
            controlAckTimeout = null
            pendingControl = null
            confirmationTimeout?.cancel()
            confirmationTimeout = null
            awaitingGlassesConfirmation = false
            awaitGlassesConfirmationForAttempt = awaitGlassesConfirmation
            resetReportedConnectPortLocked()
            generation
        }
        transition(attempt, GlassesManualPairingState.WAITING_FOR_CODE)
        return true
    }

    fun openAccessibilitySettings(): Boolean =
        requestSettings(GlassesManualControlAction.OPEN_ACCESSIBILITY_SETTINGS)

    fun enableDeveloperOptions(): Boolean =
        requestSettings(GlassesManualControlAction.ENABLE_DEVELOPER_OPTIONS)

    fun openDeveloperOptions(): Boolean = requestSettings(GlassesManualControlAction.OPEN_DEVELOPER_OPTIONS)

    fun showWirelessDebugging(): Boolean = requestSettings(GlassesManualControlAction.OPEN_WIRELESS_DEBUGGING)

    private fun requestSettings(action: GlassesManualControlAction): Boolean {
        // Opening a screen on the lens is navigation, not a pairing step. Gating it on
        // WAITING_FOR_CODE meant every button on the manual screen fired before the engine had
        // been started, returned false, and was reported to the owner as a lost link — while the
        // link was up the whole time. IDLE is precisely when this help is needed.
        if (state != GlassesManualPairingState.IDLE &&
            state != GlassesManualPairingState.WAITING_FOR_CODE
        ) {
            return false
        }
        val attempt = synchronized(lock) { generation }
        return requestManualControl(attempt, action)
    }

    /** Correlates a glasses acknowledgement or `/error` with the active settings request. */
    /**
     * The connect port the glasses report over the bus.
     *
     * mDNS discovery is the documented way to find it and it simply does not survive a router that
     * will not forward multicast — the pairing succeeds and then the setup dies looking for a
     * service it cannot see. The glasses know the port; taking their word for it removes the
     * network from the equation entirely.
     */
    @Volatile
    private var reportedConnectPort: Int = 0
    private var connectPortSignal = CountDownLatch(1)
    private var connectPortReportRequestId: String? = null

    fun onPhoneAssistedConnectPort(port: Int) {
        acceptGlassesConnectPort(port)
    }

    /** Accepts a pushed port only when it belongs to the acknowledged live manual request. */
    fun onGlassesConnectPort(requestId: String, port: Int): Boolean = synchronized(lock) {
        if (requestId.isBlank() || requestId != connectPortReportRequestId || port !in 1..65535) {
            false
        } else {
            reportedConnectPort = port
            connectPortSignal.countDown()
            true
        }
    }

    fun onManualControlResponse(requestId: String, errorCode: String?): Boolean {
        val pending = synchronized(lock) {
            val current = pendingControl
            if (current?.requestId != requestId) {
                null
            } else {
                pendingControl = null
                controlAckTimeout?.cancel()
                controlAckTimeout = null
                if (errorCode.isNullOrBlank() && current.attempt == generation) {
                    connectPortReportRequestId = requestId
                }
                current
            }
        } ?: return false

        if (!errorCode.isNullOrBlank()) {
            fail(
                pending.attempt,
                manualControlUserMessage(errorCode),
                IOException("Manual setup control rejected: $errorCode"),
            )
        }
        return true
    }

    fun submit(
        host: String,
        pairPort: Int,
        code: String,
        allowStartFromIdle: Boolean = false,
    ): Boolean {
        val cleanHost = host.trim()
        var ephemeralCode = code.trim()
        if (cleanHost.isBlank() || cleanHost.length > 255 ||
            pairPort !in 1..65535 || !PAIRING_CODE.matches(ephemeralCode)
        ) {
            ephemeralCode = ""
            return false
        }
        val attempt = synchronized(lock) {
            when (state) {
                GlassesManualPairingState.IDLE -> {
                    // The typed form is the recourse when automation never ran, so for it IDLE is
                    // a legitimate starting state — but only the wizard opts in. For every other
                    // caller IDLE keeps meaning "no authorized attempt": the phone-assisted path
                    // must not resurrect an offer that a cancel() already tore down.
                    if (!allowStartFromIdle) {
                        null
                    } else {
                        // Same reset as start(), except the glasses-reported connect port is
                        // kept: every manual-navigation ack carries it, and wiping it would
                        // force the mDNS discovery fallback that dies on multicast-blocking
                        // routers.
                        generation += 1L
                        activeWork?.cancel()
                        activeWork = null
                        controlAckTimeout?.cancel()
                        controlAckTimeout = null
                        pendingControl = null
                        confirmationTimeout?.cancel()
                        confirmationTimeout = null
                        awaitingGlassesConfirmation = false
                        awaitGlassesConfirmationForAttempt = true
                        generation
                    }
                }
                GlassesManualPairingState.WAITING_FOR_CODE -> generation
                else -> null
            }
        }
        if (attempt == null) {
            ephemeralCode = ""
            return false
        }
        transition(attempt, GlassesManualPairingState.PAIRING)
        return runAttempt(attempt) {
            var stage = WorkStage.PAIRING
            var session: ManualAdbSession? = null
            try {
                try {
                    backend.pair(cleanHost, pairPort, ephemeralCode)
                } finally {
                    // The immutable caller string cannot be wiped, but the engine never stores it and this
                    // captured reference is cleared immediately after the sole pairing call.
                    ephemeralCode = ""
                }
                if (!isCurrent(attempt)) return@runAttempt

                stage = WorkStage.CONNECTING
                transition(attempt, GlassesManualPairingState.CONNECTING)
                val known = awaitGlassesConnectPort(attempt)
                if (!isCurrent(attempt)) return@runAttempt
                val endpoint = if (known in 1..65535) {
                    GlassesManualAdbEndpoint(cleanHost, known)
                } else {
                    backend.discoverConnectEndpoint(cleanHost)
                }
                session = backend.connect(endpoint)
                if (!isCurrent(attempt)) {
                    session.close()
                    session = null
                    return@runAttempt
                }

                stage = WorkStage.ARMING
                transition(attempt, GlassesManualPairingState.ARMING)
                val armSession = session
                session = null // The arm runner owns transport cleanup, including reconnects.
                backend.arm(armSession, cleanHost)
                if (!isCurrent(attempt)) return@runAttempt
                if (shouldAwaitGlassesConfirmation(attempt)) {
                    awaitGlassesConfirmation(attempt)
                } else {
                    completeWithoutGlassesConfirmation(attempt)
                }
            } catch (failure: Throwable) {
                ephemeralCode = ""
                runCatching { session?.close() }
                fail(attempt, userMessage(stage), failure)
            }
        }
    }

    /**
     * Hands the pairing work to the executor without letting a dead engine crash the caller. A
     * surviving activity can still hold this engine after close() shut the executor down; before
     * IDLE submissions were accepted that call could never happen, now it must fail as an attempt,
     * not as an uncaught RejectedExecutionException on the UI thread.
     */
    private fun runAttempt(attempt: Long, work: () -> Unit): Boolean {
        val task = try {
            worker.submit(work)
        } catch (rejected: RejectedExecutionException) {
            fail(attempt, userMessage(WorkStage.PAIRING), rejected)
            return true
        }
        synchronized(lock) {
            if (attempt == generation) activeWork = task else task.cancel()
        }
        return true
    }

    /**
     * [abandonPairing] is the owner walking away: the glasses also end a self-pairing they are
     * running, so it cannot resume later as a takeover nobody is waiting for.
     */
    fun cancel(abandonPairing: Boolean = true) {
        val shouldNotify = synchronized(lock) {
            generation += 1L
            activeWork?.cancel()
            activeWork = null
            controlAckTimeout?.cancel()
            controlAckTimeout = null
            pendingControl = null
            confirmationTimeout?.cancel()
            confirmationTimeout = null
            awaitingGlassesConfirmation = false
            resetReportedConnectPortLocked()
            state != GlassesManualPairingState.IDLE
        }
        sendClose(armed = false, abandonPairing = abandonPairing)
        if (shouldNotify) transitionUnconditionally(GlassesManualPairingState.IDLE)
    }

    fun onGlassesSetupReported(setupComplete: Boolean) {
        if (!setupComplete) return
        val finish = synchronized(lock) {
            if (!awaitingGlassesConfirmation || state != GlassesManualPairingState.ARMING) {
                false
            } else {
                awaitingGlassesConfirmation = false
                confirmationTimeout?.cancel()
                confirmationTimeout = null
                activeWork = null
                resetReportedConnectPortLocked()
                true
            }
        }
        if (finish) transitionUnconditionally(GlassesManualPairingState.DONE)
    }

    override fun close() {
        // The hub going away is not the owner cancelling anything.
        cancel(abandonPairing = false)
        observers.clear()
        shutdownResources()
    }

    private fun awaitGlassesConfirmation(attempt: Long) {
        synchronized(lock) {
            if (attempt != generation) return
            awaitingGlassesConfirmation = true
            activeWork = null
        }
        val closeError = sendClose(armed = true)
        if (closeError != null) {
            fail(
                attempt,
                "Secure setup finished, but the glasses could not verify it.",
                IOException("Manual setup confirmation failed: $closeError"),
            )
            return
        }
        val scheduled = timeoutScheduler.schedule(confirmationTimeoutMs) {
            fail(
                attempt,
                "The glasses did not confirm secure setup. Retry manual pairing.",
                IOException("Glasses setupComplete confirmation timed out"),
            )
        }
        synchronized(lock) {
            if (attempt == generation && awaitingGlassesConfirmation) {
                confirmationTimeout = scheduled
            } else {
                scheduled.cancel()
            }
        }
    }

    private fun shouldAwaitGlassesConfirmation(attempt: Long): Boolean =
        synchronized(lock) {
            attempt == generation && awaitGlassesConfirmationForAttempt
        }

    private fun completeWithoutGlassesConfirmation(attempt: Long) {
        val completed = synchronized(lock) {
            if (attempt != generation) {
                false
            } else {
                activeWork = null
                resetReportedConnectPortLocked()
                true
            }
        }
        if (completed) transition(attempt, GlassesManualPairingState.DONE)
    }

    private fun fail(attempt: Long, userMessage: String, failure: Throwable) {
        val detail = ManualPairingSupportDiagnostic.causeChain(failure)
        val accepted = synchronized(lock) {
            if (attempt != generation) {
                false
            } else {
                activeWork = null
                controlAckTimeout?.cancel()
                controlAckTimeout = null
                pendingControl = null
                awaitingGlassesConfirmation = false
                confirmationTimeout?.cancel()
                confirmationTimeout = null
                resetReportedConnectPortLocked()
                true
            }
        }
        if (!accepted) return
        sendClose(armed = false)
        logger("manual self-arm failed: $detail")
        transitionUnconditionally(GlassesManualPairingState.ERROR(userMessage, detail))
    }

    private fun transition(attempt: Long, next: GlassesManualPairingState) {
        synchronized(lock) {
            if (attempt != generation) return
            state = next
        }
        observers.forEach { observer -> observer(next) }
    }

    private fun transitionUnconditionally(next: GlassesManualPairingState) {
        synchronized(lock) { state = next }
        observers.forEach { observer -> observer(next) }
    }

    private fun isCurrent(attempt: Long): Boolean = synchronized(lock) { attempt == generation }

    private fun acceptGlassesConnectPort(port: Int) {
        if (port !in 1..65535) return
        synchronized(lock) {
            reportedConnectPort = port
            connectPortSignal.countDown()
        }
    }

    private fun awaitGlassesConnectPort(attempt: Long): Int {
        val signal = synchronized(lock) {
            if (attempt != generation || reportedConnectPort in 1..65535) {
                return@synchronized null
            }
            connectPortSignal
        }
        if (signal != null && connectPortWaitTimeoutMs > 0L) {
            try {
                signal.await(connectPortWaitTimeoutMs, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
        return synchronized(lock) {
            reportedConnectPort.takeIf { attempt == generation && it in 1..65535 } ?: 0
        }
    }

    private fun resetReportedConnectPortLocked() {
        reportedConnectPort = 0
        connectPortReportRequestId = null
        connectPortSignal.countDown()
        connectPortSignal = CountDownLatch(1)
    }

    private fun requestManualControl(
        attempt: Long,
        action: GlassesManualControlAction,
    ): Boolean {
        val requestId = nextRequestId()
        val pending = PendingControl(requestId, attempt)
        val accepted = synchronized(lock) {
            if (attempt != generation || pendingControl != null) {
                false
            } else {
                pendingControl = pending
                true
            }
        }
        if (!accepted) return false

        val scheduled = timeoutScheduler.schedule(controlAckTimeoutMs) {
            onManualControlResponse(requestId, CONTROL_ACK_TIMEOUT)
        }
        synchronized(lock) {
            if (pendingControl?.requestId == requestId) {
                controlAckTimeout = scheduled
            } else {
                scheduled.cancel()
            }
        }

        val error = control.send(
            requestId,
            action,
            false,
            false,
        )
        if (error != null) {
            onManualControlResponse(requestId, error)
            return false
        }
        return true
    }

    private fun sendClose(armed: Boolean, abandonPairing: Boolean = false): String? = control.send(
        nextRequestId(),
        GlassesManualControlAction.CLOSE,
        armed,
        abandonPairing,
    )

    private fun manualControlUserMessage(errorCode: String): String = when (errorCode) {
        "NO_LOCAL_CLIENT", "INVALID_ACTION" ->
            "Manual setup needs a newer Nexus app on the glasses. Update the glasses app and try again."
        "ACCESSIBILITY_UNAVAILABLE" ->
            "Nexus can't move through the glasses menus. Run step 1 to turn on Accessibility " +
                "access, then try again."
        "DEVELOPER_OPTIONS_DISABLED" ->
            "Developer options are still disabled on the glasses. Turn on ADB debugging in the " +
                "Hi Rokid app (developer section), then run step 2 and wait for it to finish."
        "DEVELOPER_OPTIONS_ENABLE_FAILED" ->
            "Nexus could not enable Developer options. Run step 2 again while wearing the glasses."
        "SETTINGS_UNAVAILABLE" ->
            "Android Settings could not open the requested page on the glasses."
        "WIRELESS_DEBUGGING_UNAVAILABLE" ->
            "Nexus could not turn on glasses Wi-Fi or open Wireless debugging. Keep the glasses on and retry step 4."
        CONTROL_ACK_TIMEOUT ->
            "The glasses did not confirm manual setup. Check the connection or update the glasses app."
        else -> "Could not open the requested settings on the glasses."
    }

    private fun userMessage(stage: WorkStage): String = when (stage) {
        WorkStage.PAIRING -> "The pairing code was not accepted. Check the code and try again."
        WorkStage.CONNECTING ->
            "Paired, but the phone could not open the glasses shell. Keep both devices on the same Wi-Fi."
        WorkStage.ARMING -> "The glasses shell connected, but secure setup did not finish. Retry manual pairing."
    }

    companion object {
        private const val DEFAULT_CONTROL_ACK_TIMEOUT_MS = 60_000L
        private const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 30_000L
        private const val DEFAULT_CONNECT_PORT_WAIT_TIMEOUT_MS = 3_000L
        private const val CONTROL_ACK_TIMEOUT = "CONTROL_ACK_TIMEOUT"
        private val PAIRING_CODE = Regex("""\d{6}""")

        fun create(
            context: Context,
            control: GlassesManualControlSender,
            logger: (String) -> Unit = {},
        ): GlassesManualPairingEngine {
            val workerExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "RokidNexusManualPairing").apply { isDaemon = true }
            }
            val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "RokidNexusManualPairingTimeout").apply { isDaemon = true }
            }
            return GlassesManualPairingEngine(
                control = control,
                backend = AndroidGlassesManualPairingBackend(context.applicationContext),
                worker = ManualPairingTaskExecutor { task ->
                    val future = workerExecutor.submit(task)
                    ManualPairingCancellation { future.cancel(true) }
                },
                timeoutScheduler = ManualPairingTimeoutScheduler { delayMs, task ->
                    val future = scheduler.schedule(task, delayMs, TimeUnit.MILLISECONDS)
                    ManualPairingCancellation { future.cancel(false) }
                },
                logger = logger,
                shutdownResources = {
                    workerExecutor.shutdownNow()
                    scheduler.shutdownNow()
                },
            )
        }
    }
}

internal object ManualPairingSupportDiagnostic {
    private const val MAX_LENGTH = 96
    private val STANDALONE_PAIRING_CODE = Regex("""\b\d{6}\b""")
    private val IPV4_LITERAL = Regex("""\d+\.\d+\.\d+\.\d+""")
    private val WHITESPACE = Regex("""\s+""")

    fun sanitize(value: String): String = value
        .replace(STANDALONE_PAIRING_CODE, "......")
        .replace(IPV4_LITERAL, "")
        .replace(WHITESPACE, " ")
        .trim()
        .take(MAX_LENGTH)

    fun causeChain(throwable: Throwable): String {
        val parts = mutableListOf<String>()
        val seen = HashSet<Throwable>()
        var current: Throwable? = throwable
        while (current != null && seen.add(current) && parts.size < 5) {
            val name = current.javaClass.simpleName.ifBlank { "Throwable" }
            val message = current.message.orEmpty().trim()
            val piece = if (message.isBlank()) name else "$name: $message"
            if (parts.lastOrNull() != piece) parts += piece
            current = current.cause
        }
        return sanitize(parts.joinToString(" <- ").ifBlank { "manual pairing failed" })
    }
}
