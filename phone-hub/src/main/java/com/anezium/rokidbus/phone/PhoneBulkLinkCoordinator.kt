package com.anezium.rokidbus.phone

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.shared.BulkLinkContract
import com.anezium.rokidbus.shared.BulkLinkOffer
import com.anezium.rokidbus.shared.BulkLinkPurpose
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/** The phone's exclusive owner of the normal Wi-Fi Direct association and bulk TCP socket. */
internal class PhoneBulkLinkCoordinator(
    context: Context,
    private val notifyOwner: (PhonePluginPrincipal, String, JSONObject) -> Unit,
    private val logger: (String) -> Unit,
) : AutoCloseable {
    private data class Lease(val offer: BulkLinkOffer, val owner: PhonePluginPrincipal)

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "phone-bulk-link").apply { isDaemon = true }
    }
    private var manager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var receiverRegistered = false
    private var lease: Lease? = null
    private var socket: Socket? = null
    private var retainedEndpoint: ParcelFileDescriptor? = null
    private var connected = false
    private var socketConnectPending = false
    private var connectionPolls = 0
    private var warmExpiry: Runnable? = null

    @Synchronized
    fun acceptOffer(offer: BulkLinkOffer, owner: PhonePluginPrincipal): Boolean {
        if (!BulkLinkContract.validate(offer) || offer.ownerPluginId != owner.descriptor.id) return false
        val current = lease
        if (current?.offer == offer && current.owner.grantKey() == owner.grantKey()) return true
        closeFeatureEndpoints()
        lease = Lease(offer, owner)
        socketConnectPending = false
        connectionPolls = 0
        cancelWarmExpiry()
        beginJoin(offer)
        return true
    }

    @Synchronized
    fun openChannel(
        sessionId: String,
        purpose: BulkLinkPurpose,
        owner: PhonePluginPrincipal,
    ): ParcelFileDescriptor? {
        val active = lease ?: return null
        if (!connected || active.owner.grantKey() != owner.grantKey() ||
            active.offer.sessionId != sessionId || active.offer.purpose != purpose ||
            retainedEndpoint != null
        ) {
            return null
        }
        val network = socket ?: return null
        val pair = runCatching { ParcelFileDescriptor.createSocketPair() }.getOrNull() ?: return null
        retainedEndpoint = pair[0]
        val scheduled = runCatching { io.execute { pump(network, pair[0]) } }.isSuccess
        if (!scheduled) {
            closeRetainedEndpoint()
            runCatching { pair[1].close() }
            return null
        }
        return pair[1]
    }

    @Synchronized
    fun release(owner: PhonePluginPrincipal, sessionId: String) {
        val active = lease ?: return
        if (active.owner.grantKey() != owner.grantKey() || active.offer.sessionId != sessionId) return
        closeFeatureEndpoints()
        lease = null
        socketConnectPending = false
        scheduleWarmExpiry()
    }

    @Synchronized
    fun onControlLinkLost() {
        cancelWarmExpiry()
        lease = null
        socketConnectPending = false
        closeTransport()
    }

    @SuppressLint("MissingPermission")
    private fun beginJoin(offer: BulkLinkOffer) {
        if (!hasP2pPermission()) return fail("bulk_permission_required", offer)
        val localManager = appContext.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            ?: return fail("bulk_unavailable", offer)
        val localChannel = channel ?: localManager.initialize(appContext, Looper.getMainLooper(), null)
            ?: return fail("bulk_unavailable", offer)
        manager = localManager
        channel = localChannel
        if (!receiverRegistered) {
            ContextCompat.registerReceiver(
                appContext,
                receiver,
                IntentFilter(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            receiverRegistered = true
        }
        runCatching {
            localManager.requestGroupInfo(localChannel) { group ->
                if (!isCurrent(offer)) return@requestGroupInfo
                when {
                    group == null -> connectByCredentials(offer)
                    !group.isGroupOwner && group.networkName == offer.ssid -> requestConnectionInfo(offer)
                    else -> fail("bulk_group_busy", offer)
                }
            }
        }.onFailure { fail("bulk_group_query_failed", offer) }
    }

    @SuppressLint("MissingPermission")
    private fun connectByCredentials(offer: BulkLinkOffer) {
        val localManager = synchronized(this) { manager } ?: return fail("bulk_unavailable", offer)
        val localChannel = synchronized(this) { channel } ?: return fail("bulk_unavailable", offer)
        val config = runCatching {
            WifiP2pConfig.Builder()
                .setNetworkName(offer.ssid)
                .setPassphrase(offer.passphrase)
                .enablePersistentMode(false)
                .build()
        }.getOrElse { return fail("bulk_config_failed", offer) }
        runCatching {
            localManager.connect(localChannel, config, object : WifiP2pManager.ActionListener {
                override fun onSuccess() = requestConnectionInfo(offer)
                override fun onFailure(reason: Int) = fail("bulk_join_failed", offer)
            })
        }.onFailure { fail("bulk_join_failed", offer) }
    }

    @SuppressLint("MissingPermission")
    private fun requestConnectionInfo(expected: BulkLinkOffer) {
        val active = synchronized(this) {
            if (lease?.offer != expected || socketConnectPending || connected) return
            if (connectionPolls++ >= MAX_CONNECTION_POLLS) {
                fail("bulk_join_timeout", expected)
                return
            }
            val localManager = manager ?: return
            val localChannel = channel ?: return
            localManager to localChannel
        }
        val localManager = active.first
        val localChannel = active.second
        runCatching {
            localManager.requestConnectionInfo(localChannel) { info ->
                handleConnectionInfo(expected, info)
            }
        }.onFailure { fail("bulk_connection_query_failed", expected) }
    }

    private fun handleConnectionInfo(expected: BulkLinkOffer, info: WifiP2pInfo?) {
        val active = synchronized(this) {
            val current = lease?.takeIf { it.offer == expected } ?: return
            if (info?.groupFormed == true && !info.isGroupOwner) {
                if (socketConnectPending || connected) return
                socketConnectPending = true
            } else {
                mainHandler.postDelayed(
                    { requestConnectionInfo(expected) },
                    CONNECTION_POLL_MS,
                )
                return
            }
            current
        }
        io.execute { connectSocket(active) }
    }

    private fun connectSocket(active: Lease) {
        val candidate = Socket()
        val prepared = runCatching {
            candidate.apply {
                tcpNoDelay = true
                keepAlive = true
                connect(InetSocketAddress(active.offer.goIp, active.offer.port), CONNECT_TIMEOUT_MS)
                BulkLinkContract.writeHandshake(
                    getOutputStream(),
                    BulkLinkContract.handshakeMeta(
                        active.offer.sessionId,
                        active.offer.purpose,
                        active.offer.epoch,
                        active.offer.token,
                    ),
                )
            }
        }.isSuccess
        if (!prepared) {
            runCatching { candidate.close() }
            fail("bulk_connect_failed", active.offer)
            return
        }
        val accepted = synchronized(this) {
            if (lease !== active) {
                false
            } else {
                socket = candidate
                connected = true
                socketConnectPending = false
                true
            }
        }
        if (!accepted) {
            runCatching { candidate.close() }
            return
        }
        notifyOwner(
            active.owner,
            STATE_PATH,
            JSONObject()
                .put("sessionId", active.offer.sessionId)
                .put("purpose", active.offer.purpose.wireValue)
                .put("state", "link_ready")
                .put("epoch", active.offer.epoch),
        )
    }

    private fun pump(network: Socket, endpoint: ParcelFileDescriptor) {
        val leftToRight = Thread(
            {
                copy(FileInputStream(endpoint.fileDescriptor), network.getOutputStream())
                finishPump(network, endpoint)
            },
            "phone-bulk-upstream",
        )
        val rightToLeft = Thread(
            {
                copy(network.getInputStream(), FileOutputStream(endpoint.fileDescriptor))
                finishPump(network, endpoint)
            },
            "phone-bulk-downstream",
        )
        leftToRight.start()
        rightToLeft.start()
        runCatching { leftToRight.join() }
        runCatching { rightToLeft.join() }
    }

    private fun copy(input: java.io.InputStream, output: java.io.OutputStream) = runCatching {
        val buffer = ByteArray(32 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
            output.flush()
        }
    }

    @Synchronized
    private fun finishPump(network: Socket, endpoint: ParcelFileDescriptor) {
        if (socket !== network || retainedEndpoint !== endpoint) return
        closeFeatureEndpoints()
    }

    private fun fail(code: String, expected: BulkLinkOffer? = null) {
        val active = synchronized(this) {
            val current = lease ?: return
            if (expected != null && current.offer != expected) return
            closeFeatureEndpoints()
            lease = null
            socketConnectPending = false
            current
        }
        logger("bulk link failed purpose=${active.offer.purpose.wireValue} code=$code")
        notifyOwner(
            active.owner,
            STATE_PATH,
            JSONObject()
                .put("sessionId", active.offer.sessionId)
                .put("purpose", active.offer.purpose.wireValue)
                .put("state", "error")
                .put("epoch", active.offer.epoch)
                .put("code", code),
        )
    }

    private fun hasP2pPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun isCurrent(offer: BulkLinkOffer): Boolean = synchronized(this) {
        lease?.offer == offer
    }

    private fun closeFeatureEndpoints() {
        runCatching { retainedEndpoint?.close() }
        retainedEndpoint = null
        runCatching { socket?.close() }
        socket = null
        connected = false
    }

    private fun scheduleWarmExpiry() {
        cancelWarmExpiry()
        warmExpiry = Runnable {
            synchronized(this) {
                warmExpiry = null
                closeTransport()
            }
        }.also { mainHandler.postDelayed(it, BulkLinkContract.WARM_GRACE_MS) }
    }

    private fun cancelWarmExpiry() {
        warmExpiry?.let(mainHandler::removeCallbacks)
        warmExpiry = null
    }

    private fun closeRetainedEndpoint() {
        runCatching { retainedEndpoint?.close() }
        retainedEndpoint = null
    }

    private fun closeTransport() {
        closeFeatureEndpoints()
        if (receiverRegistered) runCatching { appContext.unregisterReceiver(receiver) }
        receiverRegistered = false
        manager = null
        channel = null
        connectionPolls = 0
    }

    override fun close() {
        synchronized(this) {
            cancelWarmExpiry()
            lease = null
            socketConnectPending = false
            closeTransport()
        }
        io.shutdownNow()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION) return
            val expected = synchronized(this@PhoneBulkLinkCoordinator) { lease?.offer } ?: return
            requestConnectionInfo(expected)
        }
    }

    private companion object {
        const val STATE_PATH = "/core/bulk-link/state"
        const val CONNECTION_POLL_MS = 500L
        const val CONNECT_TIMEOUT_MS = 12_000
        const val MAX_CONNECTION_POLLS = 30
    }
}
