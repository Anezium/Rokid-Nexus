package com.anezium.liveocr.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal enum class PhoneLinkState { IDLE, JOINING_P2P, CONNECTING_TCP, CONNECTED }

/** Credential-only Wi-Fi Direct join plus a token-authenticated, full-duplex TCP client. */
internal class PhoneLinkClient(
    context: Context,
    private val onState: (PhoneLinkState, String) -> Unit,
    private val onPacket: (SpikePacket, Long) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val socketExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "live-ocr-phone-socket").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "live-ocr-phone-writer").apply { isDaemon = true }
    }
    private val ackQueue = ArrayBlockingQueue<SpikePacket>(ACK_QUEUE_CAPACITY)
    private val generation = AtomicLong(0)
    private val tcpConnecting = AtomicBoolean(false)
    private val socketLock = Any()

    @Volatile private var closed = false
    @Volatile private var credentials: LinkCredentials? = null
    @Volatile private var ownerAddress: String? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var output: java.io.OutputStream? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var connectionPoll: Runnable? = null
    private var timeout: Runnable? = null

    private val p2pReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (!enabled) state(PhoneLinkState.IDLE, "Wi-Fi Direct disabled")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) currentSession()?.let { (id, offer) ->
                        requestConnectionInfo(id, offer)
                    }
                }
            }
        }
    }

    init {
        writerExecutor.execute(::writerLoop)
    }

    fun connect(next: LinkCredentials) {
        require(next.isValid()) { "Invalid Wi-Fi Direct credentials" }
        if (closed) return
        credentials = next
        val id = generation.incrementAndGet()
        closeSocket()
        mainHandler.post {
            teardownP2p(removeGroup = true)
            if (isCurrent(id, next)) startP2p(id, next)
        }
    }

    fun sendAck(frameId: Long) {
        if (closed || output == null) return
        val packet = SpikePacket(PacketType.OCR_ACK, frameId = frameId)
        if (!ackQueue.offer(packet)) {
            ackQueue.poll()
            ackQueue.offer(packet)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startP2p(id: Long, offer: LinkCredentials) {
        if (!isCurrent(id, offer)) return
        if (!hasWifiPermission()) return state(PhoneLinkState.IDLE, "Nearby Wi-Fi permission required")
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        val localManager = manager
        val localChannel = channel
        if (localManager == null || localChannel == null) {
            state(PhoneLinkState.IDLE, "Wi-Fi Direct unavailable")
            return
        }
        registerReceiver()
        state(PhoneLinkState.JOINING_P2P, "Resetting previous P2P group")
        timeout = Runnable {
            if (isCurrent(id, offer) && ownerAddress == null) {
                state(PhoneLinkState.IDLE, "P2P join timed out")
            }
        }.also { mainHandler.postDelayed(it, P2P_TIMEOUT_MS) }
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleCredentialJoin(id, offer)
            override fun onFailure(reason: Int) = scheduleCredentialJoin(id, offer)
        })
    }

    private fun scheduleCredentialJoin(id: Long, offer: LinkCredentials) {
        mainHandler.postDelayed({ connectByCredentials(id, offer) }, RESET_SETTLE_MS)
    }

    @SuppressLint("MissingPermission")
    private fun connectByCredentials(id: Long, offer: LinkCredentials) {
        if (!isCurrent(id, offer)) return
        val localManager = manager ?: return
        val localChannel = channel ?: return
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(offer.ssid)
                .setPassphrase(offer.passphrase)
                .enablePersistentMode(false)
                .build()
        }.getOrElse {
            state(PhoneLinkState.IDLE, "P2P credential config failed")
            return
        }
        state(PhoneLinkState.JOINING_P2P, "Joining glasses P2P group")
        localManager.connect(localChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleConnectionPoll(id, offer, CONNECTION_POLL_MS)
            override fun onFailure(reason: Int) {
                if (!isCurrent(id, offer)) return
                state(PhoneLinkState.JOINING_P2P, "P2P join retry ($reason)")
                mainHandler.postDelayed({ connectByCredentials(id, offer) }, RETRY_MS)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo(id: Long, offer: LinkCredentials) {
        if (!isCurrent(id, offer)) return
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestConnectionInfo(localChannel) { info -> handleConnectionInfo(id, offer, info) }
    }

    private fun handleConnectionInfo(id: Long, offer: LinkCredentials, info: WifiP2pInfo?) {
        if (!isCurrent(id, offer)) return
        if (info?.groupFormed != true) {
            scheduleConnectionPoll(id, offer, CONNECTION_POLL_MS)
            return
        }
        if (info.isGroupOwner) {
            state(PhoneLinkState.IDLE, "Phone unexpectedly became group owner")
            return
        }
        connectionPoll?.let(mainHandler::removeCallbacks)
        connectionPoll = null
        timeout?.let(mainHandler::removeCallbacks)
        timeout = null
        val address = info.groupOwnerAddress?.hostAddress.orEmpty().ifBlank { offer.goIp }
        ownerAddress = address
        state(PhoneLinkState.CONNECTING_TCP, "Connecting TCP to $address:${offer.port}")
        socketExecutor.execute { connectSocket(id, offer, address) }
    }

    private fun scheduleConnectionPoll(id: Long, offer: LinkCredentials, delayMs: Long) {
        connectionPoll?.let(mainHandler::removeCallbacks)
        connectionPoll = Runnable {
            connectionPoll = null
            requestConnectionInfo(id, offer)
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun connectSocket(id: Long, offer: LinkCredentials, address: String) {
        if (!isSocketCurrent(id, offer, address) || !tcpConnecting.compareAndSet(false, true)) return
        var current: Socket? = null
        try {
            current = Socket().apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(address, offer.port), TCP_CONNECT_TIMEOUT_MS)
            }
            if (!isSocketCurrent(id, offer, address)) return
            val currentOutput = current.getOutputStream()
            SpikeProtocol.write(
                currentOutput,
                SpikePacket(PacketType.HELLO, payload = offer.token.toByteArray(Charsets.UTF_8)),
            )
            synchronized(socketLock) {
                socket = current
                output = currentOutput
                ackQueue.clear()
            }
            state(PhoneLinkState.CONNECTED, "TCP connected; decoding stream")
            val input = current.getInputStream()
            while (isSocketCurrent(id, offer, address) && !current.isClosed) {
                val packet = SpikeProtocol.read(input)
                val receivedNanos = android.os.SystemClock.elapsedRealtimeNanos()
                onPacket(packet, receivedNanos)
            }
        } catch (failure: Throwable) {
            if (isSocketCurrent(id, offer, address)) {
                Log.w(TAG, "TCP link ended: ${failure.javaClass.simpleName}")
            }
        } finally {
            closeSocket(current)
            tcpConnecting.set(false)
            if (isSocketCurrent(id, offer, address)) {
                state(PhoneLinkState.CONNECTING_TCP, "TCP disconnected; retrying")
                socketExecutor.schedule(
                    { connectSocket(id, offer, address) },
                    RETRY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun writerLoop() {
        while (!closed) {
            val packet = runCatching { ackQueue.poll(250, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val currentOutput = synchronized(socketLock) { output } ?: continue
            runCatching { SpikeProtocol.write(currentOutput, packet) }
                .onFailure { closeSocket() }
        }
    }

    private fun closeSocket(expected: Socket? = null) {
        synchronized(socketLock) {
            if (expected != null && socket !== expected) {
                runCatching { expected.close() }
                return
            }
            output = null
            ackQueue.clear()
            runCatching { socket?.close() }
            socket = null
        }
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentSession(): Pair<Long, LinkCredentials>? {
        val offer = credentials ?: return null
        return generation.get() to offer
    }

    private fun isCurrent(id: Long, offer: LinkCredentials): Boolean =
        !closed && generation.get() == id && credentials == offer

    private fun isSocketCurrent(id: Long, offer: LinkCredentials, address: String): Boolean =
        isCurrent(id, offer) && ownerAddress == address

    private fun state(next: PhoneLinkState, detail: String) {
        Log.i(TAG, "state=$next detail=$detail")
        mainHandler.post { onState(next, detail) }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            appContext.registerReceiver(p2pReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(p2pReceiver, filter)
        }
        receiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    private fun teardownP2p(removeGroup: Boolean) {
        ownerAddress = null
        connectionPoll?.let(mainHandler::removeCallbacks)
        timeout?.let(mainHandler::removeCallbacks)
        connectionPoll = null
        timeout = null
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            if (removeGroup) runCatching { localManager.removeGroup(localChannel, null) }
        }
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(p2pReceiver) }
        receiverRegistered = false
        manager = null
        channel = null
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        credentials = null
        closeSocket()
        mainHandler.post { teardownP2p(removeGroup = true) }
        socketExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "LiveOcrPhoneLink"
        private const val ACK_QUEUE_CAPACITY = 16
        private const val TCP_CONNECT_TIMEOUT_MS = 5_000
        private const val P2P_TIMEOUT_MS = 60_000L
        private const val CONNECTION_POLL_MS = 1_000L
        private const val RESET_SETTLE_MS = 500L
        private const val RETRY_MS = 1_000L
    }
}
