package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import java.io.File
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class SelfArmSessionUnavailableException(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal class SelfArmLocalAdbBootstrapper(
    context: Context,
) {
    private val appContext = context.applicationContext

    data class BootstrapResult(
        val pairHost: String,
        val pairPort: Int,
        val connectHost: String,
        val connectPort: Int,
        val output: String,
    )

    fun bootstrap(
        pairPort: Int,
        pairingCode: String,
        connectPort: Int,
        sessionId: String? = null,
    ): BootstrapResult {
        requireCurrentSession(sessionId)
        val cleanCode = pairingCode.trim()
        if (cleanCode.isBlank()) throw IOException("Wireless Debugging pairing code is missing")
        if (pairPort <= 0) throw IOException("Wireless Debugging pairing port is missing")
        if (connectPort <= 0) throw IOException("Wireless Debugging connect port is missing")

        configureKadbCert(appContext)
        pairWirelessDebugging(pairPort, cleanCode)
        val initialSession = openPairedSessionWithRetry(
            oldAdbdPid = null,
            preferredPort = connectPort,
            timeoutMs = INITIAL_SESSION_TIMEOUT_MS,
        )
        return try {
            val watchdogScript = appContext.assets.open(SelfArmConstants.WATCHDOG_ASSET)
                .bufferedReader()
                .use { it.readText() }
                .replace("\r\n", "\n")
                .replace("\r", "\n")
            val bridgeScript = SelfArmController.ensureInternalCommandBridge(appContext).readText()
            val result = runSequence(
                context = appContext,
                initialSession = initialSession,
                watchdogScript = watchdogScript,
                bridgeScript = bridgeScript,
                restartWatchdog = true,
            )
            requireCurrentSession(sessionId)
            recordBootstrapComplete(appContext, sessionId)
            SelfArmOnboardingStore.recordNetworkPosture(appContext, result.posture, sessionId)
            Log.i(
                TAG,
                "self-pair bootstrap success port=${result.port} " +
                    "restartedAdbd=${result.restartedAdbd} bridge=${result.bridgeRunning}",
            )
            BootstrapResult(
                pairHost = LOCALHOST,
                pairPort = pairPort,
                connectHost = LOCALHOST,
                connectPort = result.port,
                output = result.output,
            )
        } catch (exception: Exception) {
            runCatching { initialSession.close() }
            if (exception is IOException) throw exception
            throw IOException(
                "connect to 127.0.0.1:$connectPort failed: " +
                    exception.message.orEmpty().ifBlank { exception::class.java.simpleName },
                exception,
            )
        }
    }

    private fun requireCurrentSession(sessionId: String?) {
        if (sessionId != null &&
            !SelfArmOnboardingStore.isCurrentSession(appContext, sessionId)
        ) {
            throw InterruptedIOException("Wireless Debugging setup session was superseded")
        }
    }

    private fun pairWirelessDebugging(port: Int, code: String) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pairingThread = Thread {
            try {
                // The name must not contain a space. adbd stores a paired key as
                // "<base64> <name>" in a single field and splits it back on whitespace, so a name
                // with a space makes the entry unreadable: adbd logs `Invalid base64 key` and then
                // refuses *every* TLS handshake, poisoning the whole store. The symptom is that
                // pairing works and the session opens once, while every later re-arm fails with
                // CERTIFICATE_VERIFY_FAILED. Keep the host-style single token used elsewhere.
                runBlocking { Kadb.pair(LOCALHOST, port, code, PAIRING_NAME) }
            } catch (throwable: Throwable) {
                failure.set(throwable)
            } finally {
                done.countDown()
            }
        }.apply {
            name = "nexus-local-adb-pair"
            isDaemon = true
        }
        Log.i(TAG, "self-pair KADB start host=$LOCALHOST port=$port codeLen=${code.length}")
        pairingThread.start()
        try {
            if (!done.await(PAIRING_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                pairingThread.interrupt()
                throw IOException("Wireless Debugging self-pairing timed out")
            }
        } catch (exception: InterruptedException) {
            pairingThread.interrupt()
            Thread.currentThread().interrupt()
            throw IOException("Wireless Debugging self-pairing was interrupted", exception)
        }
        val cause = failure.get()
        if (cause is Error) throw cause
        if (cause != null) throw IOException("Wireless Debugging self-pairing failed: ${shortMessage(cause)}", cause)
        Log.i(TAG, "self-pair KADB success host=$LOCALHOST port=$port")
    }

    private fun shortMessage(throwable: Throwable): String =
        throwable.message.orEmpty().trim().ifBlank { throwable::class.java.simpleName }

    companion object {
        private const val TAG = "NexusWirelessSetup"
        /** Single token by construction — see [pairWirelessDebugging] for why a space breaks adbd. */
        private const val PAIRING_NAME = "rokid-nexus@glasses"
        private const val LOCALHOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val SHELL_TIMEOUT_MS = 8_000
        private const val PAIRING_TIMEOUT_MS = 12_000L
        private const val INITIAL_SESSION_TIMEOUT_MS = 8_000L
        private const val RECONNECT_TIMEOUT_MS = 20_000L
        private const val RETRY_INTERVAL_MS = 250L
        private const val PREFS_NAME = "selfarm_wireless"
        const val BOOTSTRAP_COMPLETE_KEY = "wireless_bootstrap_complete"
        private val CERT_LOCK = Any()
        private var kadbCertConfigured = false
        private val certificateRejections = java.util.concurrent.atomic.AtomicInteger(0)
        private const val MAX_BACKGROUND_SELF_PAIRING_ATTEMPTS = 6
        private val selfPairingGate = SelfArmSelfPairingGate(MAX_BACKGROUND_SELF_PAIRING_ATTEMPTS)
        private val selfPairingLock = Any()
        private const val CERTIFICATE_REJECTIONS_BEFORE_RESET = 2
        private val CERTIFICATE_REJECTION_MARKERS = listOf(
            "CERTIFICATE_VERIFY_FAILED",
            "CERTIFICATE_UNKNOWN",
            "SSLV3_ALERT",
            "Failure in SSL library",
        )

        fun isBootstrapComplete(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(BOOTSTRAP_COMPLETE_KEY, false)

        fun openPairedSession(context: Context): SelfArmShellSession {
            val appContext = context.applicationContext
            configureKadbCert(appContext)
            return openPairedSessionWithRetry(
                oldAdbdPid = null,
                preferredPort = null,
                timeoutMs = INITIAL_SESSION_TIMEOUT_MS,
            )
        }

        fun runSequence(
            context: Context,
            initialSession: SelfArmShellSession,
            watchdogScript: String,
            bridgeScript: String,
            restartWatchdog: Boolean,
        ): SelfArmSequenceResult {
            val appContext = context.applicationContext
            return SelfArmArmSequence.run(
                initialSession = initialSession,
                watchdogScript = watchdogScript,
                bridgeScript = bridgeScript,
                restartWatchdog = restartWatchdog,
                operations = SelfArmSequenceOperations(
                    awaitRestartDecision = {
                        SelfArmNetworkPostureVerifier.awaitRestartDecision(appContext)
                    },
                    reconnectTls = { oldAdbdPid, preferredPort ->
                        openPairedSessionWithRetry(oldAdbdPid, preferredPort, RECONNECT_TIMEOUT_MS)
                    },
                    awaitSafe = { SelfArmNetworkPostureVerifier.awaitSafe(appContext) },
                    log = { Log.i(TAG, it) },
                ),
            ).also { result ->
                // Every successful arm — first bootstrap or maintenance re-arm — funnels through
                // here, which makes this the one spot the arm epoch is always put on record. That
                // epoch is the app's only readable proof of which boot the bridge was alive on:
                // the bridge's own heartbeat file is shell-owned and invisible to the app.
                SelfArmBridgeLivenessStore.recordArmed(appContext, result.port, result.bridgeRunning)
            }
        }

        private fun openExactPairedSession(port: Int, probeMarker: String): SelfArmShellSession {
            val session = KadbShellSession(port, Kadb(LOCALHOST, port, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS))
            return try {
                val probe = session.shell("echo $probeMarker")
                if (probe.exitCode != 0 || probe.output.trim() != probeMarker) {
                    throw IOException("paired TLS probe failed on 127.0.0.1:$port: ${allOutput(probe)}")
                }
                session
            } catch (throwable: Throwable) {
                runCatching { session.close() }
                throw throwable
            }
        }

        private fun openPairedSessionWithRetry(
            oldAdbdPid: String?,
            preferredPort: Int?,
            timeoutMs: Long,
        ): SelfArmShellSession {
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            var lastFailure: Throwable? = null
            var attempt = 0
            do {
                attempt += 1
                val discoveredPort = SelfArmWirelessAdbController.readWirelessPort()
                val ports = listOfNotNull(
                    discoveredPort.takeIf { it > 0 },
                    preferredPort?.takeIf { it > 0 },
                ).distinct()
                for (port in ports) {
                    var session: SelfArmShellSession? = null
                    try {
                        session = openExactPairedSession(port, "rokid-nexus-maintenance")
                        val pidResult = session.shell("pidof adbd")
                        val currentPids = pidSet(pidResult.output)
                        val oldPids = pidSet(oldAdbdPid.orEmpty())
                        if (pidResult.exitCode == 0 && currentPids.isNotEmpty() &&
                            (oldPids.isEmpty() || currentPids.intersect(oldPids).isEmpty())
                        ) {
                            val readySession = session
                            session = null
                            Log.i(
                                TAG,
                                "paired TLS ready attempt=$attempt port=$port adbdPid=${currentPids.sorted()} " +
                                    "oldAdbdPid=${oldPids.sorted().ifEmpty { listOf("none") }}",
                            )
                            return readySession
                        }
                        lastFailure = IOException(
                            "paired TLS still belongs to old adbd pid=${currentPids.ifEmpty { setOf("missing") }}",
                        )
                    } catch (throwable: Throwable) {
                        lastFailure = throwable
                        Log.i(TAG, "paired TLS retry attempt=$attempt port=$port: ${shortThrowable(throwable)}")
                    } finally {
                        runCatching { session?.close() }
                    }
                }
                try {
                    Thread.sleep(RETRY_INTERVAL_MS)
                } catch (exception: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Wireless Debugging TLS reconnect was interrupted", exception)
                }
            } while (SystemClock.elapsedRealtime() < deadline)
            throw SelfArmSessionUnavailableException(
                "Wireless Debugging TLS session unavailable after $attempt attempts: " +
                    shortThrowable(lastFailure),
                lastFailure,
            )
        }

        /**
         * True when adbd refused our certificate rather than failing to reach us.
         *
         * Measured on a device in this state: adbd logs `Invalid base64 key` and rejects the
         * handshake with `CERTIFICATE_VERIFY_FAILED`, while a laptop's adb pairs and connects to
         * the very same daemon over the very same port — so the daemon and its key store are
         * healthy and the credential we present is not.
         */
        private fun isCertificateRejection(throwable: Throwable?): Boolean {
            var cause = throwable
            var depth = 0
            while (cause != null && depth < 6) {
                val message = cause.message.orEmpty()
                if (CERTIFICATE_REJECTION_MARKERS.any { message.contains(it, ignoreCase = true) }) {
                    return true
                }
                cause = cause.cause
                depth += 1
            }
            return false
        }

        /**
         * Throws away the pairing identity so the next setup can start clean.
         *
         * A credential adbd will not accept is worse than no credential at all: every re-arm keeps
         * failing, silently, for the life of the install — which is exactly how a glasses unit ends
         * up months behind on its watchdog and bridge scripts with nothing on screen to say so.
         * Dropping it turns that into a state the wearer can act on, because the setup flow asks to
         * pair again.
         */
        private fun resetPairingIdentity(context: Context, reason: String) {
            synchronized(CERT_LOCK) {
                val keyDirectory = File(context.applicationContext.filesDir, "kadb-tls")
                val removed = runCatching {
                    keyDirectory.listFiles()?.count { it.delete() } ?: 0
                }.getOrDefault(0)
                kadbCertConfigured = false
                runCatching {
                    context.applicationContext
                        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(BOOTSTRAP_COMPLETE_KEY, false)
                        .commit()
                }
                Log.w(TAG, "pairing identity reset reason=$reason removedKeyFiles=$removed")
            }
            requestSelfPairing(context)
        }

        /**
         * Asks the glasses to pair themselves again, without anyone touching them.
         *
         * The command bridge covers privileged *commands* without ADB, but it cannot spawn itself:
         * it and the watchdog are shell processes that die with every reboot, and only a privileged
         * session brings them back. So a unit that has lost its own pairing has also lost its
         * autonomy — it stays maintained only for as long as someone runs the manual flow from the
         * phone. Re-pairing is what gives it back.
         */
        internal fun requestSelfPairing(context: Context, owner: Boolean = false): SelfPairingOutcome {
            val appContext = context.applicationContext
            return synchronized(selfPairingLock) {
                // The first background request usually lands before the accessibility service has
                // connected, and that service is what drives the Settings screens. Keep asking on
                // later attempts rather than burning the single try on a moment when nothing
                // could have answered.
                val admission = selfPairingGate.admit(owner) { sessionId ->
                    SelfArmOnboardingStore.isSessionLive(appContext, sessionId)
                }
                admission.releasedSessionId?.let {
                    log("self-pairing latch released reason=session_not_live")
                }
                val outcome = admission.refusal ?: run {
                    val started = runCatching {
                        RokidBusAccessibilityService.requestWirelessBootstrap(appContext, force = true)
                    }.getOrDefault(false)
                    val sessionId = SelfArmOnboardingStore.currentActiveSessionId(appContext)
                    if (started && sessionId.isNotBlank()) {
                        selfPairingGate.onStarted(sessionId)
                        SelfPairingOutcome.STARTED
                    } else {
                        SelfPairingOutcome.SERVICE_UNAVAILABLE
                    }
                }
                // On the repair log tag on purpose: an owner reading why a repair did nothing
                // must find this line next to the repair's own.
                log(
                    "self-pairing requested owner=$owner outcome=${outcome.logValue} " +
                        "backgroundAttempt=${admission.backgroundAttempt}/" +
                        MAX_BACKGROUND_SELF_PAIRING_ATTEMPTS,
                )
                outcome
            }
        }

        /** The run a pairing request started has ended, however it ended: the next may start. */
        internal fun onSetupSessionEnded(sessionId: String) {
            if (selfPairingGate.release(sessionId)) {
                log("self-pairing latch released reason=session_ended")
            }
        }

        /**
         * Called when a re-arm could not open its session. Two consecutive certificate rejections
         * are enough: a transient network problem does not produce them, and waiting longer only
         * means more days of a unit that cannot be maintained.
         */
        internal fun onSessionUnavailable(
            context: Context,
            throwable: Throwable?,
            mayRequestPairing: Boolean = true,
        ) {
            if (!isCertificateRejection(throwable)) {
                certificateRejections.set(0)
                return
            }
            val count = certificateRejections.incrementAndGet()
            Log.w(TAG, "paired TLS certificate rejected count=$count")
            if (count < CERTIFICATE_REJECTIONS_BEFORE_RESET) return
            // Dropping the identity ends in accessibility automation driving Settings, which takes
            // over whatever the wearer is looking at. A background liveness re-arm has no business
            // doing that unannounced: the count is kept, so the next attempt the owner actually
            // asked for escalates immediately.
            if (!mayRequestPairing) {
                Log.w(TAG, "paired TLS certificate rejected; deferring re-pairing to a foreground attempt")
                return
            }
            certificateRejections.set(0)
            resetPairingIdentity(context, "certificate_rejected")
        }

        private fun pidSet(value: String): Set<String> = value
            .trim()
            .replace(',', ' ')
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()

        private fun shortThrowable(throwable: Throwable?): String = throwable
            ?.message
            .orEmpty()
            .trim()
            .ifBlank { throwable?.javaClass?.simpleName ?: "unknown error" }

        private fun allOutput(result: SelfArmShellResult): String =
            (result.errorOutput + result.output).trim().take(500)

        fun configureKadbCert(context: Context) {
            synchronized(CERT_LOCK) {
                if (kadbCertConfigured) return
                val privateKey = File(File(context.applicationContext.filesDir, "kadb-tls"), "adbkey.pem")
                val dir = privateKey.parentFile
                if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                    throw IllegalStateException("Could not create KADB key directory")
                }
                KadbCert.configure(
                    OkioFilePrivateKeyStore(okioPath(privateKey.absolutePath), FileSystem.SYSTEM),
                    KadbCertPolicy(),
                    emptyList(),
                )
                kadbCertConfigured = true
            }
        }

        internal fun recordBootstrapComplete(context: Context, sessionId: String? = null) {
            if (sessionId != null &&
                !SelfArmOnboardingStore.isCurrentSession(context, sessionId)
            ) {
                throw InterruptedIOException("Wireless Debugging setup session was superseded")
            }
            if (!context.applicationContext
                    .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean(BOOTSTRAP_COMPLETE_KEY, true)
                    .commit()
            ) {
                throw IOException("Could not persist Wireless Debugging bootstrap completion")
            }
        }

        private fun okioPath(value: String): Path =
            Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path
    }
}

private class KadbShellSession(
    override val port: Int,
    private val kadb: Kadb,
) : SelfArmShellSession {
    override val transport: SelfArmShellTransport = SelfArmShellTransport.PAIRED_TLS

    override fun shell(command: String): SelfArmShellResult {
        val result = kadb.shell(command)
        return SelfArmShellResult(
            exitCode = result.exitCode,
            output = result.output,
            errorOutput = result.errorOutput,
        )
    }

    override fun close() {
        kadb.close()
    }
}
