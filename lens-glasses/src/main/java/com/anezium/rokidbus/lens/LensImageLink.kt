package com.anezium.rokidbus.lens

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
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.LensLinkPacket
import com.anezium.rokidbus.shared.LensLinkPacketType
import com.anezium.rokidbus.shared.LensLinkProtocol
import org.json.JSONObject
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

data class LensLinkOffer(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("version", 1)
        .put("ssid", ssid)
        .put("passphrase", passphrase)
        .put("port", port)
        .put("token", token)
        .put("goIp", goIp)
}

/** Optional, activity-scoped Wi-Fi Direct image transport. All failures are non-fatal. */
class LensImageLink(
    context: Context,
    private val onOfferReady: (LensLinkOffer) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lens-image-link").apply { isDaemon = true }
    }
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private val outputLock = Any()

    @Volatile private var running = false
    @Volatile private var connected = false
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
    private var currentOffer: LensLinkOffer? = null

    val isConnected: Boolean
        get() = connected

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running && enabled) resetGroupThenCreate()
                    if (running && !enabled) Log.w(TAG, "lensLinkP2pDisabled")
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (running && (networkInfo?.isConnected == true || currentOffer == null)) {
                        requestGroupInfo()
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        createAttempts = 0
        wifiWaitAttempts = 0
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            fail("lensLinkP2pUnavailable")
            return
        }
        registerReceiver()
        Log.i(TAG, "lensLinkStart")
        if (ensureWifiReady()) resetGroupThenCreate() else waitForWifi()
    }

    fun resendOfferIfDisconnected() {
        if (!connected) currentOffer?.let(onOfferReady)
    }

    fun send(packet: LensLinkPacket): Boolean {
        if (!connected) return false
        return synchronized(outputLock) {
            val output = clientOutput ?: return@synchronized false
            runCatching { LensLinkProtocol.write(output, packet) }
                .onFailure {
                    Log.w(TAG, "lensLinkSendFailure type=${packet.type.name}", it)
                    closeClient()
                }
                .isSuccess
        }
    }

    /** Closes without taking the output lock so a watchdog can interrupt a blocked write. */
    fun abortClient() {
        connected = false
        runCatching { clientSocket?.close() }
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenCreate() {
        if (!running) return
        if (!ensureWifiReady()) return waitForWifi()
        val localManager = manager ?: return fail("lensLinkP2pManagerLost")
        val localChannel = channel ?: return fail("lensLinkP2pChannelLost")
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
        val localManager = manager ?: return fail("lensLinkP2pManagerLost")
        val localChannel = channel ?: return fail("lensLinkP2pChannelLost")
        createAttempts += 1
        Log.i(TAG, "lensLinkCreateGroup attempt=$createAttempts")
        localManager.createGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                Log.i(TAG, "lensLinkCreateGroupRequested")
                scheduleGroupPoll(0L)
            }

            override fun onFailure(reason: Int) {
                if ((reason == WifiP2pManager.BUSY || reason == WifiP2pManager.ERROR) &&
                    createAttempts < MAX_CREATE_ATTEMPTS
                ) {
                    Log.w(TAG, "lensLinkCreateGroupBusy reason=$reason attempt=$createAttempts")
                    scheduleCreate(CREATE_RETRY_MS)
                } else {
                    fail("lensLinkCreateGroupFailure reason=$reason")
                }
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val localManager = manager ?: return
        val localChannel = channel ?: return
        runCatching {
            localManager.requestGroupInfo(localChannel) { group -> handleGroup(group) }
        }.onFailure { Log.w(TAG, "lensLinkGroupInfoFailure", it) }
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
        clearGroupPoll()
        if (serverSocket == null) startServer(interfaceName)
        requestGroupOwnerAddress { groupOwnerAddress ->
            if (!running) return@requestGroupOwnerAddress
            val offer = LensLinkOffer(ssid, passphrase, PORT, token, groupOwnerAddress)
            currentOffer = offer
            Log.i(TAG, "lensLinkOfferReady port=$PORT")
            onOfferReady(offer)
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupOwnerAddress(onAddress: (String) -> Unit) {
        val localManager = manager ?: return onAddress(GROUP_OWNER_IP)
        val localChannel = channel ?: return onAddress(GROUP_OWNER_IP)
        runCatching {
            localManager.requestConnectionInfo(localChannel) { info: WifiP2pInfo? ->
                val ownerAddress = info
                    ?.takeIf { it.groupFormed && it.isGroupOwner }
                    ?.groupOwnerAddress
                    ?.hostAddress
                    .orEmpty()
                    .ifBlank { GROUP_OWNER_IP }
                onAddress(ownerAddress)
            }
        }.onFailure {
            Log.w(TAG, "lensLinkConnectionInfoFailure", it)
            onAddress(GROUP_OWNER_IP)
        }
    }

    private fun startServer(interfaceName: String) {
        runCatching {
            val address = NetworkInterface.getByName(interfaceName)
                ?.inetAddresses
                ?.asSequence()
                ?.filterIsInstance<Inet4Address>()
                ?.firstOrNull { !it.isLoopbackAddress }
                ?: throw IllegalStateException("No P2P IPv4 address")
            ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(address, PORT))
            }
        }.onSuccess { server ->
            serverSocket = server
            Log.i(TAG, "lensLinkServerReady port=$PORT")
            ioExecutor.execute { acceptLoop(server) }
        }.onFailure {
            Log.w(TAG, "lensLinkServerFailure", it)
            scheduleGroupPoll(GROUP_POLL_MS)
        }
    }

    private fun acceptLoop(server: ServerSocket) {
        while (running && !server.isClosed) {
            val socket = runCatching { server.accept() }
                .onFailure { if (running) Log.w(TAG, "lensLinkAcceptFailure", it) }
                .getOrNull() ?: continue
            handleClient(socket)
        }
    }

    private fun handleClient(socket: Socket) {
        closeClient()
        clientSocket = socket
        socket.tcpNoDelay = true
        socket.keepAlive = true
        try {
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val hello = LensLinkProtocol.read(input)
                ?: throw IllegalArgumentException("Missing HELLO")
            val presentedToken = runCatching { JSONObject(hello.meta).optString("token") }.getOrDefault("")
            if (hello.type != LensLinkPacketType.HELLO || presentedToken != token) {
                throw SecurityException("Invalid HELLO")
            }
            synchronized(outputLock) { clientOutput = output }
            connected = true
            Log.i(TAG, "lensLinkConnected")
            while (running && !socket.isClosed) {
                val startedAt = SystemClock.elapsedRealtime()
                val packet = LensLinkProtocol.read(input) ?: break
                val elapsedMs = (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(1L)
                when (packet.type) {
                    LensLinkPacketType.PROBE -> {
                        val bytes = packet.payload.size
                        LensLinkProtocol.write(
                            output,
                            LensLinkPacket(
                                type = LensLinkPacketType.PROBE_ACK,
                                requestId = packet.requestId,
                                seq = packet.seq,
                                meta = JSONObject().put("bytes", bytes).put("elapsedMs", elapsedMs).toString(),
                            ),
                        )
                        Log.i(TAG, "lensLinkProbe bytes=$bytes ms=$elapsedMs mbps=${"%.1f".format(bytes * 8.0 / elapsedMs / 1000.0)}")
                    }
                    else -> Log.w(TAG, "lensLinkUnexpectedPacket type=${packet.type.name}")
                }
            }
        } catch (failure: Throwable) {
            if (running) Log.w(TAG, "lensLinkClientFailure type=${failure.javaClass.simpleName}")
        } finally {
            closeClient(socket)
            if (running) Log.i(TAG, "lensLinkDisconnected")
        }
    }

    @Suppress("DEPRECATION")
    private fun ensureWifiReady(): Boolean {
        val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return true
        if (wifi.isWifiEnabled) return true
        val requested = runCatching { wifi.setWifiEnabled(true) }.getOrDefault(false)
        Log.w(TAG, "lensLinkWifiEnable requested=$requested")
        return wifi.isWifiEnabled
    }

    private fun waitForWifi() {
        if (!running) return
        clearWifiRetry()
        wifiWaitAttempts += 1
        if (wifiWaitAttempts > MAX_WIFI_WAIT_ATTEMPTS) return fail("lensLinkWifiUnavailable")
        wifiRetry = Runnable {
            wifiRetry = null
            if (running) {
                if (ensureWifiReady()) resetGroupThenCreate() else waitForWifi()
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
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(appContext, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun fail(message: String) {
        Log.e(TAG, message)
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
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        currentOffer = null
        ioExecutor.shutdownNow()
        Log.i(TAG, "lensLinkStopped")
    }

    private fun closeClient(expected: Socket? = null) {
        synchronized(outputLock) {
            if (expected != null && clientSocket !== expected) return
            clientOutput = null
            connected = false
            runCatching { clientSocket?.close() }
            clientSocket = null
        }
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
        private const val TAG = "LENS"
        const val PORT = 38_401
        private const val GROUP_OWNER_IP = "192.168.49.1"
        private const val MAX_WIFI_WAIT_ATTEMPTS = 6
        private const val WIFI_WAIT_MS = 1_500L
        private const val MAX_CREATE_ATTEMPTS = 8
        private const val CREATE_RETRY_MS = 1_500L
        private const val RESET_SETTLE_MS = 500L
        private const val GROUP_POLL_MS = 1_000L
    }
}
