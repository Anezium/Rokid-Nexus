package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.LauncherFavourite
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's pinned launcher applications: order, the four-entry cap, a growing revision. */
class PhoneLauncherFavouritesStoreTest {
    private val transit = LauncherFavourite.Plugin("transit")
    private val media = LauncherFavourite.Plugin("media-deck")
    private val camera = LauncherFavourite.Plugin("camera")
    private val browser = LauncherFavourite.NativeApp("com.android.chrome")
    private val settings = LauncherFavourite.NativeApp("com.android.settings")

    @Test
    fun `pins keep their order and never exceed four`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage())
        assertTrue(store.pin(transit))
        assertTrue(store.pin(browser))
        assertTrue(store.pin(media))
        assertFalse("the same application twice", store.pin(browser))
        assertTrue(store.pin(camera))
        assertTrue(store.isFull())
        assertFalse("a fifth entry", store.pin(settings))
        assertEquals(listOf(transit, browser, media, camera), store.entries())
    }

    @Test
    fun `moves are clamped and unpinning closes the gap`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage())
        listOf(transit, browser, media).forEach { assertTrue(store.pin(it)) }
        assertFalse("already first", store.move(transit, -1))
        assertTrue(store.move(media, -1))
        assertEquals(listOf(transit, media, browser), store.entries())
        assertTrue(store.move(transit, +5))
        assertEquals(listOf(media, browser, transit), store.entries())
        assertFalse("not pinned", store.move(camera, -1))
        assertTrue(store.unpin(browser))
        assertFalse(store.unpin(browser))
        assertEquals(listOf(media, transit), store.entries())
    }

    @Test
    fun `the revision only grows and survives a reload`() {
        val storage = MemoryStorage()
        val store = PhoneLauncherFavouritesStore(storage)
        val initial = store.favourites().revision
        assertTrue(store.pin(transit))
        val afterPin = store.favourites().revision
        assertTrue(afterPin > initial)
        assertTrue(store.pin(browser))
        assertTrue(store.move(browser, -1))
        val afterMove = store.favourites().revision
        assertTrue(afterMove > afterPin)

        val reloaded = PhoneLauncherFavouritesStore(storage)
        assertEquals(afterMove, reloaded.favourites().revision)
        assertEquals(listOf(browser, transit), reloaded.entries())
        assertTrue(reloaded.unpin(transit))
        assertTrue(reloaded.favourites().revision > afterMove)
    }

    @Test
    fun `what is stored is the wire payload, so the glasses parser validates it`() {
        val storage = MemoryStorage()
        val store = PhoneLauncherFavouritesStore(storage)
        assertTrue(store.pin(camera))
        assertTrue(store.pin(settings))
        val parsed = LauncherFavouritesContract.parse(JSONObject(storage.value!!))
        assertNotNull(parsed)
        assertEquals(store.favourites(), parsed)
    }

    @Test
    fun `a corrupt record starts empty instead of failing`() {
        val storage = MemoryStorage().apply { value = "{not json" }
        val store = PhoneLauncherFavouritesStore(storage)
        assertTrue(store.entries().isEmpty())
        assertTrue(store.pin(transit))
        assertEquals(listOf(transit), PhoneLauncherFavouritesStore(storage).entries())
    }

    @Test
    fun `listeners hear every change and nothing else`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage())
        var changes = 0
        val subscription = store.addChangeListener { changes++ }
        assertTrue(store.pin(transit))
        assertFalse(store.pin(transit))
        assertTrue(store.unpin(transit))
        assertEquals(2, changes)
        subscription.close()
        assertTrue(store.pin(media))
        assertEquals(2, changes)
    }

    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }
}
