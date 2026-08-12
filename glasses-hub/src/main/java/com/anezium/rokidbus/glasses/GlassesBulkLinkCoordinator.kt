package com.anezium.rokidbus.glasses

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Base64
import com.anezium.rokidbus.shared.BulkLinkContract
import com.anezium.rokidbus.shared.BulkLinkOffer
import com.anezium.rokidbus.shared.BulkLinkPurpose
import com.anezium.rokidbus.shared.BulkLinkState
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.Executors

internal enum class GlassesBulkLeasePurpose { CAMERA, VIDEO }

/** Owns the one normal P2P group and leases its data plane to one glasses feature at a time. */
internal class GlassesBulkLinkCoordinator(
    context: Context,
    private val offerSink: (JSONObject) -> Unit,
    private val stateSink: (String) -> Unit,
    private val onWarmExpired: () -> Unit,
) : AutoCloseable {
    private data class Lease(
        val purpose: GlassesBulkLeasePurpose,
        val sessionId: String,
        val ownerPluginId: String?,
        val epoch: Long,
        val token: String,
    )

    private val appContext = context.applicationContext
    private val profileStore = CameraP2pProfileStore(appContext)
    private val handler = Handler(Looper.getMainLooper())
    private val acceptExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "bulk-link-accept").apply { isDaemon = true }
    }
    private val random = SecureRandom()
    private var lease: Lease? = null
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var server: ServerSocket? = null
    private var networkSocket: Socket? = null
    private var localEndpoint: ParcelFileDescriptor? = null
    private var groupCreatedOrAdopted = false
    private var groupStartPending = false
    private var groupAttempts = 0
    private var groupRetry: Runnable? = null
    private var warmExpiry: Runnable? = null
    private var pumpRunning = false
    private var transportGeneration = 0L

    @Synchronized
    fun isWarm(): Boolean = warmExpiry != null

    @Synchronized
    fun acquire(
        purpose: GlassesBulkLeasePurpose,
        sessionId: String,
        ownerPluginId: String? = null,
    ): Boolean {
        if (!isCanonicalUuid(sessionId)) return false
        val current = lease
        if (current != null) {
            if (current.purpose == purpose && current.sessionId == sessionId &&
                current.ownerPluginId == ownerPluginId
            ) {
                if (manager == null) startP2p()
                return true
            }
            if (purpose != GlassesBulkLeasePurpose.CAMERA) return false
        }
        invalidateDataChannel()
        lease = Lease(
            purpose = purpose,
            sessionId = sessionId,
            ownerPluginId = ownerPluginId,
            epoch = nextEpoch(),
            token = randomToken(),
        )
        cancelWarmExpiry()
        groupAttempts = 0
        if (manager == null) startP2p() else requestGroup()
        return true
    }

    /** Returns one single-open local endpoint; credentials never cross this process boundary. */
    @Synchronized
    fun openChannel(
        purpose: GlassesBulkLeasePurpose,
        sessionId: String,
    ): ParcelFileDescriptor? {
        val active = lease ?: return null
        if (active.purpose != purpose || active.sessionId != sessionId || localEndpoint != null) return null
        val pair = runCatching { ParcelFileDescriptor.createSocketPair() }.getOrNull() ?: return null
        localEndpoint = pair[0]
        maybeStartPump()
        return pair[1]
    }

    @Synchronized
    fun release(purpose: GlassesBulkLeasePurpose, sessionId: String) {
        val active = lease ?: return
        if (active.purpose != purpose || active.sessionId != sessionId) return
        lease = null
        invalidateDataChannel()
        scheduleWarmExpiry()
    }

    @Synchronized
    fun release(purpose: GlassesBulkLeasePurpose) {
        val active = lease?.takeIf { it.purpose == purpose } ?: return
        release(active.purpose, active.sessionId)
    }

    @Synchronized
    fun onControlLinkLost() {
        lease = null
        cancelWarmExpiry()
        closeTransport()
    }

    @Synchronized
    fun yieldToLegacyLink() {
        lease = null
        cancelWarmExpiry()
        closeTransport()
        stateSink("bulk_legacy_yield")
    }

    @Synchronized
    fun acceptPeerState(payload: JSONObject): Boolean {
        val state = BulkLinkContract.stateFromJson(payload) ?: return false
        val active = lease ?: return false
        if (state.sessionId != active.sessionId || state.purpose != active.purpose.toBulkPurpose() ||
            state.epoch != active.epoch
        ) {
            return false
        }
        stateSink("bulk_peer_${state.state.wireValue}")
        if (state.state == BulkLinkState.ERROR || state.state == BulkLinkState.RELEASED) {
            invalidateDataChannel()
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private fun startP2p() {
        val generation = synchronized(this) {
            transportGeneration += 1L
            manager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            channel = manager?.initialize(appContext, Looper.getMainLooper(), null)
            transportGeneration
        }
        if (synchronized(this) { manager == null || channel == null }) {
            synchronized(this) {
                manager = null
                channel = null
            }
            stateSink("bulk_unavailable")
            return
        }
        requestGroup(generation)
    }

    @SuppressLint("MissingPermission")
    private fun requestGroup(
        expectedGeneration: Long = synchronized(this) { transportGeneration },
    ) {
        val transport = synchronized(this) {
            if (transportGeneration != expectedGeneration) return
            val activeManager = manager ?: return
            val activeChannel = channel ?: return
            activeManager to activeChannel
        }
        val activeManager = transport.first
        val activeChannel = transport.second
        runCatching {
            activeManager.requestGroupInfo(activeChannel) { group ->
                if (!isTransportCurrent(expectedGeneration, activeManager, activeChannel)) {
                    return@requestGroupInfo
                }
                val profile = profileStore.loadOrCreate()
                when {
                    group == null -> createStableGroup(profile, expectedGeneration)
                    group.isGroupOwner &&
                        group.networkName == profile.networkName &&
                        group.passphrase == profile.passphrase -> {
                        synchronized(this) {
                            groupCreatedOrAdopted = true
                            groupStartPending = false
                            groupAttempts = 0
                        }
                        activate(group)
                    }
                    else -> stateSink("bulk_group_busy")
                }
            }
        }.onFailure { scheduleGroupRetry("bulk_group_query_failed", expectedGeneration) }
    }

    @SuppressLint("MissingPermission")
    private fun createStableGroup(
        profile: CameraP2pProfile,
        expectedGeneration: Long,
    ) {
        val active = synchronized(this) {
            if (transportGeneration != expectedGeneration || groupStartPending || lease == null) return
            val activeManager = manager ?: return
            val activeChannel = channel ?: return
            groupStartPending = true
            activeManager to activeChannel
        }
        val activeManager = active.first
        val activeChannel = active.second
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(profile.networkName)
                .setPassphrase(profile.passphrase)
                .setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ)
                .enablePersistentMode(true)
                .build()
        }.getOrNull()
        val listener = object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                if (!isTransportCurrent(expectedGeneration, activeManager, activeChannel)) {
                    runCatching { activeManager.removeGroup(activeChannel, null) }
                    return
                }
                synchronized(this@GlassesBulkLinkCoordinator) {
                    groupStartPending = false
                    groupCreatedOrAdopted = true
                }
                handler.postDelayed({ requestGroup(expectedGeneration) }, GROUP_SETTLE_MS)
            }

            override fun onFailure(reason: Int) {
                if (!isTransportCurrent(expectedGeneration, activeManager, activeChannel)) return
                synchronized(this@GlassesBulkLinkCoordinator) { groupStartPending = false }
                scheduleGroupRetry("bulk_group_failed", expectedGeneration)
            }
        }
        runCatching {
            if (config == null) {
                activeManager.createGroup(activeChannel, listener)
            } else {
                activeManager.createGroup(activeChannel, config, listener)
            }
        }.onFailure {
            if (!isTransportCurrent(expectedGeneration, activeManager, activeChannel)) return@onFailure
            synchronized(this) { groupStartPending = false }
            scheduleGroupRetry("bulk_group_failed", expectedGeneration)
        }
    }

    private fun scheduleGroupRetry(
        state: String,
        expectedGeneration: Long = synchronized(this) { transportGeneration },
    ) {
        val retry = synchronized(this) {
            stateSink(state)
            if (transportGeneration != expectedGeneration || lease == null ||
                groupAttempts++ >= MAX_GROUP_ATTEMPTS || groupRetry != null
            ) {
                return
            }
            Runnable {
                synchronized(this) { groupRetry = null }
                requestGroup(expectedGeneration)
            }.also { groupRetry = it }
        }
        handler.postDelayed(retry, GROUP_RETRY_MS)
    }

    private fun activate(group: WifiP2pGroup) {
        val address = groupAddress(group) ?: run {
            scheduleGroupRetry("bulk_address_failed")
            return
        }
        var startAccept: ServerSocket? = null
        synchronized(this) {
            if (server == null) {
                val listening = runCatching {
                    ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(address, BulkLinkContract.PORT))
                    }
                }.getOrNull() ?: run {
                    stateSink("bulk_port_failed")
                    return
                }
                server = listening
                startAccept = listening
            }
        }
        profileStore.save(CameraP2pProfile(group.networkName, group.passphrase))
        startAccept?.let { listening -> acceptExecutor.execute { acceptLoop(listening) } }
        publishOffer(group, address)
    }

    private fun publishOffer(group: WifiP2pGroup, address: Inet4Address) {
        val active = synchronized(this) { lease } ?: return
        val offer = BulkLinkOffer(
            sessionId = active.sessionId,
            purpose = active.purpose.toBulkPurpose(),
            ownerPluginId = active.ownerPluginId,
            epoch = active.epoch,
            token = active.token,
            ssid = group.networkName,
            passphrase = group.passphrase,
            goIp = address.hostAddress,
        )
        runCatching { offerSink(BulkLinkContract.toJson(offer)) }
            .onSuccess { stateSink("bulk_waiting") }
            .onFailure { stateSink("bulk_offer_failed") }
    }

    private fun acceptLoop(listening: ServerSocket) {
        while (!listening.isClosed) {
            val incoming = runCatching { listening.accept() }.getOrNull() ?: break
            val expected = synchronized(this) { lease }
            if (expected == null || !validHandshake(incoming, expected)) {
                runCatching { incoming.close() }
                continue
            }
            val accepted = synchronized(this) {
                if (lease !== expected) {
                    false
                } else {
                    closeNetworkSocket()
                    networkSocket = incoming
                    maybeStartPump()
                    true
                }
            }
            if (!accepted) {
                runCatching { incoming.close() }
            } else {
                stateSink("bulk_connected")
            }
        }
    }

    private fun validHandshake(socket: Socket, expected: Lease): Boolean = runCatching {
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        val actual = BulkLinkContract.readHandshake(socket.getInputStream()) ?: return@runCatching false
        require(actual.length() == 5)
        require(actual.optInt("version") == BulkLinkContract.VERSION)
        require(actual.optString("sessionId") == expected.sessionId)
        require(actual.optString("purpose") == expected.purpose.toBulkPurpose().wireValue)
        require(actual.optLong("epoch") == expected.epoch)
        require(actual.optString("token") == expected.token)
        socket.soTimeout = 0
        true
    }.getOrDefault(false)

    @Synchronized
    private fun maybeStartPump() {
        if (pumpRunning) return
        val network = networkSocket ?: return
        val endpoint = localEndpoint ?: return
        pumpRunning = true
        Thread(
            {
                pump(network, endpoint)
                synchronized(this) {
                    if (networkSocket === network) networkSocket = null
                    if (localEndpoint === endpoint) localEndpoint = null
                    pumpRunning = false
                    maybeStartPump()
                }
            },
            "bulk-link-pump",
        ).apply { isDaemon = true }.start()
    }

    private fun pump(socket: Socket, endpoint: ParcelFileDescriptor) {
        val upstream = Thread(
            {
                copy(FileInputStream(endpoint.fileDescriptor), socket.getOutputStream())
                closePumpEndpoints(socket, endpoint)
            },
            "bulk-link-upstream",
        )
        val downstream = Thread(
            {
                copy(socket.getInputStream(), FileOutputStream(endpoint.fileDescriptor))
                closePumpEndpoints(socket, endpoint)
            },
            "bulk-link-downstream",
        )
        upstream.start()
        downstream.start()
        runCatching { upstream.join() }
        runCatching { downstream.join() }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) = runCatching {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            output.flush()
        }
    }

    private fun closePumpEndpoints(socket: Socket, endpoint: ParcelFileDescriptor) {
        runCatching { socket.close() }
        runCatching { endpoint.close() }
    }

    private fun scheduleWarmExpiry() {
        cancelWarmExpiry()
        warmExpiry = Runnable {
            synchronized(this) {
                warmExpiry = null
                closeTransport()
            }
            onWarmExpired()
        }.also { handler.postDelayed(it, BulkLinkContract.WARM_GRACE_MS) }
    }

    private fun cancelWarmExpiry() {
        warmExpiry?.let(handler::removeCallbacks)
        warmExpiry = null
    }

    private fun cancelGroupRetry() {
        groupRetry?.let(handler::removeCallbacks)
        groupRetry = null
    }

    private fun invalidateDataChannel() {
        closeNetworkSocket()
        runCatching { localEndpoint?.close() }
        localEndpoint = null
    }

    private fun closeNetworkSocket() {
        runCatching { networkSocket?.close() }
        networkSocket = null
    }

    @SuppressLint("MissingPermission")
    private fun closeTransport() {
        transportGeneration += 1L
        cancelGroupRetry()
        invalidateDataChannel()
        runCatching { server?.close() }
        server = null
        if (groupCreatedOrAdopted) {
            runCatching { manager?.removeGroup(channel, null) }
        }
        groupCreatedOrAdopted = false
        groupStartPending = false
        groupAttempts = 0
        manager = null
        channel = null
    }

    override fun close() {
        synchronized(this) {
            lease = null
            cancelWarmExpiry()
            closeTransport()
        }
        acceptExecutor.shutdownNow()
    }

    private fun groupAddress(group: WifiP2pGroup): Inet4Address? = runCatching {
        NetworkInterface.getByName(group.`interface`)?.inetAddresses?.asSequence()
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { !it.isLoopbackAddress }
    }.getOrNull()

    private fun isTransportCurrent(
        expectedGeneration: Long,
        expectedManager: WifiP2pManager,
        expectedChannel: WifiP2pManager.Channel,
    ): Boolean = synchronized(this) {
        transportGeneration == expectedGeneration &&
            manager === expectedManager && channel === expectedChannel
    }

    private fun nextEpoch(): Long = random.nextLong().and(Long.MAX_VALUE).coerceAtLeast(1L)

    private fun randomToken(): String = ByteArray(24)
        .also(random::nextBytes)
        .let { Base64.encodeToString(it, Base64.NO_WRAP or Base64.URL_SAFE or Base64.NO_PADDING) }

    private fun isCanonicalUuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value.lowercase()
    }.getOrDefault(false)

    private fun GlassesBulkLeasePurpose.toBulkPurpose(): BulkLinkPurpose = when (this) {
        GlassesBulkLeasePurpose.CAMERA -> BulkLinkPurpose.CAMERA
        GlassesBulkLeasePurpose.VIDEO -> BulkLinkPurpose.VIDEO
    }

    private companion object {
        const val GROUP_SETTLE_MS = 600L
        const val GROUP_RETRY_MS = 500L
        const val MAX_GROUP_ATTEMPTS = 30
        const val HANDSHAKE_TIMEOUT_MS = 5_000
    }
}
