package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateNotificationPolicyTest {
    @Test
    fun `app update notifies once for each available version`() {
        assertTrue(UpdateNotificationPolicy.shouldNotifyAppUpdate("1.0.24", null))
        assertFalse(UpdateNotificationPolicy.shouldNotifyAppUpdate("1.0.24", "1.0.24"))
        assertTrue(UpdateNotificationPolicy.shouldNotifyAppUpdate("1.0.25", "1.0.24"))
    }

    @Test
    fun `same plugin update set does not notify again regardless of order`() {
        val updates = listOf(
            update("weather", "1.2.0"),
            update("transit", "2.0.1"),
        )
        val lastNotified = UpdateNotificationPolicy.pluginUpdateSet(updates)
        val reordered = UpdateNotificationPolicy.pluginUpdateSet(updates.reversed())

        assertTrue(UpdateNotificationPolicy.shouldNotifyPluginUpdates(lastNotified, null))
        assertFalse(UpdateNotificationPolicy.shouldNotifyPluginUpdates(reordered, lastNotified))
    }

    @Test
    fun `plugin identity uses id and available version only`() {
        val lastNotified = UpdateNotificationPolicy.pluginUpdateSet(
            listOf(update("weather", "1.2.0")),
        )
        val renamed = UpdateNotificationPolicy.pluginUpdateSet(
            listOf(
                PluginUpdateInfo(
                    pluginId = "weather",
                    name = "Forecasts",
                    installedVersionName = "1.1.5",
                    availableVersionName = "1.2.0",
                ),
            ),
        )

        assertFalse(UpdateNotificationPolicy.shouldNotifyPluginUpdates(renamed, lastNotified))
    }

    @Test
    fun `changed plugin version or membership notifies again`() {
        val lastNotified = UpdateNotificationPolicy.pluginUpdateSet(
            listOf(
                update("weather", "1.2.0"),
                update("transit", "2.0.1"),
            ),
        )
        val newerVersion = UpdateNotificationPolicy.pluginUpdateSet(
            listOf(
                update("weather", "1.3.0"),
                update("transit", "2.0.1"),
            ),
        )
        val changedMembership = UpdateNotificationPolicy.pluginUpdateSet(
            listOf(update("weather", "1.2.0")),
        )

        assertTrue(UpdateNotificationPolicy.shouldNotifyPluginUpdates(newerVersion, lastNotified))
        assertTrue(UpdateNotificationPolicy.shouldNotifyPluginUpdates(changedMembership, lastNotified))
    }

    @Test
    fun `empty plugin update set never notifies`() {
        assertFalse(UpdateNotificationPolicy.shouldNotifyPluginUpdates(emptySet(), null))
    }

    private fun update(pluginId: String, availableVersionName: String): PluginUpdateInfo =
        PluginUpdateInfo(
            pluginId = pluginId,
            name = pluginId,
            installedVersionName = "1.0.0",
            availableVersionName = availableVersionName,
        )
}
