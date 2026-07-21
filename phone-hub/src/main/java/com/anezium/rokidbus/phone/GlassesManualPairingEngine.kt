package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal sealed interface GlassesManualPairingState {
    data object IDLE : GlassesManualPairingState
    data object OPENING_SCREEN : GlassesManualPairingState
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
    OPEN_DEVELOPER_OPTIONS("open_developer_options"),
    OPEN_WIRELESS_DEBUGGING("open_wireless_debugging"),
    OPEN_PAIRING_DIALOG("open_pairing_dialog"),
    CLOSE("close"),
}

internal fun interface GlassesManualControlSender {
    /** Returns null on success or a non-sensitive transport error code. */
    fun send(action: GlassesManualControlAction, armed: Boolean): String?
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
    private val confirmationTimeoutMs: Long = DEFAULT_CONFIRMATION_TIMEOUT_MS,
    private val logger: (String) -> Unit = {},
    private val shutdownResources: () -> Unit = {},
) : Closeable {
    private enum class WorkStage { PAIRING, CONNECTING, ARMING }

    private val lock = Any()
    private val observers = CopyOnWriteArraySet<(GlassesManualPairingState) -> Unit>()
    private var generation = 0L
    private var activeWork: ManualPairingCancellation? = null
    private var confirmationTimeout: ManualPairingCancellation? = null
    private var awaitingGlassesConfirmation = false

    @Volatile
    var state: GlassesManualPairingState = GlassesManualPairingState.IDLE
        private set

    fun observe(observer: (GlassesManualPairingState) -> Unit): Closeable {
        observers += observer
        observer(state)
        return Closeable { observers -= observer }
    }

    fun start(): Boolean {
        val attempt = synchronized(lock) {
            generation += 1L
            activeWork?.cancel()
            activeWork = null
            confirmationTimeout?.cancel()
            confirmationTimeout = null
            awaitingGlassesConfirmation = false
            generation
        }
        transition(attempt, GlassesManualPairingState.OPENING_SCREEN)
        for (action in OPENING_ACTIONS) {
            val error = control.send(action, false)
            if (error != null) {
                fail(
                    attempt,
                    "Could not open Wireless Debugging on the glasses.",
                    IOException("Manual setup control failed: $error"),
                )
                return false
            }
        }
        transition(attempt, GlassesManualPairingState.WAITING_FOR_CODE)
        return true
    }

    fun submit(host: String, pairPort: Int, code: String): Boolean {
        val cleanHost = host.trim()
        var ephemeralCode = code.trim()
        if (state != GlassesManualPairingState.WAITING_FOR_CODE ||
            cleanHost.isBlank() || cleanHost.length > 255 ||
            pairPort !in 1..65535 || !PAIRING_CODE.matches(ephemeralCode)
        ) {
            ephemeralCode = ""
            return false
        }
        val attempt = synchronized(lock) { generation }
        transition(attempt, GlassesManualPairingState.PAIRING)
        val task = worker.submit {
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
                if (!isCurrent(attempt)) return@submit

                stage = WorkStage.CONNECTING
                transition(attempt, GlassesManualPairingState.CONNECTING)
                val endpoint = backend.discoverConnectEndpoint(cleanHost)
                session = backend.connect(endpoint)
                if (!isCurrent(attempt)) {
                    session.close()
                    session = null
                    return@submit
                }

                stage = WorkStage.ARMING
                transition(attempt, GlassesManualPairingState.ARMING)
                val armSession = session
                session = null // The arm runner owns transport cleanup, including reconnects.
                backend.arm(armSession, cleanHost)
                if (!isCurrent(attempt)) return@submit
                awaitGlassesConfirmation(attempt)
            } catch (failure: Throwable) {
                ephemeralCode = ""
                runCatching { session?.close() }
                fail(attempt, userMessage(stage), failure)
            }
        }
        synchronized(lock) {
            if (attempt == generation) activeWork = task else task.cancel()
        }
        return true
    }

    fun cancel() {
        val shouldNotify = synchronized(lock) {
            generation += 1L
            activeWork?.cancel()
            activeWork = null
            confirmationTimeout?.cancel()
            confirmationTimeout = null
            awaitingGlassesConfirmation = false
            state != GlassesManualPairingState.IDLE
        }
        control.send(GlassesManualControlAction.CLOSE, false)
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
                true
            }
        }
        if (finish) transitionUnconditionally(GlassesManualPairingState.DONE)
    }

    override fun close() {
        cancel()
        observers.clear()
        shutdownResources()
    }

    private fun awaitGlassesConfirmation(attempt: Long) {
        synchronized(lock) {
            if (attempt != generation) return
            awaitingGlassesConfirmation = true
            activeWork = null
        }
        val closeError = control.send(GlassesManualControlAction.CLOSE, true)
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

    private fun fail(attempt: Long, userMessage: String, failure: Throwable) {
        val detail = ManualPairingSupportDiagnostic.causeChain(failure)
        val accepted = synchronized(lock) {
            if (attempt != generation) {
                false
            } else {
                activeWork = null
                awaitingGlassesConfirmation = false
                confirmationTimeout?.cancel()
                confirmationTimeout = null
                true
            }
        }
        if (!accepted) return
        control.send(GlassesManualControlAction.CLOSE, false)
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

    private fun userMessage(stage: WorkStage): String = when (stage) {
        WorkStage.PAIRING -> "The pairing code was not accepted. Check the code and try again."
        WorkStage.CONNECTING ->
            "Paired, but the phone could not open the glasses shell. Keep both devices on the same Wi-Fi."
        WorkStage.ARMING -> "The glasses shell connected, but secure setup did not finish. Retry manual pairing."
    }

    companion object {
        private const val DEFAULT_CONFIRMATION_TIMEOUT_MS = 30_000L
        private val PAIRING_CODE = Regex("""\d{6}""")
        private val OPENING_ACTIONS = listOf(
            GlassesManualControlAction.OPEN_DEVELOPER_OPTIONS,
            GlassesManualControlAction.OPEN_WIRELESS_DEBUGGING,
            GlassesManualControlAction.OPEN_PAIRING_DIALOG,
        )

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
