package com.anezium.rokidbus.plugin.nav

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads guidance from the navigation apps Navigation follows and nothing
 * else: every other package's notification, and any app the wearer switched
 * off, is dropped before it is read.
 *
 * One app drives the route at a time: the first to post guidance. Another
 * app's guidance waits until that route ends, then takes over if it is still
 * posted.
 */
class NavNotificationListener : NotificationListenerService() {
    private val runtime by lazy { NavRuntime(applicationContext) }
    private val citymapper = CitymapperParser()
    private val guidanceKeys = mutableMapOf<NavSource, String>()
    private var activeSource: NavSource? = null

    override fun onListenerConnected() {
        super.onListenerConnected()
        NavState.listenerConnected = true
        NavControl.attach(this, runtime)
        // A route already running when access was granted or the process restarted.
        scanActive()
    }

    override fun onListenerDisconnected() {
        NavControl.detach(this)
        NavState.listenerConnected = false
        guidanceKeys.clear()
        activeSource = null
        citymapper.reset()
        runtime.shutdown()
        super.onListenerDisconnected()
    }

    override fun onDestroy() {
        NavControl.detach(this)
        NavState.listenerConnected = false
        runtime.shutdown()
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let(::ingest)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn ?: return
        val source = NavSource.of(sbn.packageName) ?: return
        if (guidanceKeys[source] != sbn.key) return
        Log.i(TAG, "guidance removed source=$source")
        endRoute(source)
    }

    /** Ends the route of an app just switched off, and picks up one just switched on. */
    internal fun applySettings() {
        val switches = NavSettings(this).switches()
        guidanceKeys.keys.filterNot(switches::allows).forEach { source ->
            Log.i(TAG, "guidance switched off source=$source")
            endRoute(source, rescan = false)
        }
        scanActive()
    }

    private fun endRoute(source: NavSource, rescan: Boolean = true) {
        guidanceKeys.remove(source)
        if (source == NavSource.CITYMAPPER) citymapper.reset()
        runtime.onRouteEnded(source)
        if (activeSource == source) {
            activeSource = null
            // The other app may still be guiding; it takes the route over now
            // rather than whenever it next happens to post.
            if (rescan) scanActive()
        }
    }

    private fun scanActive() {
        runCatching { activeNotifications.orEmpty().forEach(::ingest) }
            .onFailure { Log.w(TAG, "active notification scan failed cause=${it.javaClass.simpleName}") }
    }

    private fun ingest(sbn: StatusBarNotification) {
        val source = NavSource.of(sbn.packageName) ?: return
        if (!NavSettings(this).switches().allows(source)) return
        val active = activeSource
        if (active != null && active != source && guidanceKeys.containsKey(active)) return
        val notification = NavNotificationReader.read(this, sbn) ?: return
        // A new Citymapper trip must not inherit the last trip's line.
        if (source == NavSource.CITYMAPPER && guidanceKeys[source] != null && guidanceKeys[source] != sbn.key) {
            citymapper.reset()
        }
        val labels = NavLabels(
            arrived = getString(R.string.nav_arrived),
            now = getString(R.string.nav_now),
        )
        val guidance = when (source) {
            NavSource.GOOGLE_MAPS -> GoogleMapsParser.parse(notification, labels)
            NavSource.CITYMAPPER -> citymapper.parse(notification, labels)
        }
        if (guidance == null) {
            Log.i(TAG, "unreadable source=$source category=${notification.category} channel=${notification.channelId}")
            // The route's own notification turned into something this version
            // cannot read: show nothing rather than keep a step that is gone.
            if (guidanceKeys[source] == sbn.key) {
                guidanceKeys.remove(source)
                runtime.onRouteEnded(source)
                if (activeSource == source) activeSource = null
            }
            return
        }
        guidanceKeys[source] = sbn.key
        activeSource = source
        runtime.onGuidance(guidance)
    }

    private companion object {
        const val TAG = "NexusNav"
    }
}
