package com.anezium.rokidnexus.sample

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

class HelloPluginService : NexusPluginService() {
    private val state = HelloPluginState()
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        render(show = true)
    }

    override fun onNexusClose() {
        surface?.hide()
        surface = null
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
        render(show = false)
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
