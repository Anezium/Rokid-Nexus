package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LauncherFavouritesContractTest {
    private val list = LauncherFavourites(
        revision = 3,
        entries = listOf(LauncherFavourite.Plugin("meta-messages"), LauncherFavourite.NativeApp("com.android.camera")),
    )

    @Test
    fun `the bundle version announces favourites`() {
        assertEquals(8, LauncherInteractiveContract.FAVOURITES_VERSION)
        assertEquals(LauncherInteractiveContract.FAVOURITES_VERSION + 1, LauncherInteractiveContract.ROW_SECTIONS_VERSION)
        assertEquals("/core/launcher/favourites", BusPaths.CORE_LAUNCHER_FAVOURITES)
    }

    @Test
    fun `a list round-trips in its order`() {
        val parsed = LauncherFavouritesContract.parse(LauncherFavouritesContract.toPayload(list))
        assertEquals(list, parsed)
        assertEquals(listOf("plugin:meta-messages", "app:com.android.camera"), parsed!!.entries.map { it.key })
    }

    @Test
    fun `an empty list is valid`() {
        assertEquals(emptyList<LauncherFavourite>(), LauncherFavouritesContract.parse(LauncherFavouritesContract.toPayload(LauncherFavourites(0, emptyList())))!!.entries)
    }

    @Test
    fun `a malformed list is refused whole, never truncated`() {
        val good = LauncherFavouritesContract.toPayload(list)
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("version", 2)))
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("revision", -1)))
        val five = JSONArray().apply { repeat(5) { put(JSONObject().put("kind", "plugin").put("id", "p$it")) } }
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("entries", five)))
        val twice = JSONArray().put(JSONObject().put("kind", "plugin").put("id", "relay")).put(JSONObject().put("kind", "plugin").put("id", "relay"))
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("entries", twice)))
        val unknown = JSONArray().put(JSONObject().put("kind", "shortcut").put("id", "relay"))
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("entries", unknown)))
        val badPackage = JSONArray().put(JSONObject().put("kind", "app").put("package", "not a package"))
        assertNull(LauncherFavouritesContract.parse(JSONObject(good.toString()).put("entries", badPackage)))
    }

    @Test
    fun `catalog keys name their entry`() {
        assertEquals(LauncherFavourite.Plugin("relay"), LauncherFavouritesContract.fromKey("plugin:relay"))
        assertEquals(LauncherFavourite.NativeApp("com.android.camera"), LauncherFavouritesContract.fromKey("app:com.android.camera"))
        assertNull(LauncherFavouritesContract.fromKey("nexus:applications"))
    }
}
