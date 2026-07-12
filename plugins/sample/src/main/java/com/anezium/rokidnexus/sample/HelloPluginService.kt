package com.anezium.rokidnexus.sample

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusImage
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class HelloPluginService : NexusPluginService() {
    private val state = HelloPluginState()
    private var surface: NexusSurfaceSession? = null
    private var showingImage = false

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        showingImage = showBundledImage()
        if (!showingImage) render(show = true)
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
        showingImage = false
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> state.move(1)
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> state.move(-1)
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> state.activate()
            KeyEvent.KEYCODE_BACK -> {
                surface?.hide()
                return
            }
            else -> return
        }
        showingImage = false
        render(show = false)
    }

    private fun showBundledImage(): Boolean {
        if (nexusClient?.supportsImageSurface != true) return false
        val bytes = resources.openRawResource(R.raw.image_surface_sample).use { it.readBytes() }
        val image = NexusImage(
            contentKey = "sample-tree-v1",
            mimeType = ImageSurfaceContract.MIME_JPEG,
            pixelWidth = 480,
            pixelHeight = 480,
            title = "Hello Nexus image",
            caption = "Bundled JPEG over the SPP data plane",
            footer = "swipe for card demo · back",
            handlesBack = true,
        )
        return surface?.showImage(image, bytes) == NexusSdkResult.SENT
    }

    private fun render(show: Boolean) {
        val card = NexusCard(
            title = "Hello Nexus",
            lines = state.lines(),
            footer = "swipe · tap · back",
            contentKey = "hello-v1",
        )
        if (show) surface?.showCard(card) else surface?.updateCard(card)
    }

    private companion object {
        const val SURFACE_ID = "main"
    }
}
