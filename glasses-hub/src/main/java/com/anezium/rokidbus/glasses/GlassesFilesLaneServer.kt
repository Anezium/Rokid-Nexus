package com.anezium.rokidbus.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesLaneEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One transfer's Wi-Fi lane on the glasses: a listening socket on the Wi-Fi address that accepts
 * exactly one phone, proven by the key handed over the bus, then carries sealed frames.
 *
 * It lives only as long as its transfer and never listens on anything but the Wi-Fi interface.
 * Holding a high-performance Wi-Fi lock for that time keeps the radio out of power save, which
 * otherwise stretches round trips to the better part of a second on these glasses.
 */
internal class GlassesFilesLaneServer private constructor(
    private val transferId: String,
    private val server: ServerSocket,
    private val key: ByteArray,
    private val wifiLock: WifiManager.WifiLock?,
) : Closeable {
    val endpoint = GlassesFilesLaneEndpoint(
        host = server.inetAddress.hostAddress.orEmpty(),
        port = server.localPort,
        key = GlassesFilesLane.encodeKey(key),
    )

    private val closed = AtomicBoolean(false)
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    private val opener = GlassesFilesLane.Opener(key, transferId, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
    private val sealer = GlassesFilesLane.Sealer(key, transferId, GlassesFilesLane.Direction.GLASSES_TO_PHONE)

    /** Waits for the phone, for at most the accept timeout; false when it never came or lied. */
    fun accept(): Boolean {
        if (closed.get()) return false
        val client = try {
            server.soTimeout = GlassesFilesLane.ACCEPT_TIMEOUT_MS
            server.accept()
        } catch (_: SocketTimeoutException) {
            null
        } catch (_: Exception) {
            null
        } finally {
            // One phone, once: nothing else gets to try the port.
            runCatching { server.close() }
        } ?: return false
        return try {
            client.soTimeout = GlassesFilesLane.ACCEPT_TIMEOUT_MS
            client.tcpNoDelay = true
            val stream = DataInputStream(BufferedInputStream(client.getInputStream(), BUFFER_BYTES))
            if (!GlassesFilesLane.readHello(stream, transferId, key)) {
                runCatching { client.close() }
                return false
            }
            // Frames can legitimately pause while the other side waits on its own disk or provider.
            client.soTimeout = READ_TIMEOUT_MS
            input = stream
            output = DataOutputStream(BufferedOutputStream(client.getOutputStream(), BUFFER_BYTES))
            socket = client
            if (closed.get()) {
                runCatching { client.close() }
                return false
            }
            true
        } catch (_: Exception) {
            runCatching { client.close() }
            false
        }
    }

    /** Blocks until the next frame from the phone; throws when the lane breaks or is closed. */
    fun read(): GlassesFilesLane.Frame {
        val stream = input ?: error("lane not connected")
        return opener.read(stream)
    }

    /** False once the lane is gone; the caller then ends the transfer. */
    fun write(frame: GlassesFilesLane.Frame): Boolean {
        val stream = output ?: return false
        return runCatching { sealer.write(stream, frame) }.isSuccess
    }

    fun isConnected(): Boolean = socket != null && !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { server.close() }
        runCatching { socket?.close() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000

        /**
         * Opens a lane on the glasses' Wi-Fi address, or returns null when they are not on a
         * Wi-Fi network: then the transfer simply stays on the bus.
         */
        fun open(context: Context, transferId: String): GlassesFilesLaneServer? {
            val address = wifiAddress(context) ?: return null
            val server = runCatching { ServerSocket(0, 1, address) }.getOrNull() ?: return null
            val lock = runCatching {
                @Suppress("DEPRECATION")
                (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager)
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RokidNexus:filesLane")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
            }.getOrNull()
            return GlassesFilesLaneServer(transferId, server, GlassesFilesLane.newKey(), lock)
        }

        private fun wifiAddress(context: Context): InetAddress? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            val network = connectivity.allNetworks.firstOrNull { candidate ->
                connectivity.getNetworkCapabilities(candidate)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            } ?: return null
            return connectivity.getLinkProperties(network)?.linkAddresses
                ?.map { it.address }
                ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress }
        }
    }
}
