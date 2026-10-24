package com.anezium.rokidbus.plugin.relay

import android.app.Application
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONObject
import org.robolectric.Shadows.shadowOf
import java.util.Collections

/** The phone hub as Relay's real client sees it through Binder: it approves, records and delivers. */
internal class FakeHub : IBusService.Stub() {
    val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
    var callback: IBusCallback? = null
    private val clients = mutableListOf<IBusCallback>()
    var link = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
    var features = BusCapabilityBits.NOTICE_SURFACE or BusCapabilityBits.EDITABLE_SURFACE
    var registrations = 0

    /** The effective page version the hub announces to this client, or none. */
    var pageVersion: Int? = null

    /** The launcher bundle version announced with the page version, or none. */
    var launcherVersion: Int? = null
    private var nextId = 0

    override fun apiVersion(): Int = 3
    override fun register(clientId: String, pathPrefixes: Array<String>, cb: IBusCallback) = Unit
    override fun unregister(cb: IBusCallback) = Unit
    override fun send(path: String, id: String, payload: ByteArray) {
        sent += path to JSONObject(String(payload, Charsets.UTF_8))
        // As the phone hub does once the request is on its disk; only the sender knows the id.
        if (path == BusPaths.NOTICE_FORGET && acceptsForgets) {
            deliverToEveryClient(BusPaths.NOTICE_FORGET_ACCEPTED) { JSONObject().put("forId", id) }
        }
    }

    /** False for a hub that never takes a forget, as when the request dies in a reconnect queue. */
    var acceptsForgets = true
    override fun linkState(): Int = link
    override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) = Unit
    override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int {
        callback = cb
        clients += cb
        registrations += 1
        // The hub's registration metadata follows its synchronous approval, as on a device.
        deliver(
            BusPaths.PLUGIN_REGISTRATION,
            JSONObject()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", CAPABILITIES)
                .put("noticeInteractionVersion", 1),
        )
        pageVersion?.let { version ->
            val capabilities = JSONObject().put("pageSessionVersion", version)
            launcherVersion?.let { capabilities.put(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION, it) }
            deliver(BusPaths.HUB_CAPABILITIES, capabilities)
        }
        return PluginRegistrationResult.APPROVED
    }
    override fun capabilities(): Int = features
    /** What the hub answers when a client asks for its grants on approval. */
    var granted: String = CAPABILITIES

    override fun approvedCapabilities(pluginId: String): String = granted

    fun deliver(path: String, payload: JSONObject) {
        val target = checkNotNull(callback) { "no registered client" }
        target.onMessage(path, "hub-${nextId++}", payload.put("pluginId", "relay").toString().toByteArray(Charsets.UTF_8))
    }

    /** A hub-wide change, such as a grant edit, reaches every client the plugin registered. */
    fun deliverToEveryClient(path: String, payload: () -> JSONObject) {
        clients.toList().forEach { client ->
            client.onMessage(path, "hub-${nextId++}", payload().put("pluginId", "relay").toString().toByteArray(Charsets.UTF_8))
        }
    }

    fun sentTo(path: String): List<JSONObject> = synchronized(sent) { sent.filter { it.first == path }.map { it.second } }

    fun sentSince(mark: Int): List<Pair<String, JSONObject>> = synchronized(sent) { sent.drop(mark) }

    companion object {
        const val CAPABILITIES = "surfaces,stt,tts"
    }
}

internal object RelayHubFixture {
    val HUB = ComponentName("com.anezium.rokidbus.phone", "com.anezium.rokidbus.phone.BusHubService")

    /** Makes the phone hub resolvable and bindable, and declares Relay's service as its manifest does. */
    fun install(app: Application, hub: FakeHub) {
        val packages = shadowOf(app.packageManager)
        packages.addServiceIfNotPresent(HUB)
        packages.addIntentFilterForService(HUB, IntentFilter(BusConstants.ACTION_HUB))
        shadowOf(app).setComponentNameAndServiceForBindService(HUB, hub)
        // As on a device: an explicit unbind is not a disconnect.
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val own = ComponentName(app, RelayPluginService::class.java)
        val info = ServiceInfo().apply {
            packageName = own.packageName
            name = own.className
            exported = true
            applicationInfo = app.applicationInfo
            metaData = Bundle().apply {
                putString("com.anezium.rokidbus.plugin.ID", "relay")
                putString("com.anezium.rokidbus.plugin.DISPLAY_NAME", "Relay")
                putString("com.anezium.rokidbus.plugin.API_VERSION", "3")
                putString("com.anezium.rokidbus.plugin.CAPABILITIES", FakeHub.CAPABILITIES)
                putString("com.anezium.rokidbus.plugin.RECEIVE_PREFIXES", "/plugin/relay,/system/plugin,/stt,/tts/started,/tts/done")
                putString("com.anezium.rokidbus.plugin.LAUNCHABLE", "true")
                putString("rokidbus.plugin.pages", "1")
            }
        }
        packages.addOrUpdateService(info)
    }

    fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs what is due within [ms] of main-thread time, such as the band's paced updates. */
    fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** A chat notification with a free-form reply action, as a messaging app posts it. */
    fun chat(app: Application, id: Int = 7, sender: String = "Mika", text: String = "on est devant"): StatusBarNotification {
        val intent = PendingIntent.getBroadcast(app, id, Intent("dev.example.REPLY"), PendingIntent.FLAG_MUTABLE)
        val action = Notification.Action.Builder(Icon.createWithResource(app, android.R.drawable.ic_menu_send), "Reply", intent)
            .addRemoteInput(RemoteInput.Builder("text").setAllowFreeFormInput(true).build())
            .build()
        val notification = Notification.Builder(app, "chat")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(sender)
            .setContentText(text)
            .addAction(action)
            .build()
        return StatusBarNotification(
            "dev.example.chat", "dev.example.chat", id, null, 10_123, 0, 0,
            notification, Process.myUserHandle(), System.currentTimeMillis(),
        )
    }
}
