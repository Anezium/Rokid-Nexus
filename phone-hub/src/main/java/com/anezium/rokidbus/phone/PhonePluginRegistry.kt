package com.anezium.rokidbus.phone

import android.content.Context
import android.view.KeyEvent
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.NexusSubscription
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

class PhonePluginRegistry(
    override val context: Context,
    plugins: List<NexusPlugin>,
    private val sendEnvelope: (BusEnvelope) -> String?,
    private val logger: (String) -> Unit,
) : NexusPluginHost {
    private data class Subscription(
        val pathPrefix: String,
        val handler: (path: String, id: String, payload: JSONObject) -> Unit,
    )

    private val subscriptions = CopyOnWriteArrayList<Subscription>()
    private val pluginsById = linkedMapOf<String, NexusPlugin>()
    @Volatile private var activePluginId: String? = null

    init {
        plugins.forEach(::register)
    }

    override fun send(path: String, payload: JSONObject) {
        val error = sendEnvelope(BusEnvelope(path = path, payload = payload))
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

    override fun log(message: String) {
        logger(message)
    }

    fun handleRemote(envelope: BusEnvelope): Boolean {
        when (envelope.path) {
            BusPaths.LAUNCHER_OPEN -> {
                val pluginId = envelope.payload.optString("pluginId")
                    .ifBlank { envelope.payload.optString("id") }
                return open(pluginId)
            }
            BusPaths.SURFACE_INPUT -> {
                handleSurfaceInput(envelope.payload)
                return true
            }
        }

        var handled = false
        subscriptions.forEach { subscription ->
            if (envelope.path.startsWith(subscription.pathPrefix)) {
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

    fun syncLauncherList() {
        send(
            BusPaths.LAUNCHER_LIST,
            JSONObject().put(
                "plugins",
                JSONArray().also { array ->
                    pluginsById.values.forEach { plugin ->
                        array.put(
                            JSONObject()
                                .put("id", plugin.id)
                                .put("displayName", plugin.displayName),
                        )
                    }
                },
            ),
        )
    }

    fun close() {
        activePluginId?.let { pluginsById[it]?.onClose() }
        subscriptions.clear()
        activePluginId = null
    }

    private fun register(plugin: NexusPlugin) {
        require(plugin.id.isNotBlank()) { "Plugin id must not be blank" }
        pluginsById[plugin.id] = plugin
        plugin.onRegister(this)
        logger("plugin registered id=${plugin.id} display=${plugin.displayName}")
    }

    private fun open(pluginId: String): Boolean {
        val plugin = pluginsById[pluginId] ?: return false
        activePluginId?.takeIf { it != plugin.id }?.let { previous ->
            pluginsById[previous]?.onClose()
        }
        activePluginId = plugin.id
        plugin.onOpen()
        logger("plugin opened id=${plugin.id}")
        return true
    }

    private fun handleSurfaceInput(payload: JSONObject) {
        val surfaceId = payload.optString("surfaceId")
        val keyCode = payload.optInt("keyCode", KeyEvent.KEYCODE_UNKNOWN)
        val action = payload.optInt("action", KeyEvent.ACTION_DOWN)
        val plugin = pluginsById[surfaceId] ?: activePluginId?.let { pluginsById[it] } ?: return
        plugin.onInput(
            NexusInputEvent(
                surfaceId = surfaceId.ifBlank { plugin.id },
                keyCode = keyCode,
                action = action,
            ),
        )
        if (keyCode == KeyEvent.KEYCODE_BACK && action == KeyEvent.ACTION_DOWN) {
            activePluginId = null
        }
    }
}
