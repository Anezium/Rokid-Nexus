package com.anezium.rokidbus.plugin.foodlog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
