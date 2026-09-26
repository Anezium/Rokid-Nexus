package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/**
 * Opening Navigation from the glasses (or tapping its activity) shows the
 * whole current instruction, which the activity only has room to abbreviate,
 * or says how to start a route.
 */
class NavPluginService : NexusPluginService() {
    private var surface: NexusSurfaceSession? = null

    override fun onNexusOpen() {
        surface = nexusSurfaceSession(SURFACE_ID)
        surface?.showCard(card())
    }

    override fun onNexusClose() {
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) = Unit

    private fun card(): NexusCard {
        val guidance = NavState.guidance
        return when {
            guidance != null -> NexusCard(
                title = guidance.instruction ?: guidance.primary,
                subtitle = guidance.source.label,
                lines = listOfNotNull(
                    listOfNotNull(guidance.primary, guidance.secondary).joinToString(" · "),
                    guidance.eta?.let { getString(R.string.nav_card_eta, it) },
                ) + guidance.detail,
                contentKey = "nav-route",
            )
            !NavState.listenerConnected -> NexusCard(
                title = getString(R.string.app_name),
                lines = listOf(getString(R.string.nav_card_no_access)),
                contentKey = "nav-no-access",
            )
            else -> NexusCard(
                title = getString(R.string.app_name),
                lines = listOf(getString(R.string.nav_card_idle)),
                contentKey = "nav-idle",
            )
        }
    }

    private companion object {
        const val SURFACE_ID = "nav"
    }
}
