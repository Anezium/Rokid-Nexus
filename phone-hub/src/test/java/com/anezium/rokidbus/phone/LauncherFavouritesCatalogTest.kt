package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LauncherFavourite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The favourites screen's two lists: pinned in the owner's order, the rest of the Applications catalog after. */
class LauncherFavouritesCatalogTest {
    private val transit = PhoneLauncherApplication("transit", "Transit", launchable = true, pageVersion = 1, iconKey = "transit")
    private val notes = PhoneLauncherApplication("notes", "Notes", launchable = false, pageVersion = 1, packageName = "dev.notes", iconDrawableResId = 7)
    private val browser = NativeGlassesApp("com.android.chrome", "Chrome", "com.android.chrome", NativeAppAction.OPEN)

    @Test
    fun `pinned rows follow the owner's order and leave the add list`() {
        val catalog = LauncherFavouritesCatalog.build(
            favourites = listOf(LauncherFavourite.NativeApp("com.android.chrome"), LauncherFavourite.Plugin("transit")),
            plugins = listOf(transit, notes),
            cameraName = "Lens",
            nativeApps = listOf(browser),
        )
        assertEquals(listOf("app:com.android.chrome", "plugin:transit"), catalog.pinned.map { it.key })
        assertEquals(listOf("Chrome", "Transit"), catalog.pinned.map { it.label })
        assertTrue(catalog.pinned.all { it.available })
        assertEquals(listOf("plugin:camera", "plugin:notes"), catalog.available.map { it.key })
        assertEquals("Lens", catalog.available.first().label)
    }

    @Test
    fun `a pinned application the glasses no longer list stays, marked unavailable`() {
        val catalog = LauncherFavouritesCatalog.build(
            favourites = listOf(LauncherFavourite.Plugin("transit"), LauncherFavourite.Plugin("media-deck"), LauncherFavourite.NativeApp("org.example.player")),
            plugins = listOf(transit),
            cameraName = null,
            nativeApps = emptyList(),
        )
        assertEquals(3, catalog.pinned.size)
        assertTrue(catalog.pinned[0].available)
        assertFalse(catalog.pinned[1].available)
        assertFalse(catalog.pinned[2].available)
        assertEquals("Deck", catalog.pinned[1].label)
        assertEquals("Player", catalog.pinned[2].label)
        assertTrue(catalog.available.isEmpty())
    }

    @Test
    fun `the camera is listed once under the approved plugin's name, with its icon`() {
        val cameraPlugin = PhoneLauncherApplication("camera", "Camera", launchable = true, pageVersion = 0)
        val catalog = LauncherFavouritesCatalog.build(
            favourites = emptyList(),
            plugins = listOf(cameraPlugin, transit),
            cameraName = "Lens",
            nativeApps = emptyList(),
        )
        val camera = catalog.available.single { it.key == "plugin:camera" }
        assertEquals("Lens", camera.label)
        assertEquals(LauncherFavouriteIcon.Plugin("camera", "lens", null, null), camera.icon)
    }

    @Test
    fun `plugin icons carry the custom artwork and glasses apps fall back to an initial`() {
        val catalog = LauncherFavouritesCatalog.build(
            favourites = emptyList(),
            plugins = listOf(notes),
            cameraName = null,
            nativeApps = listOf(browser),
        )
        assertEquals(LauncherFavouriteIcon.Plugin("notes", null, "dev.notes", 7), catalog.available[0].icon)
        assertEquals(LauncherFavouriteIcon.Letter("C"), catalog.available[1].icon)
    }
}
