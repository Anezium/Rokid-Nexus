package com.anezium.rokidbus.phone

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [30])
class NotificationReconnectTest {
    @Test fun aNotificationTapReconnectsAnAlreadyExistingHome() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        activity.getSharedPreferences("rokidbus_phone", 0).edit()
            .putBoolean("hub_enabled", true).putString("cxrl_token", "test-token").commit()

        deliver(activity, ACTION_NOTIFICATION_RECONNECT)

        val start = shadowOf(activity.application).nextStartedService
        assertNotNull(start)
        assertTrue(HubCommandIntents.isTrusted(start))
        assertEquals(BusHubService::class.java.name, start.component?.className)
    }

    @Test fun aNormalOpenOrStaleNotificationDoesNotEnableAStoppedHub() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val prefs = activity.getSharedPreferences("rokidbus_phone", 0)
        prefs.edit().putBoolean("hub_enabled", true).putString("cxrl_token", "test-token").commit()
        deliver(activity, Intent.ACTION_MAIN)
        assertNull(shadowOf(activity.application).nextStartedService)

        prefs.edit().putBoolean("hub_enabled", false).commit()
        deliver(activity, ACTION_NOTIFICATION_RECONNECT)
        assertNull(shadowOf(activity.application).nextStartedService)
        assertFalse(BusHubService.isEnabled(activity))
    }

    private fun deliver(activity: MainActivity, action: String) {
        MainActivity::class.java.getDeclaredMethod("onNewIntent", Intent::class.java)
            .apply { isAccessible = true }
            .invoke(activity, Intent(action))
    }
}
