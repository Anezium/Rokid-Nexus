package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A card whose destination the bundle validated before the link went keeps navigating there,
 * read-only, until the phone announces again (plan 028a §1.4, §5.1): the open asks nothing.
 */
class HeldDestinationTest {
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { false },
    )
    private val reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId, launcherActive = { false })
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    @Test
    fun `a held destination opens exactly and inert, saying the phone is reconnecting, with the link back but unannounced`() {
        open(card(held = true))
        assertEquals("maps:route", top().pageId)
        assertEquals(PARAMS, top().paramsJson)
        val status = top().status as FrameStatus.Unavailable
        assertNull(status.lastSnapshot)
        assertEquals(SessionReducer.UNAVAILABLE_RECONNECTING, status.reason)
        assertTrue(effects.none { it is RequestPage })
    }

    @Test
    fun `a held destination opened with the link down says so`() {
        open(card(held = true), linkLost = true)
        assertEquals(PARAMS, top().paramsJson)
        assertEquals(SessionReducer.UNAVAILABLE_LINK_LOST, (top().status as FrameStatus.Unavailable).reason)
        assertTrue(effects.none { it is RequestPage })
    }

    @Test
    fun `a card that is not held keeps the v1 open without the bundle, even with a destination`() {
        open(card(held = false))
        assertEquals(LocalPages.activityPageId("maps"), top().pageId)
        assertNull(top().paramsJson)
    }

    private fun card(held: Boolean) = ActivityStop(
        id = "maps:activity",
        pageId = LocalPages.activityPageId("maps"),
        text = "120 m",
        ownerPluginId = "maps",
        ownerLabel = "Maps",
        destination = PageDestination("maps:route", PARAMS),
        activation = ActivityActivation.DESTINATION,
        stale = true,
        held = held,
    )

    private fun open(stop: ActivityStop, linkLost: Boolean = false) {
        send(SessionEvent.ActivityStarted(stop))
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        // As the host does: a session that opens is told the phone is already gone.
        if (linkLost) send(SessionEvent.LinkLost)
        SessionFocus.tokenOf(model)?.let { send(SessionEvent.FocusShown(it, 1_900)) }
        send(SessionEvent.Enter(2_040, pressStartMs = 2_000))
    }

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun top() = (model.state as SessionState.InPage).frames.last()

    private companion object {
        const val PARAMS = """{"trip":"home","mode":"walk"}"""
    }
}
