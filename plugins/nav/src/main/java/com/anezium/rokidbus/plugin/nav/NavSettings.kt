package com.anezium.rokidbus.plugin.nav

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import com.anezium.rokidbus.client.plugin.NexusPluginClient

/** Which guidance the wearer lets through: everything, per app, or nothing. */
internal data class NavSwitches(
    val enabled: Boolean = true,
    val googleMaps: Boolean = true,
    val citymapper: Boolean = true,
    val organicMaps: Boolean = true,
    val osmand: Boolean = true,
    val yandexMaps: Boolean = true,
    val mapsMe: Boolean = true,
) {
    fun allows(source: NavSource): Boolean = enabled && when (source) {
        NavSource.GOOGLE_MAPS -> googleMaps
        NavSource.CITYMAPPER -> citymapper
        NavSource.ORGANIC_MAPS -> organicMaps
        NavSource.OSMAND -> osmand
        NavSource.YANDEX_MAPS -> yandexMaps
        NavSource.MAPS_ME -> mapsMe
        // A journey is started by the wearer, never read from a notification.
        NavSource.NEXUS -> false
    }
}

/** The switches, stored on the phone. Nothing about routes is ever stored. */
internal class NavSettings(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun switches(): NavSwitches = NavSwitches(
        enabled = prefs.getBoolean(KEY_ENABLED, true),
        googleMaps = prefs.getBoolean(KEY_GOOGLE_MAPS, true),
        citymapper = prefs.getBoolean(KEY_CITYMAPPER, true),
        organicMaps = prefs.getBoolean(KEY_ORGANIC_MAPS, true),
        osmand = prefs.getBoolean(KEY_OSMAND, true),
        yandexMaps = prefs.getBoolean(KEY_YANDEX_MAPS, true),
        mapsMe = prefs.getBoolean(KEY_MAPS_ME, true),
    )

    fun setEnabled(value: Boolean) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun setSource(source: NavSource, value: Boolean) = prefs.edit().putBoolean(
        when (source) {
            NavSource.GOOGLE_MAPS -> KEY_GOOGLE_MAPS
            NavSource.CITYMAPPER -> KEY_CITYMAPPER
            NavSource.ORGANIC_MAPS -> KEY_ORGANIC_MAPS
            NavSource.OSMAND -> KEY_OSMAND
            NavSource.YANDEX_MAPS -> KEY_YANDEX_MAPS
            NavSource.MAPS_ME -> KEY_MAPS_ME
            NavSource.NEXUS -> error("Walking journeys are started explicitly, not switched as a notification source")
        },
        value,
    ).apply()

    private companion object {
        const val PREFS = "nav_settings"
        const val KEY_ENABLED = "enabled"
        const val KEY_GOOGLE_MAPS = "source_google_maps"
        const val KEY_CITYMAPPER = "source_citymapper"
        const val KEY_ORGANIC_MAPS = "source_organic_maps"
        const val KEY_OSMAND = "source_osmand"
        const val KEY_YANDEX_MAPS = "source_yandex_maps"
        const val KEY_MAPS_ME = "source_maps_me"
    }
}

/**
 * The process's one meeting point.
 *
 * The settings screen tells the listener when a switch changes: off ends that
 * app's live route at once, on picks up a route already running without
 * waiting for its next update.
 *
 * The route sends through [NavPluginService]'s client, Navigation's only
 * registration: while guidance is live the runtime keeps that service bound
 * ([holdBus]), and the service hands its client and its hub callbacks over
 * here.
 *
 * A walking journey the wearer started owns the route while it runs: its runtime replaces the
 * notification runtime as the one the hub callbacks reach and the only one allowed to publish,
 * and notification guidance resumes from what is posted at that moment once the journey ends.
 */
internal object NavControl {
    @Volatile
    private var listener: NavNotificationListener? = null

    @Volatile
    private var runtime: NavRuntime? = null

    @Volatile
    private var journeyRuntime: NavRuntime? = null

    private val activeRuntime: NavRuntime? get() = journeyRuntime ?: runtime

    /** A walking journey the wearer started is running and owns the route. */
    val journeyActive: Boolean get() = journeyRuntime != null

    private var journeyCount = 0L

    /** Names the running journey, so an action confirmed on a page of an earlier one goes stale. */
    @Volatile
    var journeyEpoch: Long? = null
        private set

    /** Whether [candidate] is the runtime allowed to publish and end the route right now. */
    fun owns(candidate: NavRuntime): Boolean = activeRuntime === candidate

    /** Ends the running journey from another surface (the glasses' route page); set by the journey. */
    @Volatile
    private var journeyStop: ((String) -> Unit)? = null

    /** The journey takes the route over: the notification route ends before the journey's starts. */
    fun attachJourney(candidate: NavRuntime, stop: (String) -> Unit) {
        // The service stays bound: the journey's activity goes through the same registration.
        activeRuntime?.takeIf { it !== candidate }?.shutdown(releaseBus = false)
        journeyRuntime = candidate
        journeyStop = stop
        journeyEpoch = ++journeyCount
        guidanceChanged()
    }

    fun detachJourney(candidate: NavRuntime) {
        if (journeyRuntime !== candidate) return
        journeyRuntime = null
        journeyStop = null
        journeyEpoch = null
        listener?.resumeNotifications()
        guidanceChanged()
    }

    /** Stops the running journey with [message]; false when none runs. */
    fun stopJourney(message: String): Boolean {
        val stop = journeyStop ?: return false
        stop(message)
        return true
    }

    /** The plugin service's client while the service exists. */
    @Volatile
    var serviceClient: NexusPluginClient? = null
        private set

    /** A Navigation card is on the glasses. */
    @Volatile
    var cardOpen: Boolean = false

    /** Told when the guidance changes, so a visible page can follow it; set by the plugin service. */
    @Volatile
    var pageListener: (() -> Unit)? = null

    fun guidanceChanged() {
        pageListener?.invoke()
    }

    private var busBinding: ServiceConnection? = null

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
        // Switching Navigation off or on changes what a visible page says, route or not.
        guidanceChanged()
    }

    fun serviceCreated(client: NexusPluginClient) {
        serviceClient = client
        activeRuntime?.onBusReady()
    }

    fun serviceDestroyed(client: NexusPluginClient) {
        if (serviceClient !== client) return
        serviceClient = null
        cardOpen = false
        activeRuntime?.onBusLost()
    }

    fun registrationState(result: Int) {
        NavState.registration = result
        activeRuntime?.onRegistrationState(result)
    }

    fun linkState(state: Int) {
        activeRuntime?.onLinkState(state)
    }

    fun activityClosed(reason: String) {
        activeRuntime?.onActivityClosed(reason)
    }

    /** Keeps the plugin service, and so its registration, alive for the route. */
    fun holdBus(context: Context) {
        if (busBinding != null) return
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) = Unit
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        val bound = runCatching {
            context.bindService(Intent(context, NavPluginService::class.java), connection, Context.BIND_AUTO_CREATE)
        }.getOrDefault(false)
        if (bound) busBinding = connection
    }

    fun releaseBus(context: Context) {
        val connection = busBinding ?: return
        busBinding = null
        runCatching { context.unbindService(connection) }
    }
}
