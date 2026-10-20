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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One transfer's Wi-Fi lane on the glasses: a listening socket on the Wi-Fi address that takes
 * exactly one phone, proven by the key handed over the bus, then carries sealed frames. Anything
 * else that connects first is sent away without costing the phone its turn.
 *
 * It lives only as long as its transfer and never listens on anything but the Wi-Fi interface.
 * Holding a high-performance Wi-Fi lock for that time keeps the radio out of power save, which
 * otherwise stretches round trips to the better part of a second on these glasses.
 */
internal class GlassesFilesLaneServer internal constructor(
    private val transferId: String,
    private val server: ServerSocket,
    private val key: ByteArray,
    private val wifiLock: WifiManager.WifiLock?,
    private val acceptTimeoutMs: Int = GlassesFilesLane.ACCEPT_TIMEOUT_MS,
    private val helloTimeoutMs: Int = GlassesFilesLane.HELLO_TIMEOUT_MS,
) : Closeable {
    val endpoint = GlassesFilesLaneEndpoint(
        host = server.inetAddress.hostAddress.orEmpty(),
        port = server.localPort,
        key = GlassesFilesLane.encodeKey(key),
    )

    private val closed = AtomicBoolean(false)
    @Volatile private var pending: Socket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var input: DataInputStream? = null
    @Volatile private var output: DataOutputStream? = null
    private val opener = GlassesFilesLane.Opener(key, transferId, GlassesFilesLane.Direction.PHONE_TO_GLASSES)
    private val sealer = GlassesFilesLane.Sealer(key, transferId, GlassesFilesLane.Direction.GLASSES_TO_PHONE)

    /**
     * Waits for the phone and its hello within one accept budget; false when it never came or the
     * budget ran out. Each connection gets at most the hello budget to prove the key, so a stranger
     * that connects first, stays silent, or trickles bytes only delays the phone, never locks it out.
     */
    fun accept(): Boolean {
        val deadline = nowMillis() + acceptTimeoutMs
        try {
            while (!closed.get()) {
                val remaining = deadline - nowMillis()
                if (remaining <= 0) return false
                val client = try {
                    server.soTimeout = remaining.toInt().coerceAtLeast(1)
                    server.accept()
                } catch (_: SocketTimeoutException) {
                    return false
                } catch (_: Exception) {
                    return false
                }
                val budget = minOf(deadline - nowMillis(), helloTimeoutMs.toLong())
                if (budget > 0 && prove(client, budget)) return true
                runCatching { client.close() }
            }
            return false
        } finally {
            // One phone, once: the port closes as soon as it is taken or the budget runs out.
            runCatching { server.close() }
        }
    }

    /** Reads one connection's hello within its budget and answers it; false sends it away. */
    private fun prove(client: Socket, budgetMs: Long): Boolean {
        pending = client
        val watchdog = deadlines.schedule({ runCatching { client.close() } }, budgetMs, TimeUnit.MILLISECONDS)
        return try {
            if (closed.get()) return false
            client.soTimeout = budgetMs.toInt().coerceAtLeast(1)
            client.tcpNoDelay = true
            val stream = DataInputStream(BufferedInputStream(client.getInputStream(), BUFFER_BYTES))
            if (!GlassesFilesLane.readHello(stream, transferId, key)) return false
            // Past this point the watchdog must not close a lane the phone is told is open.
            if (!watchdog.cancel(false)) return false
            val out = DataOutputStream(BufferedOutputStream(client.getOutputStream(), BUFFER_BYTES))
            sealer.write(out, GlassesFilesLane.ANSWER)
            // Frames can legitimately pause while the other side waits on its own disk or provider.
            client.soTimeout = READ_TIMEOUT_MS
            input = stream
            output = out
            socket = client
            !closed.get()
        } catch (_: Exception) {
            false
        } finally {
            watchdog.cancel(false)
            pending = null
        }
    }

    /** The phone stays on the bus: stop waiting for it, unless it is already connected. */
    fun decline() {
        if (socket == null) close()
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
        runCatching { pending?.close() }
        runCatching { socket?.close() }
        runCatching { if (wifiLock?.isHeld == true) wifiLock.release() }
    }

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000L

    companion object {
        private val deadlines = Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "glasses-files-lane-deadline").apply { isDaemon = true }
        }
        private const val BUFFER_BYTES = 64 * 1024
        private const val READ_TIMEOUT_MS = 30_000

        // Room for the phone to queue behind a stranger instead of having its connect refused.
        private const val BACKLOG = 4

        /**
         * Opens a lane on the glasses' Wi-Fi address, or returns null when they are not on a
         * Wi-Fi network: then the transfer simply stays on the bus.
         */
        fun open(context: Context, transferId: String): GlassesFilesLaneServer? {
            val address = wifiAddress(context) ?: return null
            val server = runCatching { ServerSocket(0, BACKLOG, address) }.getOrNull() ?: return null
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
