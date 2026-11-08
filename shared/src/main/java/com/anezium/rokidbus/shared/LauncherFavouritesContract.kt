package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject

/**
 * One application the owner pinned to the launcher root: a Nexus plugin by id, or a native
 * glasses app by package. [key] is the Applications catalog's stable key for the same entry.
 */
sealed interface LauncherFavourite {
    val key: String

    data class Plugin(val pluginId: String) : LauncherFavourite {
        override val key: String get() = LauncherFavouritesContract.PLUGIN_PREFIX + pluginId
    }

    data class NativeApp(val packageName: String) : LauncherFavourite {
        override val key: String get() = LauncherFavouritesContract.APP_PREFIX + packageName
    }
}

/** The owner's favourites in their chosen order; [revision] only grows. */
data class LauncherFavourites(val revision: Long, val entries: List<LauncherFavourite>)

/**
 * The phone's favourites list for the launcher root (`/core/launcher/favourites`, launcher
 * bundle [LauncherInteractiveContract.FAVOURITES_VERSION]). A hub-to-hub control: no plugin can
 * send or read it. The phone owns the list and sends it whole on every change and announce; the
 * glasses keep the latest and resolve each entry against their catalog when a session opens.
 */
object LauncherFavouritesContract {
    const val VERSION = 1

    /** As many as the root shows under the activities without pushing Applications off screen. */
    const val MAX_ENTRIES = 4

    const val PLUGIN_PREFIX = "plugin:"
    const val APP_PREFIX = "app:"

    private const val KIND_PLUGIN = "plugin"
    private const val KIND_APP = "app"

    fun toPayload(favourites: LauncherFavourites): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("revision", favourites.revision)
        .put(
            "entries",
            JSONArray().apply {
                favourites.entries.forEach { entry ->
                    put(
                        when (entry) {
                            is LauncherFavourite.Plugin -> JSONObject().put("kind", KIND_PLUGIN).put("id", entry.pluginId)
                            is LauncherFavourite.NativeApp -> JSONObject().put("kind", KIND_APP).put("package", entry.packageName)
                        },
                    )
                }
            },
        )

    /**
     * The list, or null when it is malformed: a wrong version, too many entries, an unknown kind,
     * an invalid id or package, or the same application twice. Never truncated.
     */
    fun parse(payload: JSONObject): LauncherFavourites? {
        if (payload.opt("version") != VERSION) return null
        val revision = when (val value = payload.opt("revision")) {
            is Int -> value.toLong()
            is Long -> value
            else -> null
        }?.takeIf { it >= 0 } ?: return null
        val array = payload.opt("entries") as? JSONArray ?: return null
        if (array.length() > MAX_ENTRIES) return null
        val entries = mutableListOf<LauncherFavourite>()
        for (index in 0 until array.length()) {
            val item = array.opt(index) as? JSONObject ?: return null
            val entry = when (item.opt("kind")) {
                KIND_PLUGIN -> (item.opt("id") as? String)?.takeIf(PluginDescriptor::isValidId)?.let(LauncherFavourite::Plugin)
                KIND_APP -> (item.opt("package") as? String)?.takeIf(NativeAppContract::isValidPackageName)?.let(LauncherFavourite::NativeApp)
                else -> null
            } ?: return null
            if (entries.any { it.key == entry.key }) return null
            entries += entry
        }
        return LauncherFavourites(revision, entries)
    }

    /** The entry a catalog key names, or null for any other key. */
    fun fromKey(key: String): LauncherFavourite? = when {
        key.startsWith(PLUGIN_PREFIX) -> key.removePrefix(PLUGIN_PREFIX).takeIf(PluginDescriptor::isValidId)?.let(LauncherFavourite::Plugin)
        key.startsWith(APP_PREFIX) -> key.removePrefix(APP_PREFIX).takeIf(NativeAppContract::isValidPackageName)?.let(LauncherFavourite::NativeApp)
        else -> null
    }
}
