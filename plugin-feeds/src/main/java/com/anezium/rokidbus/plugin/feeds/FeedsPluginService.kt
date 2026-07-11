package com.anezium.rokidbus.plugin.feeds

import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class FeedsPluginService : NexusPluginService() {
    private var runtime: FeedsRuntime? = null
    private var surface: NexusSurfaceSession? = null
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
        }

        override fun hideSurface() {
            surface?.hide()
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
