package com.anezium.rokidbus.client.ui

import com.anezium.rokidbus.client.R
import org.junit.Assert.assertEquals
import org.junit.Test

class NexusPluginIconsTest {
    private val customIcon = PluginCustomIcon("dev.example.plugin", 123)

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
        assertEquals(R.drawable.ic_plugin_cart, NexusPluginIcons.drawableFor(null, "shoplist"))
    }

    @Test
    fun `cart resolves as a built-in icon key`() {
        assertEquals(R.drawable.ic_plugin_cart, NexusPluginIcons.drawableFor("cart"))
    }

    @Test
    fun `unknown icon and plugin id use grid fallback`() {
        assertEquals(R.drawable.ic_plugin_grid, NexusPluginIcons.drawableFor("unknown", "other"))
        assertEquals(R.drawable.ic_plugin_grid, NexusPluginIcons.drawableFor(null))
    }

    @Test
    fun `built-in key wins over custom icon`() {
        var customLoads = 0

        val resolved = NexusPluginIcons.resolveWithLoaders(
            iconKey = "star",
            customIcon = customIcon,
            builtInLoader = { "built-in:$it" },
            customLoader = {
                customLoads += 1
                "custom"
            },
        )

        assertEquals("built-in:${R.drawable.ic_plugin_star}", resolved)
        assertEquals(0, customLoads)
    }

    @Test
    fun `custom icon is used when no built-in key is supplied`() {
        val resolved = NexusPluginIcons.resolveWithLoaders(
            iconKey = null,
            customIcon = customIcon,
            pluginId = "lyrics",
            builtInLoader = { "built-in:$it" },
            customLoader = { "custom:${it.packageName}:${it.resId}" },
        )

        assertEquals("custom:dev.example.plugin:123", resolved)
    }

    @Test
    fun `custom loader failure falls through to grid`() {
        val resolved = NexusPluginIcons.resolveWithLoaders(
            iconKey = null,
            customIcon = customIcon,
            builtInLoader = { it },
            customLoader = { throw AssertionError("broken drawable") },
        )

        assertEquals(R.drawable.ic_plugin_grid, resolved)
    }

    @Test
    fun `caller fallback is used after descriptor resolution fails`() {
        val resolved = NexusPluginIcons.resolveWithLoaders(
            iconKey = "unknown",
            customIcon = customIcon,
            pluginId = "lyrics",
            builtInLoader = { it },
            customLoader = { throw AssertionError("broken drawable") },
            fallbackResId = R.drawable.ic_plugin_star,
        )

        assertEquals(R.drawable.ic_plugin_star, resolved)
    }

    @Test
    fun `missing key and custom icon use grid`() {
        val resolved = NexusPluginIcons.resolveWithLoaders(
            iconKey = null,
            customIcon = null,
            builtInLoader = { it },
            customLoader = { error("custom loader should not run") },
        )

        assertEquals(R.drawable.ic_plugin_grid, resolved)
    }
}
