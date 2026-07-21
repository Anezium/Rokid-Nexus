package com.anezium.rokidbus.glasses

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
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
import com.anezium.rokidbus.shared.CameraLinkPacketFlags
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import com.anezium.rokidbus.shared.CameraLinkEndpointOffer
import com.anezium.rokidbus.shared.CameraLinkMode
import com.anezium.rokidbus.shared.CameraLinkSecurity
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
import java.util.concurrent.atomic.AtomicBoolean

internal data class CameraLinkOffer(
    val ssid: String,
    val passphrase: String,
    val port: Int,
    val token: String,
    val goIp: String,
)

private enum class CameraLinkTransportRole { P2P_SERVER, LOHS_CLIENT }

internal fun CameraLinkSecurity.toWifiConnectSecurity(): WifiConnectSecurity = when (this) {
    CameraLinkSecurity.OPEN -> WifiConnectSecurity.OPEN
    CameraLinkSecurity.WPA2_PSK -> WifiConnectSecurity.WPA2
    CameraLinkSecurity.WPA3_SAE -> WifiConnectSecurity.WPA3
}

/** Activity-scoped camera data plane. The server remains listening across client crashes. */
internal class CameraLink(
    context: Context,
    private val sessionStartedAtMs: Long,
    private val startupMode: CameraLinkStartupMode,
    private val onOfferReady: (CameraLinkOffer, Int) -> Unit,
    private val onAuthenticated: (Boolean) -> Unit,
    private val onFrozenTransferFinished: (Long) -> Unit,
    private val onWifiJoinRequested: (CameraLinkEndpointOffer, WifiConnectSecurity) -> Unit,
    private val onState: (String) -> Unit,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val profileStore = CameraP2pProfileStore(appContext)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-link-accept").apply { isDaemon = true }
    }
    private val writerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-link-writer").apply { isDaemon = true }
    }
    private val packets = ArrayBlockingQueue<CameraLinkPacket>(NETWORK_QUEUE_CAPACITY)
    private val packetQueueLock = Any()
    private val transferPolicy = CameraLinkTransferPolicy()
    private val offerRetryPolicy = CameraLinkOfferRetryPolicy(
        coldGroupSettleMs = COLD_GROUP_SETTLE_MS,
        intervalMs = OFFER_RETRY_MS,
        maxOffers = MAX_OFFER_SENDS,
        offersBeforeGroupRecreate = OFFERS_BEFORE_GO_RECOVERY,
        offersBetweenGroupRecreates = OFFERS_BETWEEN_GO_RECOVERIES,
        maxGroupRecreates = MAX_GO_RECREATES,
    )
    private val profileRecoveryPolicy = CameraP2pProfileRecoveryPolicy()
    private val groupRemovalGracePolicy = CameraLinkGroupRemovalGracePolicy(
        clientGraceMs = ASSOCIATED_CLIENT_GRACE_MS,
        maxPolls = MAX_GO_RECOVERY_CLIENT_POLLS,
    )
    private val reverseOfferFallbackPolicy = CameraLinkReverseOfferFallbackPolicy(
        timeoutMs = REVERSE_OFFER_FALLBACK_MS,
    )
    private val token = ByteArray(24).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING)
    }
    private val socketLock = Any()

    @Volatile private var running = false
    @Volatile private var authenticated = false
    @Volatile private var serverSocket: ServerSocket? = null
    @Volatile private var clientSocket: Socket? = null
    @Volatile private var transportRole = CameraLinkTransportRole.P2P_SERVER
    @Volatile private var reverseConnecting = false
    @Volatile private var reverseJoinReady = false
    private var clientOutput: java.io.OutputStream? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var createAttempts = 0
    private var wifiWaitAttempts = 0
    private var configuredCreateAttempted = false
    private var legacyCreateAttempted = false
    private var conflictRecoveryAttempted = false
    private var waitingForCreatedGroup = false
    private var createRetry: Runnable? = null
    private var wifiRetry: Runnable? = null
    private var groupPoll: Runnable? = null
    private var offerRetry: Runnable? = null
    private var goRecoveryCreate: Runnable? = null
    private var goRecoveryClientPoll: Runnable? = null
    private var goRecoveryGroupInfoTimeout: Runnable? = null
    private var currentOffer: CameraLinkOffer? = null
    private var offersSent = 0
    private var groupRecreatesDone = 0
    private var goRecoveryInProgress = false
    private var goRecoveryRemovedGroup = false
    private var groupStageLogged = false
    private var initialOfferDelayMs = 0L
    private var goRecoveryGroupInfoRequestId = 0L
    private var reverseOffer: CameraLinkEndpointOffer? = null
    private var reverseGatewayIp: String? = null
    private var reverseJoinStartedAtMs = 0L
    private var reverseNetworkPoll: Runnable? = null
    private var reverseP2pTeardownTimeout: Runnable? = null
    private var reverseOfferFallback: Runnable? = null
    private var writerStarted = false
    private var p2pStartupStarted = false

    val isAuthenticated: Boolean get() = authenticated

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running && transportRole == CameraLinkTransportRole.P2P_SERVER &&
                        enabled && currentOffer == null
                    ) inspectGroup()
                    if (running && transportRole == CameraLinkTransportRole.P2P_SERVER && !enabled) {
                        state("WAITING FOR WI-FI")
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO, NetworkInfo::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_NETWORK_INFO)
                    }
                    if (running && transportRole == CameraLinkTransportRole.P2P_SERVER &&
                        (info?.isConnected == true || currentOffer == null)
                    ) requestGroupInfo()
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        running = true
        if (startupMode == CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE) {
            startWriter()
            waitForReverseOffer()
        } else {
            startP2pPath()
        }
    }

    private fun startP2pPath() {
        if (!running || p2pStartupStarted || transportRole != CameraLinkTransportRole.P2P_SERVER) return
        p2pStartupStarted = true
        manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
        channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
        if (manager == null || channel == null) return fail("WI-FI DIRECT UNAVAILABLE")
        registerReceiver()
        startWriter()
        if (isWifiReady()) inspectGroup() else waitForWifi()
    }

    private fun startWriter() {
        if (writerStarted) return
        writerStarted = true
        writerExecutor.execute(::writerLoop)
    }

    private fun waitForReverseOffer() {
        if (!running || startupMode != CameraLinkStartupMode.WAIT_FOR_LOHS_REVERSE) return
        state("WAITING FOR PHONE")
        onOfferReady(reverseBootstrapOffer(), REVERSE_BOOTSTRAP_OFFER_NUMBER)
        reverseOfferFallback = Runnable {
            reverseOfferFallback = null
            if (reverseOfferFallbackPolicy.shouldStartP2p(startupMode, reverseOffer != null)) {
                Log.w(TAG, "cameraLinkReverseOfferTimeout fallback=p2p elapsedMs=${stageElapsedMs()}")
                startP2pPath()
            }
        }.also { mainHandler.postDelayed(it, reverseOfferFallbackPolicy.timeoutMs) }
    }

    private fun reverseBootstrapOffer(): CameraLinkOffer = CameraLinkOffer(
        ssid = "DIRECT-LR-${token.take(6)}",
        passphrase = token.take(24),
        port = PORT,
        token = token,
        goIp = DEFAULT_GO_IP,
    )

    fun resendOfferIfDisconnected() {
        if (authenticated || transportRole != CameraLinkTransportRole.P2P_SERVER) return
        mainHandler.post {
            if (running && !authenticated && !goRecoveryInProgress && currentOffer != null) {
                clearOfferRetry()
                runNextOfferAction()
            }
        }
    }

    /** Never performs socket I/O on the codec callback thread. */
    fun enqueue(packet: CameraLinkPacket): Boolean {
        synchronized(packetQueueLock) {
            if (!authenticated) return false
            if (packet.type == CameraLinkPacketType.VIDEO_FRAME &&
                !transferPolicy.shouldAdmitVideo(packet.isKeyFrame())
            ) return false
            if (packet.type == CameraLinkPacketType.FROZEN_IMAGE) {
                transferPolicy.beginFrozenMode(packet.requestId)
                dropQueuedVideoFrames()
            }
            if (packets.offer(packet)) return true
            return if (packet.type == CameraLinkPacketType.VIDEO_FRAME) {
                dropOneVideoFrame() && packets.offer(packet)
            } else {
                while (packets.remainingCapacity() == 0 && dropOneVideoFrame()) Unit
                packets.offer(packet)
            }
        }
    }

    /** Starts the video gate at the tap, before the camera produces the large JPEG. */
    fun beginFrozenTransfer(requestId: Long): Boolean = synchronized(packetQueueLock) {
        if (!authenticated) return false
        transferPolicy.beginFrozenMode(requestId)
        dropQueuedVideoFrames()
        true
    }

    /** Ends frozen mode, cancels queued JPEG work, and requests a fresh resume key frame. */
    fun cancelFrozenTransfer(requestId: Long): Boolean {
        val shouldResume = synchronized(packetQueueLock) {
            packets.removeIf {
                it.type == CameraLinkPacketType.FROZEN_IMAGE && it.requestId == requestId
            }
            transferPolicy.endFrozenMode(requestId)
        }
        if (shouldResume) mainHandler.post { onFrozenTransferFinished(requestId) }
        return shouldResume
    }

    fun abortClient() {
        runCatching { clientSocket?.close() }
    }

    fun acceptReverseOffer(offer: CameraLinkEndpointOffer) {
        if (!running || offer.mode != CameraLinkMode.LOHS_REVERSE || offer.token != token) return
        mainHandler.post {
            if (!running || offer.token != token) return@post
            clearReverseOfferFallback()
            val sameOffer = reverseOffer == offer
            reverseOffer = offer
            if (!sameOffer) reverseGatewayIp = null
            if (transportRole != CameraLinkTransportRole.LOHS_CLIENT) {
                transportRole = CameraLinkTransportRole.LOHS_CLIENT
                deactivateP2pForReverse(offer)
            } else if (!sameOffer) {
                closeClient()
                beginReverseJoin(offer)
            } else if (!authenticated) {
                scheduleReverseNetworkPoll(0L)
            }
        }
    }

    private fun dropOneVideoFrame(): Boolean {
        val stale = packets.firstOrNull { it.type == CameraLinkPacketType.VIDEO_FRAME } ?: return false
        return packets.remove(stale)
    }

    private fun dropQueuedVideoFrames() {
        packets.removeIf { it.type == CameraLinkPacketType.VIDEO_FRAME }
    }

    private fun CameraLinkPacket.isKeyFrame(): Boolean =
        flags and CameraLinkPacketFlags.KEY_FRAME != 0

    @SuppressLint("MissingPermission")
    private fun deactivateP2pForReverse(offer: CameraLinkEndpointOffer) {
        reverseJoinReady = false
        clearCreateRetry()
        clearWifiRetry()
        clearGroupPoll()
        clearOfferRetry()
        clearGoRecoveryCreate()
        clearGoRecoveryClientInspection()
        goRecoveryInProgress = false
        goRecoveryRemovedGroup = false
        currentOffer = null
        offersSent = 0
        closeClient()
        closeServer()

        val localManager = manager
        val localChannel = channel
        manager = null
        channel = null
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        if (localManager == null || localChannel == null) {
            beginReverseJoin(offer)
            return
        }
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        val completed = AtomicBoolean(false)
        fun finish() {
            if (!completed.compareAndSet(false, true)) return
            reverseP2pTeardownTimeout?.let(mainHandler::removeCallbacks)
            reverseP2pTeardownTimeout = null
            runCatching { localChannel.close() }
            if (running && transportRole == CameraLinkTransportRole.LOHS_CLIENT &&
                reverseOffer == offer
            ) beginReverseJoin(offer)
        }
        reverseP2pTeardownTimeout = Runnable(::finish)
            .also { mainHandler.postDelayed(it, REVERSE_P2P_TEARDOWN_TIMEOUT_MS) }
        runCatching {
            localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = finish()
                override fun onFailure(reason: Int) {
                    Log.w(TAG, "cameraLinkReverseP2pRemoveFailed reason=$reason")
                    finish()
                }
            })
        }.onFailure {
            Log.w(TAG, "cameraLinkReverseP2pRemoveFailed type=${it.javaClass.simpleName}")
            finish()
        }
    }

    private fun beginReverseJoin(offer: CameraLinkEndpointOffer) {
        if (!running || transportRole != CameraLinkTransportRole.LOHS_CLIENT ||
            reverseOffer != offer
        ) return
        state("JOINING PHONE HOTSPOT")
        reverseJoinReady = true
        reverseJoinStartedAtMs = SystemClock.elapsedRealtime()
        onWifiJoinRequested(offer, offer.security.toWifiConnectSecurity())
        scheduleReverseNetworkPoll(0L, resetDeadline = false)
    }

    private fun scheduleReverseNetworkPoll(delayMs: Long, resetDeadline: Boolean = false) {
        clearReverseNetworkPoll()
        if (resetDeadline || reverseJoinStartedAtMs == 0L) {
            reverseJoinStartedAtMs = SystemClock.elapsedRealtime()
        }
        reverseNetworkPoll = Runnable {
            reverseNetworkPoll = null
            pollReverseNetwork()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    @Suppress("DEPRECATION")
    private fun pollReverseNetwork() {
        val offer = reverseOffer ?: return
        if (!running || transportRole != CameraLinkTransportRole.LOHS_CLIENT || authenticated ||
            reverseConnecting || !reverseJoinReady
        ) return
        if (SystemClock.elapsedRealtime() - reverseJoinStartedAtMs >= REVERSE_JOIN_TIMEOUT_MS) {
            fail("PHONE HOTSPOT LINK FAILED")
            return
        }
        val wifiManager = appContext.getSystemService(WifiManager::class.java)
        val connectedSsid = runCatching {
            LohsGatewayResolver.normalizeSsid(wifiManager?.connectionInfo?.ssid)
        }.getOrDefault("")
        val gateway = runCatching {
            LohsGatewayResolver.fromDhcpGateway(wifiManager?.dhcpInfo?.gateway ?: 0)
        }.getOrNull()
        if (connectedSsid == offer.ssid && gateway != null) {
            reverseGatewayIp = gateway
            connectToPhoneHotspot(offer, gateway)
        } else {
            scheduleReverseNetworkPoll(REVERSE_NETWORK_POLL_MS)
        }
    }

    private fun connectToPhoneHotspot(offer: CameraLinkEndpointOffer, gateway: String) {
        if (!isReverseCurrent(offer, gateway) || reverseConnecting || authenticated) return
        reverseConnecting = true
        state("CONNECTING TO PHONE")
        acceptExecutor.execute {
            var currentSocket: Socket? = null
            try {
                currentSocket = Socket().apply {
                    tcpNoDelay = true
                    keepAlive = true
                    connect(InetSocketAddress(gateway, offer.port), REVERSE_CONNECT_TIMEOUT_MS)
                }
                if (!isReverseCurrent(offer, gateway)) return@execute
                val input = currentSocket.getInputStream()
                val output = currentSocket.getOutputStream()
                CameraLinkProtocol.write(
                    output,
                    CameraLinkPacket(
                        type = CameraLinkPacketType.HELLO,
                        meta = JSONObject().put("token", token).toString(),
                    ),
                )
                synchronized(socketLock) {
                    if (!isReverseCurrent(offer, gateway)) return@synchronized
                    clientSocket = currentSocket
                    clientOutput = output
                    packets.clear()
                    authenticated = true
                    reverseJoinStartedAtMs = 0L
                }
                if (!authenticated || clientSocket !== currentSocket) return@execute
                Log.i(TAG, "cameraLinkStage stage=connected transport=lohs_reverse elapsedMs=${stageElapsedMs()}")
                mainHandler.post { onAuthenticated(true) }
                state("PHONE LINKED")
                while (isReverseCurrent(offer, gateway) && !currentSocket.isClosed) {
                    val packet = CameraLinkProtocol.read(input) ?: break
                    when (packet.type) {
                        CameraLinkPacketType.PROBE -> enqueue(
                            CameraLinkPacket(
                                type = CameraLinkPacketType.PROBE_ACK,
                                requestId = packet.requestId,
                                seq = packet.seq,
                                meta = JSONObject().put("bytes", packet.payload.size).toString(),
                            ),
                        )
                        CameraLinkPacketType.VIDEO_ACK, CameraLinkPacketType.PROBE_ACK -> Unit
                        else -> Log.w(TAG, "cameraLinkUnexpectedPacket type=${packet.type.name}")
                    }
                }
            } catch (failure: Throwable) {
                if (isReverseCurrent(offer, gateway)) {
                    Log.w(TAG, "cameraLinkReverseClientEnded type=${failure.javaClass.simpleName}")
                }
            } finally {
                reverseConnecting = false
                if (clientSocket === currentSocket) {
                    closeClient(currentSocket)
                } else {
                    runCatching { currentSocket?.close() }
                    if (running && transportRole == CameraLinkTransportRole.LOHS_CLIENT &&
                        !authenticated
                    ) {
                        mainHandler.post {
                            if (running && transportRole == CameraLinkTransportRole.LOHS_CLIENT) {
                                scheduleReverseNetworkPoll(REVERSE_CONNECT_RETRY_MS)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun isReverseCurrent(offer: CameraLinkEndpointOffer, gateway: String): Boolean =
        running && transportRole == CameraLinkTransportRole.LOHS_CLIENT &&
            reverseOffer == offer && reverseGatewayIp == gateway

    @SuppressLint("MissingPermission")
    private fun inspectGroup() {
        if (!running || transportRole != CameraLinkTransportRole.P2P_SERVER) return
        if (!isWifiReady()) return waitForWifi()
        state("INSPECTING CAMERA LINK")
        requestGroupInfo()
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
        if (transportRole != CameraLinkTransportRole.P2P_SERVER) return
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        runCatching { localManager.requestGroupInfo(localChannel, ::handleGroup) }
            .onFailure {
                if (running) {
                    if (waitingForCreatedGroup) scheduleGroupPoll(GROUP_POLL_MS)
                    else createConfiguredGroup()
                }
            }
    }

    private fun handleGroup(group: WifiP2pGroup?) {
        if (!running || transportRole != CameraLinkTransportRole.P2P_SERVER) return
        if (isUsableOwnerGroup(group)) {
            activateGroup(group!!, if (waitingForCreatedGroup) "created" else "active_reuse")
            return
        }
        if (waitingForCreatedGroup) {
            scheduleGroupPoll(GROUP_POLL_MS)
            return
        }
        if (group == null) {
            createConfiguredGroup()
        } else {
            recoverConflictingGroup()
        }
    }

    private fun isUsableOwnerGroup(group: WifiP2pGroup?): Boolean =
        group != null && group.isGroupOwner && group.networkName.isNotBlank() &&
            group.passphrase.isNotBlank() && group.getInterface().isNotBlank()

    @SuppressLint("MissingPermission")
    private fun recoverConflictingGroup() {
        if (conflictRecoveryAttempted) return fail("CAMERA LINK CONFLICT")
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        conflictRecoveryAttempted = true
        state("RECOVERING CAMERA LINK")
        runCatching { localManager.stopPeerDiscovery(localChannel, null) }
        runCatching { localManager.cancelConnect(localChannel, null) }
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleCreate(RECOVERY_SETTLE_MS, ::createConfiguredGroup)
            override fun onFailure(reason: Int) = fail("CAMERA LINK CONFLICT")
        })
    }

    @SuppressLint("MissingPermission")
    private fun createConfiguredGroup() {
        if (!running || configuredCreateAttempted) return
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        configuredCreateAttempted = true
        createAttempts += 1
        state("CREATING CAMERA LINK")
        Log.i(TAG, "cameraLinkCreate path=configured elapsedMs=${stageElapsedMs()}")
        val profile = profileStore.loadOrCreate()
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(profile.networkName)
                .setPassphrase(profile.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build()
        }.getOrElse {
            Log.w(TAG, "cameraLinkConfiguredBuildRejected type=${it.javaClass.simpleName}")
            scheduleCreate(LEGACY_FALLBACK_MS, ::createLegacyGroup)
            return
        }
        localManager.createGroup(localChannel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitCreatedGroup()
            override fun onFailure(reason: Int) {
                Log.w(TAG, "cameraLinkConfiguredCreateRejected reason=$reason")
                scheduleCreate(LEGACY_FALLBACK_MS, ::createLegacyGroup)
            }
        })
    }

    @SuppressLint("MissingPermission")
    private fun createLegacyGroup() {
        if (!running || legacyCreateAttempted) return
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        legacyCreateAttempted = true
        createAttempts += 1
        state("CREATING CAMERA LINK")
        Log.i(TAG, "cameraLinkCreate path=legacy elapsedMs=${stageElapsedMs()}")
        localManager.createGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = awaitCreatedGroup()
            override fun onFailure(reason: Int) {
                Log.e(TAG, "cameraLinkLegacyCreateFailed reason=$reason")
                fail("CAMERA LINK UNAVAILABLE")
            }
        })
    }

    private fun awaitCreatedGroup() {
        waitingForCreatedGroup = true
        scheduleGroupPoll(0L)
    }

    private fun scheduleCreate(delayMs: Long, action: () -> Unit) {
        clearCreateRetry()
        createRetry = Runnable {
            createRetry = null
            if (running) action()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun activateGroup(group: WifiP2pGroup, path: String) {
        if (!running || transportRole != CameraLinkTransportRole.P2P_SERVER) return
        clearCreateRetry()
        clearGroupPoll()
        waitingForCreatedGroup = false
        val recreatedGroup = goRecoveryInProgress && goRecoveryRemovedGroup
        val ssid = group.networkName
        val passphrase = group.passphrase
        val interfaceName = group.getInterface()
        Log.i(
            TAG,
            "cameraLinkGroup path=$path frequencyMHz=${group.frequency} " +
                "band=${if (group.frequency >= 5_000) "5ghz" else "2ghz_or_unknown"}",
        )
        profileStore.save(CameraP2pProfile(ssid, passphrase))
        if (!groupStageLogged) {
            groupStageLogged = true
            // A group resurrected after Wi-Fi was off is still a cold supplicant start and needs
            // the settle window. The measured warm path starts with Wi-Fi/group active and stays immediate.
            val coldP2pStartup = path == "created" || createAttempts > 0 || wifiWaitAttempts > 0
            initialOfferDelayMs = offerRetryPolicy.initialDelayMs(coldP2pStartup)
            if (recreatedGroup) {
                groupRecreatesDone += 1
                goRecoveryInProgress = false
                goRecoveryRemovedGroup = false
                clearGoRecoveryCreate()
                Log.i(TAG, "cameraLinkStage stage=group_recreated elapsedMs=${stageElapsedMs()}")
            } else {
                val stage = if (coldP2pStartup) "group_created" else "group_reused"
                Log.i(TAG, "cameraLinkStage stage=$stage path=$path elapsedMs=${stageElapsedMs()}")
            }
        }
        if (serverSocket == null) startServer(interfaceName)
        requestOwnerAddress { address ->
            if (!running || goRecoveryInProgress) return@requestOwnerAddress
            val nextOffer = CameraLinkOffer(ssid, passphrase, PORT, token, address)
            if (currentOffer?.sameLinkAs(nextOffer) == true) return@requestOwnerAddress
            currentOffer = nextOffer
            if (!recreatedGroup) offersSent = 0
            clearOfferRetry()
            scheduleOfferAction(initialOfferDelayMs)
            state("WAITING FOR PHONE")
        }
    }

    private fun CameraLinkOffer.sameLinkAs(other: CameraLinkOffer): Boolean =
        ssid == other.ssid && passphrase == other.passphrase && port == other.port && token == other.token

    private fun scheduleOfferAction(delayMs: Long) {
        clearOfferRetry()
        if (delayMs <= 0L) {
            runNextOfferAction()
            return
        }
        offerRetry = Runnable {
            offerRetry = null
            runNextOfferAction()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun runNextOfferAction() {
        if (!running || authenticated || goRecoveryInProgress) return
        val current = currentOffer ?: return
        val action = offerRetryPolicy.nextAction(offersSent, groupRecreatesDone)
        when (action.type) {
            CameraLinkOfferRetryActionType.OFFER -> {
                offersSent = checkNotNull(action.offerNumber)
                onOfferReady(current, offersSent)
                scheduleOfferAction(action.nextDelayMs)
            }
            CameraLinkOfferRetryActionType.RECREATE_GROUP -> recoverDeafGroup()
            CameraLinkOfferRetryActionType.TIMEOUT -> fail("PHONE LINK TIMEOUT")
        }
    }

    @SuppressLint("MissingPermission")
    private fun recoverDeafGroup() {
        if (!running || authenticated || goRecoveryInProgress) return
        if (manager == null || channel == null) return fail("WI-FI DIRECT LOST")
        goRecoveryInProgress = true
        goRecoveryRemovedGroup = false
        clearOfferRetry()
        state("RECOVERING CAMERA LINK")
        Log.i(TAG, "cameraLinkGoRecovery offersSent=$offersSent elapsedMs=${stageElapsedMs()}")

        // Authentication can finish on the accept thread while this main-thread action starts.
        if (!running || authenticated) {
            abortGoRecoveryBeforeRemoval()
            return
        }
        requestGroupInfoBeforeGoRecoveryRemoval(pollNumber = 1)
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfoBeforeGoRecoveryRemoval(pollNumber: Int) {
        if (!running || !goRecoveryInProgress || goRecoveryRemovedGroup) return
        if (authenticated) return abortGoRecoveryBeforeRemoval()
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        goRecoveryClientPoll?.let(mainHandler::removeCallbacks)
        goRecoveryClientPoll = null
        clearGoRecoveryGroupInfoTimeout()
        goRecoveryGroupInfoRequestId += 1L
        val requestId = goRecoveryGroupInfoRequestId
        goRecoveryGroupInfoTimeout = Runnable {
            goRecoveryGroupInfoTimeout = null
            if (!isGoRecoveryGroupInfoCurrent(requestId, localChannel)) return@Runnable
            Log.w(TAG, "cameraLinkGoRecoveryGroupInfoTimeout poll=$pollNumber")
            removeGroupAfterClientInspection(cause = "group_info_timeout")
        }.also { mainHandler.postDelayed(it, GO_RECOVERY_GROUP_INFO_TIMEOUT_MS) }
        runCatching {
            localManager.requestGroupInfo(localChannel) { group ->
                if (!isGoRecoveryGroupInfoCurrent(requestId, localChannel)) {
                    return@requestGroupInfo
                }
                clearGoRecoveryGroupInfoTimeout()
                if (authenticated) return@requestGroupInfo abortGoRecoveryBeforeRemoval()
                val clientCount = group?.clientList?.size ?: 0
                val action = groupRemovalGracePolicy.nextAction(
                    associatedClientPresent = clientCount > 0,
                    pollNumber = pollNumber,
                )
                when (action.type) {
                    CameraLinkGroupRemovalActionType.POLL_GROUP -> {
                        val nextPollNumber = checkNotNull(action.nextPollNumber)
                        Log.i(
                            TAG,
                            "cameraLinkGoRecoveryClientGrace clients=$clientCount " +
                                "poll=$pollNumber delayMs=${action.delayMs}",
                        )
                        goRecoveryClientPoll = Runnable {
                            goRecoveryClientPoll = null
                            requestGroupInfoBeforeGoRecoveryRemoval(nextPollNumber)
                        }.also { mainHandler.postDelayed(it, action.delayMs) }
                    }
                    CameraLinkGroupRemovalActionType.REMOVE_GROUP ->
                        removeGroupAfterClientInspection(cause = "client_poll_$pollNumber")
                }
            }
        }.onFailure {
            if (!isGoRecoveryGroupInfoCurrent(requestId, localChannel)) return@onFailure
            clearGoRecoveryGroupInfoTimeout()
            Log.w(TAG, "cameraLinkGoRecoveryGroupInfoFailed type=${it.javaClass.simpleName}")
            removeGroupAfterClientInspection(cause = "group_info_exception")
        }
    }

    private fun isGoRecoveryGroupInfoCurrent(
        requestId: Long,
        expectedChannel: WifiP2pManager.Channel,
    ): Boolean = running && goRecoveryInProgress && !goRecoveryRemovedGroup &&
        goRecoveryGroupInfoRequestId == requestId && channel === expectedChannel

    @SuppressLint("MissingPermission")
    private fun removeGroupAfterClientInspection(cause: String) {
        if (!running || !goRecoveryInProgress || goRecoveryRemovedGroup) return
        if (authenticated) return abortGoRecoveryBeforeRemoval()
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        clearGoRecoveryClientInspection()
        Log.i(TAG, "cameraLinkGoRecoveryRemove cause=$cause")
        runCatching {
            localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
                override fun onSuccess() {
                    if (!running || !goRecoveryInProgress) return
                    goRecoveryRemovedGroup = true
                    clearCreateRetry()
                    clearGroupPoll()
                    waitingForCreatedGroup = false
                    currentOffer = null
                    closeServer()
                    if (!authenticated) scheduleGoRecoveryCreate(RECOVERY_SETTLE_MS)
                }

                override fun onFailure(reason: Int) {
                    if (!running || !goRecoveryInProgress) return
                    if (authenticated) {
                        abortGoRecoveryBeforeRemoval()
                    } else {
                        Log.e(TAG, "cameraLinkGoRecoveryRemoveFailed reason=$reason")
                        fail("CAMERA LINK UNAVAILABLE")
                    }
                }
            })
        }.onFailure {
            if (!running || !goRecoveryInProgress) return@onFailure
            if (authenticated) {
                abortGoRecoveryBeforeRemoval()
            } else {
                Log.e(TAG, "cameraLinkGoRecoveryRemoveFailed type=${it.javaClass.simpleName}")
                fail("CAMERA LINK UNAVAILABLE")
            }
        }
    }

    private fun clearGoRecoveryGroupInfoTimeout() {
        goRecoveryGroupInfoTimeout?.let(mainHandler::removeCallbacks)
        goRecoveryGroupInfoTimeout = null
    }

    private fun clearGoRecoveryClientInspection() {
        goRecoveryGroupInfoRequestId += 1L
        goRecoveryClientPoll?.let(mainHandler::removeCallbacks)
        goRecoveryClientPoll = null
        clearGoRecoveryGroupInfoTimeout()
    }

    private fun scheduleGoRecoveryCreate(delayMs: Long) {
        clearGoRecoveryCreate()
        goRecoveryCreate = Runnable {
            goRecoveryCreate = null
            if (!running || !goRecoveryInProgress || !goRecoveryRemovedGroup) return@Runnable
            // A late HELLO wins over recovery. If that client later disconnects, recovery resumes.
            if (authenticated) return@Runnable
            resetCreateStateForGoRecovery()
            createConfiguredGroup()
        }.also { mainHandler.postDelayed(it, delayMs) }
    }

    private fun resetCreateStateForGoRecovery() {
        clearCreateRetry()
        clearGroupPoll()
        configuredCreateAttempted = false
        legacyCreateAttempted = false
        conflictRecoveryAttempted = false
        waitingForCreatedGroup = false
        groupStageLogged = false
        initialOfferDelayMs = 0L
        when (profileRecoveryPolicy.actionFor(groupRecreatesDone)) {
            CameraP2pProfileRecoveryAction.REUSE -> {
                val stable = profileStore.loadOrCreate()
                Log.i(TAG, "cameraLinkGoRecovery reusedSsid=${stable.networkName}")
            }
            CameraP2pProfileRecoveryAction.ROTATE -> {
                val rotated = profileStore.rotate()
                Log.i(TAG, "cameraLinkGoRecovery rotatedSsid=${rotated.networkName}")
            }
        }
    }

    private fun abortGoRecoveryBeforeRemoval() {
        clearGoRecoveryCreate()
        clearGoRecoveryClientInspection()
        goRecoveryInProgress = false
        goRecoveryRemovedGroup = false
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
        if (!running || transportRole != CameraLinkTransportRole.P2P_SERVER) return
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

    private fun closeServer() {
        runCatching { serverSocket?.close() }
        serverSocket = null
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
        if (transportRole != CameraLinkTransportRole.P2P_SERVER) {
            runCatching { socket.close() }
            return
        }
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
            Log.i(TAG, "cameraLinkStage stage=connected elapsedMs=${stageElapsedMs()}")
            mainHandler.post {
                clearOfferRetry()
                if (goRecoveryInProgress && !goRecoveryRemovedGroup) {
                    abortGoRecoveryBeforeRemoval()
                }
                offersSent = 0
                onAuthenticated(true)
            }
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
            if (packet.type == CameraLinkPacketType.VIDEO_FRAME) {
                val shouldWrite = synchronized(packetQueueLock) {
                    transferPolicy.shouldAdmitVideo(packet.isKeyFrame()).also { admitted ->
                        if (admitted) transferPolicy.onVideoWriteStarted(packet.isKeyFrame())
                    }
                }
                if (!shouldWrite) continue
            }
            val output = synchronized(socketLock) { clientOutput } ?: continue
            runCatching { CameraLinkProtocol.write(output, packet) }
                .onSuccess {
                    if (packet.type == CameraLinkPacketType.FROZEN_IMAGE) {
                        Log.i(
                            TAG,
                            "cameraLinkStage stage=frozen_sent requestId=${packet.requestId} " +
                                "bytes=${packet.payload.size} elapsedMs=${stageElapsedMs()}",
                        )
                    }
                }
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
            runCatching { clientSocket?.close() }
            clientSocket = null
        }
        synchronized(packetQueueLock) {
            packets.clear()
            transferPolicy.reset()
        }
        val reconnectReverse = running && transportRole == CameraLinkTransportRole.LOHS_CLIENT &&
            reverseOffer != null
        if (notify || reconnectReverse) {
            mainHandler.post {
                if (notify) onAuthenticated(false)
                if (!running || authenticated) return@post
                if (transportRole == CameraLinkTransportRole.LOHS_CLIENT) {
                    if (reverseJoinReady) scheduleReverseNetworkPoll(REVERSE_CONNECT_RETRY_MS)
                } else if (goRecoveryInProgress && goRecoveryRemovedGroup) {
                    if (goRecoveryCreate == null) scheduleGoRecoveryCreate(0L)
                } else if (!goRecoveryInProgress && currentOffer != null) {
                    scheduleOfferAction(0L)
                }
            }
        }
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
                if (isWifiReady()) inspectGroup() else waitForWifi()
            }
        }.also { mainHandler.postDelayed(it, WIFI_WAIT_MS) }
    }

    private fun scheduleGroupPoll(delayMs: Long) {
        clearGroupPoll()
        groupPoll = Runnable {
            groupPoll = null
            if (running && transportRole == CameraLinkTransportRole.P2P_SERVER) requestGroupInfo()
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
        stop()
    }

    override fun close() = stop()

    private fun stop() {
        running = false
        clearCreateRetry()
        clearWifiRetry()
        clearGroupPoll()
        clearOfferRetry()
        clearGoRecoveryCreate()
        clearGoRecoveryClientInspection()
        clearReverseNetworkPoll()
        clearReverseOfferFallback()
        reverseP2pTeardownTimeout?.let(mainHandler::removeCallbacks)
        reverseP2pTeardownTimeout = null
        goRecoveryInProgress = false
        goRecoveryRemovedGroup = false
        reverseJoinReady = false
        reverseConnecting = false
        closeClient()
        closeServer()
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
        }
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        currentOffer = null
        reverseOffer = null
        reverseGatewayIp = null
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

    private fun clearOfferRetry() {
        offerRetry?.let(mainHandler::removeCallbacks)
        offerRetry = null
    }

    private fun clearReverseNetworkPoll() {
        reverseNetworkPoll?.let(mainHandler::removeCallbacks)
        reverseNetworkPoll = null
    }

    private fun clearReverseOfferFallback() {
        reverseOfferFallback?.let(mainHandler::removeCallbacks)
        reverseOfferFallback = null
    }

    private fun clearGoRecoveryCreate() {
        goRecoveryCreate?.let(mainHandler::removeCallbacks)
        goRecoveryCreate = null
    }

    private fun stageElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L)

    companion object {
        private const val TAG = "CameraLink"
        const val PORT = 38_401
        private const val DEFAULT_GO_IP = "192.168.49.1"
        private const val NETWORK_QUEUE_CAPACITY = 12
        // Wi-Fi enable now runs through the accessibility toggle (opening the system
        // Wi-Fi panel and tapping it), which is slower than the old silent shell path.
        // Give it enough runway to open the panel, click, and associate before failing.
        private const val MAX_WIFI_WAIT_ATTEMPTS = 16
        private const val WIFI_WAIT_MS = 750L
        private const val RECOVERY_SETTLE_MS = 350L
        private const val LEGACY_FALLBACK_MS = 150L
        private const val GROUP_POLL_MS = 500L
        private const val COLD_GROUP_SETTLE_MS = 350L
        private const val OFFER_RETRY_MS = 2_500L
        private const val MAX_OFFER_SENDS = 10
        private const val OFFERS_BEFORE_GO_RECOVERY = 5
        private const val OFFERS_BETWEEN_GO_RECOVERIES = 4
        private const val MAX_GO_RECREATES = 2
        private const val ASSOCIATED_CLIENT_GRACE_MS = 2_500L
        private const val MAX_GO_RECOVERY_CLIENT_POLLS = 2
        private const val GO_RECOVERY_GROUP_INFO_TIMEOUT_MS = 750L
        private const val REVERSE_P2P_TEARDOWN_TIMEOUT_MS = 1_000L
        private const val REVERSE_JOIN_TIMEOUT_MS = 15_000L
        private const val REVERSE_NETWORK_POLL_MS = 250L
        private const val REVERSE_CONNECT_RETRY_MS = 750L
        private const val REVERSE_CONNECT_TIMEOUT_MS = 5_000
        internal const val REVERSE_OFFER_FALLBACK_MS = 3_000L
        private const val REVERSE_BOOTSTRAP_OFFER_NUMBER = 0
    }
}
