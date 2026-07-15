package com.anezium.rokidbus.client.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.anezium.rokidbus.client.R

data class PluginCustomIcon(val packageName: String, val resId: Int)

object NexusPluginIcons {
    fun resolve(
        context: Context,
        iconKey: String?,
        customIcon: PluginCustomIcon?,
        pluginId: String? = null,
    ): Drawable = resolveWithLoaders(
        iconKey = iconKey,
        customIcon = customIcon,
        pluginId = pluginId,
        builtInLoader = { resId ->
            requireNotNull(ContextCompat.getDrawable(context, resId))
        },
        customLoader = { icon -> loadCustomDrawable(context, icon) },
    )

    fun drawableFor(iconKey: String?, pluginId: String? = null): Int {
        val explicitIcon = drawableForBuiltInKey(iconKey)
        if (explicitIcon != null) return explicitIcon

        return drawableForLegacyPlugin(pluginId)
    }

    internal fun <T> resolveWithLoaders(
        iconKey: String?,
        customIcon: PluginCustomIcon?,
        pluginId: String? = null,
        builtInLoader: (Int) -> T,
        customLoader: (PluginCustomIcon) -> T,
    ): T {
        drawableForBuiltInKey(iconKey)?.let { return builtInLoader(it) }
        if (customIcon != null) {
            try {
                return customLoader(customIcon)
            } catch (_: Throwable) {
                // A plugin resource can disappear or fail to inflate at any time.
            }
        }
        return builtInLoader(drawableForLegacyPlugin(pluginId))
    }

    private fun loadCustomDrawable(context: Context, customIcon: PluginCustomIcon): Drawable {
        val packageContext = context.createPackageContext(customIcon.packageName, 0)
        val drawable = requireNotNull(
            ResourcesCompat.getDrawable(
                packageContext.resources,
                customIcon.resId,
                packageContext.theme,
            ),
        )
        return DrawableCompat.wrap(drawable).mutate().also { wrapped ->
            DrawableCompat.setTint(wrapped, NexusUi.GREEN)
        }
    }

    private fun drawableForBuiltInKey(iconKey: String?): Int? =
        when (iconKey) {
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

    private fun drawableForLegacyPlugin(pluginId: String?): Int =
        when (pluginId) {
            "lyrics" -> R.drawable.ic_plugin_music
            "media" -> R.drawable.ic_plugin_disc
            "transit" -> R.drawable.ic_plugin_bus
            "lens" -> R.drawable.ic_plugin_lens
            else -> R.drawable.ic_plugin_grid
        }
}
