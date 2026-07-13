package com.anezium.rokidbus.plugin.lens

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
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class PhoneLensLinkOffer(
    val sessionId: String,
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
) {
    companion object {
        fun parse(payload: JSONObject): PhoneLensLinkOffer? {
            if (payload.optInt("version", 1) != 1) return null
            val sessionId = payload.optString("sessionId")
            val ssid = payload.optString("ssid")
            val passphrase = payload.optString("passphrase")
            val token = payload.optString("token")
            val goIp = payload.optString("goIp")
            val port = payload.optInt("port")
            if (sessionId.isBlank() || sessionId.length > 128 ||
                ssid.isBlank() || ssid.length > 128 ||
                passphrase.length !in 8..128 || token.length !in 16..256 ||
                goIp.isBlank() || goIp.length > 64 || port !in 1..65535
            ) return null
            return PhoneLensLinkOffer(sessionId, ssid, passphrase, port, token, goIp)
        }
    }
}

internal enum class PhoneLensLinkState { IDLE, WAITING_NETWORK, CONNECTING, CONNECTED }

/** Joins the offered Wi-Fi Direct group as a P2P client; default-data routing is unchanged. */
internal class PhoneLensImageLink(
    context: Context,
    private val logger: (String) -> Unit,
    private val onPacket: (CameraLinkPacket) -> Unit = {},
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
    @Volatile private var p2pConnecting = false
    @Volatile private var groupOwnerAddress: String? = null
    @Volatile private var socket: Socket? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var connectionInfoPoll: Runnable? = null
    private var joinRetry: Runnable? = null
    private var tcpRetry: ScheduledFuture<*>? = null
    private var conflictRecoveryAttempted = false
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
                    if (
                        enabled && p2pRunning && !p2pConnecting &&
                        state != PhoneLensLinkState.CONNECTED && socket == null &&
                        !tcpConnecting.get()
                    ) {
                        currentSession()?.let { (expectedGeneration, currentOffer) ->
                            inspectGroupThenConnect(expectedGeneration, currentOffer)
                        }
                    } else if (!enabled && p2pRunning) {
                        log("lensLinkP2pDisabled")
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
            teardownP2p()
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
        conflictRecoveryAttempted = false
        state = PhoneLensLinkState.WAITING_NETWORK
        registerReceiver()
        mainHandler.postDelayed(timeout, TIMEOUT_MS)
        inspectGroupThenConnect(expectedGeneration, currentOffer)
    }

    @SuppressLint("MissingPermission")
    private fun inspectGroupThenConnect(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || isConnectedOrConnectingSocket()) return
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        clearJoinRetry()
        clearConnectionInfoPoll()
        localManager.requestGroupInfo(localChannel) { group ->
            if (!isP2pCurrent(expectedGeneration, currentOffer) || isConnectedOrConnectingSocket()) return@requestGroupInfo
            when {
                group == null -> connectByCredentials(expectedGeneration, currentOffer)
                group.isGroupOwner -> recoverConflictingGroup(expectedGeneration, currentOffer)
                group.networkName == currentOffer.ssid -> {
                    log("lensLinkGroupReuse")
                    p2pConnecting = true
                    requestConnectionInfo(expectedGeneration, currentOffer)
                }
                else -> recoverConflictingGroup(expectedGeneration, currentOffer)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun recoverConflictingGroup(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
    ) {
        if (!isP2pCurrent(expectedGeneration, currentOffer) || isConnectedOrConnectingSocket()) return
        if (conflictRecoveryAttempted) return failP2p("lensLinkP2pConflict")
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        conflictRecoveryAttempted = true
        p2pConnecting = false
        groupOwnerAddress = null
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleJoinRetry(expectedGeneration, currentOffer, RESET_SETTLE_MS)
            override fun onFailure(reason: Int) = failP2p("lensLinkP2pConflict reason=$reason")
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
        if (!isP2pCurrent(expectedGeneration, currentOffer) ||
            isConnectedOrConnectingSocket() || p2pConnecting
        ) return
        clearJoinRetry()
        val localManager = manager ?: return failP2p("lensLinkP2pManagerLost")
        val localChannel = channel ?: return failP2p("lensLinkP2pChannelLost")
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(currentOffer.ssid)
                .setPassphrase(currentOffer.passphrase)
                .enablePersistentMode(true)
                .build()
        }.getOrElse {
            failP2p("lensLinkConfigBuildFailed")
            return
        }
        p2pConnecting = true
        state = PhoneLensLinkState.CONNECTING
        log("lensLinkJoinByCredentials")
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
                    scheduleJoinRetry(expectedGeneration, currentOffer, RETRY_MS)
                }
            },
        )
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
            p2pConnecting = false
            recoverConflictingGroup(expectedGeneration, currentOffer)
            return
        }

        val callbackAddress = info.groupOwnerAddress?.hostAddress.orEmpty()
        val ownerAddress = callbackAddress.ifBlank {
            log("lensLinkOwnerAddressFallback")
            currentOffer.goIp
        }
        p2pConnecting = false
        groupOwnerAddress = ownerAddress
        clearJoinRetry()
        clearConnectionInfoPoll()
        mainHandler.removeCallbacks(timeout)
        state = PhoneLensLinkState.CONNECTING
        executor.execute { connectToGroupOwner(expectedGeneration, currentOffer, ownerAddress) }
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

    private fun scheduleJoinRetry(
        expectedGeneration: Long,
        currentOffer: PhoneLensLinkOffer,
        delayMs: Long,
    ) {
        clearJoinRetry()
        joinRetry = Runnable {
            joinRetry = null
            connectByCredentials(expectedGeneration, currentOffer)
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun clearJoinRetry() {
        joinRetry?.let(mainHandler::removeCallbacks)
        joinRetry = null
    }

    private fun clearTcpRetry() {
        tcpRetry?.cancel(false)
        tcpRetry = null
    }

    private fun isConnectedOrConnectingSocket(): Boolean =
        state == PhoneLensLinkState.CONNECTED || socket != null || tcpConnecting.get()

    private fun registerReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
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
            CameraLinkProtocol.write(
                output,
                CameraLinkPacket(
                    type = CameraLinkPacketType.HELLO,
                    requestId = requestId,
                    meta = JSONObject().put("token", currentOffer.token).toString(),
                ),
            )
            state = PhoneLensLinkState.CONNECTED
            clearJoinRetry()
            clearConnectionInfoPoll()
            clearTcpRetry()
            mainHandler.removeCallbacks(timeout)
            log("lensLinkConnected")

            val probe = ByteArray(PROBE_BYTES).also(random::nextBytes)
            val probeStartedAt = SystemClock.elapsedRealtime()
            CameraLinkProtocol.write(
                output,
                CameraLinkPacket(CameraLinkPacketType.PROBE, requestId, payload = probe),
            )
            while (isSocketCurrent(expectedGeneration, currentOffer, ownerAddress) &&
                !currentSocket.isClosed
            ) {
                val packet = CameraLinkProtocol.read(input) ?: break
                if (packet.type == CameraLinkPacketType.PROBE_ACK && packet.requestId == requestId) {
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
                clearTcpRetry()
                tcpRetry = executor.schedule(
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
        teardownP2p()
    }

    private fun closeSocket() {
        runCatching { socket?.close() }
        socket = null
        tcpConnecting.set(false)
    }

    private fun teardownP2p() {
        p2pRunning = false
        p2pConnecting = false
        groupOwnerAddress = null
        mainHandler.removeCallbacks(timeout)
        clearJoinRetry()
        clearConnectionInfoPoll()
        clearTcpRetry()
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
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
        teardownP2p()
        executor.shutdownNow()
    }

    companion object {
        private const val TAG = "RokidBusLens"
        private const val PROBE_BYTES = 512 * 1024
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val TIMEOUT_MS = 60_000L
        private const val RETRY_MS = 1_000L
        private const val CONNECTION_INFO_POLL_MS = 1_000L
        private const val RESET_SETTLE_MS = 500L
    }
}
