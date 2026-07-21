package com.anezium.rokidbus.phone

import android.content.Context
import android.os.SystemClock
import com.anezium.rokidbus.phone.selfarm.adb.AdbMdnsPairingResolver
import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbClient
import com.anezium.rokidbus.phone.selfarm.adb.ManualAdbSession
import java.io.IOException

internal data class GlassesManualAdbEndpoint(val host: String, val port: Int)

internal interface GlassesManualPairingBackend {
    fun pair(host: String, pairPort: Int, code: String)
    fun discoverConnectEndpoint(dialogHost: String): GlassesManualAdbEndpoint
    fun connect(endpoint: GlassesManualAdbEndpoint): ManualAdbSession
    fun arm(session: ManualAdbSession, dialogHost: String)
}

internal class AndroidGlassesManualPairingBackend(context: Context) : GlassesManualPairingBackend {
    private val resolver = AdbMdnsPairingResolver(context.applicationContext)
    private val adbClient = ManualAdbClient(context.applicationContext)

    override fun pair(host: String, pairPort: Int, code: String) {
        adbClient.pairWirelessDebugging(host, pairPort, code)
    }

    override fun discoverConnectEndpoint(dialogHost: String): GlassesManualAdbEndpoint {
        val resolved = resolver.resolveConnectEndpoint(dialogHost)
        val confirmedHost = resolved.host.ifBlank { dialogHost }
        if (confirmedHost != dialogHost) {
            throw IOException("Wireless Debugging connect service did not match the pairing-dialog host")
        }
        return GlassesManualAdbEndpoint(confirmedHost, resolved.port)
    }

    override fun connect(endpoint: GlassesManualAdbEndpoint): ManualAdbSession =
        adbClient.connect(endpoint.host, endpoint.port)

    override fun arm(session: ManualAdbSession, dialogHost: String) {
        GlassesManualArmRunner.run(session, dialogHost) { host, oldAdbdPid, preferredPort ->
            reconnectAfterAdbdRestart(host, oldAdbdPid, preferredPort)
        }
    }

    private fun reconnectAfterAdbdRestart(
        host: String,
        oldAdbdPid: String,
        preferredPort: Int,
    ): ManualAdbSession {
        val deadline = SystemClock.elapsedRealtime() + RECONNECT_TIMEOUT_MS
        var lastFailure: Throwable? = null
        do {
            var candidate: ManualAdbSession? = null
            try {
                val remaining = deadline - SystemClock.elapsedRealtime()
                val endpoint = resolver.resolveConnectEndpoint(
                    host,
                    remaining.coerceIn(1_000L, MDNS_RETRY_SLICE_MS),
                )
                candidate = adbClient.connect(endpoint.host.ifBlank { host }, endpoint.port)
                val pid = candidate.shell("pidof adbd")
                val currentPids = pidSet(pid.output)
                if (pid.exitCode == 0 && currentPids.isNotEmpty() &&
                    currentPids.intersect(pidSet(oldAdbdPid)).isEmpty()
                ) {
                    return candidate.also { candidate = null }
                }
                lastFailure = IOException("Reconnected shell still belongs to the previous adbd process")
            } catch (failure: Throwable) {
                lastFailure = failure
            } finally {
                runCatching { candidate?.close() }
            }
            try {
                Thread.sleep(RECONNECT_POLL_MS)
            } catch (exception: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Wireless Debugging reconnect was interrupted", exception)
            }
        } while (SystemClock.elapsedRealtime() < deadline)
        throw IOException(
            "Wireless Debugging did not reconnect after adbd restart on the prior port $preferredPort",
            lastFailure,
        )
    }

    private fun pidSet(value: String): Set<String> = value
        .trim()
        .replace(',', ' ')
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }
        .toSet()

    private companion object {
        const val RECONNECT_TIMEOUT_MS = 20_000L
        const val MDNS_RETRY_SLICE_MS = 8_000L
        const val RECONNECT_POLL_MS = 250L
    }
}
