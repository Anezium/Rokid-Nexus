package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Binder
import android.os.IBinder
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.FrameProtocol
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

object GlassesHub {
    data class LauncherEntry(
        val id: String,
        val displayName: String,
    )

    private data class Registration(
        val clientId: String,
        val prefixes: List<String>,
        val uid: Int,
        val callback: IBusCallback,
    )

    private val started = AtomicBoolean(false)
    private val registrations = CopyOnWriteArrayList<Registration>()
    private val launcherListeners = CopyOnWriteArrayList<(List<LauncherEntry>) -> Unit>()
    @Volatile private var launcherEntries: List<LauncherEntry> = emptyList()
    @Volatile private var appContext: Context? = null
    @Volatile private var cxrUp = false
    @Volatile private var phoneConnected = false

    private val aidl = object : IBusService.Stub() {
        override fun apiVersion(): Int = BusConstants.API_VERSION

        override fun register(clientId: String, pathPrefixes: Array<out String>, cb: IBusCallback) {
            unregister(cb)
            val cleanPrefixes = pathPrefixes.filter { it.isNotBlank() }
            registrations += Registration(clientId, cleanPrefixes, Binder.getCallingUid(), cb)
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

        override fun linkState(): Int = this@GlassesHub.linkState()
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
        if (deliverLocal(envelope, excludeUid = senderUid)) return
        val errorCode = sendRemote(envelope)
        if (errorCode != null) {
            deliverLocal(errorEnvelope(envelope.id, errorCode))
        }
    }

    private fun deliverLocal(envelope: BusEnvelope, excludeUid: Int? = null): Boolean {
        val payload = envelope.payload.toString().toByteArray(Charsets.UTF_8)
        var delivered = false
        registrations.forEach { registration ->
            if (excludeUid != null && registration.uid == excludeUid) return@forEach
            if (registration.prefixes.any { envelope.path.startsWith(it) }) {
                runCatching {
                    registration.callback.onMessage(envelope.path, envelope.id, payload)
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
}
