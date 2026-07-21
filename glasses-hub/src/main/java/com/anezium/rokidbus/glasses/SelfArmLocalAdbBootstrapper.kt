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

    fun bootstrap(pairPort: Int, pairingCode: String, connectPort: Int): BootstrapResult {
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
            markBootstrapComplete(appContext)
            SelfArmOnboardingStore.recordNetworkPosture(appContext, result.posture)
            Log.i(
                TAG,
                "self-pair bootstrap success port=${result.port} restartedAdbd=${result.restartedAdbd}",
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

    private fun pairWirelessDebugging(port: Int, code: String) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pairingThread = Thread {
            try {
                runBlocking { Kadb.pair(LOCALHOST, port, code, "Rokid Nexus") }
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
            )
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

        private fun markBootstrapComplete(context: Context) {
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
