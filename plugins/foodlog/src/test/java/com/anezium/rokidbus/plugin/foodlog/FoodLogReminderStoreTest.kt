package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FoodLogReminderStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `create update exact delete and delivery claim are atomic`() {
        val store = FoodLogReminderStore(temporary.root, { "00000000-0000-4000-8000-000000000001" })
        val saved = store.create(FoodLogReminderKind.HYDRATION, "Water", 2_000L)
        assertEquals(saved, store.get(saved.id))
        assertEquals(true, store.update(saved.copy(enabled = false)))
        assertNull(store.takeForDelivery(saved.id))
        assertEquals(saved.copy(enabled = false), store.cancel(saved.id))
        assertNull(store.cancel(saved.id))
    }
    @Test(expected = IllegalArgumentException::class) fun `labels are bounded`() {
        FoodLogReminderStore(temporary.root).create(FoodLogReminderKind.MEAL, " ", 1L)
    }
    @Test fun `backup merge is idempotent by stable UUID`() {
        val id = "00000000-0000-4000-8000-000000000001"
        val store = FoodLogReminderStore(temporary.root)
        val reminder = FoodLogReminder(id, FoodLogReminderKind.MEAL, "Dinner", 3_000L, true)
        store.merge(listOf(reminder))
        store.merge(listOf(reminder.copy(label = "Late dinner")))
        assertEquals(1, store.all().size)
        assertEquals("Late dinner", store.get(id)?.label)
    }
    @Test fun `corrupt reminder state fails closed`() {
        java.io.File(temporary.root, "food_log_reminders_v1.json").writeText("not-json")
        val result = runCatching { FoodLogReminderStore(temporary.root).all() }
        assertTrue(result.isFailure)
    }
    @Test fun `valid backup merge quarantines corrupt state before recovery`() {
        java.io.File(temporary.root, "food_log_reminders_v1.json").writeText("not-json")
        val reminder = FoodLogReminder(
            "00000000-0000-4000-8000-000000000001",
            FoodLogReminderKind.HYDRATION,
            "Water",
            4_000L,
            true,
        )
        val store = FoodLogReminderStore(temporary.root)
        store.merge(listOf(reminder))
        assertEquals(reminder, store.all().single())
        assertTrue(temporary.root.listFiles().orEmpty().any { it.name.startsWith("food_log_reminders_v1.json.corrupt-") })
    }
}
