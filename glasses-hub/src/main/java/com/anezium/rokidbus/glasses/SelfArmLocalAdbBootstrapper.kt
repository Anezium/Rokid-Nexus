package com.anezium.rokidbus.glasses

import android.content.Context
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
        if (cleanCode.isBlank()) {
            throw IOException("Wireless Debugging pairing code is missing")
        }
        if (pairPort <= 0) {
            throw IOException("Wireless Debugging pairing port is missing")
        }
        if (connectPort <= 0) {
            throw IOException("Wireless Debugging connect port is missing")
        }

        configureKadbCert(appContext)
        pairWirelessDebugging(pairPort, cleanCode)

        val kadb = Kadb(LOCALHOST, connectPort, CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS)
        return try {
            val probe = kadb.shell("echo rokid-nexus")
            if (probe.exitCode != 0 || probe.output.trim() != "rokid-nexus") {
                throw IOException("connect probe failed on 127.0.0.1:$connectPort: ${probe.allOutput.trim()}")
            }
            val watchdogScript = appContext.assets.open(SelfArmConstants.WATCHDOG_ASSET)
                .bufferedReader()
                .use { it.readText() }
            val bootstrap = kadb.shell(
                SelfArmSessionCommand.build(
                    watchdogScript = watchdogScript,
                    restartWatchdog = true,
                ),
            )
            if (bootstrap.exitCode != 0 || !SelfArmSessionCommand.succeeded(bootstrap.output)) {
                throw IOException(
                    "bootstrap shell failed with exit ${bootstrap.exitCode}: " +
                        (bootstrap.errorOutput + bootstrap.output).trim(),
                )
            }
            val marker = bootstrap.output
                .lineSequence()
                .firstOrNull { it.contains("ROKID_NEXUS_INSTALL_RESULT") }
                .orEmpty()
            val posture = SelfArmNetworkPostureVerifier.awaitSafe(appContext)
            markBootstrapComplete(appContext)
            SelfArmOnboardingStore.recordNetworkPosture(appContext, posture)
            Log.i(TAG, "self-pair bootstrap success marker=${marker.ifBlank { "no-marker" }}")
            Log.i(
                TAG,
                "legacy ADB disabled; wirelessDebuggingTls=${posture.wirelessDebuggingEnabled}",
            )
            BootstrapResult(
                pairHost = LOCALHOST,
                pairPort = pairPort,
                connectHost = LOCALHOST,
                connectPort = connectPort,
                output = bootstrap.output,
            )
        } catch (exception: RuntimeException) {
            throw IOException(
                "connect to 127.0.0.1:$connectPort failed: " +
                    exception.message.orEmpty().ifBlank { exception::class.java.simpleName },
                exception,
            )
        } finally {
            runCatching { kadb.close() }
        }
    }

    private fun pairWirelessDebugging(port: Int, code: String) {
        val done = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val pairingThread = Thread {
            try {
                runBlocking {
                    Kadb.pair(LOCALHOST, port, code, "Rokid Nexus")
                }
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
        if (cause != null) {
            throw IOException("Wireless Debugging self-pairing failed: ${shortMessage(cause)}", cause)
        }
        Log.i(TAG, "self-pair KADB success host=$LOCALHOST port=$port")
    }

    private fun shortMessage(throwable: Throwable): String =
        throwable.message.orEmpty().trim().ifBlank { throwable::class.java.simpleName }

    companion object {
        private const val TAG = "NexusWirelessSetup"
        private const val LOCALHOST = "127.0.0.1"
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SHELL_TIMEOUT_MS = 15_000
        private const val PAIRING_TIMEOUT_MS = 12_000L
        private const val PREFS_NAME = "selfarm_wireless"
        const val BOOTSTRAP_COMPLETE_KEY = "wireless_bootstrap_complete"
        private val CERT_LOCK = Any()
        private var kadbCertConfigured = false

        fun isBootstrapComplete(context: Context): Boolean =
            context.applicationContext
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(BOOTSTRAP_COMPLETE_KEY, false)

        fun configureKadbCert(context: Context) {
            synchronized(CERT_LOCK) {
                if (kadbCertConfigured) return
                val privateKey = File(
                    File(context.applicationContext.filesDir, "kadb-tls"),
                    "adbkey.pem",
                )
                val dir = privateKey.parentFile
                if (dir != null && !dir.isDirectory && !dir.mkdirs() && !dir.isDirectory) {
                    throw IllegalStateException("Could not create KADB key directory")
                }
                KadbCert.configure(
                    OkioFilePrivateKeyStore(
                        okioPath(privateKey.absolutePath),
                        FileSystem.SYSTEM,
                    ),
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
