package com.anezium.rokidbus.plugin.feeds

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.NexusSubscription
import org.json.JSONArray
import org.json.JSONObject

class FeedsPlugin : NexusPlugin {
    override val id: String = SURFACE_ID
    override val displayName: String = "Feeds"
    override val handlesBack: Boolean = true

    private lateinit var host: NexusPluginHost
    private lateinit var runtime: FeedsRuntime
    private val subscriptions = mutableListOf<NexusSubscription>()
    private var surfaceShown = false

    private val runtimeHost = object : FeedsRuntimeHost {
        override fun sendCard(card: FeedCardContent, show: Boolean) {
            host.send(
                if (show || !surfaceShown) BusPaths.SURFACE_SHOW else BusPaths.SURFACE_UPDATE,
                JSONObject()
                    .put("surfaceId", SURFACE_ID)
                    .put("kind", "card")
                    .put("title", card.title)
                    .put("lines", JSONArray(card.lines))
                    .put("footer", card.footer)
                    .put("contentKey", card.contentKey())
                    .put("handlesBack", true),
            )
            surfaceShown = true
        }

        override fun sendImage(payload: JSONObject, bytes: ByteArray) {
            host.sendBinary(
                if (!surfaceShown) BusPaths.SURFACE_SHOW else BusPaths.SURFACE_UPDATE,
                JSONObject(payload.toString())
                    .put("surfaceId", SURFACE_ID)
                    .put("handlesBack", true),
                bytes,
            )
            surfaceShown = true
        }

        override fun supportsImage(): Boolean = host.supportsImageSurface()

        override fun hideSurface() {
            if (!surfaceShown) return
            host.send(BusPaths.SURFACE_HIDE, JSONObject().put("surfaceId", SURFACE_ID))
            surfaceShown = false
        }

        override fun post(action: () -> Unit) = host.post(action)

        override fun log(message: String) = host.log(message)
    }

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        runtime = FeedsRuntime(
            context = host.context,
            host = runtimeHost,
            settings = FeedsSettingsStore(host.context)::load,
        )
        subscriptions += host.subscribe(PLUGIN_PREFIX) { _, _, _ -> Unit }
        subscriptions += host.subscribe(SYSTEM_PLUGIN_PREFIX) { _, _, _ -> Unit }
    }

    override fun onOpen() {
        surfaceShown = false
        runtime.open()
    }

    override fun onClose() = runtime.close()

    override fun onInput(event: NexusInputEvent) = runtime.input(event)

    private companion object {
        const val SURFACE_ID = "feeds"
        const val PLUGIN_PREFIX = "/plugin/feeds"
        const val SYSTEM_PLUGIN_PREFIX = "/system/plugin"
    }
}
