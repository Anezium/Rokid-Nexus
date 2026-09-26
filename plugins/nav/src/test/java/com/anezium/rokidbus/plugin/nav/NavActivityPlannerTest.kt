package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NavActivityPlannerTest {
    private fun step(key: String, primary: String, imminent: Boolean = false, arrived: Boolean = false) = NavGuidance(
        source = NavSource.GOOGLE_MAPS,
        glyph = "turn-right",
        primary = primary,
        secondary = "Rue de Rivoli",
        stepKey = key,
        imminent = imminent,
        arrived = arrived,
    )

    @Test
    fun `the first guidance starts the activity`() {
        val planner = NavActivityPlanner()

        assertTrue(planner.plan(step("a", "80 m")) is NavPlan.Start)
    }

    @Test
    fun `a shrinking distance is a quiet update and a repeat sends nothing`() {
        val planner = NavActivityPlanner()
        planner.plan(step("a", "80 m"))

        assertEquals(NavPlan.Update(step("a", "70 m"), significant = false, urgent = false), planner.plan(step("a", "70 m")))
        assertEquals(NavPlan.Unchanged, planner.plan(step("a", "70 m")))
    }

    @Test
    fun `a new step is significant`() {
        val planner = NavActivityPlanner()
        planner.plan(step("a", "10 m"))

        val plan = planner.plan(step("b", "200 m")) as NavPlan.Update

        assertTrue(plan.significant)
        assertEquals(false, plan.urgent)
    }

    @Test
    fun `becoming imminent is urgent once per step, and urgent is always significant`() {
        val planner = NavActivityPlanner()
        planner.plan(step("a", "80 m"))

        assertEquals(
            NavPlan.Update(step("a", "30 m", imminent = true), significant = true, urgent = true),
            planner.plan(step("a", "30 m", imminent = true)),
        )
        assertEquals(
            NavPlan.Update(step("a", "20 m", imminent = true), significant = false, urgent = false),
            planner.plan(step("a", "20 m", imminent = true)),
        )
        val next = planner.plan(step("b", "30 m", imminent = true)) as NavPlan.Update
        assertTrue(next.urgent && next.significant)
    }

    @Test
    fun `arriving is significant even on the same step`() {
        val planner = NavActivityPlanner()
        planner.plan(step("a", "10 m"))

        assertTrue((planner.plan(step("a", "Arrived", arrived = true)) as NavPlan.Update).significant)
    }

    @Test
    fun `after a reset the next guidance starts again`() {
        val planner = NavActivityPlanner()
        planner.plan(step("a", "80 m"))
        planner.reset()

        assertTrue(planner.plan(step("a", "80 m")) is NavPlan.Start)
    }
}
