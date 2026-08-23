package com.anezium.rokidbus.plugin.lyrics

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusLyricsWidget
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.lyrics.LyricsRuntime
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.lyrics.LyricsRuntimeHost
import com.anezium.rokidbus.lyrics.settings.LyricsWidgetSettingsStore
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class LyricsPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null
    private val runtime: LyricsRuntime by lazy { LyricsRuntime(runtimeHost) }

    private val runtimeHost = object : LyricsRuntimeHost {
        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun sendTimedLines(lines: NexusTimedLines, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showTimedLines(lines) else session.updateTimedLines(lines)
        }

        override fun updateTimedLinesAnchor(contentKey: String, anchor: NexusPlaybackAnchor) {
            surfaceSession()?.updateTimedLinesAnchor(contentKey, anchor)
        }

        override fun hideSurface() {
            surface?.hide()
        }

        override fun showWidget(widget: NexusLyricsWidget) {
            nexusClient?.showWidget(widget)
        }

        override fun updateWidgetAnchor(contentKey: String, anchor: NexusPlaybackAnchor) {
            nexusClient?.updateWidgetAnchor(contentKey, anchor)
        }

        override fun hideWidget() {
            nexusClient?.hideWidget()
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime.register()
        LyricsRuntimeGraph.onWidgetModeChanged = { runtime.setWidgetMode(it) }
    }

    override fun onNexusOpen() {
        surfaceSession()
        runtime.setWidgetMode(LyricsWidgetSettingsStore(this).mode())
        runtime.setBackgroundOpen(currentOpenType == BusPaths.PLUGIN_OPEN_TYPE_MEDIA_TRIGGER)
        LyricsRuntimeGraph.start(applicationContext)
        runtime.open()
    }

    override fun onNexusClose() {
        runtime.setBackgroundOpen(false)
        runtime.close()
        LyricsRuntimeGraph.stop()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        runtime.input(event)
    }

    override fun onNexusRegistrationState(result: Int) {
        if (result == PluginRegistrationResult.APPROVED) {
            runtime.registrationApproved()
        } else {
            runtime.close()
            LyricsRuntimeGraph.stop()
            surface = null
        }
    }

    override fun onDestroy() {
        LyricsRuntimeGraph.onWidgetModeChanged = null
        runtime.unregister()
        LyricsRuntimeGraph.stop()
        surface = null
        super.onDestroy()
    }

    private fun surfaceSession(): NexusSurfaceSession? =
        surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }

    private companion object {
        const val SURFACE_ID = "lyrics"
    }
}
