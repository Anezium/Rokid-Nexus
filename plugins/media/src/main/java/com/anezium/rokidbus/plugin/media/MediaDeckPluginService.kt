package com.anezium.rokidbus.plugin.media

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActivityCommand
import com.anezium.rokidbus.client.plugin.NexusActivityCommandResult
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusMedia
import com.anezium.rokidbus.client.plugin.NexusMediaAnchor
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageAction
import com.anezium.rokidbus.client.plugin.NexusPageClosed
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageVisibility
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.media.MediaDeckActivityControl
import com.anezium.rokidbus.media.MediaDeckPageProvider
import com.anezium.rokidbus.media.MediaDeckRuntime
import com.anezium.rokidbus.media.MediaDeckRuntimeHost
import com.anezium.rokidbus.media.session.MediaDeckMonitorHub
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject

class MediaDeckPluginService : NexusPluginService() {
    private val main = Handler(Looper.getMainLooper())
    private var surface: NexusSurfaceSession? = null
    private var runtime: MediaDeckRuntime? = null

    private val runtimeHost = object : MediaDeckRuntimeHost {
        override fun supportsImage(): Boolean = nexusClient?.supportsImageSurface == true

        override fun sendCard(card: NexusCard, show: Boolean) {
            val session = surfaceSession() ?: return
            if (show) session.showCard(card) else session.updateCard(card)
        }

        override fun sendMedia(media: NexusMedia, imageBytes: ByteArray?, show: Boolean) {
            val session = surfaceSession() ?: return
            if (imageBytes == null) {
                if (show) session.showMedia(media) else session.updateMedia(media)
            } else {
                if (show) session.showMedia(media, imageBytes) else session.updateMedia(media, imageBytes)
            }
        }

        override fun updateMediaAnchor(contentKey: String, anchor: NexusMediaAnchor) {
            surfaceSession()?.updateMediaAnchor(contentKey, anchor)
        }

        override fun hideSurface() {
            surface?.hide()
        }
    }

    // Pages read the shared monitor; they never open, close or touch the immersion.
    private val pages by lazy {
        MediaDeckPageProvider<NexusPageRequest>(
            hub = MediaDeckMonitorHub.get(applicationContext),
            pageIdOf = NexusPageRequest::pageId,
            floorOf = NexusPageRequest::minimumRevision,
            publish = { request, page -> nexusClient?.publishPage(request, page) ?: NexusSdkResult.NOT_REGISTERED },
            extended = { nexusClient?.supportsLauncherInteractive() == true },
            now = SystemClock::elapsedRealtime,
            schedule = { delayMs, task ->
                val runnable = Runnable { task() }
                main.postDelayed(runnable, delayMs)
                ({ main.removeCallbacks(runnable) })
            },
        )
    }

    override fun onCreate() {
        super.onCreate()
        nexusClient?.let { MediaDeckActivityControl.serviceCreated(this, it) }
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
        if (result != PluginRegistrationResult.APPROVED) {
            runtime?.close()
            pages.forget()
        }
        MediaDeckActivityControl.registrationState(result)
    }

    override fun onNexusLinkState(state: Int) {
        runtime?.imageCapabilityChanged()
        if (state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) pages.forget()
        MediaDeckActivityControl.linkState(state)
    }

    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage = pages.onRequest(request)

    override fun onNexusPageAction(action: NexusPageAction): NexusPageResult =
        pages.onAction(action.pageId, action.revision, action.actionId, action.typed)

    override fun onNexusPageVisibility(visibility: NexusPageVisibility) =
        pages.onVisibility(visibility.pageId, visibility.visible, visibility.leaseDurationMs)

    override fun onNexusPageClosed(closed: NexusPageClosed) = pages.onClosed(closed.pageId)

    override fun onNexusActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? =
        MediaDeckActivityControl.command(command)

    // Only glasses without the launcher bundle deliver this; the same session check applies.
    override fun onNexusActivityAction(id: String) = MediaDeckActivityControl.legacyAction(id)

    override fun onNexusActivityClosed(reason: String) = MediaDeckActivityControl.activityClosed(reason)

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path == BusPaths.ERROR) MediaDeckActivityControl.error(payload.optString("code"))
    }

    override fun onDestroy() {
        runtime?.close()
        runtime = null
        surface = null
        pages.forget()
        nexusClient?.let(MediaDeckActivityControl::serviceDestroyed)
        super.onDestroy()
    }

    private fun surfaceSession(): NexusSurfaceSession? =
        surface ?: nexusSurfaceSession(SURFACE_ID).also { surface = it }

    private fun ensureRuntime(): MediaDeckRuntime = runtime ?: MediaDeckRuntime(
        context = applicationContext,
        host = runtimeHost,
        hub = MediaDeckMonitorHub.get(applicationContext),
    ).also { runtime = it }

    private companion object {
        const val SURFACE_ID = "media"
    }
}
