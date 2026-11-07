package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherFavourite
import com.anezium.rokidbus.shared.LauncherFavouritesContract
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The whole list goes to glasses that read bundle v8, and only to them. */
class PhoneLauncherFavouritesSyncTest {
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private val logged = mutableListOf<String>()

    @Test
    fun `glasses below the favourites bundle get nothing`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage()).apply { pin(LauncherFavourite.Plugin("transit")) }
        for (version in listOf(null, 0, LauncherInteractiveContract.FAVOURITES_VERSION - 1)) {
            PhoneLauncherFavouritesSync(store, { p, j -> sent += p to j; null }, { version }, logged::add).push()
        }
        assertTrue(sent.isEmpty())
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `the payload is the store's list, whole and in order`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage()).apply {
            pin(LauncherFavourite.NativeApp("com.android.chrome"))
            pin(LauncherFavourite.Plugin("transit"))
        }
        PhoneLauncherFavouritesSync(store, { p, j -> sent += p to j; null }, { LauncherInteractiveContract.FAVOURITES_VERSION }, logged::add).push()
        val (path, payload) = sent.single()
        assertEquals(BusPaths.CORE_LAUNCHER_FAVOURITES, path)
        assertEquals(store.favourites(), LauncherFavouritesContract.parse(payload))
        assertEquals(listOf("launcher favourites sent revision=${store.favourites().revision} entries=2"), logged)
    }

    @Test
    fun `a transport refusal is logged by code, with nothing else`() {
        val store = PhoneLauncherFavouritesStore(MemoryStorage())
        PhoneLauncherFavouritesSync(store, { _, _ -> "NO_LINK" }, { LauncherInteractiveContract.FAVOURITES_VERSION + 1 }, logged::add).push()
        assertEquals(listOf("launcher favourites not sent code=NO_LINK"), logged)
    }

    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }
}
