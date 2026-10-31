package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.*
import org.junit.Assert.*
import org.junit.Test

class ActivityDisplayPolicyTest {
    private val id = "media:activity"
    private val state = ActivityStateMachine().apply { setCapacity(4) }
    private val events = ActivityDisplayPreference(ActivityDisplayMode.EVENTS)
    private fun content(key: String? = "track-1") = ActivitySurfaceContent(
        "disc", "Track", "Artist", null, null, emptyList(), emptyList(), null, eventKey = key,
    )
    private fun snapshot(now: Long, display: ActivityDisplayPreference = events,
        context: ActivityPresentationContext = ActivityPresentationContext.IDLE_HOME,
        mode: ForegroundAmbientMode = ForegroundAmbientMode.RESIDENTS,
    ) = state.snapshot(now, context, null, false, displayOf = { if (it == "media") display else ActivityDisplayPreference.DEFAULT }, mode = mode)

    @Test fun `an event disappears on its deadline without removing the activity`() {
        state.start(id, "media", 1, content(), 0)
        assertEquals(ActivityPresentation.CHIP, snapshot(0).items.single().presentation)
        assertEquals(5_000L, state.nextDeadlineMs(0, false) { events })
        val hidden = snapshot(5_000)
        assertEquals(ActivityPresentation.HIDDEN, hidden.items.single().presentation)
        assertTrue(hidden.items.single().animateExit)
        assertEquals(id, hidden.collection.single().surfaceId)
    }

    @Test fun `ordinary metadata and paused updates never renew the event window`() {
        state.start(id, "media", 1, content(), 0)
        state.update(id, 2, ActivitySurfacePatch(secondary = ActivityField("Paused · Artist")), 4_000)
        assertEquals(ActivityPresentation.HIDDEN, snapshot(5_000).items.single().presentation)
        state.update(id, 3, ActivitySurfacePatch(eventKey = ActivityField("track-2")), 6_000)
        assertEquals(ActivityPresentation.CHIP, snapshot(6_000).items.single().presentation)
        assertEquals(ActivityPresentation.HIDDEN, snapshot(11_000).items.single().presentation)
        assertNull(snapshot(6_000).collection.single().lastSignificantOrder?.takeIf { it > 1 })
    }

    @Test fun `reconnect with the same event does not replay it`() {
        state.start(id, "media", 1, content(), 0)
        state.clearAll(2)
        state.start(id, "media", 3, content(), 6_000)
        assertEquals(ActivityPresentation.HIDDEN, snapshot(6_000).items.single().presentation)
        state.update(id, 4, ActivitySurfacePatch(eventKey = ActivityField("track-2")), 7_000)
        assertEquals(ActivityPresentation.CHIP, snapshot(7_000).items.single().presentation)
    }

    @Test fun `home only and persistent honor their scopes while nothing and the camera win`() {
        state.start(id, "media", 1, content(), 0)
        val home = ActivityDisplayPreference(ActivityDisplayMode.HOME)
        assertTrue(snapshot(0, home).items.isNotEmpty())
        assertTrue(snapshot(0, home, ActivityPresentationContext.NATIVE_APP).items.isEmpty())
        val always = ActivityDisplayPreference(ActivityDisplayMode.PERSISTENT)
        assertEquals(ActivityPresentation.CHIP, snapshot(0, always, ActivityPresentationContext.NATIVE_APP,
            ForegroundAmbientMode.SIGNALS).items.single().presentation)
        assertTrue(snapshot(0, always, mode = ForegroundAmbientMode.NOTHING).items.none { it.presentation != ActivityPresentation.HIDDEN })
        assertEquals(ActivityPresentation.HIDDEN, snapshot(0, always, ActivityPresentationContext.CAMERA_OVERLAY).items.single().presentation)
    }

    @Test fun `an event hidden by the camera is not replayed on return`() {
        state.start(id, "media", 1, content(), 0)
        state.presentEvent(id, ActivityPresentationContext.CAMERA_OVERLAY, false, false, 0, false, displayOf = { events })
        assertEquals(ActivityPresentation.HIDDEN, snapshot(500).items.single().presentation)
    }

    @Test fun `temporary media never steals collection priority and leaves the resident slot when it expires`() {
        state.start(id, "media", 1, content(), 0)
        state.start("nav:activity", "nav", 2, content(null), 1)
        state.update("nav:activity", 3, ActivitySurfacePatch(significant = true), 2)
        state.start("todo:activity", "todo", 4, content(null).copy(userPinned = true), 3)
        state.update(id, 5, ActivitySurfacePatch(eventKey = ActivityField("track-2")), 10)
        val live = snapshot(10)
        assertEquals("todo:activity", live.collection.single { it.primary }.surfaceId)
        assertTrue(live.items.any { it.activity.surfaceId == id && it.presentation != ActivityPresentation.HIDDEN })
        val expired = snapshot(5_010)
        assertEquals(2, expired.items.count { it.presentation != ActivityPresentation.HIDDEN })
        assertEquals(3, expired.collection.size)
    }

    @Test fun `a stale offline start has no event window and duration is bounded`() {
        state.start(id, "media", 1, content().copy(state = ActivityState.OFFLINE), 0)
        assertTrue(snapshot(0).items.none { it.presentation != ActivityPresentation.HIDDEN })
        assertThrows(IllegalArgumentException::class.java) { ActivityDisplayPreference(ActivityDisplayMode.EVENTS, 60_000) }
    }

    @Test fun `simultaneous events share the two native slots and never overlap in a fallback corner`() {
        state.start("nav:activity", "nav", 1, content(null), 0)
        state.start(id, "media", 2, content("track-1"), 1)
        state.start("todo:activity", "todo", 3, content("list-1"), 2)
        val displayOf: (String) -> ActivityDisplayPreference = { if (it == "nav") ActivityDisplayPreference.DEFAULT else events }
        val snapshot = state.snapshot(3, ActivityPresentationContext.IDLE_HOME, null, false, displayOf = displayOf)
        val drawn = snapshot.items.filter { it.presentation != ActivityPresentation.HIDDEN }
        assertEquals(2, drawn.size)
        assertEquals(2, drawn.map { it.activity.corner }.distinct().size)
        assertEquals(3, snapshot.collection.size)
    }
}
