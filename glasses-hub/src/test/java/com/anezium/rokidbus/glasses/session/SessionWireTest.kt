package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionWireTest {
    private fun request(pageId: String = "nav:main", reason: String = "open") =
        PageSurfaceRequest(PageCorrelation("r1", 3, 1, pageId), reason)

    @Test
    fun `a request carries what is left of its absolute budget and its revision floor`() {
        val payload = SessionWire.requestPayload(SessionEffect.RequestPage(request(), deadlineMs = 9_000, minimumRevision = 4), nowMs = 2_000)
        assertEquals(7_000L, payload.getLong(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS))
        assertEquals(4L, payload.getLong(PageSurfaceContract.FIELD_MINIMUM_REVISION))
        assertTrue(PageSurfaceContract.validateRequest(payload) is com.anezium.rokidbus.shared.PageSurfaceValidationResult.Valid)
        // Never more than eight seconds, never negative, and no floor without one.
        val late = SessionWire.requestPayload(SessionEffect.RequestPage(request(), deadlineMs = 1_000, minimumRevision = null), nowMs = 2_000)
        assertEquals(0L, late.getLong(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS))
        assertFalse(late.has(PageSurfaceContract.FIELD_MINIMUM_REVISION))
        val plain = SessionWire.requestPayload(SessionEffect.RequestPage(request()), nowMs = 2_000)
        assertEquals(PageSurfaceContract.PAGE_TIMEOUT_MS, plain.getLong(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS))
    }

    @Test
    fun `visibility and close name their frame and are never sent without one or for a local page`() {
        val frame = FrameIdentity(3, 2)
        val visible = SessionWire.visibilityPayload(SessionEffect.SendVisibility("nav:main", true, 130_000, frame), nowMs = 10_000)!!
        assertEquals(3L, visible.getLong("sessionGeneration"))
        assertEquals(2, visible.getInt("frameIndex"))
        assertEquals(120_000L, visible.getLong(PageSurfaceContract.FIELD_LEASE_DURATION_MS))
        assertEquals(130_000L, visible.getLong("leaseUntilMs"))
        val covered = SessionWire.visibilityPayload(SessionEffect.SendVisibility("nav:main", false, null, frame), nowMs = 10_000)!!
        assertFalse(covered.has("leaseUntilMs") || covered.has(PageSurfaceContract.FIELD_LEASE_DURATION_MS))
        assertNull(SessionWire.visibilityPayload(SessionEffect.SendVisibility("nav:main", true, 130_000), nowMs = 10_000))
        assertNull(SessionWire.visibilityPayload(SessionEffect.SendVisibility(RootStops.APPLICATIONS_PAGE_ID, true, 130_000, frame), nowMs = 10_000))

        val closed = SessionWire.closedPayload(SessionEffect.SendClosed("nav:main", "back", frame))!!
        assertEquals(2, closed.getInt("frameIndex"))
        assertTrue(PageSurfaceContract.validateClosed(closed) is com.anezium.rokidbus.shared.PageSurfaceValidationResult.Valid)
        assertNull(SessionWire.closedPayload(SessionEffect.SendClosed("nav:p6", SessionReducer.CLOSE_FRAME_LIMIT)))
        assertNull(SessionWire.closedPayload(SessionEffect.SendClosed(RootStops.NOTICE_PAGE_ID, "back", frame)))
    }

    @Test
    fun `only answers stamped for their own page owner enter, never for a hub page`() {
        fun answer(pageId: String, owner: String?) = PageSurfaceContract.correlationPayload(PageCorrelation("r", 1, 1, pageId))
            .apply { owner?.let { put(PageSurfaceContract.FIELD_PLUGIN_ID, it) } }
        assertTrue(SessionWire.acceptsResponse(answer("nav:main", "nav")))
        assertFalse(SessionWire.acceptsResponse(answer("nav:main", null)))
        assertFalse(SessionWire.acceptsResponse(answer("nav:main", "relay")))
        assertFalse(SessionWire.acceptsResponse(answer("nexus:applications", "nexus")))
        assertFalse(SessionWire.acceptsResponse(answer("nexus:notifications", "relay")))
        val result = JSONObject().put("invocationId", "i").put("status", "done").put(PageSurfaceContract.FIELD_PLUGIN_ID, "relay")
        assertTrue(SessionWire.acceptsResult(result, "relay:thread"))
        assertFalse(SessionWire.acceptsResult(result, "nav:main"))
        assertFalse(SessionWire.acceptsResult(result, null))
        assertFalse(SessionWire.acceptsResult(result, RootStops.APPLICATIONS_PAGE_ID))
    }

    @Test
    fun `the activity feed starts, updates in place and ends real activities`() {
        var clock = 1_000L
        val ready = mutableSetOf("nav")
        val feed = ActivityFeed(
            ownerLabel = { if (it == "nav") "Navigation" else null },
            pageIdFor = { if (it in ready) "$it:main" else LocalPages.activityPageId(it) },
            wallClock = { clock },
        )
        val nav = ActivitySource("nav:local", "nav", true, "300 m", "Rue A", "12:41")
        val transit = ActivitySource("transit:local", "transit", false, "3 stops", null, null)
        val started = feed.update(listOf(nav, transit))
        assertEquals(listOf("nav:local", "transit:local"), started.map { (it as SessionEvent.ActivityStarted).stop.id })
        val first = (started.first() as SessionEvent.ActivityStarted).stop
        assertEquals("nav:main", first.pageId)
        assertEquals("Navigation", first.ownerLabel)
        assertTrue(first.pinned)
        assertEquals(1_000L, first.updatedAtWallMs)
        assertEquals("nexus:activity:transit", (started[1] as SessionEvent.ActivityStarted).stop.pageId)
        assertEquals("transit", (started[1] as SessionEvent.ActivityStarted).stop.ownerLabel)

        // A redraw with the same values is no event; a new value is an update with a new time.
        clock = 2_000
        assertTrue(feed.update(listOf(nav, transit)).isEmpty())
        val moved = feed.update(listOf(nav.copy(text = "120 m"), transit))
        val updated = (moved.single() as SessionEvent.ActivityUpdated).stop
        assertEquals("120 m", updated.text)
        assertEquals(2_000L, updated.updatedAtWallMs)
        // A change of primary alone keeps the content time.
        clock = 3_000
        val swapped = feed.update(listOf(nav.copy(text = "120 m", primary = false), transit.copy(primary = true)))
        assertEquals(2, swapped.size)
        assertEquals(2_000L, (swapped.first() as SessionEvent.ActivityUpdated).stop.updatedAtWallMs)
        assertEquals(listOf(SessionEvent.ActivityEnded("transit:local")), feed.update(listOf(nav.copy(text = "120 m", primary = false))))
        assertEquals(listOf("nav:local"), feed.current().map(ActivityStop::id))
    }
}
