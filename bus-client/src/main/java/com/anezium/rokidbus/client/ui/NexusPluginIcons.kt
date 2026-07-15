package com.anezium.rokidbus.client.ui

import com.anezium.rokidbus.client.R

object NexusPluginIcons {
    fun drawableFor(iconKey: String?, pluginId: String? = null): Int {
        val explicitIcon = when (iconKey) {
            "music" -> R.drawable.ic_plugin_music
            "disc" -> R.drawable.ic_plugin_disc
            "bus" -> R.drawable.ic_plugin_bus
            "lens" -> R.drawable.ic_plugin_lens
            "mic" -> R.drawable.ic_plugin_mic
            "send" -> R.drawable.ic_plugin_send
            "feed" -> R.drawable.ic_plugin_feed
            "weather" -> R.drawable.ic_plugin_weather
            "chat" -> R.drawable.ic_plugin_chat
            "calendar" -> R.drawable.ic_plugin_calendar
            "clock" -> R.drawable.ic_plugin_clock
            "star" -> R.drawable.ic_plugin_star
            "heart" -> R.drawable.ic_plugin_heart
            "game" -> R.drawable.ic_plugin_game
            "globe" -> R.drawable.ic_plugin_globe
            "bell" -> R.drawable.ic_plugin_bell
            "terminal" -> R.drawable.ic_plugin_terminal
            "grid" -> R.drawable.ic_plugin_grid
            "map" -> R.drawable.ic_plugin_map
            "bolt" -> R.drawable.ic_plugin_bolt
            "bookmark" -> R.drawable.ic_plugin_bookmark
            else -> null
        }
        if (explicitIcon != null) return explicitIcon

        return when (pluginId) {
            "lyrics" -> R.drawable.ic_plugin_music
            "media" -> R.drawable.ic_plugin_disc
            "transit" -> R.drawable.ic_plugin_bus
            "lens" -> R.drawable.ic_plugin_lens
            else -> R.drawable.ic_plugin_grid
        }
    }
}
