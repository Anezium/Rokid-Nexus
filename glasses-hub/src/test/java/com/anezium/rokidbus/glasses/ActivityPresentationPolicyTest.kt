package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.PinSurfacePosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityPresentationPolicyTest {

    @Test
    fun `presentation truth table covers every input combination`() {
        ActivityPresentationContext.entries.forEach { context ->
            listOf(false, true).forEach { significant ->
                listOf(false, true).forEach { flareBudget ->
                    ActivityCollapseState.entries.forEach { collapseState ->
                        val expected = when {
                            context == ActivityPresentationContext.CAMERA_OVERLAY ->
                                ActivityPresentation.HIDDEN
                            significant && flareBudget ->
                                ActivityPresentation.FLARE
                            significant ->
                                ActivityPresentation.PULSE
                            context == ActivityPresentationContext.ACTIVE_SURFACE ||
                                context == ActivityPresentationContext.NEXUS_LAUNCHER ->
                                ActivityPresentation.PULSE
                            collapseState == ActivityCollapseState.ELAPSED ->
                                ActivityPresentation.CHIP
                            else ->
                                ActivityPresentation.PANEL
                        }

                        assertEquals(
                            "context=$context significant=$significant " +
                                "flareBudget=$flareBudget collapseState=$collapseState",
                            expected,
                            selectActivityPresentation(
                                context = context,
                                significant = significant,
                                flareBudgetAvailable = flareBudget,
                                collapseState = collapseState,
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `a throttled significant update degrades to pulse and is never queued by camera`() {
        assertEquals(
            ActivityPresentation.PULSE,
            selectActivityPresentation(
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = true,
                flareBudgetAvailable = false,
                collapseState = ActivityCollapseState.RUNNING,
            ),
        )
        assertEquals(
            ActivityPresentation.HIDDEN,
            selectActivityPresentation(
                ActivityPresentationContext.CAMERA_OVERLAY,
                significant = true,
                flareBudgetAvailable = true,
                collapseState = ActivityCollapseState.ALWAYS_EXPANDED,
            ),
        )
    }

    @Test
    fun `always expanded is a wearer collapse choice and launcher remains a passive chip`() {
        assertEquals(
            ActivityPresentation.PANEL,
            selectActivityPresentation(
                ActivityPresentationContext.IDLE_OR_NATIVE_HOME,
                significant = false,
                flareBudgetAvailable = false,
                collapseState = ActivityCollapseState.ALWAYS_EXPANDED,
            ),
        )
        assertEquals(
            ActivityPresentation.PULSE,
            selectActivityPresentation(
                ActivityPresentationContext.NEXUS_LAUNCHER,
                significant = false,
                flareBudgetAvailable = false,
                collapseState = ActivityCollapseState.ALWAYS_EXPANDED,
            ),
        )
    }

    @Test
    fun `primary follows significant recency and otherwise stays with first live activity`() {
        val initial = listOf(
            ActivityPrimaryCandidate("maps:activity", startedOrder = 1, lastSignificantOrder = null),
            ActivityPrimaryCandidate("ride:activity", startedOrder = 2, lastSignificantOrder = null),
        )
        assertEquals("maps:activity", selectPrimaryActivity(initial))

        val rideSignificant = initial.map {
            if (it.activityId == "ride:activity") it.copy(lastSignificantOrder = 3) else it
        }
        assertEquals("ride:activity", selectPrimaryActivity(rideSignificant))

        val mapsNewer = rideSignificant.map {
            if (it.activityId == "maps:activity") it.copy(lastSignificantOrder = 4) else it
        }
        assertEquals("maps:activity", selectPrimaryActivity(mapsNewer))
        assertEquals(
            "ride:activity",
            selectPrimaryActivity(mapsNewer.filterNot { it.activityId == "maps:activity" }),
        )
        assertNull(selectPrimaryActivity(emptyList()))
    }

    @Test
    fun `delayed activity tap remains bound to its idle primary session`() {
        val captured = ActivityInputTarget(
            "maps:activity",
            startedOrder = 7,
            actionId = "reroute",
        )

        assertTrue(
            canResolveActivityTap(
                captured,
                current = captured,
                idleLayerStillOwned = true,
            ),
        )
        assertFalse(
            canResolveActivityTap(
                captured,
                current = captured.copy(startedOrder = 8),
                idleLayerStillOwned = true,
            ),
        )
        assertTrue(
            canResolveActivityTap(
                captured,
                current = captured.copy(actionId = "cancel"),
                idleLayerStillOwned = true,
            ),
        )
        assertFalse(
            canResolveActivityTap(
                captured,
                current = ActivityInputTarget(
                    "ride:activity",
                    startedOrder = 7,
                    actionId = "reroute",
                ),
                idleLayerStillOwned = true,
            ),
        )
        assertFalse(
            canResolveActivityTap(
                captured,
                current = captured,
                idleLayerStillOwned = false,
            ),
        )
    }

    @Test
    fun `corner allocation reserves the pin and keeps stable activity corners`() {
        val allocated = allocateActivityCorners(
            activityIdsInOrder = listOf("maps:activity", "ride:activity"),
            existing = emptyMap(),
            pinCorner = PinSurfacePosition.TOP_RIGHT,
        )

        assertEquals(PinSurfacePosition.TOP_LEFT, allocated["maps:activity"])
        assertEquals(PinSurfacePosition.BOTTOM_LEFT, allocated["ride:activity"])
        assertFalse(allocated.values.contains(PinSurfacePosition.TOP_RIGHT))

        val stable = allocateActivityCorners(
            activityIdsInOrder = listOf("maps:activity", "ride:activity"),
            existing = allocated,
            pinCorner = PinSurfacePosition.BOTTOM_RIGHT,
        )
        assertEquals(allocated, stable)
    }

    @Test
    fun `a pin collision moves only the colliding activity`() {
        val existing = linkedMapOf(
            "maps:activity" to PinSurfacePosition.TOP_LEFT,
            "ride:activity" to PinSurfacePosition.BOTTOM_RIGHT,
        )

        val reallocated = allocateActivityCorners(
            activityIdsInOrder = existing.keys.toList(),
            existing = existing,
            pinCorner = PinSurfacePosition.TOP_LEFT,
        )

        assertEquals(PinSurfacePosition.TOP_RIGHT, reallocated["maps:activity"])
        assertEquals(PinSurfacePosition.BOTTOM_RIGHT, reallocated["ride:activity"])
        assertTrue(reallocated.values.distinct().size == reallocated.size)
    }

    @Test
    fun `a displaced early resident cannot steal a later residents valid corner`() {
        val reallocated = allocateActivityCorners(
            activityIdsInOrder = listOf("maps:activity", "ride:activity"),
            existing = linkedMapOf(
                "maps:activity" to PinSurfacePosition.TOP_LEFT,
                "ride:activity" to PinSurfacePosition.TOP_RIGHT,
            ),
            pinCorner = PinSurfacePosition.TOP_LEFT,
        )

        assertEquals(PinSurfacePosition.BOTTOM_LEFT, reallocated["maps:activity"])
        assertEquals(PinSurfacePosition.TOP_RIGHT, reallocated["ride:activity"])
    }

    @Test
    fun `allocator omits residents only after all four corners are occupied`() {
        val allocated = allocateActivityCorners(
            activityIdsInOrder = listOf("a", "b", "c", "d"),
            existing = emptyMap(),
            pinCorner = PinSurfacePosition.BOTTOM_RIGHT,
        )

        assertEquals(3, allocated.size)
        assertFalse(allocated.containsKey("d"))
        assertFalse(allocated.values.contains(PinSurfacePosition.BOTTOM_RIGHT))
    }
}
