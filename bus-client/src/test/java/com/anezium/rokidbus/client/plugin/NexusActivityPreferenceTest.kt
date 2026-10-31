package com.anezium.rokidbus.client.plugin

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class NexusActivityPreferenceTest {
    @Test fun `opt in persists and removal does not alter plugin data`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("activity-preference-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putString("groceries", "Milk").commit()
        val setting = NexusActivityPreference(prefs)
        val content = NexusActivity(glyph = "disc", primary = "Ready", destination = NexusPageDestination("media:main"))
        assertFalse(setting.enabled)
        assertNull(setting.retained(content))
        setting.enabled = true
        assertEquals(NexusActivityPersistence.ONGOING, NexusActivityPreference(prefs).retained(content)?.persistence)
        setting.enabled = false
        assertNull(NexusActivityPreference(prefs).retained(content))
        assertEquals("Milk", prefs.getString("groceries", null))
    }

    @Test fun `existing plugin opt in needs no migration reset`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("legacy-activity-test", Context.MODE_PRIVATE)
        prefs.edit().clear().putBoolean("show_in_activities", true).commit()
        assertTrue(NexusActivityPreference(prefs, "show_in_activities").enabled)
    }
}
