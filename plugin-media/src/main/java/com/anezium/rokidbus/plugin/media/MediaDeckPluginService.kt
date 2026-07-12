package com.anezium.rokidbus.plugin.media

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusMedia
import com.anezium.rokidbus.client.plugin.NexusMediaAnchor
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.media.MediaDeckRuntime
import com.anezium.rokidbus.media.MediaDeckRuntimeHost
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class MediaDeckPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null
    private var runtime: MediaDeckRuntime? = null

    private val runtimeHost = object : MediaDeckRuntimeHost {
        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun sendMedia(media: NexusMedia, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showMedia(media) else session.updateMedia(media)
        }

        override fun updateMediaAnchor(contentKey: String, anchor: NexusMediaAnchor) {
            surfaceSession()?.updateMediaAnchor(contentKey, anchor)
        }

        override fun hideSurface() {
            surface?.hide()
        }

        override fun post(action: () -> Unit) {
            mainExecutor.execute(action)
        }
    }

    override fun onNexusOpen() {
        surfaceSession()
        ensureRuntime().open()
    }

    override fun onNexusClose() {
        runtime?.close()
        surface = null
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

    private fun surfaceSession(): NexusSurfaceSession? =
        surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }

    private fun ensureRuntime(): MediaDeckRuntime = runtime ?: MediaDeckRuntime(
        context = applicationContext,
        host = runtimeHost,
    ).also { runtime = it }

    private companion object {
        const val SURFACE_ID = "media"
    }
}
