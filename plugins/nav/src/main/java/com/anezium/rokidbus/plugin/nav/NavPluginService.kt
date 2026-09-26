package com.anezium.rokidbus.plugin.nav

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * Navigation's one registration with the hub.
 *
 * The hub delivers a plugin's opening, input and closing only while it has
 * exactly one registration, so the route does not register a client of its
 * own: while a route is live the listener keeps this service bound and the
 * route sends through this service's client. Opening Navigation, with or
 * without a route, therefore always reaches this service, which shows the
 * whole current instruction or how to start one.
 */
class NavPluginService : NexusPluginService() {
    override fun onCreate() {
        super.onCreate()
        nexusClient?.let(NavControl::serviceCreated)
    }

    override fun onNexusOpen() {
        NavControl.cardOpen = true
        nexusSurfaceSession(NavCard.SURFACE_ID)?.showCard(NavCard.build(this))
    }

    override fun onNexusClose() {
        NavControl.cardOpen = false
    }

    // Back from the card backgrounds Navigation rather than closing it. An
    // update to a backgrounded card brings it back up, so the route stops
    // refreshing it here too.
    override fun onNexusBackground() {
        NavControl.cardOpen = false
    }

    // The hub hands Back to an external plugin rather than closing it; the
    // plugin closes its own card, which releases the glasses for other plugins.
    override fun onNexusInput(event: NexusInputEvent) {
        if (event.keyCode != KeyEvent.KEYCODE_BACK || event.action != KeyEvent.ACTION_DOWN) return
        NavControl.cardOpen = false
        nexusSurfaceSession(NavCard.SURFACE_ID)?.hide()
    }

    override fun onNexusRegistrationState(result: Int) = NavControl.registrationState(result)

    override fun onNexusLinkState(state: Int) = NavControl.linkState(state)

    override fun onNexusActivityClosed(reason: String) = NavControl.activityClosed(reason)

    override fun onDestroy() {
        nexusClient?.let(NavControl::serviceDestroyed)
        super.onDestroy()
    }
}
