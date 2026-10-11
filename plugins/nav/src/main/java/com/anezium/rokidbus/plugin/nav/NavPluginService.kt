package com.anezium.rokidbus.plugin.nav

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageAction
import com.anezium.rokidbus.client.plugin.NexusPageActionMode
import com.anezium.rokidbus.client.plugin.NexusPageClosed
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPageResult
import com.anezium.rokidbus.client.plugin.NexusPageVisibility
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    private val main = Handler(Looper.getMainLooper())
    private var scheduled: Runnable? = null
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    // Pages are read here, in the one client, and never touch the card or its open state.
    private val pages = NavPageProvider<NexusPageRequest>(
        pageIdOf = NexusPageRequest::pageId,
        floorOf = NexusPageRequest::minimumRevision,
        publish = { request, page -> nexusClient?.publishPage(request, page) ?: NexusSdkResult.NOT_REGISTERED },
        state = {
            NavPageState(
                guidance = NavState.guidance,
                listenerConnected = NavState.listenerConnected,
                enabled = NavSettings(this).switches().enabled,
                updatedAt = NavState.updatedAtMs?.let { timeFormat.format(Date(it)) },
                journeyActive = NavControl.journeyActive,
                journey = NavState.journey,
                journeyDestination = NavState.journeyDestination,
                journeyMessage = NavState.journeyMessage,
                extended = nexusClient?.supportsLauncherInteractive() == true,
            )
        },
        text = {
            NavPageText(
                noAccess = getString(R.string.nav_card_no_access),
                off = getString(R.string.nav_card_off),
                idle = getString(R.string.nav_card_idle),
            )
        },
        journey = { NavControl.journeyEpoch },
        now = SystemClock::elapsedRealtime,
        schedule = { delayMs, task ->
            val runnable = Runnable { scheduled = null; task() }
            scheduled?.let(main::removeCallbacks)
            scheduled = runnable
            main.postDelayed(runnable, delayMs)
        },
        cancelScheduled = {
            scheduled?.let(main::removeCallbacks)
            scheduled = null
        },
    )

    override fun onCreate() {
        super.onCreate()
        nexusClient?.let(NavControl::serviceCreated)
        NavControl.pageListener = { main.post { pages.guidanceChanged() } }
    }

    override fun onNexusPageRequest(request: NexusPageRequest): NexusPage = pages.onRequest(request)

    override fun onNexusPageVisibility(visibility: NexusPageVisibility) =
        pages.onVisibility(visibility.pageId, visibility.visible)

    override fun onNexusPageClosed(closed: NexusPageClosed) = pages.onClosed(closed.pageId)

    // The route page's one plugin action; the hub confirmed it with the wearer on the revision shown.
    override fun onNexusPageAction(action: NexusPageAction): NexusPageResult {
        if (action.pageId != NavPages.MAIN || action.actionId != NavRoutePage.END_JOURNEY) return NexusPageResult.rejected()
        if (action.mode != NexusPageActionMode.CONFIRMED) return NexusPageResult.rejected()
        // The wearer confirmed on a page of one journey; a journey started since is not theirs to end.
        if (!pages.showedCurrentJourney(action.revision)) return NexusPageResult.stale("The journey changed")
        return if (NavControl.stopJourney(JOURNEY_ENDED_FROM_GLASSES)) {
            NexusPageResult.done("Journey ended")
        } else {
            NexusPageResult.stale("No journey is running")
        }
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

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) pages.forget()
        NavControl.registrationState(result)
    }

    override fun onNexusLinkState(state: Int) {
        if (state and (LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP) == 0) pages.forget()
        NavControl.linkState(state)
    }

    override fun onNexusActivityClosed(reason: String) = NavControl.activityClosed(reason)

    override fun onDestroy() {
        NavControl.pageListener = null
        pages.forget()
        nexusClient?.let(NavControl::serviceDestroyed)
        super.onDestroy()
    }

    private companion object {
        const val JOURNEY_ENDED_FROM_GLASSES = "Journey ended from the glasses"
    }
}
