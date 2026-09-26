package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivityField
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.ActivitySurfacePatch
import com.anezium.rokidbus.shared.PinSurfacePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityStateMachineTest {
    private val state = ActivityStateMachine()
    private var now = 10_000L

    @Test
    fun `start update end and clear-all enforce sequence watermarks`() {
        assertTrue(
            state.start("maps:activity", "maps", 10, content("300 m"), now)
                is ActivityMutation.Applied,
        )
        assertTrue(
            state.update(
                "maps:activity",
                11,
                ActivitySurfacePatch(primary = ActivityField("250 m")),
                now,
            ) is ActivityMutation.Applied,
        )
        assertTrue(
            state.update(
                "maps:activity",
                10,
                ActivitySurfacePatch(primary = ActivityField("stale")),
                now,
            ) is ActivityMutation.DroppedStale,
        )
        assertEquals(
            "250 m",
            state.snapshot(
                now,
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                pinCorner = null,
                alwaysExpanded = false,
            ).primary?.activity?.content?.primary,
        )

        val partialClear = state.clearAll(10)
        assertTrue(partialClear is ActivityMutation.Cleared)
        assertTrue((partialClear as ActivityMutation.Cleared).surfaceIds.isEmpty())
        assertEquals(setOf("maps:activity"), state.surfaceIds())
        assertTrue(state.clearAll(20) is ActivityMutation.Cleared)
        assertTrue(
            state.start("maps:activity", "maps", 19, content("ghost"), now)
                is ActivityMutation.DroppedStale,
        )
        assertTrue(
            state.start("maps:activity", "maps", 21, content("fresh"), now)
                is ActivityMutation.Applied,
        )
        assertTrue(state.end("maps:activity", 22) is ActivityMutation.Removed)
        assertTrue(state.end("maps:activity", 21) is ActivityMutation.DroppedStale)
        assertTrue(state.surfaceIds().isEmpty())
    }

    @Test
    fun `delayed clear removes older ghosts while preserving newer resend starts`() {
        state.start("ghost:activity", "ghost", 10, content("stale"), now)
        state.start("maps:activity", "maps", 20, content("fresh"), now)

        val mutation = state.clearAll(15)

        assertEquals(
            listOf("ghost:activity"),
            (mutation as ActivityMutation.Cleared).surfaceIds,
        )
        assertEquals(setOf("maps:activity"), state.surfaceIds())
        assertTrue(
            state.start("late:activity", "late", 14, content("ghost"), now)
                is ActivityMutation.DroppedStale,
        )
        assertEquals(setOf("maps:activity"), state.surfaceIds())
    }

    @Test
    fun `significant recency owns primary and flare budget is per activity`() {
        state.start("maps:activity", "maps", 1, content("300 m"), now)
        state.start("ride:activity", "ride", 2, content("4 min"), now)
        assertEquals("maps:activity", state.primarySurfaceId())

        state.update(
            "ride:activity",
            3,
            ActivitySurfacePatch(
                primary = ActivityField("3 min"),
                significant = true,
            ),
            now,
        )
        assertEquals("ride:activity", state.primarySurfaceId())
        assertEquals(
            ActivityPresentation.FLARE,
            state.presentationForEvent(
                "ride:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )

        now += 9_999L
        assertEquals(
            ActivityPresentation.PULSE,
            state.presentationForEvent(
                "ride:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )
        state.update(
            "maps:activity",
            4,
            ActivitySurfacePatch(significant = true),
            now,
        )
        // The other activity owns an independent flare bucket after it becomes
        // primary in its own right.
        assertEquals(
            ActivityPresentation.FLARE,
            state.presentationForEvent(
                "maps:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )

        now += 1L
        state.update(
            "ride:activity",
            5,
            ActivitySurfacePatch(significant = true),
            now,
        )
        assertEquals(
            ActivityPresentation.FLARE,
            state.presentationForEvent(
                "ride:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )
    }

    @Test
    fun `camera consumes no flare budget and restore cannot replay significance`() {
        state.start("maps:activity", "maps", 1, content("300 m"), now)
        state.update(
            "maps:activity",
            2,
            ActivitySurfacePatch(significant = true),
            now,
        )
        assertEquals(
            ActivityPresentation.HIDDEN,
            state.presentationForEvent(
                "maps:activity",
                ActivityPresentationContext.CAMERA_OVERLAY,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )

        val restored = state.snapshot(
            nowMs = now,
            context = ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
            pinCorner = null,
            alwaysExpanded = false,
        ).primary
        assertEquals(ActivityPresentation.PANEL, restored?.presentation)

        // Since hiding did not spend admission, a later real significant event
        // can flare immediately.
        assertEquals(
            ActivityPresentation.FLARE,
            state.presentationForEvent(
                "maps:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )
    }

    @Test
    fun `idle collapses at ten seconds while wearer setting stays expanded`() {
        state.start("timer:activity", "timer", 1, content("10:00"), now)
        assertEquals(
            ActivityPresentation.PANEL,
            snapshotPrimaryPresentation(alwaysExpanded = false),
        )
        assertEquals(
            ActivityPresentation.PANEL,
            snapshotPrimaryPresentation(alwaysExpanded = true),
        )

        now += ActivityStateMachine.COLLAPSE_AFTER_MS
        assertEquals(
            ActivityPresentation.CHIP,
            snapshotPrimaryPresentation(alwaysExpanded = false),
        )
        assertEquals(
            ActivityPresentation.PANEL,
            snapshotPrimaryPresentation(alwaysExpanded = true),
        )

        state.update(
            "timer:activity",
            2,
            ActivitySurfacePatch(primary = ActivityField("09:59")),
            now,
        )
        assertEquals(
            ActivityPresentation.PANEL,
            snapshotPrimaryPresentation(alwaysExpanded = false),
        )
    }

    @Test
    fun `pin corner is reserved and existing activity corner remains stable`() {
        state.start("maps:activity", "maps", 1, content("300 m"), now)
        state.start("ride:activity", "ride", 2, content("4 min"), now)
        val first = state.snapshot(
            now,
            ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
            pinCorner = PinSurfacePosition.TOP_RIGHT,
            alwaysExpanded = false,
        )
        assertEquals(
            mapOf(
                "maps:activity" to PinSurfacePosition.TOP_LEFT,
                "ride:activity" to PinSurfacePosition.BOTTOM_LEFT,
            ),
            first.items.associate { it.activity.surfaceId to it.activity.corner },
        )

        val pinMoved = state.snapshot(
            now,
            ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
            pinCorner = PinSurfacePosition.BOTTOM_RIGHT,
            alwaysExpanded = false,
        )
        assertEquals(
            first.items.associate { it.activity.surfaceId to it.activity.corner },
            pinMoved.items.associate { it.activity.surfaceId to it.activity.corner },
        )
    }

    @Test
    fun `third activity evicts oldest non-primary and preserves primary`() {
        state.start("alpha:activity", "alpha", 1, content("a"), now)
        state.start("beta:activity", "beta", 2, content("b"), now)
        state.update(
            "beta:activity",
            3,
            ActivitySurfacePatch(significant = true),
            now,
        )
        state.update(
            "alpha:activity",
            4,
            ActivitySurfacePatch(primary = ActivityField("new a")),
            now,
        )

        val result = state.start(
            "gamma:activity",
            "gamma",
            5,
            content("c"),
            now,
        ) as ActivityMutation.Applied

        assertEquals("alpha:activity", result.replacedSurfaceId)
        assertEquals(setOf("beta:activity", "gamma:activity"), state.surfaceIds())
        assertEquals("beta:activity", state.primarySurfaceId())
    }

    @Test
    fun `max duration is fixed and absent duration has no expiry`() {
        state.start("route:activity", "route", 1, content("far"), now)
        assertNull(state.nextDeadlineMs(now, alwaysExpanded = true))

        state.start(
            "timer:activity",
            "timer",
            2,
            content("1 min", maxDurationMs = 60_000L),
            now,
        )
        val deadline = now + 60_000L
        assertEquals(deadline, state.nextDeadlineMs(now, alwaysExpanded = true))
        now += 30_000L
        state.update(
            "timer:activity",
            3,
            ActivitySurfacePatch(primary = ActivityField("30 sec")),
            now,
        )
        assertEquals(deadline, state.nextDeadlineMs(now, alwaysExpanded = true))
        assertTrue(state.expire(now).isEmpty())
        now = deadline
        assertEquals(listOf("timer:activity"), state.expire(now))
        assertTrue("route:activity" in state.surfaceIds())
    }

    @Test
    fun `action selection wraps and preserves action id across patches`() {
        val actions = listOf(
            ActivityAction("pause", "pause", "Pause"),
            ActivityAction("skip", "next", "Skip"),
        )
        state.start(
            "player:activity",
            "player",
            1,
            content("playing").copy(actions = actions),
            now,
        )
        assertEquals("pause", state.selectedAction("player:activity")?.id)
        assertTrue(state.moveSelection("player:activity", 1, now))
        assertEquals("skip", state.selectedAction("player:activity")?.id)
        assertTrue(state.moveSelection("player:activity", 1, now))
        assertEquals("pause", state.selectedAction("player:activity")?.id)
        assertTrue(state.moveSelection("player:activity", -1, now))
        assertEquals("skip", state.selectedAction("player:activity")?.id)

        state.update(
            "player:activity",
            2,
            ActivitySurfacePatch(
                actions = ActivityField(actions.reversed()),
            ),
            now,
        )
        assertEquals("skip", state.selectedAction("player:activity")?.id)
    }

    @Test
    fun `action interaction re-expands a collapsed panel and restarts its idle timer`() {
        state.start(
            "player:activity",
            "player",
            1,
            content("playing").copy(
                actions = listOf(ActivityAction("pause", "pause", "Pause")),
            ),
            now,
        )
        now += ActivityStateMachine.COLLAPSE_AFTER_MS
        assertEquals(ActivityPresentation.CHIP, snapshotPrimaryPresentation(alwaysExpanded = false))

        assertTrue(state.moveSelection("player:activity", 1, now))
        assertEquals(ActivityPresentation.PANEL, snapshotPrimaryPresentation(alwaysExpanded = false))

        now += ActivityStateMachine.COLLAPSE_AFTER_MS - 1L
        assertEquals(ActivityPresentation.PANEL, snapshotPrimaryPresentation(alwaysExpanded = false))
        now += 1L
        assertEquals(ActivityPresentation.CHIP, snapshotPrimaryPresentation(alwaysExpanded = false))
    }

    @Test
    fun `urgent flares through a spent flare budget once a minute`() {
        state.start("nav:activity", "nav", 1, content("3 stops"), now)
        state.update("nav:activity", 2, ActivitySurfacePatch(significant = true), now)
        assertEquals(ActivityPresentation.FLARE, urgentEvent(urgent = false).presentation)

        // Five seconds later the ordinary budget is spent, but "get off at the
        // next stop" is not swallowed by the maneuver flare before it.
        now += 5_000L
        state.update(
            "nav:activity",
            3,
            ActivitySurfacePatch(significant = true, urgent = true),
            now,
        )
        val urgent = urgentEvent(urgent = true)
        assertEquals(ActivityPresentation.FLARE, urgent.presentation)
        assertTrue(urgent.urgent)
        val rendered = state.snapshot(
            nowMs = now,
            context = ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
            pinCorner = null,
            alwaysExpanded = false,
            eventSurfaceId = "nav:activity",
            eventPresentation = urgent.presentation,
            eventUrgent = urgent.urgent,
        ).primary
        assertTrue(rendered?.urgent == true)

        // A second urgent inside the minute is an ordinary significant update.
        now += 30_000L
        val throttled = urgentEvent(urgent = true)
        assertEquals(ActivityPresentation.FLARE, throttled.presentation)
        assertTrue(!throttled.urgent)

        now += 5_000L
        val spent = urgentEvent(urgent = true)
        assertEquals(ActivityPresentation.PULSE, spent.presentation)
        assertTrue(!spent.urgent)

        now += 25_000L
        assertTrue(urgentEvent(urgent = true).urgent)
    }

    @Test
    fun `urgent is never drawn under the camera or for a non-primary activity`() {
        state.start("nav:activity", "nav", 1, content("3 stops"), now)
        assertEquals(
            ActivityEventPresentation(ActivityPresentation.HIDDEN),
            state.presentEvent(
                "nav:activity",
                ActivityPresentationContext.CAMERA_OVERLAY,
                significant = true,
                urgent = true,
                nowMs = now,
                alwaysExpanded = false,
            ),
        )
        // Hidden spent nothing, so the next real urgent event is honoured.
        assertTrue(urgentEvent(urgent = true).urgent)

        state.start("ride:activity", "ride", 2, content("4 min"), now)
        state.update("ride:activity", 3, ActivitySurfacePatch(significant = true), now)
        assertEquals(
            ActivityEventPresentation(ActivityPresentation.PULSE),
            state.presentEvent(
                "nav:activity",
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                urgent = true,
                nowMs = now + 60_000L,
                alwaysExpanded = false,
            ),
        )
    }

    private fun urgentEvent(urgent: Boolean) = state.presentEvent(
        "nav:activity",
        ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
        significant = true,
        urgent = urgent,
        nowMs = now,
        alwaysExpanded = false,
    )

    private fun snapshotPrimaryPresentation(alwaysExpanded: Boolean) =
        state.snapshot(
            nowMs = now,
            context = ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
            pinCorner = null,
            alwaysExpanded = alwaysExpanded,
        ).primary?.presentation

    private fun content(
        primary: String,
        maxDurationMs: Long? = null,
    ) = ActivitySurfaceContent(
        glyph = "straight",
        primary = primary,
        secondary = "Rue de la Paix",
        progress = null,
        eta = null,
        detail = emptyList(),
        actions = emptyList(),
        maxDurationMs = maxDurationMs,
    )
}
