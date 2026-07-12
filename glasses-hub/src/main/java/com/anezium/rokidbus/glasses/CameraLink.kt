package com.anezium.rokidbus.glasses

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
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class CameraLinkOffer(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
)

/** Activity-scoped camera data plane. The server remains listening across client crashes. */
internal class CameraLink(
    context: Context,
    private val onOfferReady: (CameraLinkOffer) -> Unit,
    private val onAuthenticated: (Boolean) -> Unit,
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-link-accept").apply { isDaemon = true }
    }
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-link-writer").apply { isDaemon = true }
    }
    private val packets = ArrayBlockingQueue<CameraLinkPacket>(NETWORK_QUEUE_CAPACITY)
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private val socketLock = Any()

    @Volatile private var running = false
    @Volatile private var authenticated = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    private var clientOutput: java.io.OutputStream? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var createAttempts = 0
    private var wifiWaitAttempts = 0
    private var createRetry: Runnable? = null
    private var wifiRetry: Runnable? = null
    private var groupPoll: Runnable? = null
    private var currentOffer: CameraLinkOffer? = null

    val isAuthenticated: Boolean get() = authenticated

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running && enabled && currentOffer == null) resetGroupThenCreate()
                    if (running && !enabled) state("WAITING FOR WI-FI")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (running && (info?.isConnected == true || currentOffer == null)) requestGroupInfo()
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
        if (manager == null || channel == null) return fail("WI-FI DIRECT UNAVAILABLE")
        registerReceiver()
        writerExecutor.execute(::writerLoop)
        if (isWifiReady()) resetGroupThenCreate() else waitForWifi()
    }

    fun resendOfferIfDisconnected() {
        if (!authenticated) currentOffer?.let(onOfferReady)
    }

    /** Never performs socket I/O on the codec callback thread. */
    fun enqueue(packet: CameraLinkPacket): Boolean {
        if (!authenticated) return false
        if (packets.offer(packet)) return true
        return if (packet.type == CameraLinkPacketType.VIDEO_FRAME) {
            dropOneVideoFrame() && packets.offer(packet)
        } else {
            while (packets.remainingCapacity() == 0 && dropOneVideoFrame()) Unit
            packets.offer(packet)
        }
    }

    fun abortClient() {
        runCatching { clientSocket?.close() }
    }

    private fun dropOneVideoFrame(): Boolean {
        val stale = packets.firstOrNull { it.type == CameraLinkPacketType.VIDEO_FRAME } ?: return false
        return packets.remove(stale)
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenCreate() {
        if (!running) return
        if (!isWifiReady()) return waitForWifi()
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        clearCreateRetry()
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleCreate(RESET_SETTLE_MS)
            override fun onFailure(reason: Int) = scheduleCreate(RESET_SETTLE_MS)
        })
    }

    private fun scheduleCreate(delayMs: Long) {
        clearCreateRetry()
        createRetry = Runnable {
            createRetry = null
            createGroup()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        if (!running) return
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        createAttempts += 1
        state("CREATING CAMERA LINK")
        localManager.createGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleGroupPoll(0L)
            override fun onFailure(reason: Int) {
                if (createAttempts < MAX_CREATE_ATTEMPTS) scheduleCreate(CREATE_RETRY_MS)
                else fail("CAMERA LINK UNAVAILABLE")
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        runCatching { localManager.requestGroupInfo(localChannel, ::handleGroup) }
            .onFailure { if (running) scheduleGroupPoll(GROUP_POLL_MS) }
    }

    private fun handleGroup(group: WifiP2pGroup?) {
        if (!running) return
        val ssid = group?.networkName.orEmpty()
        val passphrase = group?.passphrase.orEmpty()
        val interfaceName = group?.getInterface().orEmpty()
        if (group == null || !group.isGroupOwner || ssid.isBlank() ||
            passphrase.isBlank() || interfaceName.isBlank()
        ) {
            scheduleGroupPoll(GROUP_POLL_MS)
            return
        }
        clearGroupPoll()
        if (serverSocket == null) startServer(interfaceName)
        requestOwnerAddress { address ->
            if (!running) return@requestOwnerAddress
            val offer = CameraLinkOffer(ssid, passphrase, PORT, token, address)
            currentOffer = offer
            onOfferReady(offer)
            state("WAITING FOR PHONE")
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestOwnerAddress(callback: (String) -> Unit) {
        val localManager = manager ?: return callback(DEFAULT_GO_IP)
        val localChannel = channel ?: return callback(DEFAULT_GO_IP)
        runCatching {
            localManager.requestConnectionInfo(localChannel) { info: WifiP2pInfo? ->
                callback(
                    info?.takeIf { it.groupFormed && it.isGroupOwner }
                        ?.groupOwnerAddress?.hostAddress.orEmpty().ifBlank { DEFAULT_GO_IP },
                )
            }
        }.onFailure { callback(DEFAULT_GO_IP) }
    }

    private fun startServer(interfaceName: String) {
        runCatching {
            val address = NetworkInterface.getByName(interfaceName)?.inetAddresses?.asSequence()
                ?.filterIsInstance<Inet4Address>()?.firstOrNull { !it.isLoopbackAddress }
                ?: error("No P2P IPv4 address")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, PORT))
            }
        }.onSuccess { server ->
            serverSocket = server
            acceptExecutor.execute { acceptLoop(server) }
        }.onFailure {
            Log.w(TAG, "cameraLinkServerFailure", it)
            scheduleGroupPoll(GROUP_POLL_MS)
        }
    }

    /** Client failures are contained inside this loop, so the listening socket survives. */
    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = try {
                server.accept()
            } catch (failure: Throwable) {
                if (running && !server.isClosed) Log.w(TAG, "cameraLinkAcceptFailure", failure)
                continue
            }
            handleClient(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        closeClient()
        try {
            socket.tcpNoDelay = true
            socket.keepAlive = true
            val input = socket.getInputStream()
            val hello = CameraLinkProtocol.read(input) ?: error("Missing HELLO")
            val presentedToken = runCatching { JSONObject(hello.meta).optString("token") }.getOrDefault("")
            require(hello.type == CameraLinkPacketType.HELLO && presentedToken == token) { "Invalid HELLO" }
            synchronized(socketLock) {
                clientSocket = socket
                clientOutput = socket.getOutputStream()
                packets.clear()
                authenticated = true
            }
            mainHandler.post { onAuthenticated(true) }
            state("PHONE LINKED")
            while (running && !socket.isClosed) {
                val startedAt = SystemClock.elapsedRealtime()
                val packet = CameraLinkProtocol.read(input) ?: break
                when (packet.type) {
                    CameraLinkPacketType.PROBE -> enqueue(
                        CameraLinkPacket(
                            type = CameraLinkPacketType.PROBE_ACK,
                            requestId = packet.requestId,
                            seq = packet.seq,
                            meta = JSONObject()
                                .put("bytes", packet.payload.size)
                                .put("elapsedMs", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L))
                                .toString(),
                        ),
                    )
                    CameraLinkPacketType.VIDEO_ACK -> Unit
                    else -> Log.w(TAG, "cameraLinkUnexpectedPacket type=${packet.type.name}")
                }
            }
        } catch (failure: Throwable) {
            if (running) Log.w(TAG, "cameraLinkClientEnded type=${failure.javaClass.simpleName}")
        } finally {
            closeClient(socket)
            if (running) state("WAITING FOR PHONE")
        }
    }

    private fun writerLoop() {
        while (running) {
            val packet = runCatching { packets.poll(250L, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            val output = synchronized(socketLock) { clientOutput } ?: continue
            runCatching { CameraLinkProtocol.write(output, packet) }
                .onFailure { closeClient() }
        }
    }

    private fun closeClient(expected: Socket? = null) {
        var notify = false
        synchronized(socketLock) {
            if (expected != null && clientSocket !== expected) return
            notify = authenticated
            authenticated = false
            clientOutput = null
            packets.clear()
            runCatching { clientSocket?.close() }
            clientSocket = null
        }
        if (notify) mainHandler.post { onAuthenticated(false) }
    }

    private fun isWifiReady(): Boolean =
        (appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.isWifiEnabled != false

    private fun waitForWifi() {
        if (!running) return
        clearWifiRetry()
        wifiWaitAttempts += 1
        if (wifiWaitAttempts > MAX_WIFI_WAIT_ATTEMPTS) return fail("WI-FI UNAVAILABLE")
        state("ENABLING WI-FI")
        wifiRetry = Runnable {
            wifiRetry = null
            if (running) {
                if (isWifiReady()) resetGroupThenCreate() else waitForWifi()
            }
        }.also { mainHandler.postDelayed(it, WIFI_WAIT_MS) }
    }

    private fun scheduleGroupPoll(delayMs: Long) {
        clearGroupPoll()
        groupPoll = Runnable {
            groupPoll = null
            if (running) requestGroupInfo()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        appContext.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            },
        )
        receiverRegistered = true
    }

    private fun state(message: String) {
        mainHandler.post { onState(message) }
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
        state(message)
        stop(removeGroup = false)
    }

    override fun close() = stop(removeGroup = true)

    @SuppressLint("MissingPermission")
    private fun stop(removeGroup: Boolean) {
        running = false
        clearCreateRetry()
        clearWifiRetry()
        clearGroupPoll()
        closeClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
        val localManager = manager
        val localChannel = channel
        if (removeGroup && localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            runCatching { localManager.removeGroup(localChannel, null) }
        }
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        currentOffer = null
        acceptExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    private fun clearCreateRetry() {
        createRetry?.let(mainHandler::removeCallbacks)
        createRetry = null
    }

    private fun clearWifiRetry() {
        wifiRetry?.let(mainHandler::removeCallbacks)
        wifiRetry = null
    }

    private fun clearGroupPoll() {
        groupPoll?.let(mainHandler::removeCallbacks)
        groupPoll = null
    }

    companion object {
        private const val TAG = "CameraLink"
        const val PORT = 38_401
        private const val DEFAULT_GO_IP = "192.168.49.1"
        private const val NETWORK_QUEUE_CAPACITY = 12
        private const val MAX_WIFI_WAIT_ATTEMPTS = 8
        private const val WIFI_WAIT_MS = 750L
        private const val MAX_CREATE_ATTEMPTS = 8
        private const val CREATE_RETRY_MS = 1_500L
        private const val RESET_SETTLE_MS = 500L
        private const val GROUP_POLL_MS = 1_000L
    }
}
