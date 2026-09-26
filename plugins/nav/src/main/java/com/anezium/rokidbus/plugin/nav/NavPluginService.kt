package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * The launcher entry when no route holds the bus.
 *
 * The hub delivers a plugin's lifecycle only while it has exactly one
 * registration. During a route the listener's runtime is that registration and
 * shows the card itself, so this service is bound only when no route is live.
 * If a route starts while it is open, the runtime borrows this service's
 * client rather than registering a second one, and takes the route back onto
 * its own client when the service closes.
 */
class NavPluginService : NexusPluginService() {
    override fun onNexusOpen() {
        val client = nexusClient ?: return
        NavControl.serviceOpened(client)
        nexusSurfaceSession(NavCard.SURFACE_ID)?.showCard(NavCard.build(this))
    }

    override fun onNexusClose() {
        nexusClient?.let(NavControl::serviceClosed)
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    override fun onNexusActivityClosed(reason: String) {
        NavControl.activityClosed(reason)
    }

    override fun onDestroy() {
        nexusClient?.let(NavControl::serviceClosed)
        super.onDestroy()
    }
}
