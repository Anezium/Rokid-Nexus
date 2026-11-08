package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LauncherFavourite

/**
 * One Nexus plugin as the glasses' Applications page lists it: approved, and either launchable or
 * a provider of pages. The same rows the phone sends on `/launcher/list`.
 */
data class PhoneLauncherApplication(
    val id: String,
    val displayName: String,
    val launchable: Boolean,
    val pageVersion: Int,
    val iconKey: String? = null,
    val packageName: String? = null,
    val iconDrawableResId: Int? = null,
)

/** How the phone draws an application's mark in the favourites screen. */
sealed interface LauncherFavouriteIcon {
    data class Plugin(val pluginId: String, val iconKey: String?, val packageName: String?, val iconDrawableResId: Int?) :
        LauncherFavouriteIcon

    /** A glasses app: no artwork crosses the link, so its initial stands in. */
    data class Letter(val glyph: String) : LauncherFavouriteIcon
}

/** One application in the favourites screen, pinned or not. */
data class LauncherFavouriteItem(
    val favourite: LauncherFavourite,
    val label: String,
    val detail: String,
    val icon: LauncherFavouriteIcon,
    /** False for a pinned application the glasses no longer list: shown, never dropped. */
    val available: Boolean = true,
) {
    val key: String get() = favourite.key
}

/** What the favourites screen shows: the pinned rows in the owner's order, then everything else. */
data class LauncherFavouritesCatalog(
    val pinned: List<LauncherFavouriteItem>,
    val available: List<LauncherFavouriteItem>,
) {
    companion object {
        /** The glasses' own camera entry, drawn under the approved camera plugin's name. */
        const val CAMERA_PLUGIN_ID = "camera"
        private const val CAMERA_ICON_KEY = "lens"

        private const val DETAIL_PLUGIN = "Nexus plugin"
        private const val DETAIL_GLASSES_APP = "Glasses app"
        private const val DETAIL_CAMERA = "Camera"
        private const val DETAIL_UNAVAILABLE = "Not on the glasses right now"

        /**
         * Merges what the phone knows the glasses list ([plugins], the camera when [cameraName]
         * is set, and the glasses' own [nativeApps] when they answered) with the owner's
         * [favourites]. A favourite that matches nothing stays in `pinned` as unavailable.
         */
        fun build(
            favourites: List<LauncherFavourite>,
            plugins: List<PhoneLauncherApplication>,
            cameraName: String?,
            nativeApps: List<NativeGlassesApp>,
        ): LauncherFavouritesCatalog {
            val known = linkedMapOf<String, LauncherFavouriteItem>()
            cameraName?.let { name ->
                val favourite = LauncherFavourite.Plugin(CAMERA_PLUGIN_ID)
                known[favourite.key] = LauncherFavouriteItem(
                    favourite = favourite,
                    label = name,
                    detail = DETAIL_CAMERA,
                    icon = LauncherFavouriteIcon.Plugin(CAMERA_PLUGIN_ID, CAMERA_ICON_KEY, null, null),
                )
            }
            plugins.forEach { plugin ->
                if (plugin.id == CAMERA_PLUGIN_ID) return@forEach
                val favourite = LauncherFavourite.Plugin(plugin.id)
                known.putIfAbsent(
                    favourite.key,
                    LauncherFavouriteItem(
                        favourite = favourite,
                        label = plugin.displayName,
                        detail = DETAIL_PLUGIN,
                        icon = LauncherFavouriteIcon.Plugin(
                            plugin.id,
                            plugin.iconKey,
                            plugin.packageName,
                            plugin.iconDrawableResId,
                        ),
                    ),
                )
            }
            nativeApps.forEach { app ->
                val favourite = LauncherFavourite.NativeApp(app.id)
                known.putIfAbsent(
                    favourite.key,
                    LauncherFavouriteItem(
                        favourite = favourite,
                        label = app.name,
                        detail = DETAIL_GLASSES_APP,
                        icon = LauncherFavouriteIcon.Letter(app.name.take(1).uppercase()),
                    ),
                )
            }
            val pinned = favourites.map { favourite ->
                known[favourite.key] ?: LauncherFavouriteItem(
                    favourite = favourite,
                    label = fallbackLabel(favourite),
                    detail = DETAIL_UNAVAILABLE,
                    icon = LauncherFavouriteIcon.Letter("?"),
                    available = false,
                )
            }
            val pinnedKeys = pinned.map { it.key }.toSet()
            return LauncherFavouritesCatalog(
                pinned = pinned,
                available = known.values.filterNot { it.key in pinnedKeys },
            )
        }

        /** The best name the phone has for an application it cannot see any more. */
        private fun fallbackLabel(favourite: LauncherFavourite): String = when (favourite) {
            is LauncherFavourite.Plugin -> favourite.pluginId
                .split('.', '-', '_')
                .last { it.isNotBlank() }
                .replaceFirstChar(Char::uppercaseChar)
            is LauncherFavourite.NativeApp -> favourite.packageName
                .substringAfterLast('.')
                .replaceFirstChar(Char::uppercaseChar)
        }
    }
}
