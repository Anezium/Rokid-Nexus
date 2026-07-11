package com.anezium.rokidbus.phone.lens

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.MacAddress
import android.net.NetworkInfo
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pDevice
import android.net.wifi.p2p.WifiP2pDeviceList
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.LensLinkPacket
import com.anezium.rokidbus.shared.LensLinkPacketType
import com.anezium.rokidbus.shared.LensLinkProtocol
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class PhoneLensLinkOffer(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
) {
    companion object {
        fun parse(payload: JSONObject): PhoneLensLinkOffer? {
            if (payload.optInt("version", 1) != 1) return null
            val ssid = payload.optString("ssid")
            val passphrase = payload.optString("passphrase")
            val token = payload.optString("token")
            val goIp = payload.optString("goIp")
            val port = payload.optInt("port")
            if (ssid.isBlank() || ssid.length > 128 ||
                passphrase.length !in 8..128 || token.length !in 16..256 ||
                goIp.isBlank() || goIp.length > 64 || port !in 1..65535
            ) return null
            return PhoneLensLinkOffer(ssid, passphrase, port, token, goIp)
        }
    }
}

internal enum class PhoneLensLinkState { IDLE, WAITING_NETWORK, CONNECTING, CONNECTED }

/** Joins the offered Wi-Fi Direct group as a P2P client; default-data routing is unchanged. */
internal class PhoneLensImageLink(
    context: Context,
    private val logger: (String) -> Unit,
    private val onPacket: (LensLinkPacket) -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = ScheduledThreadPoolExecutor(2) { runnable ->
        Thread(runnable, "lens-phone-link").apply { isDaemon = true }
    }.apply { setRemoveOnCancelPolicy(true) }
    private val generation = AtomicLong(0L)
    private val tcpConnecting = AtomicBoolean(false)
    private val random = SecureRandom()

    @Volatile private var closed = false
    @Volatile private var offer: PhoneLensLinkOffer? = null
    @Volatile private var p2pRunning = false
    @Volatile private var discovering = false
    @Volatile private var p2pConnecting = false
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var socket: Socket? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var connectionInfoPoll: Runnable? = null
    private var peerPoll: Runnable? = null
    @Volatile var state: PhoneLensLinkState = PhoneLensLinkState.IDLE
        private set

    private val timeout = Runnable {
        failP2p("lensLinkP2pTimeout")
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (enabled && p2pRunning && !discovering && !p2pConnecting) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            startDiscovery(expectedGeneration, currentOffer)
                        }
                    } else if (!enabled && p2pRunning) {
                        log("lensLinkP2pDisabled")
                    }
                }

                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    if (discovering) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            requestPeers(expectedGeneration, currentOffer)
                        }
                    }
                }

                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val networkInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO,
                            NetworkInfo::class.java,
                        )
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (networkInfo?.isConnected == true) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            requestConnectionInfo(expectedGeneration, currentOffer)
                        }
                    }
                }
            }
        }
    }

    fun updateOffer(payload: JSONObject) {
        if (closed) return
        val parsed = PhoneLensLinkOffer.parse(payload)
        if (parsed == null) {
            log("lensLinkOfferRejected")
            return
        }
        if (offer == parsed && state != PhoneLensLinkState.IDLE) return
        offer = parsed
        val nextGeneration = generation.incrementAndGet()
        closeSocket()
        mainHandler.post {
            teardownP2p(removeGroup = true)
            if (isCurrent(nextGeneration, parsed)) startP2p(nextGeneration, parsed)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startP2p(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isCurrent(expectedGeneration, currentOffer)) return
        if (!hasWifiPermission()) {
            state = PhoneLensLinkState.IDLE
            log("lensLinkPermissionMissing")
            return
        }
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) {
            state = PhoneLensLinkState.IDLE
            log("lensLinkP2pUnavailable")
            return
        }
        p2pRunning = true
        state = PhoneLensLinkState.WAITING_NETWORK
        registerReceiver()
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
        resetGroupThenDiscover(expectedGeneration, currentOffer)
    }

    @SuppressLint("MissingPermission")
    private fun resetGroupThenDiscover(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        discovering = false
        p2pConnecting = false
        groupOwnerAddress = null
        clearPeerPoll()
        clearConnectionInfoPoll()
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                mainHandler.postDelayed(
                    { connectByCredentials(expectedGeneration, currentOffer) },
                    RESET_SETTLE_MS,
                )
            }

            override fun onFailure(reason: Int) {
                mainHandler.postDelayed(
                    { connectByCredentials(expectedGeneration, currentOffer) },
                    RESET_SETTLE_MS,
                )
            }
        })
    }

    /**
     * Joins the glasses' autonomous group directly by SSID + passphrase. An autonomous group
     * owner is not returned by discoverPeers() on this hardware, so peer discovery/matching is
     * skipped entirely; a credential-only WifiP2pConfig also avoids the WifiNetworkSpecifier
     * system approval dialog.
     */
    @SuppressLint("MissingPermission")
    private fun connectByCredentials(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(currentOffer.ssid)
                .setPassphrase(currentOffer.passphrase)
                .enablePersistentMode(false)
                .build()
        }.getOrElse {
            failP2p("lensLinkConfigBuildFailed")
            return
        }
        p2pConnecting = true
        discovering = false
        state = PhoneLensLinkState.CONNECTING
        log("lensLinkJoinByCredentials ssid=${currentOffer.ssid}")
        localManager.connect(
            localChannel,
            config,
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isP2pCurrent(expectedGeneration, currentOffer)) return
                    scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = false)
                }

                override fun onFailure(reason: Int) {
                    if (!isP2pCurrent(expectedGeneration, currentOffer)) return
                    p2pConnecting = false
                    log("lensLinkJoinRetry reason=$reason")
                    mainHandler.postDelayed(
                        { connectByCredentials(expectedGeneration, currentOffer) },
                        RETRY_MS,
                    )
                }
            },
        )
    }

    @SuppressLint("MissingPermission")
    private fun startDiscovery(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        discovering = true
        state = PhoneLensLinkState.WAITING_NETWORK
        localManager.discoverPeers(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                requestPeers(expectedGeneration, currentOffer)
                schedulePeerPoll(expectedGeneration, currentOffer)
            }

            override fun onFailure(reason: Int) {
                if (!isP2pCurrent(expectedGeneration, currentOffer)) return
                log("lensLinkDiscoveryRetry reason=$reason")
                mainHandler.postDelayed(
                    { startDiscovery(expectedGeneration, currentOffer) },
                    RETRY_MS,
                )
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun requestPeers(expectedGeneration: Long, currentOffer: PhoneLensLinkOffer) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return
        val localChannel = channel ?: return
        localManager.requestPeers(localChannel) { peers ->
            handlePeers(expectedGeneration, currentOffer, peers)
        }
    }

    private fun handlePeers(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        peers: WifiP2pDeviceList,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || p2pConnecting) return
        val match = peers.deviceList.firstOrNull { isTargetDevice(it, currentOffer.ssid) }
        if (match == null) {
            log("lensLinkPeerNotVisible")
            return
        }
        clearPeerPoll()
        discovering = false
        connect(expectedGeneration, currentOffer, match)
    }

    private fun isTargetDevice(device: WifiP2pDevice, offeredSsid: String): Boolean {
        val nameMatches = device.deviceName.equals(offeredSsid, ignoreCase = true)
        val fuzzyNameMatches = device.deviceName.isNotBlank() &&
            (device.deviceName.contains(offeredSsid, ignoreCase = true) ||
                offeredSsid.contains(device.deviceName, ignoreCase = true))
        return nameMatches || fuzzyNameMatches
    }

    @SuppressLint("MissingPermission")
    private fun connect(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        device: WifiP2pDevice,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        p2pConnecting = true
        state = PhoneLensLinkState.CONNECTING
        localManager.connect(
            localChannel,
            buildConfig(device, currentOffer),
            object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!isP2pCurrent(expectedGeneration, currentOffer)) return
                    runCatching { localManager.stopPeerDiscovery(localChannel, null) }
                    scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = false)
                }

                override fun onFailure(reason: Int) {
                    if (!isP2pCurrent(expectedGeneration, currentOffer)) return
                    p2pConnecting = false
                    log("lensLinkConnectRetry reason=$reason")
                    mainHandler.postDelayed(
                        { connect(expectedGeneration, currentOffer, device) },
                        RETRY_MS,
                    )
                }
            },
        )
    }

    private fun buildConfig(
        device: WifiP2pDevice,
        currentOffer: PhoneLensLinkOffer,
    ): WifiP2pConfig = runCatching {
        WifiP2pConfig.Builder()
            .setDeviceAddress(MacAddress.fromString(device.deviceAddress))
            .setNetworkName(currentOffer.ssid)
            .setPassphrase(currentOffer.passphrase)
            .enablePersistentMode(false)
            .build()
    }.getOrElse {
        WifiP2pConfig().apply {
            deviceAddress = device.deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        localManager.requestConnectionInfo(localChannel) { info ->
            handleConnectionInfo(expectedGeneration, currentOffer, info)
        }
    }

    private fun handleConnectionInfo(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        info: WifiP2pInfo?,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer)) return
        if (info?.groupFormed != true) {
            scheduleConnectionInfoPoll(expectedGeneration, currentOffer, immediate = false)
            return
        }
        if (info.isGroupOwner) {
            log("lensLinkUnexpectedGroupOwner")
            mainHandler.postDelayed(
                { resetGroupThenDiscover(expectedGeneration, currentOffer) },
                RETRY_MS,
            )
            return
        }

        val callbackAddress = info.groupOwnerAddress?.hostAddress.orEmpty()
        val ownerAddress = callbackAddress.ifBlank {
            log("lensLinkOwnerAddressFallback")
            currentOffer.goIp
        }
        p2pConnecting = false
        discovering = false
        groupOwnerAddress = ownerAddress
        clearConnectionInfoPoll()
        mainHandler.removeCallbacks(timeout)
        state = PhoneLensLinkState.CONNECTING
        executor.execute { connectToGroupOwner(expectedGeneration, currentOffer, ownerAddress) }
    }

    private fun schedulePeerPoll(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        clearPeerPoll()
        peerPoll = Runnable {
            peerPoll = null
            if (isP2pCurrent(expectedGeneration, currentOffer) && discovering) {
                requestPeers(expectedGeneration, currentOffer)
                schedulePeerPoll(expectedGeneration, currentOffer)
            }
        }.also { mainHandler.postDelayed(it, PEER_POLL_MS) }
    }

    private fun clearPeerPoll() {
        peerPoll?.let(mainHandler::removeCallbacks)
        peerPoll = null
    }

    private fun scheduleConnectionInfoPoll(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        immediate: Boolean,
        delayMs: Long = CONNECTION_INFO_POLL_MS,
    ) {
        clearConnectionInfoPoll()
        connectionInfoPoll = Runnable {
            connectionInfoPoll = null
            if (isP2pCurrent(expectedGeneration, currentOffer) && p2pConnecting) {
                requestConnectionInfo(expectedGeneration, currentOffer)
            }
        }.also { mainHandler.postDelayed(it, if (immediate) 0L else delayMs) }
    }

    private fun clearConnectionInfoPoll() {
        connectionInfoPoll?.let(mainHandler::removeCallbacks)
        connectionInfoPoll = null
    }

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        receiverRegistered = true
    }

    private fun connectToGroupOwner(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        ownerAddress: String,
    ) {
        if (!isSocketCurrent(expectedGeneration, currentOffer, ownerAddress) ||
            !tcpConnecting.compareAndSet(false, true)
        ) return
        state = PhoneLensLinkState.CONNECTING
        var currentSocket: Socket? = null
        try {
            currentSocket = Socket()
            currentSocket.tcpNoDelay = true
            currentSocket.keepAlive = true
            currentSocket.connect(InetSocketAddress(ownerAddress, currentOffer.port), CONNECT_TIMEOUT_MS)
            if (!isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) return
            socket = currentSocket
            val output = currentSocket.getOutputStream()
            val input = currentSocket.getInputStream()
            val requestId = random.nextLong()
            LensLinkProtocol.write(
                output,
                LensLinkPacket(
                    type = LensLinkPacketType.HELLO,
                    requestId = requestId,
                    meta = JSONObject().put("token", currentOffer.token).toString(),
                ),
            )
            state = PhoneLensLinkState.CONNECTED
            log("lensLinkConnected")

            val probe = ByteArray(PROBE_BYTES).also(random::nextBytes)
            val probeStartedAt = SystemClock.elapsedRealtime()
            LensLinkProtocol.write(
                output,
                LensLinkPacket(LensLinkPacketType.PROBE, requestId, payload = probe),
            )
            while (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress) &&
                !currentSocket.isClosed
            ) {
                val packet = LensLinkProtocol.read(input) ?: break
                if (packet.type == LensLinkPacketType.PROBE_ACK && packet.requestId == requestId) {
                    val elapsedMs = (SystemClock.elapsedRealtime() - probeStartedAt).coerceAtLeast(1L)
                    val bytes = runCatching { JSONObject(packet.meta).optInt("bytes", PROBE_BYTES) }
                        .getOrDefault(PROBE_BYTES)
                    log("lensLinkProbe bytes=$bytes ms=$elapsedMs mbps=${"%.1f".format(bytes * 8.0 / elapsedMs / 1000.0)}")
                } else {
                    onPacket(packet)
                }
            }
        } catch (failure: Throwable) {
            if (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) {
                log("lensLinkSocketFailure type=${failure.javaClass.simpleName}")
            }
        } finally {
            if (socket === currentSocket) socket = null
            runCatching { currentSocket?.close() }
            tcpConnecting.set(false)
            if (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress)) {
                state = PhoneLensLinkState.WAITING_NETWORK
                executor.schedule(
                    { connectToGroupOwner(expectedGeneration, currentOffer, ownerAddress) },
                    RETRY_MS,
                    TimeUnit.MILLISECONDS,
                )
            }
        }
    }

    private fun hasWifiPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return appContext.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun currentSession(): Pair<Long, PhoneLensLinkOffer>? {
        val currentOffer = offer ?: return null
        return generation.get() to currentOffer
    }

    private fun isCurrent(expectedGeneration: Long, expectedOffer: PhoneLensLinkOffer): Boolean =
        !closed && generation.get() == expectedGeneration && offer == expectedOffer

    private fun isP2pCurrent(
        expectedGeneration: Long,
        expectedOffer: PhoneLensLinkOffer,
    ): Boolean = p2pRunning && isCurrent(expectedGeneration, expectedOffer)

    private fun isSocketCurrent(
        expectedGeneration: Long,
        expectedOffer: PhoneLensLinkOffer,
        expectedOwnerAddress: String,
    ): Boolean = isP2pCurrent(expectedGeneration, expectedOffer) &&
        groupOwnerAddress == expectedOwnerAddress

    private fun log(message: String) {
        Log.i(TAG, message)
        logger(message)
    }

    private fun failP2p(message: String) {
        if (!p2pRunning) return
        log(message)
        state = PhoneLensLinkState.IDLE
        closeSocket()
        teardownP2p(removeGroup = true)
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        tcpConnecting.set(false)
    }

    @SuppressLint("MissingPermission")
    private fun teardownP2p(removeGroup: Boolean) {
        p2pRunning = false
        discovering = false
        p2pConnecting = false
        groupOwnerAddress = null
        mainHandler.removeCallbacks(timeout)
        clearPeerPoll()
        clearConnectionInfoPoll()
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
            if (removeGroup) runCatching { localManager.removeGroup(localChannel, null) }
        }
        if (receiverRegistered) {
            runCatching { appContext.unregisterReceiver(receiver) }
            receiverRegistered = false
        }
        manager = null
        channel = null
    }

    override fun close() {
        if (closed) return
        closed = true
        generation.incrementAndGet()
        state = PhoneLensLinkState.IDLE
        offer = null
        closeSocket()
        teardownP2p(removeGroup = true)
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "RokidBusLens"
        private const val PROBE_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val TIMEOUT_MS = 60_000L
        private const val RETRY_MS = 1_000L
        private const val PEER_POLL_MS = 1_500L
        private const val CONNECTION_INFO_POLL_MS = 1_000L
        private const val RESET_SETTLE_MS = 500L
    }
}
