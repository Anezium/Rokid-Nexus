package com.anezium.rokidbus.plugin.foodlog

import android.app.NotificationManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [32])
class FoodReminderDeliveryTest {
    @Test fun inexactFallbackPostsExactlyOnceWithoutStartingAService() {
        val context = RuntimeEnvironment.getApplication()
        val store = FoodLogReminderStore(context)
        val reminder = store.create(FoodLogReminderKind.MEAL, "Lunch", System.currentTimeMillis())
        val notifications = shadowOf(context.getSystemService(NotificationManager::class.java))
        assertTrue(deliverFoodLogPhoneReminder(context, reminder.id, false))
        assertFalse(deliverFoodLogPhoneReminder(context, reminder.id, false))
        assertNull(store.get(reminder.id))
        assertEquals(1, notifications.size())
        assertNull(shadowOf(context).nextStartedService)
    }

    @Test fun aPausedReminderDoesNotNotifyOrStartAService() {
        val context = RuntimeEnvironment.getApplication()
        val store = FoodLogReminderStore(context)
        val reminder = store.create(FoodLogReminderKind.HYDRATION, "Water", System.currentTimeMillis(), enabled = false)
        assertFalse(deliverFoodLogPhoneReminder(context, reminder.id, true))
        assertEquals(reminder, store.get(reminder.id))
        assertEquals(0, shadowOf(context.getSystemService(NotificationManager::class.java)).size())
        assertNull(shadowOf(context).nextStartedService)
    }
}
