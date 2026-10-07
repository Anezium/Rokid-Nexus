package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.NativeAppEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherMenuCatalogTest {
    @Test
    fun `keeps plugins before native glasses apps`() {
        val entries = LauncherMenuCatalog.merge(
            plugins = listOf(GlassesHub.LauncherEntry("media", "Media")),
            nativeApps = listOf(NativeAppEntry("app.morphe.android.youtube", "YouTube")),
        )

        assertTrue(entries[0] is LauncherMenuEntry.Plugin)
        assertTrue(entries[1] is LauncherMenuEntry.NativeApp)
        assertEquals("plugin:media", entries[0].stableId)
        assertEquals("app:app.morphe.android.youtube", entries[1].stableId)
    }

    @Test
    fun `does not require phone plugins to expose native apps`() {
        val entries = LauncherMenuCatalog.merge(
            plugins = emptyList(),
            nativeApps = listOf(NativeAppEntry("com.anezium.rokid.newpipe", "RokidPipe")),
        )

        assertEquals(listOf("RokidPipe"), entries.map(LauncherMenuEntry::displayName))
    }
}
