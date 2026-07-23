package com.anezium.rokidbus.phone

import com.anezium.rokidbus.client.R as BusClientR
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreIconFallbackTest {
    @Test
    fun `legacy map assigns send only to feeds and grid to unknown plugins`() {
        assertEquals(BusClientR.drawable.ic_plugin_send, iconFor("feeds"))
        assertEquals(BusClientR.drawable.ic_plugin_grid, iconFor("shoplist"))
        assertEquals(BusClientR.drawable.ic_plugin_grid, iconFor("unknown"))
    }

    @Test
    fun `installed descriptor is selected ahead of the legacy fallback`() {
        val selected = selectStoreIconFallback(
            pluginId = "feeds",
            installedPackageName = "dev.example.feeds",
            iconKey = "star",
            customIconResId = 42,
        )

        assertTrue(selected is StoreIconFallback.InstalledDescriptor)
        selected as StoreIconFallback.InstalledDescriptor
        assertEquals("dev.example.feeds", selected.packageName)
        assertEquals("star", selected.iconKey)
        assertEquals(42, selected.customIconResId)
        assertEquals(BusClientR.drawable.ic_plugin_send, selected.legacyResId)
    }

    @Test
    fun `missing installed descriptor selects the explicit legacy map`() {
        val selected = selectStoreIconFallback(
            pluginId = "shoplist",
            installedPackageName = null,
            iconKey = "star",
            customIconResId = 42,
        )

        assertEquals(
            StoreIconFallback.Legacy(BusClientR.drawable.ic_plugin_grid),
            selected,
        )
    }
}
