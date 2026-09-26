package com.anezium.rokidbus.plugin.nav

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Reads guidance from the navigation apps Navigation follows and nothing
 * else: every other package's notification, and any app the wearer switched
 * off, is dropped before it is read.
 */
class NavNotificationListener : NotificationListenerService() {
    private val runtime by lazy { NavRuntime(applicationContext) }
    private val citymapper = CitymapperParser()
    private val guidanceKeys = mutableMapOf<NavSource, String>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        NavState.listenerConnected = true
        NavControl.attach(this)
        // A route already running when access was granted or the process restarted.
        scanActive()
    }

    override fun onListenerDisconnected() {
        NavControl.detach(this)
        NavState.listenerConnected = false
        guidanceKeys.clear()
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
        guidanceKeys.remove(source)
        if (source == NavSource.CITYMAPPER) citymapper.reset()
        Log.i(TAG, "guidance removed source=$source")
        runtime.onRouteEnded(source)
    }

    /** Ends the route of an app just switched off, and picks up one just switched on. */
    internal fun applySettings() {
        val switches = NavSettings(this).switches()
        guidanceKeys.keys.filterNot(switches::allows).forEach { source ->
            guidanceKeys.remove(source)
            if (source == NavSource.CITYMAPPER) citymapper.reset()
            Log.i(TAG, "guidance switched off source=$source")
            runtime.onRouteEnded(source)
        }
        scanActive()
    }

    private fun scanActive() {
        runCatching { activeNotifications.orEmpty().forEach(::ingest) }
            .onFailure { Log.w(TAG, "active notification scan failed cause=${it.javaClass.simpleName}") }
    }

    private fun ingest(sbn: StatusBarNotification) {
        val source = NavSource.of(sbn.packageName) ?: return
        if (!NavSettings(this).switches().allows(source)) return
        val notification = NavNotificationReader.read(this, sbn) ?: return
        val labels = NavLabels(
            arrived = getString(R.string.nav_arrived),
            now = getString(R.string.nav_now),
        )
        val guidance = when (source) {
            NavSource.GOOGLE_MAPS -> GoogleMapsParser.parse(notification, labels)
            NavSource.CITYMAPPER -> citymapper.parse(notification, labels)
        }
        if (guidance == null) {
            // Not guidance, or guidance this version cannot read: show nothing
            // rather than something wrong. Only the key's owner can end a route.
            Log.i(TAG, "ignored source=$source category=${notification.category} channel=${notification.channelId}")
            return
        }
        guidanceKeys[source] = sbn.key
        runtime.onGuidance(guidance)
    }

    private companion object {
        const val TAG = "NexusNav"
    }
}
