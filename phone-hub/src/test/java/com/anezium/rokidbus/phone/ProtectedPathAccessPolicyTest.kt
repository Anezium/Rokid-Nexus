package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtectedPathAccessPolicyTest {
    private class MemoryStorage : PluginGrantStorage {
        var value: String? = null
        override fun read(): String? = value
        override fun write(value: String) { this.value = value }
    }

    private fun principal(
        packageName: String,
        pluginId: String,
        digest: String = "same-signer",
    ) = PhonePluginPrincipal(
        packageName,
        ComponentName(packageName, "$packageName.Service"),
        10001,
        digest,
        PluginDescriptor(
            pluginId,
            pluginId,
            3,
            setOf(PluginCapability.CAMERA),
            listOf("/system/plugin", "/camera/session/state", "/camera/link/offer"),
            null,
            false,
        ),
    )

    @Test
    fun `hub keeps lens and camera access while camera principal needs exact enabled grant`() {
        val store = PluginGrantStore(MemoryStorage())
        val granted = principal("dev.camera", "camera.one")
        val sameSignedButUngranted = principal("dev.other", "camera.other")
        val unsignedOther = principal("dev.unsigned", "camera.unsigned", "other-signer")
        store.approve(granted, setOf(PluginCapability.CAMERA))

        listOf(BusPaths.LENS_LINK_OFFER, BusPaths.CAMERA_SESSION_STATE, BusPaths.CAMERA_OVERLAY)
            .forEach { path ->
                assertTrue(ProtectedPathAccessPolicy.isAllowed(path, true, null, null))
            }
        assertTrue(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.CAMERA_LINK_OFFER,
                false,
                granted,
                store.stateFor(granted),
            ),
        )
        assertFalse(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.LENS_LINK_OFFER,
                false,
                granted,
                store.stateFor(granted),
            ),
        )
        listOf(sameSignedButUngranted, unsignedOther).forEach { ungranted ->
            assertFalse(
                ProtectedPathAccessPolicy.isAllowed(
                    BusPaths.CAMERA_SESSION_STATE,
                    false,
                    ungranted,
                    store.stateFor(ungranted),
                ),
            )
        }
        store.setEnabled(granted, false)
        assertFalse(
            ProtectedPathAccessPolicy.isAllowed(
                BusPaths.CAMERA_OVERLAY,
                false,
                granted,
                store.stateFor(granted),
            ),
        )
        assertTrue(ProtectedPathAccessPolicy.isAllowed("/plugin/camera.one", false, unsignedOther, null))
    }
}
