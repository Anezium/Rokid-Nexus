package com.anezium.rokidbus.client.ui

import com.anezium.rokidbus.client.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NexusPluginIconsTest {
    @Test
    fun `explicit icon keys resolve to their drawables`() {
        val icons = mapOf(
            "music" to R.drawable.ic_plugin_music,
            "disc" to R.drawable.ic_plugin_disc,
            "bus" to R.drawable.ic_plugin_bus,
            "lens" to R.drawable.ic_plugin_lens,
            "mic" to R.drawable.ic_plugin_mic,
            "send" to R.drawable.ic_plugin_send,
            "feed" to R.drawable.ic_plugin_feed,
            "weather" to R.drawable.ic_plugin_weather,
            "chat" to R.drawable.ic_plugin_chat,
            "calendar" to R.drawable.ic_plugin_calendar,
            "clock" to R.drawable.ic_plugin_clock,
            "star" to R.drawable.ic_plugin_star,
            "heart" to R.drawable.ic_plugin_heart,
            "game" to R.drawable.ic_plugin_game,
            "globe" to R.drawable.ic_plugin_globe,
            "bell" to R.drawable.ic_plugin_bell,
            "terminal" to R.drawable.ic_plugin_terminal,
            "grid" to R.drawable.ic_plugin_grid,
            "map" to R.drawable.ic_plugin_map,
            "bolt" to R.drawable.ic_plugin_bolt,
            "bookmark" to R.drawable.ic_plugin_bookmark,
        )

        icons.forEach { (key, drawable) ->
            assertEquals(drawable, NexusPluginIcons.drawableFor(key, pluginId = "lyrics"))
        }
    }

    @Test
    fun `legacy plugin ids retain their historical icons`() {
        assertEquals(R.drawable.ic_plugin_music, NexusPluginIcons.drawableFor(null, "lyrics"))
        assertEquals(R.drawable.ic_plugin_disc, NexusPluginIcons.drawableFor(null, "media"))
        assertEquals(R.drawable.ic_plugin_bus, NexusPluginIcons.drawableFor(null, "transit"))
        assertEquals(R.drawable.ic_plugin_lens, NexusPluginIcons.drawableFor("unknown", "lens"))
    }

    @Test
    fun `unknown icon and plugin id use grid fallback`() {
        assertEquals(R.drawable.ic_plugin_grid, NexusPluginIcons.drawableFor("unknown", "other"))
        assertEquals(R.drawable.ic_plugin_grid, NexusPluginIcons.drawableFor(null))
    }
}
