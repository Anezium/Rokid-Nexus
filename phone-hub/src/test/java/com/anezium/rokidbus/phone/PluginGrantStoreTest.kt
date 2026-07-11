package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginGrantStoreTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private fun principal(
        packageName: String = "dev.example.hello",
        pluginId: String = "hello",
        digest: String = "abc123",
        capabilities: Set<PluginCapability> = setOf(PluginCapability.SURFACES, PluginCapability.HTTP_PROXY),
    ) = PhonePluginPrincipal(
        packageName = packageName,
        serviceComponent = ComponentName(packageName, "$packageName.Service"),
        uid = 10001,
        signingDigestSha256 = digest,
        descriptor = PluginDescriptor(
            id = pluginId,
            displayName = "Hello",
            apiVersion = 3,
            requestedCapabilities = capabilities,
            receivePrefixes = listOf("/plugin/$pluginId", "/system/plugin"),
            settingsActivity = null,
            launchable = true,
        ),
    )

    @Test
    fun `fresh principal is pending and partial approval is granular`() {
        val store = PluginGrantStore(MemoryStorage())
        val principal = principal()
        assertEquals(PluginGrantState.Pending, store.stateFor(principal))
        store.approve(principal, setOf(PluginCapability.SURFACES))
        assertEquals(
            PluginGrantState.Approved(setOf(PluginCapability.SURFACES)),
            store.stateFor(principal),
        )
    }

    @Test
    fun `certificate or descriptor identity change returns pending`() {
        val store = PluginGrantStore(MemoryStorage())
        val original = principal()
        store.approve(original, setOf(PluginCapability.SURFACES))
        assertEquals(PluginGrantState.Pending, store.stateFor(principal(digest = "rotated")))
        assertEquals(PluginGrantState.Pending, store.stateFor(principal(pluginId = "renamed")))
    }

    @Test
    fun `capability expansion invalidates current decision`() {
        val store = PluginGrantStore(MemoryStorage())
        val original = principal(capabilities = setOf(PluginCapability.SURFACES))
        store.approve(original, setOf(PluginCapability.SURFACES))
        assertEquals(
            PluginGrantState.Pending,
            store.stateFor(principal(capabilities = setOf(PluginCapability.SURFACES, PluginCapability.HTTP_PROXY))),
        )
    }

    @Test
    fun `deny disable and revoke fail closed`() {
        val store = PluginGrantStore(MemoryStorage())
        val principal = principal()
        store.deny(principal)
        assertEquals(PluginGrantState.Denied, store.stateFor(principal))
        store.approve(principal, setOf(PluginCapability.SURFACES))
        store.setEnabled(principal, false)
        assertEquals(PluginGrantState.Disabled, store.stateFor(principal))
        store.revoke(principal)
        assertEquals(PluginGrantState.Pending, store.stateFor(principal))
    }

    @Test
    fun `uninstall reconciliation removes stale grants`() {
        val store = PluginGrantStore(MemoryStorage())
        val keep = principal(packageName = "dev.keep", pluginId = "keep")
        val remove = principal(packageName = "dev.remove", pluginId = "remove")
        store.approve(keep, setOf(PluginCapability.SURFACES))
        store.approve(remove, setOf(PluginCapability.SURFACES))
        store.reconcile(listOf(keep))
        assertEquals(PluginGrantState.Approved(setOf(PluginCapability.SURFACES)), store.stateFor(keep))
        assertEquals(PluginGrantState.Pending, store.stateFor(remove))
    }

    @Test
    fun `serialization is deterministic and rejects corrupt records`() {
        val first = principal(packageName = "dev.z", pluginId = "zulu")
        val second = principal(packageName = "dev.a", pluginId = "alpha")
        val grants = listOf(
            PluginGrant(first.grantKey(), first.descriptor.requestedCapabilities, setOf(PluginCapability.SURFACES), PluginGrantDecision.APPROVED, true),
            PluginGrant(second.grantKey(), second.descriptor.requestedCapabilities, emptySet(), PluginGrantDecision.DENIED, false),
        )
        assertEquals(PluginGrantCodec.encode(grants), PluginGrantCodec.encode(grants.reversed()))
        assertEquals(grants.sortedBy { it.key.packageName }, PluginGrantCodec.decode(PluginGrantCodec.encode(grants)))
        assertTrue(PluginGrantCodec.decode("not-json").isEmpty())
    }
}
