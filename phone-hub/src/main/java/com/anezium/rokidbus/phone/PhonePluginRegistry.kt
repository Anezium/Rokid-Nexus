package com.anezium.rokidbus.phone

import android.content.Context
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.NexusSubscription
import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicLong

class PhonePluginRegistry(
    override val context: Context,
    plugins: List<NexusPlugin>,
    private val sendEnvelope: (BusEnvelope) -> String?,
    private val capabilitiesProvider: () -> Int,
    private val logger: (String) -> Unit,
    private val catalogProvider: (() -> PluginCatalog)? = null,
    private val externalController: ExternalPluginController? = null,
    private val journal: PluginBusJournal? = null,
) : NexusPluginHost {
    private data class Subscription(
        val pathPrefix: String,
        val handler: (path: String, id: String, payload: JSONObject) -> Unit,
    )

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val pluginsById = linkedMapOf<String, NexusPlugin>()
    private val builtInPlugins = plugins.toList()
    private val pluginExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "nexus-plugin")
    }
    // Wall-clock seed so a hub process restart never replays seq values the glasses already saw.
    private val surfaceSeq = AtomicLong(System.currentTimeMillis())
    @Volatile private var activePluginId: String? = null

    init {
        plugins.forEach(::register)
    }

    override fun send(path: String, payload: JSONObject) {
        sendPluginEnvelope(path = path, id = null, payload = payload)
    }

    override fun send(path: String, id: String, payload: JSONObject) {
        sendPluginEnvelope(path = path, id = id, payload = payload)
    }

    override fun sendBinary(path: String, payload: JSONObject, data: ByteArray) {
        sendPluginEnvelope(path = path, id = null, payload = payload, binary = data)
    }

    override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        sendPluginEnvelope(path = path, id = id, payload = payload, binary = data)
    }

    override fun supportsImageSurface(): Boolean =
        capabilitiesProvider() and BusCapabilityBits.IMAGE_SURFACE != 0

    private fun sendPluginEnvelope(
        path: String,
        id: String?,
        payload: JSONObject,
        binary: ByteArray? = null,
    ) {
        if (path == BusPaths.SURFACE_HIDE && payload.optString("surfaceId") == activePluginId) {
            activePluginId = null
        }
        val outgoing = payload.withSurfaceMetadata(path)
        val envelope = if (id == null) {
            BusEnvelope(path = path, payload = outgoing, binary = binary)
        } else {
            BusEnvelope(path = path, id = id, payload = outgoing, binary = binary)
        }
        val error = sendEnvelope(envelope)
        if (error != null) logger("plugin send failed path=$path code=$error")
    }

    override fun subscribe(
        pathPrefix: String,
        handler: (path: String, id: String, payload: JSONObject) -> Unit,
    ): NexusSubscription {
        val subscription = Subscription(pathPrefix, handler)
        subscriptions += subscription
        return NexusSubscription { subscriptions.remove(subscription) }
    }

    override fun post(action: () -> Unit) {
        enqueue("plugin post", action)
    }

    override fun log(message: String) {
        logger(message)
    }

    fun handleRemote(envelope: BusEnvelope): Boolean {
        when (envelope.path) {
            BusPaths.LAUNCHER_OPEN -> {
                val pluginId = envelope.payload.optString("pluginId")
                    .ifBlank { envelope.payload.optString("id") }
                val opened = open(pluginId)
                recordRemote(
                    pluginId = pluginId.ifBlank { null },
                    category = PluginBusJournal.Category.LAUNCHER,
                    path = envelope.path,
                    verdict = if (opened) PluginBusJournal.Verdict.OK else PluginBusJournal.Verdict.REJECTED,
                    reason = if (opened) null else if (pluginId.isBlank()) "MISSING_PLUGIN_ID" else "OPEN_FAILED",
                )
                return opened
            }
            BusPaths.SURFACE_INPUT -> {
                handleSurfaceInput(envelope.payload)
                return true
            }
        }

        var handled = false
        subscriptions.forEach { subscription ->
            if (PathRules.matchesPrefix(envelope.path, subscription.pathPrefix)) {
                handled = true
                runCatching {
                    subscription.handler(envelope.path, envelope.id, envelope.payload)
                }.onFailure {
                    logger("plugin subscription failed path=${envelope.path}: ${it.message}")
                }
            }
        }
        return handled
    }

    /**
     * Gate for surfaces pushed by external plugins on their own initiative: only the
     * foreground plugin may draw on the HUD. On an idle HUD a show adopts the sender
     * as the foreground plugin; anything pushed while another plugin holds the HUD is
     * rejected so a background auto-open (lyrics) cannot steal the display.
     */
    fun allowExternalSurface(principal: PhonePluginPrincipal, path: String): Boolean {
        val controller = externalController ?: return true
        val externalActive = controller.activeId()
        if (externalActive == principal.descriptor.id) return true
        if (externalActive != null || activePluginId != null) return false
        if (path != BusPaths.SURFACE_SHOW) return false
        return controller.adopt(principal)
    }

    fun syncLauncherList() {
        send(
            BusPaths.LAUNCHER_LIST,
            JSONObject().put(
                "plugins",
                JSONArray().also { array ->
                    catalog().launchableEntries.forEach { entry ->
                        array.put(
                            JSONObject()
                                .put("id", entry.id)
                                .put("displayName", entry.displayName),
                        )
                    }
                },
            ),
        )
    }

    fun close() {
        externalController?.closeActive("hub_destroyed")
        activePluginId?.let { pluginId ->
            pluginsById[pluginId]?.let { plugin ->
                enqueue("plugin onClose id=${plugin.id}") { plugin.onClose() }
            }
        }
        subscriptions.clear()
        activePluginId = null
        // Shutdown after the final onClose is enqueued; executor shutdown drains submitted work in order.
        pluginExecutor.shutdown()
    }

    private fun register(plugin: NexusPlugin) {
        require(plugin.id.isNotBlank()) { "Plugin id must not be blank" }
        require(plugin.id !in pluginsById) { "Duplicate built-in plugin id=${plugin.id}" }
        pluginsById[plugin.id] = plugin
        plugin.onRegister(this)
        logger("plugin registered id=${plugin.id} display=${plugin.displayName}")
    }

    private fun open(pluginId: String): Boolean {
        val entry = catalog().entry(pluginId)?.takeIf { it.launchable } ?: return false
        val external = entry.principal
        if (external != null) {
            activePluginId?.let { previous ->
                pluginsById[previous]?.let { previousPlugin ->
                    enqueue("plugin onClose id=${previousPlugin.id}") { previousPlugin.onClose() }
                }
            }
            activePluginId = null
            return externalController?.open(external) == true
        }
        externalController?.closeActive("built_in_opened")
        val plugin = entry.builtIn ?: pluginsById[pluginId]?.takeIf { it.launchable } ?: return false
        activePluginId?.takeIf { it != plugin.id }?.let { previous ->
            pluginsById[previous]?.let { previousPlugin ->
                enqueue("plugin onClose id=${previousPlugin.id}") { previousPlugin.onClose() }
            }
        }
        activePluginId = plugin.id
        enqueue("plugin onOpen id=${plugin.id}") {
            plugin.onOpen()
            logger("plugin opened id=${plugin.id}")
        }
        return true
    }

    private fun handleSurfaceInput(payload: JSONObject) {
        val surfaceId = payload.optString("surfaceId")
        val keyCode = payload.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
        val action = payload.optInt("action", KeyEvent.ACTION_DOWN)
        val ownerPluginId = payload.optString("ownerPluginId")
            .ifBlank { surfaceId.substringBefore(':').takeIf { ':' in surfaceId }.orEmpty() }
        val localSurfaceId = payload.optString("localSurfaceId")
            .ifBlank { surfaceId.substringAfter(':', surfaceId) }
        if (ownerPluginId.isNotBlank() &&
            externalController?.input(ownerPluginId, localSurfaceId, keyCode, action) == true
        ) {
            recordRemote(
                pluginId = ownerPluginId,
                category = PluginBusJournal.Category.INPUT,
                path = BusPaths.SURFACE_INPUT,
                verdict = PluginBusJournal.Verdict.OK,
            )
            return
        }
        val plugin = pluginsById[surfaceId] ?: activePluginId?.let { pluginsById[it] }
        if (plugin == null) {
            recordRemote(
                pluginId = ownerPluginId.ifBlank { null },
                category = PluginBusJournal.Category.INPUT,
                path = BusPaths.SURFACE_INPUT,
                verdict = PluginBusJournal.Verdict.REJECTED,
                reason = "NO_ACTIVE_PLUGIN",
            )
            return
        }
        val event = NexusInputEvent(
            surfaceId = surfaceId.ifBlank { plugin.id },
            keyCode = keyCode,
            action = action,
        )
        recordRemote(
            pluginId = plugin.id,
            category = PluginBusJournal.Category.INPUT,
            path = BusPaths.SURFACE_INPUT,
            verdict = PluginBusJournal.Verdict.OK,
        )
        enqueue("plugin onInput id=${plugin.id}") { plugin.onInput(event) }
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            if (!plugin.handlesBack) {
                activePluginId = null
            }
        }
    }

    private fun recordRemote(
        pluginId: String?,
        category: PluginBusJournal.Category,
        path: String,
        verdict: PluginBusJournal.Verdict,
        reason: String? = null,
    ) {
        val target = journal ?: return
        if (!target.enabled.get()) return
        try {
            target.record(
                pluginId = pluginId,
                category = category,
                direction = PluginBusJournal.Direction.GLASSES_TO_HUB,
                path = path,
                verdict = verdict,
                reason = reason,
            )
        } catch (_: Throwable) {
            // Diagnostics must never affect plugin dispatch.
        }
    }

    fun catalog(): PluginCatalog = catalogProvider?.invoke()
        ?: PluginCatalog.build(builtInPlugins, emptyList()) { PluginGrantState.Pending }

    private fun enqueue(label: String, action: () -> Unit) {
        try {
            pluginExecutor.execute {
                runCatching(action).onFailure {
                    logger("$label failed: ${it.javaClass.simpleName}: ${it.message}")
                }
            }
        } catch (failure: RejectedExecutionException) {
            logger("$label dropped after plugin dispatcher shutdown: ${failure.message}")
        }
    }

    private fun JSONObject.withSurfaceMetadata(path: String): JSONObject {
        var outgoing = this
        if (path == BusPaths.SURFACE_SHOW || path == BusPaths.SURFACE_UPDATE || path == BusPaths.SURFACE_HIDE) {
            outgoing = JSONObject(toString()).put("seq", surfaceSeq.incrementAndGet())
        }
        if (path != BusPaths.SURFACE_SHOW && path != BusPaths.SURFACE_UPDATE) return outgoing
        val surfaceId = optString("surfaceId")
        val plugin = pluginsById[surfaceId] ?: return outgoing
        if (!plugin.handlesBack) return outgoing
        if (outgoing === this) outgoing = JSONObject(toString())
        return outgoing.put("handlesBack", true)
    }
}
