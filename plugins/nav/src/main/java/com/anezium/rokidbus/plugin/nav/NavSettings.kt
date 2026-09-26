package com.anezium.rokidbus.plugin.nav

import android.content.Context
import com.anezium.rokidbus.client.plugin.NexusPluginClient

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
 * The process's one meeting point. The settings screen tells the listener
 * when a switch changes: off ends that app's live route at once, on picks up a
 * route already running without waiting for its next update. The plugin
 * service lends its hub client to the route while it is open, because the
 * hub serves one registration per plugin.
 */
internal object NavControl {
    @Volatile
    private var listener: NavNotificationListener? = null

    @Volatile
    private var runtime: NavRuntime? = null

    /** The open plugin service's client, which the route borrows. */
    @Volatile
    var serviceClient: NexusPluginClient? = null
        private set

    /** A Navigation card is on the glasses, shown by the service or the route. */
    @Volatile
    var cardOpen: Boolean = false

    fun attach(service: NavNotificationListener, routeRuntime: NavRuntime) {
        listener = service
        runtime = routeRuntime
    }

    fun detach(service: NavNotificationListener) {
        if (listener === service) {
            listener = null
            runtime = null
        }
    }

    fun settingsChanged() {
        listener?.applySettings()
    }

    fun serviceOpened(client: NexusPluginClient) {
        serviceClient = client
        cardOpen = true
    }

    fun serviceClosed(client: NexusPluginClient) {
        if (serviceClient !== client) return
        serviceClient = null
        cardOpen = false
        runtime?.onServiceClientGone(client)
    }

    fun activityClosed(reason: String) {
        runtime?.onActivityClosed(reason)
    }
}
