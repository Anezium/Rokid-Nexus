package com.anezium.rokidbus.plugin.lens

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.CameraLinkEndpointOffer
import com.anezium.rokidbus.shared.CameraLinkMode
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Phone-side LOHS owner and TCP server used only while the phone STA toggle is off. */
internal class PhoneLohsImageServer(
    context: Context,
    private val sourceOffer: PhoneLensLinkOffer,
    private val logger: (String) -> Unit,
    private val onPacket: (CameraLinkPacket) -> Unit,
    private val onReverseOffer: (CameraLinkEndpointOffer) -> Boolean,
    private val stageElapsedMs: () -> Long,
    private val onState: (PhoneLensLinkState) -> Unit,
    private val onFailed: () -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "lens-lohs-server").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }
    private val random = SecureRandom()
    private val running = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val socketLock = Any()

    @Volatile private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var connected = false
    private var reverseOffer: CameraLinkEndpointOffer? = null
    private var offerRetry: ScheduledFuture<*>? = null

    private val hotspotCallback = object : WifiManager.LocalOnlyHotspotCallback() {
        override fun onStarted(startedReservation: WifiManager.LocalOnlyHotspotReservation) {
            if (!running.get()) {
                runCatching { startedReservation.close() }
                return
            }
            reservation = startedReservation
            if (!running.get()) {
                reservation = null
                runCatching { startedReservation.close() }
                return
            }
            val configuration = startedReservation.softApConfiguration
            val ssid = configuration.ssid
            val passphrase = configuration.passphrase
            if (ssid.isNullOrBlank() || passphrase.isNullOrBlank()) {
                fail("lensLinkLohsInvalidConfiguration")
                return
            }
            runCatching { executor.execute { startServer(ssid, passphrase) } }
                .onFailure { fail("lensLinkLohsServerDispatchFailed") }
        }

        override fun onStopped() {
            if (running.get()) fail("lensLinkLohsStopped")
        }

        override fun onFailed(reason: Int) {
            if (running.get()) fail("lensLinkLohsStartFailed reason=$reason")
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (closed.get() || !running.compareAndSet(false, true)) return
        onState(PhoneLensLinkState.WAITING_NETWORK)
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        if (wifiManager == null) {
            fail("lensLinkLohsUnavailable")
            return
        }
        runCatching { wifiManager.startLocalOnlyHotspot(hotspotCallback, mainHandler) }
            .onFailure { fail("lensLinkLohsStartException type=${it.javaClass.simpleName}") }
    }

    private fun startServer(ssid: String, passphrase: String) {
        if (!running.get()) return
        val server = runCatching {
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", sourceOffer.port))
            }
        }.getOrElse {
            fail("lensLinkLohsServerBindFailed type=${it.javaClass.simpleName}")
            return
        }
        if (!running.get()) {
            runCatching { server.close() }
            return
        }
        serverSocket = server
        reverseOffer = CameraLinkEndpointOffer(
            sessionId = sourceOffer.sessionId,
            ssid = ssid,
            passphrase = passphrase,
            port = sourceOffer.port,
            token = sourceOffer.token,
            mode = CameraLinkMode.LOHS_REVERSE,
        )
        logger("cameraLinkStage stage=lohs_started elapsedMs=${stageElapsedMs().coerceAtLeast(0L)}")
        sendOfferAndScheduleRetry()
        acceptLoop(server)
    }

    private fun sendOfferAndScheduleRetry() {
        if (!running.get() || connected) return
        val offer = reverseOffer ?: return
        val sent = runCatching { onReverseOffer(offer) }.getOrDefault(false)
        logger("lensLinkLohsOffer sent=$sent")
        offerRetry?.cancel(false)
        offerRetry = executor.schedule(
            ::sendOfferAndScheduleRetry,
            OFFER_RETRY_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running.get() && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (failure: Throwable) {
                if (running.get() && !server.isClosed) {
                    logger("lensLinkLohsAcceptFailed type=${failure.javaClass.simpleName}")
                }
                continue
            }
            handleClient(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        synchronized(socketLock) {
            runCatching { clientSocket?.close() }
            clientSocket = socket
        }
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            socket.soTimeout = AUTH_TIMEOUT_MS
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val hello = CameraLinkProtocol.read(input) ?: error("Missing HELLO")
            val presentedToken = runCatching { JSONObject(hello.meta).optString("token") }
                .getOrDefault("")
            require(hello.type == CameraLinkPacketType.HELLO &&
                presentedToken == sourceOffer.token
            ) { "Invalid HELLO" }
            socket.soTimeout = 0
            connected = true
            offerRetry?.cancel(false)
            offerRetry = null
            onState(PhoneLensLinkState.CONNECTED)
            logger("cameraLinkStage stage=connected elapsedMs=${stageElapsedMs().coerceAtLeast(0L)}")
            logger("lensLinkConnected transport=lohs_reverse")

            val requestId = random.nextLong()
            val probe = ByteArray(PROBE_BYTES).also(random::nextBytes)
            val probeStartedAt = SystemClock.elapsedRealtime()
            CameraLinkProtocol.write(
                output,
                CameraLinkPacket(CameraLinkPacketType.PROBE, requestId, payload = probe),
            )
            while (running.get() && !socket.isClosed) {
                val packet = CameraLinkProtocol.read(input) ?: break
                if (packet.type == CameraLinkPacketType.PROBE_ACK && packet.requestId == requestId) {
                    val elapsedMs = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                    val bytes = runCatching { JSONObject(packet.meta).optInt("bytes", PROBE_BYTES) }
                        .getOrDefault(PROBE_BYTES)
                    logger(
                        "lensLinkProbe transport=lohs_reverse bytes=$bytes ms=$elapsedMs " +
                            "mbps=${"%.1f".format(bytes * 8.0 / elapsedMs / 1000.0)}",
                    )
                } else {
                    onPacket(packet)
                }
            }
        } catch (failure: Throwable) {
            if (running.get()) logger("lensLinkLohsClientEnded type=${failure.javaClass.simpleName}")
        } finally {
            synchronized(socketLock) {
                if (clientSocket === socket) clientSocket = null
            }
            runCatching { socket.close() }
            if (connected) connected = false
            if (running.get()) {
                onState(PhoneLensLinkState.WAITING_NETWORK)
                sendOfferAndScheduleRetry()
            }
        }
    }

    private fun fail(message: String) {
        if (!running.getAndSet(false)) return
        logger(message)
        onFailed()
        closeResources()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        running.set(false)
        closeResources()
    }

    private fun closeResources() {
        offerRetry?.cancel(false)
        offerRetry = null
        runCatching { serverSocket?.close() }
        serverSocket = null
        synchronized(socketLock) {
            runCatching { clientSocket?.close() }
            clientSocket = null
        }
        connected = false
        runCatching { reservation?.close() }
        reservation = null
        executor.shutdownNow()
    }

    private companion object {
        const val AUTH_TIMEOUT_MS = 5_000
        const val PROBE_BYTES = 512 * 1024
        const val OFFER_RETRY_MS = 2_500L
    }
}
