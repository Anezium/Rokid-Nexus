package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FoodLogReminderPlannerTest {
    @Test fun `future reminder schedules at its requested wall time`() {
        val plan = FoodLogReminderPlanner.plan(reminder(2_000L), 1_000L) as FoodLogAlarmPlan.Schedule
        assertEquals(2_000L, plan.triggerAtMillis)
    }
    @Test fun `passed reminder is delivered immediately`() {
        assertTrue(FoodLogReminderPlanner.plan(reminder(1_000L), 1_000L) is FoodLogAlarmPlan.DeliverImmediately)
    }
    @Test fun `scheduler passes exact permission through and has no retry loop`() {
        val gateway = RecordingGateway()
        val scheduler = FoodLogReminderScheduler(gateway, { false }, { 1_000L })
        assertFalse(scheduler.schedule(reminder(2_000L)))
        assertEquals(1, gateway.scheduled)
    }
    @Test fun `restored overdue reminder is marked late without direct delivery`() {
        val gateway = RecordingGateway()
        val scheduler = FoodLogReminderScheduler(gateway, { true }, { 2_000L })
        scheduler.reschedule(reminder(1_000L))
        assertEquals(true, gateway.lastLate)
        assertEquals(0, gateway.delivered)
    }
    private fun reminder(epoch: Long) = FoodLogReminder("00000000-0000-4000-8000-000000000001", FoodLogReminderKind.MEAL, "Lunch", epoch, true)
    private class RecordingGateway : FoodLogAlarmGateway {
        var scheduled = 0
        var delivered = 0
        var lastLate = false
        override fun schedule(id: String, triggerAtMillis: Long, exact: Boolean, late: Boolean): Boolean {
            scheduled++
            lastLate = late
            return exact
        }
        override fun cancel(id: String) = Unit
        override fun deliverNow(id: String, late: Boolean) { delivered++ }
    }
}
