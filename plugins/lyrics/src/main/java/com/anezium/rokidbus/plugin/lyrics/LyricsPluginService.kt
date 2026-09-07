package com.anezium.rokidbus.plugin.lyrics

import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusSdkResult
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
    private val widgetSettings by lazy { LyricsWidgetSettingsStore(this) }
    private val main = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            runtime.tick()
            main.postDelayed(this, 1_000L)
        }
    }
    private val runtime: LyricsRuntime by lazy {
        LyricsRuntime(runtimeHost, dismissedTrack = widgetSettings::dismissedTrack,
            clearDismissedTrack = { widgetSettings.dismissTrack(null) })
    }

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

        override fun showWidget(widget: NexusLyricsWidget): Boolean =
            nexusClient?.showWidget(widget) == NexusSdkResult.SENT

        override fun updateWidgetAnchor(contentKey: String, anchor: NexusPlaybackAnchor): Boolean =
            nexusClient?.updateWidgetAnchor(contentKey, anchor) == NexusSdkResult.SENT

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
        main.removeCallbacks(tick)
        main.postDelayed(tick, 1_000L)
    }

    override fun onNexusClose() {
        main.removeCallbacks(tick)
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
            main.removeCallbacks(tick)
            runtime.close()
            LyricsRuntimeGraph.stop()
            surface = null
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
