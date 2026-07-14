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
    private val sessionStartedAtMs: Long,
    private val onOfferReady: (CameraLinkOffer, Int) -> Unit,
    private val onAuthenticated: (Boolean) -> Unit,
    private val onFrozenTransferFinished: (Long) -> Unit,
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
    )
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
    private var configuredCreateAttempted = false
    private var legacyCreateAttempted = false
    private var conflictRecoveryAttempted = false
    private var bandMigrationAttempted = false
    private var waitingForCreatedGroup = false
    private var createRetry: Runnable? = null
    private var wifiRetry: Runnable? = null
    private var groupPoll: Runnable? = null
    private var offerRetry: Runnable? = null
    private var currentOffer: CameraLinkOffer? = null
    private var groupStageLogged = false
    private var initialOfferDelayMs = 0L

    val isAuthenticated: Boolean get() = authenticated

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val enabled = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1) ==
                        WifiP2pManager.WIFI_P2P_STATE_ENABLED
                    if (running && enabled && currentOffer == null) inspectGroup()
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
        if (isWifiReady()) inspectGroup() else waitForWifi()
    }

    fun resendOfferIfDisconnected() {
        if (authenticated) return
        mainHandler.post {
            if (running && !authenticated && currentOffer != null) {
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
    private fun inspectGroup() {
        if (!running) return
        if (!isWifiReady()) return waitForWifi()
        state("INSPECTING CAMERA LINK")
        requestGroupInfo()
    }

    @SuppressLint("MissingPermission")
    private fun requestGroupInfo() {
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
        if (!running) return
        if (isUsableOwnerGroup(group)) {
            val activeGroup = group!!
            if (shouldMigrateTo5Ghz(activeGroup)) {
                migrateTo5Ghz(activeGroup)
            } else {
                activateGroup(activeGroup, if (waitingForCreatedGroup) "created" else "active_reuse")
            }
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

    private fun shouldMigrateTo5Ghz(group: WifiP2pGroup): Boolean =
        !bandMigrationAttempted && createAttempts == 0 && group.frequency in 2_400..2_500

    @SuppressLint("MissingPermission")
    private fun migrateTo5Ghz(group: WifiP2pGroup) {
        val localManager = manager ?: return fail("WI-FI DIRECT LOST")
        val localChannel = channel ?: return fail("WI-FI DIRECT LOST")
        bandMigrationAttempted = true
        state("UPGRADING CAMERA LINK")
        Log.i(TAG, "cameraLinkBandMigration fromMHz=${group.frequency} target=5ghz")
        localManager.removeGroup(localChannel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() = scheduleCreate(RECOVERY_SETTLE_MS, ::createConfiguredGroup)

            override fun onFailure(reason: Int) {
                Log.w(TAG, "cameraLinkBandMigrationSkipped reason=$reason")
                activateGroup(group, "active_reuse_2ghz")
            }
        })
    }

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
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ)
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
        clearCreateRetry()
        clearGroupPoll()
        waitingForCreatedGroup = false
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
            val stage = if (coldP2pStartup) "group_created" else "group_reused"
            Log.i(TAG, "cameraLinkStage stage=$stage path=$path elapsedMs=${stageElapsedMs()}")
        }
        if (serverSocket == null) startServer(interfaceName)
        requestOwnerAddress { address ->
            if (!running) return@requestOwnerAddress
            val nextOffer = CameraLinkOffer(ssid, passphrase, PORT, token, address)
            if (currentOffer?.sameLinkAs(nextOffer) == true) return@requestOwnerAddress
            currentOffer = nextOffer
            offerRetryPolicy.reset()
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
        if (!running || authenticated) return
        val current = currentOffer ?: return
        val action = offerRetryPolicy.nextAction()
        if (action.timedOut) return fail("PHONE LINK TIMEOUT")
        onOfferReady(current, checkNotNull(action.offerNumber))
        scheduleOfferAction(action.nextDelayMs)
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
            Log.i(TAG, "cameraLinkStage stage=connected elapsedMs=${stageElapsedMs()}")
            mainHandler.post {
                clearOfferRetry()
                offerRetryPolicy.reset()
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
        if (notify) {
            mainHandler.post {
                onAuthenticated(false)
                if (running && !authenticated && currentOffer != null) scheduleOfferAction(0L)
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
        stop()
    }

    override fun close() = stop()

    private fun stop() {
        running = false
        clearCreateRetry()
        clearWifiRetry()
        clearGroupPoll()
        clearOfferRetry()
        closeClient()
        runCatching { serverSocket?.close() }
        serverSocket = null
        val localManager = manager
        val localChannel = channel
        if (localManager != null && localChannel != null) {
            runCatching { localManager.stopPeerDiscovery(localChannel, null) }
            runCatching { localManager.cancelConnect(localChannel, null) }
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

    private fun clearOfferRetry() {
        offerRetry?.let(mainHandler::removeCallbacks)
        offerRetry = null
    }

    private fun stageElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L)

    companion object {
        private const val TAG = "CameraLink"
        const val PORT = 38_401
        private const val DEFAULT_GO_IP = "192.168.49.1"
        private const val NETWORK_QUEUE_CAPACITY = 12
        private const val MAX_WIFI_WAIT_ATTEMPTS = 8
        private const val WIFI_WAIT_MS = 750L
        private const val RECOVERY_SETTLE_MS = 350L
        private const val LEGACY_FALLBACK_MS = 150L
        private const val GROUP_POLL_MS = 500L
        private const val COLD_GROUP_SETTLE_MS = 350L
        private const val OFFER_RETRY_MS = 2_500L
        private const val MAX_OFFER_SENDS = 8
    }
}
