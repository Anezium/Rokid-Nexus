package com.anezium.rokidbus.plugin.nav

import android.content.Context

/** Which guidance the wearer lets through: everything, per app, or nothing. */
internal data class NavSwitches(
    val enabled: Boolean = true,
    val googleMaps: Boolean = true,
    val citymapper: Boolean = true,
) {
    fun allows(source: NavSource): Boolean = enabled && when (source) {
        NavSource.GOOGLE_MAPS -> googleMaps
        NavSource.CITYMAPPER -> citymapper
    }
}

/** The switches, stored on the phone. Nothing about routes is ever stored. */
internal class NavSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun switches(): NavSwitches = NavSwitches(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        googleMaps = prefs.getBoolean(KEY_GOOGLE_MAPS, true),
        citymapper = prefs.getBoolean(KEY_CITYMAPPER, true),
    )

    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun setSource(source: NavSource, value: Boolean) = prefs.edit().putBoolean(
        when (source) {
            NavSource.GOOGLE_MAPS -> KEY_GOOGLE_MAPS
            NavSource.CITYMAPPER -> KEY_CITYMAPPER
        },
        value,
    ).apply()

    private companion object {
        const val PREFS = "nav_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_GOOGLE_MAPS = "source_google_maps"
        const val KEY_CITYMAPPER = "source_citymapper"
    }
}

/**
 * The settings screen and the listener share a process. A switch turned off
 * ends that app's live route at once; turned back on, a route already running
 * is picked up again without waiting for its next update.
 */
internal object NavControl {
    @Volatile
    private var listener: NavNotificationListener? = null

    fun attach(service: NavNotificationListener) {
        listener = service
    }

    fun detach(service: NavNotificationListener) {
        if (listener === service) listener = null
    }

    fun settingsChanged() {
        listener?.applySettings()
    }
}
