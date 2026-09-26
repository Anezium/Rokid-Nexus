package com.anezium.rokidbus.client.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusGuidancePlannerTest {
    private fun step(key: String, primary: String, imminent: Boolean = false, arrived: Boolean = false) =
        NexusGuidanceStep(
            glyph = NexusGuidanceGlyphs.TURN_RIGHT,
            primary = primary,
            secondary = "Rue de Rivoli",
            stepKey = key,
            imminent = imminent,
            arrived = arrived,
        )

    @Test
    fun `the first guidance starts the activity`() {
        assertTrue(NexusGuidancePlanner().plan(step("a", "80 m")) is NexusGuidancePlan.Start)
    }

    @Test
    fun `a shrinking distance is a quiet update and a repeat sends nothing`() {
        val planner = NexusGuidancePlanner()
        planner.plan(step("a", "80 m"))

        assertEquals(
            NexusGuidancePlan.Update(step("a", "70 m"), significant = false, urgent = false),
            planner.plan(step("a", "70 m")),
        )
        assertEquals(NexusGuidancePlan.Unchanged, planner.plan(step("a", "70 m")))
    }

    @Test
    fun `a new step is significant`() {
        val planner = NexusGuidancePlanner()
        planner.plan(step("a", "10 m"))

        val plan = planner.plan(step("b", "200 m")) as NexusGuidancePlan.Update

        assertTrue(plan.significant)
        assertEquals(false, plan.urgent)
    }

    @Test
    fun `becoming imminent is urgent once per step, and urgent is always significant`() {
        val planner = NexusGuidancePlanner()
        planner.plan(step("a", "80 m"))

        assertEquals(
            NexusGuidancePlan.Update(step("a", "30 m", imminent = true), significant = true, urgent = true),
            planner.plan(step("a", "30 m", imminent = true)),
        )
        assertEquals(
            NexusGuidancePlan.Update(step("a", "20 m", imminent = true), significant = false, urgent = false),
            planner.plan(step("a", "20 m", imminent = true)),
        )
        val next = planner.plan(step("b", "30 m", imminent = true)) as NexusGuidancePlan.Update
        assertTrue(next.urgent && next.significant)
    }

    @Test
    fun `arriving is significant even on the same step`() {
        val planner = NexusGuidancePlanner()
        planner.plan(step("a", "10 m"))

        assertTrue((planner.plan(step("a", "Arrived", arrived = true)) as NexusGuidancePlan.Update).significant)
    }

    @Test
    fun `after a reset the next guidance starts again`() {
        val planner = NexusGuidancePlanner()
        planner.plan(step("a", "80 m"))
        planner.reset()

        assertTrue(planner.plan(step("a", "80 m")) is NexusGuidancePlan.Start)
    }

    @Test
    fun `a step maps onto the activity it describes`() {
        val activity = step("a", "3 stops").copy(
            glyph = NexusGuidanceGlyphs.BUS,
            badge = "38",
            track = NexusActivityTrack(count = 5, at = 2, target = 4, label = "Luxembourg"),
            progressPercent = 140,
        ).toActivity(maxDurationMs = 60_000L, wakeDisplay = true)

        assertEquals("bus", activity.glyph)
        assertEquals("38", activity.badge)
        assertEquals(NexusActivityProgress.Percent(100), activity.progress)
        assertEquals(60_000L, activity.maxDurationMs)
        assertTrue(activity.wakeDisplay)
    }

    @Test
    fun `transit modes map onto the vehicle glyphs`() {
        assertEquals("bus", NexusGuidanceGlyphs.forTransitMode("BUS"))
        assertEquals("metro", NexusGuidanceGlyphs.forTransitMode("subway"))
        assertEquals("train", NexusGuidanceGlyphs.forTransitMode("REGIONAL_RAIL"))
        assertEquals("tram", NexusGuidanceGlyphs.forTransitMode("TRAM"))
        assertEquals("walk", NexusGuidanceGlyphs.forTransitMode("WALK"))
        assertNull(NexusGuidanceGlyphs.forTransitMode("FERRY"))
    }
}
