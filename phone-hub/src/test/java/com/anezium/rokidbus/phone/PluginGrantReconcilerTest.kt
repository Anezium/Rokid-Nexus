package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginGrantReconcilerTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) {
            this.value = value
        }
    }

    @Test
    fun `startup reconciliation removes grant for plugin that is no longer installed`() {
        val store = PluginGrantStore(MemoryStorage())
        val uninstalled = principal()
        store.approve(uninstalled, setOf(PluginCapability.SURFACES))

        reconciler(store, emptyList()).reconcile()

        assertTrue(store.all().isEmpty())
        assertEquals(PluginGrantState.Pending, store.stateFor(uninstalled))
    }

    @Test
    fun `startup reconciliation keeps grant for exact installed principal`() {
        val store = PluginGrantStore(MemoryStorage())
        val installed = principal()
        store.approve(installed, setOf(PluginCapability.SURFACES))

        reconciler(store, listOf(PhonePluginCandidate.Valid(installed))).reconcile()

        assertEquals(
            PluginGrantState.Approved(setOf(PluginCapability.SURFACES)),
            store.stateFor(installed),
        )
    }

    @Test
    fun `startup reconciliation leaves capability change pending logic untouched`() {
        val store = PluginGrantStore(MemoryStorage())
        val approved = principal(capabilities = setOf(PluginCapability.SURFACES))
        val changed = principal(
            capabilities = setOf(PluginCapability.SURFACES, PluginCapability.HTTP_PROXY),
        )
        store.approve(approved, setOf(PluginCapability.SURFACES))

        reconciler(store, listOf(PhonePluginCandidate.Valid(changed))).reconcile()

        assertEquals(PluginGrantState.Pending, store.stateFor(changed))
        assertEquals(
            listOf(approved.descriptor.requestedCapabilities),
            store.all().map { it.requestedCapabilities },
        )
    }

    private fun reconciler(
        store: PluginGrantStore,
        candidates: List<PhonePluginCandidate>,
    ) = PluginGrantReconciler(
        discoverCandidates = { candidates },
        reconcileGrants = store::reconcile,
    )

    private fun principal(
        capabilities: Set<PluginCapability> = setOf(PluginCapability.SURFACES),
    ) = PhonePluginPrincipal(
        packageName = "dev.example.plugin",
        serviceComponent = ComponentName("dev.example.plugin", "dev.example.plugin.Service"),
        uid = 10001,
        signingDigestSha256 = "abc123",
        descriptor = PluginDescriptor(
            id = "example",
            displayName = "Example",
            apiVersion = 3,
            requestedCapabilities = capabilities,
            receivePrefixes = listOf("/plugin/example"),
            settingsActivity = null,
            launchable = true,
        ),
    )
}
