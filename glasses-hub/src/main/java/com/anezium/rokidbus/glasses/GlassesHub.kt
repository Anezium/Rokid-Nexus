package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.IBinder
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

object GlassesHub {
    private const val LOCAL_BINARY_MAX_BYTES = 512 * 1024

    data class LauncherEntry(
        val id: String,
        val displayName: String,
    )

    private data class Registration(
        val clientId: String,
        val prefixes: List<String>,
        val uid: Int,
        val trusted: Boolean,
        val callback: IBusCallback,
    )

    private val started = AtomicBoolean(false)
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val launcherListeners = CopyOnWriteArrayList<(List<LauncherEntry>) -> Unit>()
    private val wifiOwnership = GlassesWifiOwnership()
    private val wifiRequestExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "RokidNexusWifi").apply { isDaemon = true }
    }
    @Volatile private var launcherEntries: List<LauncherEntry> = emptyList()
    @Volatile private var appContext: Context? = null
    @Volatile private var cxrUp = false
    @Volatile private var phoneConnected = false

    private val aidl = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            unregister(cb)
            val cleanPrefixes = pathPrefixes.filter { it.isNotBlank() }
            val uid = Binder.getCallingUid()
            registrations += Registration(clientId, cleanPrefixes, uid, isTrustedUid(uid), cb)
            log("client registered id=$clientId prefixes=${cleanPrefixes.joinToString()}")
            runCatching { cb.onLinkState(linkState()) }
            appContext?.let { GlassesClientSupervisor.onClientRegistered(it, cleanPrefixes) }
        }

        override fun unregister(cb: IBusCallback) {
            registrations.removeAll { it.callback.asBinder() == cb.asBinder() }
        }

        override fun send(path: String, id: String, payload: ByteArray) {
            val json = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json), Binder.getCallingUid())
        }

        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) {
            val json = runCatching { JSONObject(String(meta, Charsets.UTF_8)) }.getOrElse { JSONObject() }
            routeLocal(BusEnvelope(path = path, id = id, payload = json, binary = data), Binder.getCallingUid())
        }

        override fun linkState(): Int = this@GlassesHub.linkState()

        override fun capabilities(): Int = BusCapabilityBits.PROTECTED_LENS_LINK
    }

    fun start(context: Context) {
        appContext = context.applicationContext
        if (started.compareAndSet(false, true)) {
            log("Glasses hub starting")
            SppServerManager.ensureStarted(context.applicationContext)
            CxrBusBridge.start(context.applicationContext)
        }
    }

    fun binder(context: Context): IBinder {
        start(context)
        return aidl
    }

    fun onSppConnected(connected: Boolean) {
        phoneConnected = connected || CxrBusBridge.isUp()
        notifyLinkState()
    }

    fun onCxrState(connected: Boolean) {
        cxrUp = connected
        phoneConnected = connected || SppServerManager.isConnected()
        notifyLinkState()
    }

    fun onRemoteEnvelope(envelope: BusEnvelope) {
        log("remote RX ${envelope.path} id=${envelope.id}")
        appContext?.let { context ->
            if (SurfaceController.handleSurfaceEnvelope(context, envelope)) return
        }
        if (envelope.path == BusPaths.LAUNCHER_LIST) {
            updateLauncherEntries(envelope.payload)
            return
        }
        if (deliverLocal(envelope)) return
        if (envelope.path == BusPaths.ERROR) {
            log("dropping undeliverable remote error id=${envelope.id}")
            return
        }
        if (envelope.binary != null) {
            log("dropping undeliverable binary ${envelope.path} id=${envelope.id}; no live registration")
            return
        }
        val context = appContext
        if (context != null && GlassesClientSupervisor.enqueue(context, envelope)) return
        sendRemote(errorEnvelope(envelope.id, "NO_LOCAL_CLIENT"))
    }

    fun deliverQueued(envelope: BusEnvelope): Boolean =
        deliverLocal(envelope)

    fun debugWake(context: Context, path: String): String {
        start(context)
        val envelope = BusEnvelope(path = path, payload = JSONObject().put("debugWake", true))
        return "wakeQueued=${GlassesClientSupervisor.enqueue(context.applicationContext, envelope)} path=$path"
    }

    fun debugPhoneWakeEcho(context: Context): String {
        start(context)
        val envelope = BusEnvelope(
            path = BusPaths.PROBE_ECHO,
            payload = JSONObject().put("message", "hello from glasses phone wake probe"),
        )
        val error = sendRemote(envelope)
        return if (error == null) {
            "phoneWakeEchoSent=true path=${BusPaths.PROBE_ECHO} id=${envelope.id}"
        } else {
            "phoneWakeEchoSent=false path=${BusPaths.PROBE_ECHO} id=${envelope.id} code=$error"
        }
    }

    fun observeLauncher(listener: (List<LauncherEntry>) -> Unit): () -> Unit {
        launcherListeners += listener
        listener(launcherEntries)
        return { launcherListeners.remove(listener) }
    }

    fun openLauncherEntry(pluginId: String): String {
        if (pluginId.isBlank()) return "launcherOpen=false reason=blank"
        val error = sendRemote(
            BusEnvelope(
                path = BusPaths.LAUNCHER_OPEN,
                payload = JSONObject().put("pluginId", pluginId),
            ),
        )
        return if (error == null) {
            "launcherOpen=true pluginId=$pluginId"
        } else {
            "launcherOpen=false pluginId=$pluginId code=$error"
        }
    }

    fun sendSurfaceInput(payload: JSONObject): String? =
        sendRemote(BusEnvelope(path = BusPaths.SURFACE_INPUT, payload = payload))

    private fun routeLocal(envelope: BusEnvelope, senderUid: Int) {
        if (BusPaths.isProtectedLensPath(envelope.path) && !isTrustedUid(senderUid)) {
            log("blocked untrusted protected lens send uid=$senderUid")
            return
        }
        if (envelope.path == BusPaths.GLASSES_WIFI_REQUEST) {
            if (!isTrustedUid(senderUid)) {
                log("blocked untrusted glasses Wi-Fi request uid=$senderUid")
                return
            }
            val rawEnabled = envelope.payload.opt("enabled")
            if (rawEnabled !is Boolean) {
                log("glassesWifiRequest rejected reason=invalid_payload")
                return
            }
            val context = appContext
            if (context == null) {
                log("glassesWifiRequest enabled=$rawEnabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
                return
            }
            wifiRequestExecutor.execute {
                handleGlassesWifiRequest(context, rawEnabled)
            }
            return
        }
        if (deliverLocal(envelope, excludeUid = senderUid)) return
        val errorCode = sendRemote(envelope)
        if (errorCode != null) {
            deliverLocal(errorEnvelope(envelope.id, errorCode))
        }
    }

    private fun deliverLocal(envelope: BusEnvelope, excludeUid: Int? = null): Boolean {
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        val binary = envelope.binary
        var delivered = false
        registrations.forEach { registration ->
            if (excludeUid != null && registration.uid == excludeUid) return@forEach
            if (BusPaths.isProtectedLensPath(envelope.path) && !registration.trusted) return@forEach
            if (registration.prefixes.any { envelope.path.startsWith(it) }) {
                if (binary != null && binary.size > LOCAL_BINARY_MAX_BYTES) {
                    log("drop local binary ${envelope.path} id=${envelope.id} bytes=${binary.size} over cap=$LOCAL_BINARY_MAX_BYTES")
                    delivered = true
                    return@forEach
                }
                runCatching {
                    if (binary == null) {
                        registration.callback.onMessage(envelope.path, envelope.id, payload)
                    } else {
                        registration.callback.onBinaryMessage(envelope.path, envelope.id, payload, binary)
                    }
                    delivered = true
                    GlassesClientSupervisor.touch()
                }.onFailure {
                    registrations.remove(registration)
                }
            }
        }
        return delivered
    }

    private fun updateLauncherEntries(payload: JSONObject) {
        val array = payload.optJSONArray("plugins") ?: JSONArray()
        val entries = buildList {
            for (index in 0 until array.length()) {
                val item = array.optJSONObject(index)
                if (item != null) {
                    val id = item.optString("id")
                    if (id.isNotBlank()) {
                        add(
                            LauncherEntry(
                                id = id,
                                displayName = item.optString("displayName", id),
                            ),
                        )
                    }
                }
            }
        }
        launcherEntries = entries
        launcherListeners.forEach { listener ->
            runCatching { listener(entries) }
        }
        log("launcher list synced count=${entries.size}")
    }

    private fun sendRemote(envelope: BusEnvelope): String? {
        if (envelope.binary != null) {
            if (!SppServerManager.isConnected()) return "NO_DATA_PLANE"
            return if (SppServerManager.send(envelope)) null else "NO_DATA_PLANE"
        }
        val bytes = FrameProtocol.toJsonBytes(envelope)
        if (bytes.size <= BusConstants.CXR_CONTROL_MAX_BYTES && CxrBusBridge.isUp()) {
            if (CxrBusBridge.send(envelope)) return null
        }
        if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES && !SppServerManager.isConnected()) {
            return "NO_DATA_PLANE"
        }
        if (SppServerManager.send(envelope)) return null
        return if (bytes.size > BusConstants.CXR_CONTROL_MAX_BYTES) "NO_DATA_PLANE" else "NO_LINK"
    }

    private fun linkState(): Int {
        var state = 0
        if (cxrUp) state = state or LinkStateBits.CXR_CONTROL_UP
        if (SppServerManager.isConnected()) state = state or LinkStateBits.SPP_DATA_UP
        if (phoneConnected) state = state or LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED
        return state
    }

    private fun notifyLinkState() {
        val state = linkState()
        registrations.forEach { registration ->
            runCatching { registration.callback.onLinkState(state) }
                .onFailure { registrations.remove(registration) }
        }
    }

    private fun errorEnvelope(id: String, code: String): BusEnvelope =
        BusEnvelope(
            path = BusPaths.ERROR,
            id = id,
            payload = JSONObject().put("code", code).put("forId", id),
        )

    private fun isTrustedUid(uid: Int): Boolean {
        val context = appContext ?: return false
        return context.packageManager.checkSignatures(uid, android.os.Process.myUid()) ==
            PackageManager.SIGNATURE_MATCH
    }

    private fun handleGlassesWifiRequest(context: Context, enabled: Boolean) {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifiManager == null) {
            log("glassesWifiRequest enabled=$enabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
            return
        }
        val wifiCurrentlyEnabled = runCatching { wifiManager.isWifiEnabled }
            .onFailure { logError("glassesWifiRequest state read failed", it) }
            .getOrNull()
        if (wifiCurrentlyEnabled == null) {
            log("glassesWifiRequest enabled=$enabled hubOwned=${wifiOwnership.isHubOwned()} applied=false")
            return
        }
        val result = wifiOwnership.handleRequest(
            enabled = enabled,
            wifiCurrentlyEnabled = wifiCurrentlyEnabled,
            setWifiEnabled = { requested ->
                runCatching { SelfArmController.setWifiEnabled(context, requested) }
                    .onFailure { logError("glassesWifiRequest shell failed", it) }
                    .getOrDefault(false)
            },
        )
        log("glassesWifiRequest enabled=$enabled hubOwned=${result.hubOwned} applied=${result.applied}")
    }
}
