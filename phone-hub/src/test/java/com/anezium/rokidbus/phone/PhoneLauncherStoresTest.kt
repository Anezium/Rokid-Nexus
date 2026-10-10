package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's per-plugin launcher choices: quick approvals, activity preferences, offline snapshots. */
class PhoneLauncherStoresTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    private fun principal(id: String, families: Set<QuickActionFamily>, digest: String = "digest-$id") = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10_000 + id.length,
        signingDigestSha256 = digest,
        descriptor = PluginDescriptor(
            id, id, 3, setOf(PluginCapability.SURFACES), listOf("/plugin/$id"), null, true,
            quickActionFamilies = families,
        ),
    )

    @Test
    fun `nothing is approved by default and only a declared family can be`() {
        val store = PluginQuickActionPolicyStore(MemoryStorage())
        val todo = principal("todo", setOf(QuickActionFamily.CHECKLIST))
        assertTrue(store.effectiveFamilies(todo).isEmpty())
        assertFalse(store.setApproved(todo, QuickActionFamily.MEDIA, true))
        assertTrue(store.setApproved(todo, QuickActionFamily.CHECKLIST, true))
        assertEquals(setOf(QuickActionFamily.CHECKLIST), store.effectiveFamilies(todo))
        assertFalse(store.setApproved(todo, QuickActionFamily.CHECKLIST, true))
    }

    @Test
    fun `a family dropped and declared again is never grandfathered`() {
        val storage = MemoryStorage()
        val store = PluginQuickActionPolicyStore(storage)
        val withMedia = principal("deck", setOf(QuickActionFamily.MEDIA))
        store.setApproved(withMedia, QuickActionFamily.MEDIA, true)

        // An update stops declaring it: it no longer counts, and the next reconcile forgets it.
        val without = principal("deck", emptySet())
        assertTrue(store.effectiveFamilies(without).isEmpty())
        assertTrue(store.retainOnly(listOf(without)))
        assertTrue(store.effectiveFamilies(withMedia).isEmpty())
        assertTrue(PluginQuickActionPolicyStore(storage).approvedFamilies(withMedia.grantKey()).isEmpty())
    }

    @Test
    fun `revoke, uninstall and a signer change purge the approvals`() {
        val storage = MemoryStorage()
        val store = PluginQuickActionPolicyStore(storage)
        val deck = principal("deck", setOf(QuickActionFamily.MEDIA))
        store.setApproved(deck, QuickActionFamily.MEDIA, true)
        assertEquals(setOf(QuickActionFamily.MEDIA), PluginQuickActionPolicyStore(storage).approvedFamilies(deck.grantKey()))

        val resigned = principal("deck", setOf(QuickActionFamily.MEDIA), digest = "another-signer")
        assertTrue(store.effectiveFamilies(resigned).isEmpty())
        assertTrue(store.retainOnly(listOf(resigned)))
        assertTrue(store.approvedFamilies(deck.grantKey()).isEmpty())

        store.setApproved(resigned, QuickActionFamily.MEDIA, true)
        assertTrue(store.purge(resigned.grantKey()))
        assertTrue(store.effectiveFamilies(resigned).isEmpty())
        assertFalse(store.purge(resigned.grantKey()))
    }

    @Test
    fun `activity preferences default to shown and unpinned and are purged with the grant`() {
        val storage = MemoryStorage()
        val preferences = PhoneActivityPreferences(storage)
        val key = PluginGrantKey("dev.example.nav", "nav", "digest-nav")
        assertEquals(PhoneActivityPreference(enabled = true, pinned = false), preferences.preferenceFor(key))

        preferences.setPinned(key, true)
        preferences.setEnabled(key, false)
        assertEquals(PhoneActivityPreference(enabled = false, pinned = true), PhoneActivityPreferences(storage).preferenceFor(key))

        val other = PluginGrantKey("dev.example.todo", "todo", "digest-todo")
        preferences.setPinned(other, true)
        assertEquals(setOf(key), preferences.retainOnly(setOf(other)))
        assertEquals(PhoneActivityPreference.DEFAULT, preferences.preferenceFor(key))
        preferences.purge(other)
        assertEquals(PhoneActivityPreference.DEFAULT, PhoneActivityPreferences(storage).preferenceFor(other))

        // Defaults leave nothing behind.
        preferences.setPinned(other, true)
        preferences.setPinned(other, false)
        assertFalse(storage.value.orEmpty().contains("todo"))
    }

    @Test
    fun `the snapshot store keeps at most eight, the most recently known`() {
        val storage = MemoryStorage()
        val store = PhoneActivitySnapshotStore(storage)
        val snapshots = (0 until 10).map { index ->
            PhoneActivitySnapshot(
                PluginGrantKey("dev.example.p$index", "act$index", "digest"),
                JSONObject().put("ownerPluginId", "act$index").put("knownAtWallMs", 1_000L + index),
            )
        }
        store.write(snapshots)
        val loaded = store.load()
        assertEquals(ActivitySurfaceContract.MAX_OFFLINE_SNAPSHOTS, loaded.size)
        assertEquals((2 until 10).map { "act$it" }.toSet(), loaded.map { it.key.pluginId }.toSet())

        storage.value = "not json"
        assertTrue(store.load().isEmpty())
    }

    @Test
    fun `only an explicit launcher choice is announced`() {
        assertEquals(PhoneLauncherBackendChoice.DEFAULT, PhoneLauncherBackendChoice.fromStored(null))
        assertEquals(PhoneLauncherBackendChoice.DEFAULT, PhoneLauncherBackendChoice.fromStored("something"))
        assertEquals(PhoneLauncherBackendChoice.SESSION, PhoneLauncherBackendChoice.fromStored("session"))
        assertEquals(PhoneLauncherBackendChoice.LEGACY, PhoneLauncherBackendChoice.fromStored("legacy"))
        assertEquals(null, PhoneLauncherBackendChoice.DEFAULT.wireValue)
    }
}
