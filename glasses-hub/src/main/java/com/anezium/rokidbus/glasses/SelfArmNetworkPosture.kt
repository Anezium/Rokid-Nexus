package com.anezium.rokidbus.glasses

import android.content.Context
import android.provider.Settings
import android.os.SystemClock
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

internal data class SelfArmNetworkPosture(
    val wirelessDebuggingEnabled: Boolean,
    val persistentLegacyPort: String,
    val serviceLegacyPort: String,
    val legacyLoopbackListening: Boolean,
) {
    enum class TeardownDecision {
        CLEAR_LEGACY_PROPERTIES,
        RESTART_ADBD,
        SAFE,
    }

    fun teardownDecision(): TeardownDecision = when {
        !isDisabledPort(persistentLegacyPort) || !isDisabledPort(serviceLegacyPort) ->
            TeardownDecision.CLEAR_LEGACY_PROPERTIES
        legacyLoopbackListening -> TeardownDecision.RESTART_ADBD
        else -> TeardownDecision.SAFE
    }

    private fun isDisabledPort(value: String): Boolean =
        value.trim() in DISABLED_PORT_VALUES

    private companion object {
        val DISABLED_PORT_VALUES = setOf("", "-1", "0")
    }
}

internal object SelfArmNetworkPostureVerifier {
    fun awaitRestartDecision(context: Context, timeoutMs: Long = 3_000L): SelfArmNetworkPosture =
        awaitDecision(context, timeoutMs) {
            it != SelfArmNetworkPosture.TeardownDecision.CLEAR_LEGACY_PROPERTIES
        }

    fun awaitSafe(context: Context, timeoutMs: Long = 8_000L): SelfArmNetworkPosture =
        awaitDecision(context, timeoutMs) {
            it == SelfArmNetworkPosture.TeardownDecision.SAFE
        }

    private fun awaitDecision(
        context: Context,
        timeoutMs: Long,
        accepted: (SelfArmNetworkPosture.TeardownDecision) -> Boolean,
    ): SelfArmNetworkPosture {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var posture: SelfArmNetworkPosture
        do {
            posture = capture(context)
            if (accepted(posture.teardownDecision())) return posture
            try {
                Thread.sleep(POLL_INTERVAL_MS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while verifying legacy ADB teardown", exception)
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IOException(
            "Legacy ADB teardown was not verified: " +
                "persist=${posture.persistentLegacyPort.ifBlank { "<empty>" }} " +
                "service=${posture.serviceLegacyPort.ifBlank { "<empty>" }} " +
                "loopbackListening=${posture.legacyLoopbackListening}",
        )
    }

    fun capture(context: Context): SelfArmNetworkPosture =
        SelfArmNetworkPosture(
            wirelessDebuggingEnabled = runCatching {
                Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1
            }.getOrDefault(false),
            persistentLegacyPort = readSystemProperty("persist.adb.tcp.port"),
            serviceLegacyPort = readSystemProperty("service.adb.tcp.port"),
            legacyLoopbackListening = isLoopbackListening(),
        )

    private fun readSystemProperty(name: String): String =
        runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            systemProperties
                .getMethod("get", String::class.java, String::class.java)
                .invoke(null, name, "") as? String
        }.getOrElse { PROPERTY_UNAVAILABLE }
            ?.trim()
            ?: PROPERTY_UNAVAILABLE

    private fun isLoopbackListening(): Boolean {
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(LOOPBACK_HOST, LEGACY_ADB_PORT), SOCKET_TIMEOUT_MS)
            true
        } catch (_: ConnectException) {
            false
        } catch (_: SocketTimeoutException) {
            true
        } catch (_: IOException) {
            true
        } catch (_: RuntimeException) {
            true
        } finally {
            runCatching { socket.close() }
        }
    }

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val LEGACY_ADB_PORT = 5555
    private const val SOCKET_TIMEOUT_MS = 250
    private const val POLL_INTERVAL_MS = 200L
    private const val PROPERTY_UNAVAILABLE = "<unavailable>"
}
