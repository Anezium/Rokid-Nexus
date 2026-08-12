package com.anezium.rokidbus.glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.anezium.rokidbus.shared.MediaLinkPacket
import com.anezium.rokidbus.shared.MediaLinkPacketType
import com.anezium.rokidbus.shared.MediaLinkProtocol
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.Executors

/** Activity-scoped video receiver. It deliberately owns a distinct Wi-Fi Direct group from CameraLink. */
internal class VideoLink(
    context: Context,
    private val sessionId: String,
    private val onOffer: (JSONObject) -> Unit,
    private val onPacket: (MediaLinkPacket) -> Unit,
    private val onState: (String) -> Unit,
    private val onFailure: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "video-link").apply { isDaemon = true } }
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    @Volatile private var server: ServerSocket? = null
    @Volatile private var socket: Socket? = null
    @Volatile private var running = false
    private var receiverRegistered = false
    private var groupCreated = false
    private var groupPolls = 0
    private var offered = false
    private val epoch = SecureRandom().nextLong().and(Long.MAX_VALUE).coerceAtLeast(1L)

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (!running) return
            if (intent?.action == WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) {
                if (intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                    WifiP2pManager.WIFI_P2P_STATE_ENABLED
                ) requestGroupInfo()
                return
            }
            if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val connected = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)?.isConnected
            } else {
                @Suppress("DEPRECATION")
                (intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO) as? NetworkInfo)?.isConnected
            }
            if (connected == true || !offered) requestGroupInfo()
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) return fail("WI-FI DIRECT UNAVAILABLE")
        appContext.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
                addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            },
        )
        receiverRegistered = true
        onState("STARTING VIDEO LINK")
        val wifiEnabled = runCatching {
            appContext.getSystemService(WifiManager::class.java)?.isWifiEnabled
        }.getOrNull()
        if (wifiEnabled == false) onState("WAITING FOR WI-FI") else requestGroupInfo()
    }

    fun isRunning(): Boolean = running

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        val activeManager = manager ?: return
        val activeChannel = channel ?: return
        activeManager.requestGroupInfo(activeChannel) { group ->
            if (!running) return@requestGroupInfo
            when {
                group != null && group.isGroupOwner && groupCreated -> offer(group)
                group != null -> fail("VIDEO LINK BUSY")
                groupCreated && groupPolls++ < MAX_GROUP_POLLS ->
                    mainHandler.postDelayed(::requestGroupInfo, GROUP_SETTLE_MS)
                groupCreated -> fail("VIDEO GROUP TIMEOUT")
                else -> createGroup()
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun createGroup() {
        val activeManager = manager ?: return
        val activeChannel = channel ?: return
        activeManager.createGroup(activeChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                groupCreated = true
                mainHandler.postDelayed({ requestGroupInfo() }, GROUP_SETTLE_MS)
            }
            override fun onFailure(reason: Int) = fail("VIDEO WI-FI DIRECT FAILED ($reason)")
        })
    }

    private fun offer(group: WifiP2pGroup) {
        if (offered) return
        val address = p2pAddress(group) ?: return fail("VIDEO GROUP ADDRESS UNAVAILABLE")
        val listening = runCatching { ServerSocket(PORT) }.getOrElse { return fail("VIDEO PORT UNAVAILABLE") }
        server = listening
        offered = true
        onOffer(
            JSONObject()
                .put("sessionId", sessionId)
                .put("epoch", epoch)
                .put("mode", "p2p")
                .put("ssid", group.networkName)
                .put("passphrase", group.passphrase)
                .put("port", PORT)
                .put("token", token)
                .put("goIp", address),
        )
        onState("WAITING FOR VIDEO")
        executor.execute { acceptLoop(listening) }
    }

    private fun acceptLoop(listening: ServerSocket) {
        while (running && !listening.isClosed) {
            val accepted = runCatching { listening.accept() }.getOrNull() ?: continue
            handleClient(accepted)
        }
    }

    private fun handleClient(candidate: Socket) {
        closeSocket()
        try {
            candidate.tcpNoDelay = true
            candidate.keepAlive = true
            candidate.soTimeout = HELLO_TIMEOUT_MS
            val input = candidate.getInputStream()
            val hello = MediaLinkProtocol.read(input) ?: error("Missing video HELLO")
            require(hello.type == MediaLinkPacketType.HELLO) { "Invalid video HELLO" }
            require(JSONObject(hello.meta).optString("token") == token) { "Invalid video token" }
            require(JSONObject(hello.meta).optString("sessionId") == sessionId) { "Invalid video session" }
            require(hello.epoch == epoch) { "Invalid video epoch" }
            candidate.soTimeout = 0
            socket = candidate
            onState("VIDEO LINKED")
            while (running && !candidate.isClosed) {
                val packet = MediaLinkProtocol.read(input) ?: break
                if (packet.epoch != epoch) continue
                onPacket(packet)
            }
        } catch (_: Throwable) {
            // The sender is expected to reconnect with a fresh key frame.
        } finally {
            closeSocket(candidate)
            if (running) onState("WAITING FOR VIDEO")
        }
    }

    private fun p2pAddress(group: WifiP2pGroup): String? = runCatching {
        NetworkInterface.getByName(group.`interface`)?.inetAddresses?.asSequence()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    private fun fail(message: String) { onState(message); onFailure(message); close() }

    override fun close() {
        running = false
        runCatching { socket?.close() }; socket = null
        runCatching { server?.close() }; server = null
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        if (groupCreated) {
            manager?.removeGroup(channel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = Unit
                override fun onFailure(reason: Int) = Unit
            })
        }
        executor.shutdownNow()
    }

    private fun closeSocket(expected: Socket? = null) {
        if (expected != null && socket !== expected) return
        runCatching { socket?.close() }
        socket = null
    }

    private companion object {
        const val PORT = 38402
        const val GROUP_SETTLE_MS = 600L
        const val MAX_GROUP_POLLS = 20
        const val HELLO_TIMEOUT_MS = 10_000
    }
}
