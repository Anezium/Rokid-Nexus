package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.AmbientPreferencesContract
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.AmbientSizeEdit
import com.anezium.rokidbus.shared.ForegroundAmbientMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneAmbientPreferencesTest {
    private val storage = MemoryStorage()
    private val store = PhoneAmbientPreferencesStore(storage)
    private val sent = mutableListOf<Pair<String, JSONObject>>()
    private var launcherActive = true
    private var hidden = emptySet<String>()
    private val sync = PhoneAmbientSync(
        store = store,
        send = { path, payload -> sent += path to payload; null },
        launcherActive = { launcherActive },
        log = {},
        initialRevision = 1_000L,
        hiddenOwners = { hidden },
    )

    @Test
    fun `sizes are set, cleared and survive a new store over the same storage`() {
        assertTrue(store.setSize("relay", AmbientSize.PANEL))
        assertTrue(store.setSize("navigation", AmbientSize.MINIMAP))
        assertFalse(store.setSize("relay", AmbientSize.PANEL))
        assertTrue(store.setSize("navigation", null))

        val reloaded = PhoneAmbientPreferencesStore(storage)
        assertEquals(mapOf("relay" to AmbientSize.PANEL), reloaded.sizes())
        assertNull(reloaded.sizeFor("navigation"))
    }

    @Test
    fun `app modes are set, cleared and survive a new store`() {
        assertTrue(store.setMode("com.google.android.youtube", ForegroundAmbientMode.NOTHING))
        assertTrue(store.setMode("org.osmand.plus", ForegroundAmbientMode.RESIDENTS))
        assertTrue(store.setMode("org.osmand.plus", null))

        assertEquals(
            mapOf("com.google.android.youtube" to ForegroundAmbientMode.NOTHING),
            PhoneAmbientPreferencesStore(storage).modes(),
        )
    }

    @Test
    fun `invalid owners and packages are refused`() {
        assertFalse(store.setSize("Not An Id", AmbientSize.TEXT))
        assertFalse(store.setMode("notapackage", ForegroundAmbientMode.SIGNALS))
        assertTrue(store.sizes().isEmpty())
        assertTrue(store.modes().isEmpty())
    }

    @Test
    fun `a full size map refuses a new owner but still updates and clears existing ones`() {
        repeat(AmbientPreferencesContract.MAX_SIZE_ENTRIES) { index ->
            assertTrue(store.setSize("plugin$index", AmbientSize.TEXT))
        }

        assertFalse(store.setSize("one-more", AmbientSize.TEXT))
        assertTrue(store.setSize("plugin0", AmbientSize.PANEL))
        assertTrue(store.setSize("plugin1", null))
        assertTrue(store.setSize("one-more", AmbientSize.TEXT))
        assertEquals(AmbientPreferencesContract.MAX_SIZE_ENTRIES, store.sizes().size)
    }

    @Test
    fun `unreadable or unknown stored values read as defaults`() {
        storage.value = JSONObject()
            .put("version", 1)
            .put("sizes", JSONObject().put("relay", "giant").put("media", "panel"))
            .put("modes", JSONObject().put("com.example.app", "sometimes").put("com.example.other", "signals"))
            .toString()

        val reloaded = PhoneAmbientPreferencesStore(storage)
        assertEquals(mapOf("media" to AmbientSize.PANEL), reloaded.sizes())
        assertEquals(mapOf("com.example.other" to ForegroundAmbientMode.SIGNALS), reloaded.modes())
        storage.value = "not json"
        assertTrue(PhoneAmbientPreferencesStore(storage).sizes().isEmpty())
    }

    @Test
    fun `a glasses size edit is stored and answered with the full map`() {
        store.setSize("media", AmbientSize.TEXT)
        sync.handleSizeSet(AmbientPreferencesContract.sizeEditPayload(AmbientSizeEdit("relay", AmbientSize.PANEL)))

        assertEquals(AmbientSize.PANEL, store.sizeFor("relay"))
        val (path, payload) = sent.single()
        assertEquals(AmbientPreferencesContract.SIZES_PATH, path)
        val sizes = AmbientPreferencesContract.parseSizes(payload)!!
        assertEquals(mapOf("media" to AmbientSize.TEXT, "relay" to AmbientSize.PANEL), sizes.sizes)
    }

    @Test
    fun `a null size edit returns the owner to automatic`() {
        store.setSize("relay", AmbientSize.PANEL)
        sync.handleSizeSet(AmbientPreferencesContract.sizeEditPayload(AmbientSizeEdit("relay", null)))

        assertNull(store.sizeFor("relay"))
        assertTrue(AmbientPreferencesContract.parseSizes(sent.single().second)!!.sizes.isEmpty())
    }

    @Test
    fun `a refused edit is still answered with the map, so the glasses undo their local guess`() {
        repeat(AmbientPreferencesContract.MAX_SIZE_ENTRIES) { store.setSize("plugin$it", AmbientSize.TEXT) }
        sync.handleSizeSet(AmbientPreferencesContract.sizeEditPayload(AmbientSizeEdit("late", AmbientSize.PANEL)))

        val sizes = AmbientPreferencesContract.parseSizes(sent.single().second)!!
        assertFalse("late" in sizes.sizes)
    }

    @Test
    fun `malformed edits change nothing and send nothing`() {
        sync.handleSizeSet(JSONObject().put("ownerPluginId", "relay").put("size", "huge"))
        sync.handleSizeSet(JSONObject().put("ownerPluginId", "relay"))

        assertTrue(store.sizes().isEmpty())
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `announce sends both full maps and every send carries a higher revision`() {
        store.setMode("com.google.android.youtube", ForegroundAmbientMode.NOTHING)
        sync.onGlassesAnnounced()
        sync.pushSizes()
        sync.pushPolicy()

        assertEquals(
            listOf(
                AmbientPreferencesContract.SIZES_PATH,
                AmbientPreferencesContract.POLICY_PATH,
                AmbientPreferencesContract.SIZES_PATH,
                AmbientPreferencesContract.POLICY_PATH,
            ),
            sent.map { it.first },
        )
        val revisions = sent.map { it.second.getLong("revision") }
        assertEquals(revisions.sorted().distinct(), revisions)
        assertTrue(revisions.first() > 1_000L)
        val policy = AmbientPreferencesContract.parsePolicy(sent[1].second)!!
        assertEquals(mapOf("com.google.android.youtube" to ForegroundAmbientMode.NOTHING), policy.modes)
    }

    @Test
    fun `a restarted hub starts above the revisions it sent before`() {
        sync.pushSizes()
        val before = sent.last().second.getLong("revision")
        val restarted = PhoneAmbientSync(
            store = store,
            send = { path, payload -> sent += path to payload; null },
            launcherActive = { true },
            log = {},
            initialRevision = 5_000L,
        )
        restarted.pushSizes()

        assertTrue(sent.last().second.getLong("revision") > before)
    }

    @Test
    fun `nothing goes to glasses without the launcher bundle, and edits from them are still stored`() {
        launcherActive = false
        sync.onGlassesAnnounced()
        sync.handleSizeSet(AmbientPreferencesContract.sizeEditPayload(AmbientSizeEdit("relay", AmbientSize.TEXT)))

        assertTrue(sent.isEmpty())
        assertEquals(AmbientSize.TEXT, store.sizeFor("relay"))
    }

    @Test
    fun `the plugins kept off the glasses ride every policy, on announce and on change`() {
        hidden = setOf("nav")
        sync.onGlassesAnnounced()
        assertEquals(setOf("nav"), AmbientPreferencesContract.parsePolicy(sent.last().second)!!.hiddenOwners)

        hidden = setOf("nav", "media")
        sync.pushPolicy()
        assertEquals(setOf("media", "nav"), AmbientPreferencesContract.parsePolicy(sent.last().second)!!.hiddenOwners)

        // Back on, or its grant gone: the next policy names nobody, so the glasses show it again.
        hidden = emptySet()
        sync.pushPolicy()
        assertEquals(emptySet<String>(), AmbientPreferencesContract.parsePolicy(sent.last().second)!!.hiddenOwners)

        launcherActive = false
        hidden = setOf("nav")
        val before = sent.size
        sync.onGlassesAnnounced()
        assertEquals(before, sent.size)
    }

    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null

        override fun read(): String? = value

        override fun write(value: String) {
            this.value = value
        }
    }
}
