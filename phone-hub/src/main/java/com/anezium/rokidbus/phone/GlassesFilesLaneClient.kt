package com.anezium.rokidbus.phone

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesLaneEndpoint
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The phone's end of one transfer's Wi-Fi lane.
 *
 * The socket is created from the Wi-Fi network itself, so a lane can never quietly route over
 * mobile data when the address happens to be reachable some other way; if the phone has no Wi-Fi
 * network, there is no lane and the transfer stays on the bus.
 */
internal class GlassesFilesLaneClient private constructor(
    private val socket: Socket,
    transferId: String,
    key: ByteArray,
) : Closeable {
    private val input = DataInputStream(BufferedInputStream(socket.getInputStream(), BUFFER_BYTES))
    private val output = DataOutputStream(BufferedOutputStream(socket.getOutputStream(), BUFFER_BYTES))
    private val sealer = GlassesFilesLane.Sealer(key, transferId, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
    private val opener = GlassesFilesLane.Opener(key, transferId, GlassesFilesLane.Direction.GLASSES_TO_PHONE)
    private val closed = AtomicBoolean(false)

    val isClosed: Boolean get() = closed.get()

    fun read(): GlassesFilesLane.Frame = opener.read(input)

    fun write(frame: GlassesFilesLane.Frame): Boolean = !closed.get() && runCatching { sealer.write(output, frame) }.isSuccess

    override fun close() {
        if (closed.compareAndSet(false, true)) runCatching { socket.close() }
    }

    companion object {
        private const val BUFFER_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000

        fun isOnWifi(context: Context): Boolean = wifiNetwork(context) != null

        /**
         * Connects, proves the key, and waits for the glasses to answer; returns null when any of
         * it fails, never throws. A connection alone proves nothing: on another network the same
         * address may be some unrelated device.
         */
        fun connect(context: Context, transferId: String, endpoint: GlassesFilesLaneEndpoint): GlassesFilesLaneClient? {
            val key = GlassesFilesLane.decodeKey(endpoint.key) ?: return null
            val network = wifiNetwork(context) ?: return null
            val socket = runCatching { network.socketFactory.createSocket() }.getOrNull() ?: return null
            return try {
                socket.connect(InetSocketAddress(endpoint.host, endpoint.port), GlassesFilesLane.CONNECT_TIMEOUT_MS)
                socket.tcpNoDelay = true
                socket.soTimeout = GlassesFilesLane.ANSWER_TIMEOUT_MS
                val client = GlassesFilesLaneClient(socket, transferId, key)
                GlassesFilesLane.writeHello(client.output, transferId, key)
                check(client.read() == GlassesFilesLane.ANSWER)
                socket.soTimeout = READ_TIMEOUT_MS
                client
            } catch (_: Exception) {
                runCatching { socket.close() }
                null
            }
        }

        private fun wifiNetwork(context: Context): Network? {
            val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
            @Suppress("DEPRECATION")
            return connectivity.allNetworks.firstOrNull { network ->
                connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }
        }
    }
}
