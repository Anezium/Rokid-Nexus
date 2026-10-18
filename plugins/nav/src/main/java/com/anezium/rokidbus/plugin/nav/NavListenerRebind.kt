package com.anezium.rokidbus.plugin.nav

import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.util.Log

/**
 * One bounded request to bind the notification listener again, for the one state Android can
 * leave the plugin in: access granted, other apps' guidance switched on, and the listener still
 * not connected, so nothing is read although nothing is wrong on the wearer's side. The request
 * is the platform's own [NotificationListenerService.requestRebind], which needs the grant that
 * already exists and changes no setting.
 *
 * It is asked once per visit to the settings screen, when the wearer opens or returns to it,
 * never from the background and never again on the screen's own refreshes. Whether it worked is
 * known only when the listener connects, which the screen already shows; nothing here says it did.
 */
internal class NavListenerRebind(
    private val request: (ComponentName) -> Unit = NotificationListenerService::requestRebind,
) {
    private var requestedThisVisit = false

    /** The wearer opened or came back to the screen: one request may be made again. */
    fun onEnter() {
        requestedThisVisit = false
    }

    /**
     * Requests the rebind when it is both allowed and needed, and not yet asked this visit.
     * True when a request was made and accepted by the platform call; a platform failure is
     * logged and counts as this visit's one attempt.
     */
    fun consider(component: ComponentName, accessGranted: Boolean, guidanceEnabled: Boolean, connected: Boolean): Boolean {
        if (!accessGranted || !guidanceEnabled || connected || requestedThisVisit) return false
        requestedThisVisit = true
        return runCatching { request(component) }
            .onFailure { Log.w(TAG, "listener rebind request failed", it) }
            .isSuccess
    }

    private companion object {
        const val TAG = "NavListenerRebind"
    }
}
