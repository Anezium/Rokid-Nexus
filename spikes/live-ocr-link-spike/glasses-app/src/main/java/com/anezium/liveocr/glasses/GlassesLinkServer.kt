package com.anezium.liveocr.glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class LinkOffer(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
)

internal class GlassesLinkServer(
    context: Context,
    private val onOffer: (LinkOffer) -> Unit,
    private val onConnected: (Boolean) -> Unit,
    private val onAck: (Long) -> Unit,
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acceptExecutor = Executors.newSingleThreadExecutor { Thread(it, "ocr-link-accept") }
    private val writerExecutor = Executors.newSingleThreadExecutor { Thread(it, "ocr-link-writer") }
    private val packets = ArrayBlockingQueue<SpikePacket>(NETWORK_QUEUE_CAPACITY)
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private val socketLock = Any()

    @Volatile private var running = false
    @Volatile private var connected = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var clientOutput: java.io.OutputStream? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var createAttempts = 0
    private var createRetry: Runnable? = null
    private var groupPoll: Runnable? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running && enabled) resetGroupThenCreate()
                    if (!enabled) state("Wi-Fi Direct disabled")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (running && info?.isConnected == true) requestGroupInfo()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) return state("Wi-Fi Direct unavailable")
        registerReceiver()
        writerExecutor.execute(::writerLoop)
        ensureWifiEnabled()
        resetGroupThenCreate()
    }

    /** Called on the codec callback thread. It never writes to the socket or waits for the writer. */
    fun enqueue(packet: SpikePacket): Boolean {
        if (!connected) return false
        if (packets.offer(packet)) return true
        if (packet.type == PacketType.VIDEO_FRAME) {
            packets.firstOrNull { it.type == PacketType.VIDEO_FRAME }?.let(packets::remove)
            return packets.offer(packet)
        }
        return false
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenCreate() {
        if (!running) return
        val localManager = manager ?: return
        val localChannel = channel ?: return
        createRetry?.let(mainHandler::removeCallbacks)
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleCreate(RESET_SETTLE_MS)
            override fun onFailure(reason: Int) = scheduleCreate(RESET_SETTLE_MS)
        })
    }

    private fun scheduleCreate(delayMs: Long) {
        createRetry?.let(mainHandler::removeCallbacks)
        createRetry = Runnable { createGroup() }.also { mainHandler.postDelayed(it, delayMs) }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!running) return
        val localManager = manager ?: return
        val localChannel = channel ?: return
        createAttempts++
        state("Creating P2P group ($createAttempts)")
        localManager.createGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleGroupPoll(0)
            override fun onFailure(reason: Int) {
                if (createAttempts < MAX_CREATE_ATTEMPTS) scheduleCreate(CREATE_RETRY_MS)
                else state("P2P group failed: $reason")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestGroupInfo(localChannel, ::handleGroup)
    }

    private fun handleGroup(group: WifiP2pGroup?) {
        if (!running) return
        val ssid = group?.networkName.orEmpty()
        val passphrase = group?.passphrase.orEmpty()
        val interfaceName = group?.getInterface().orEmpty()
        if (group == null || !group.isGroupOwner || ssid.isBlank() || passphrase.isBlank() || interfaceName.isBlank()) {
            scheduleGroupPoll(GROUP_POLL_MS)
            return
        }
        groupPoll?.let(mainHandler::removeCallbacks)
        if (serverSocket == null) startServer(interfaceName)
        requestOwnerAddress { address ->
            onOffer(LinkOffer(ssid, passphrase, PORT, token, address))
            state("Waiting for phone")
        }
    }

    private fun scheduleGroupPoll(delayMs: Long) {
        groupPoll?.let(mainHandler::removeCallbacks)
        groupPoll = Runnable { requestGroupInfo() }.also { mainHandler.postDelayed(it, delayMs) }
    }

    @SuppressLint("MissingPermission")
    private fun requestOwnerAddress(callback: (String) -> Unit) {
        val localManager = manager ?: return callback(DEFAULT_GO_IP)
        val localChannel = channel ?: return callback(DEFAULT_GO_IP)
        localManager.requestConnectionInfo(localChannel) { info: WifiP2pInfo? ->
            callback(info?.groupOwnerAddress?.hostAddress.orEmpty().ifBlank { DEFAULT_GO_IP })
        }
    }

    private fun startServer(interfaceName: String) {
        runCatching {
            val address = NetworkInterface.getByName(interfaceName)?.inetAddresses?.asSequence()
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
                ?: error("No P2P IPv4 address")
            ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(address, PORT)) }
        }.onSuccess { server ->
            serverSocket = server
            acceptExecutor.execute { acceptLoop(server) }
        }.onFailure { state("TCP server failed: ${it.javaClass.simpleName}") }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = runCatching { server.accept() }.getOrNull() ?: continue
            handleClient(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        closeClient()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val hello = SpikeProtocol.read(input)
            require(hello.type == PacketType.HELLO && hello.payload.toString(Charsets.UTF_8) == token) {
                "Invalid HELLO"
            }
            synchronized(socketLock) {
                clientSocket = socket
                clientOutput = socket.getOutputStream()
                packets.clear()
                connected = true
            }
            mainHandler.post { onConnected(true) }
            while (running && !socket.isClosed) {
                val packet = SpikeProtocol.read(input)
                if (packet.type == PacketType.OCR_ACK) mainHandler.post { onAck(packet.frameId) }
            }
        } catch (failure: Throwable) {
            if (running) Log.w(TAG, "Client ended: ${failure.javaClass.simpleName}")
        } finally {
            closeClient(socket)
        }
    }

    private fun writerLoop() {
        while (running) {
            val packet = runCatching { packets.poll(250, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val output = synchronized(socketLock) { clientOutput } ?: continue
            runCatching { SpikeProtocol.write(output, packet) }.onFailure { closeClient() }
        }
    }

    private fun closeClient(expected: Socket? = null) {
        var notify = false
        synchronized(socketLock) {
            if (expected != null && clientSocket !== expected) return
            notify = connected
            connected = false
            clientOutput = null
            packets.clear()
            runCatching { clientSocket?.close() }
            clientSocket = null
        }
        if (notify) mainHandler.post { onConnected(false) }
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiEnabled() {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        if (!wifi.isWifiEnabled) runCatching { wifi.setWifiEnabled(true) }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        appContext.registerReceiver(receiver, filter)
        receiverRegistered = true
    }

    private fun state(message: String) {
        Log.i(TAG, message)
        mainHandler.post { onState(message) }
    }

    @SuppressLint("MissingPermission")
    override fun close() {
        running = false
        createRetry?.let(mainHandler::removeCallbacks)
        groupPoll?.let(mainHandler::removeCallbacks)
        closeClient()
        runCatching { serverSocket?.close() }
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) runCatching { localManager.removeGroup(localChannel, null) }
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        acceptExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    companion object {
        private const val TAG = "LiveOcrGlassesLink"
        const val PORT = 38_401
        private const val DEFAULT_GO_IP = "192.168.49.1"
        private const val NETWORK_QUEUE_CAPACITY = 12
        private const val RESET_SETTLE_MS = 500L
        private const val CREATE_RETRY_MS = 1_500L
        private const val GROUP_POLL_MS = 1_000L
        private const val MAX_CREATE_ATTEMPTS = 8
    }
}
