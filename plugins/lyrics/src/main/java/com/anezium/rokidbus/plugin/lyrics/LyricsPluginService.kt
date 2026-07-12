package com.anezium.rokidbus.plugin.lyrics

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.lyrics.LyricsRuntime
import com.anezium.rokidbus.lyrics.LyricsRuntimeGraph
import com.anezium.rokidbus.lyrics.LyricsRuntimeHost
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class LyricsPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null
    private val runtime by lazy { LyricsRuntime(runtimeHost) }

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
    }

    override fun onCreate() {
        super.onCreate()
        runtime.register()
    }

    override fun onNexusOpen() {
        surfaceSession()
        LyricsRuntimeGraph.start(applicationContext)
        runtime.open()
    }

    override fun onNexusClose() {
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
