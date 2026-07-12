package com.anezium.rokidbus.plugin.feeds

import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject

class FeedsPluginService : NexusPluginService() {
    private var runtime: FeedsRuntime? = null
    private var surface: NexusSurfaceSession? = null
    private var surfaceShown = false
    private val settingsStore by lazy { FeedsSettingsStore(applicationContext) }

    private val runtimeHost = object : FeedsRuntimeHost {
        override fun sendCard(card: FeedCardContent, show: Boolean) {
            val sdkCard = NexusCard(
                title = card.title,
                lines = card.lines,
                footer = card.footer,
                contentKey = card.contentKey(),
                handlesBack = true,
            )
            val session = surface ?: return
            if (show) session.showCard(sdkCard) else session.updateCard(sdkCard)
            surfaceShown = true
        }

        override fun sendImage(payload: JSONObject, bytes: ByteArray) {
            val session = surface ?: return
            val image = runCatching {
                NexusImage(
                    contentKey = payload.getString("contentKey"),
                    mimeType = payload.getString("mimeType"),
                    pixelWidth = payload.getInt("pixelWidth"),
                    pixelHeight = payload.getInt("pixelHeight"),
                    title = payload.optString("title").takeIf(String::isNotEmpty),
                    caption = payload.optString("caption").takeIf(String::isNotEmpty),
                    footer = payload.optString("footer").takeIf(String::isNotEmpty),
                    handlesBack = payload.optBoolean("handlesBack"),
                )
            }.getOrElse { failure ->
                Log.w(TAG, "Image payload rejected: ${failure.message}")
                return
            }
            if (surfaceShown) session.updateImage(image, bytes) else session.showImage(image, bytes)
            surfaceShown = true
        }

        override fun supportsImage(): Boolean = nexusClient?.supportsImageSurface == true

        override fun hideSurface() {
            surface?.hide()
            surfaceShown = false
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }

        override fun log(message: String) {
            Log.i(TAG, message)
        }
    }

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        surfaceShown = false
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        surface = null
        surfaceShown = false
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime?.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) runtime?.close()
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        surface = null
        super.onDestroy()
    }

    private fun ensureRuntime(): FeedsRuntime = runtime ?: FeedsRuntime(
        context = applicationContext,
        host = runtimeHost,
        settings = settingsStore::load,
    ).also { runtime = it }

    private companion object {
        const val TAG = "NexusFeeds"
        const val SURFACE_ID = "feeds"
    }
}
