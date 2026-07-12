package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Intent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginPostInstallCoordinatorTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    @Test
    fun `successful install refreshes and hands off exact pending principal`() {
        val principal = principal()
        var refreshCount = 0
        val coordinator = PluginPostInstallCoordinator(
            discoverPackage = { listOf(PhonePluginCandidate.Valid(principal)) },
            grantState = { PluginGrantState.Pending },
            refreshCatalog = { refreshCount++ },
        )

        val result = coordinator.onInstalled(PACKAGE, "feeds") as PluginPostInstallResult.Ready

        assertEquals(1, refreshCount)
        assertEquals(PluginGrantTarget(PACKAGE, "feeds"), result.target)
        assertTrue(result.target.matches(result.principal))
        assertEquals(PluginGrantState.Pending, result.grantState)
    }

    @Test
    fun `same-signer update keeps approved grant`() {
        val storage = MemoryStorage()
        val store = PluginGrantStore(storage)
        val beforeUpdate = principal(uid = 10001)
        store.approve(beforeUpdate, setOf(PluginCapability.SURFACES))
        val afterUpdate = principal(uid = 10044)
        val coordinator = coordinator(afterUpdate, store)

        val result = coordinator.onInstalled(PACKAGE, "feeds") as PluginPostInstallResult.Ready

        assertEquals(PluginGrantState.Approved(setOf(PluginCapability.SURFACES)), result.grantState)
        assertEquals(PluginGrantState.Approved(setOf(PluginCapability.SURFACES)), store.stateFor(afterUpdate))
    }

    @Test
    fun `update does not silently enable disabled or revoked grant`() {
        val disabledStorage = MemoryStorage()
        val disabledStore = PluginGrantStore(disabledStorage)
        val principal = principal()
        disabledStore.approve(principal, setOf(PluginCapability.SURFACES))
        disabledStore.setEnabled(principal, false)

        val disabled = coordinator(principal, disabledStore)
            .onInstalled(PACKAGE, "feeds") as PluginPostInstallResult.Ready
        assertEquals(PluginGrantState.Disabled, disabled.grantState)

        disabledStore.revoke(principal)
        val revoked = coordinator(principal, disabledStore)
            .onInstalled(PACKAGE, "feeds") as PluginPostInstallResult.Ready
        assertEquals(PluginGrantState.Pending, revoked.grantState)
    }

    @Test
    fun `identity mismatch fails closed but still refreshes`() {
        var refreshCount = 0
        val wrong = principal(pluginId = "transit")
        val coordinator = PluginPostInstallCoordinator(
            discoverPackage = { listOf(PhonePluginCandidate.Valid(wrong)) },
            grantState = { PluginGrantState.Pending },
            refreshCatalog = { refreshCount++ },
        )

        val result = coordinator.onInstalled(PACKAGE, "feeds")

        assertTrue(result is PluginPostInstallResult.Failure)
        assertEquals(1, refreshCount)
    }

    @Test
    fun `temporary package removal during update does not reconcile grants`() {
        assertFalse(PluginPackageChangePolicy.shouldReconcile(Intent.ACTION_PACKAGE_REMOVED, replacing = true))
        assertTrue(PluginPackageChangePolicy.shouldReconcile(Intent.ACTION_PACKAGE_REMOVED, replacing = false))
        assertTrue(PluginPackageChangePolicy.shouldReconcile(Intent.ACTION_PACKAGE_ADDED, replacing = true))
    }

    private fun coordinator(
        principal: PhonePluginPrincipal,
        store: PluginGrantStore,
    ) = PluginPostInstallCoordinator(
        discoverPackage = { listOf(PhonePluginCandidate.Valid(principal)) },
        grantState = store::stateFor,
        refreshCatalog = {},
    )

    private fun principal(
        uid: Int = 10001,
        pluginId: String = "feeds",
    ) = PhonePluginPrincipal(
        packageName = PACKAGE,
        serviceComponent = ComponentName(PACKAGE, "$PACKAGE.Service"),
        uid = uid,
        signingDigestSha256 = "same-signer",
        descriptor = PluginDescriptor(
            id = pluginId,
            displayName = "Feeds",
            apiVersion = 3,
            requestedCapabilities = setOf(PluginCapability.SURFACES),
            receivePrefixes = listOf("/plugin/$pluginId"),
            settingsActivity = null,
            launchable = true,
        ),
    )

    companion object {
        private const val PACKAGE = "com.anezium.rokidbus.plugin.feeds"
    }
}
