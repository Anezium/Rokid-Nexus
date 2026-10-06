package com.anezium.rokidbus.phone

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.anezium.rokidbus.shared.YoutubePatcherContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class YoutubePatcherHandoffTest {
    @Test fun `handoff uses only the explicit patch component and targeted Store entry`() {
        val intent = YoutubePatcherHandoff.patchIntent()
        assertEquals(YoutubePatcherContract.ACTION_PATCH, intent.action)
        assertEquals(YoutubePatcherContract.PACKAGE, intent.component!!.packageName)
        assertEquals(YoutubePatcherContract.PATCH_ACTIVITY, intent.component!!.className)
        val store = YoutubePatcherHandoff.storeIntent(RuntimeEnvironment.getApplication())
        assertEquals(StorePluginDetailActivity::class.java.name, store.component!!.className)
        assertEquals(YoutubePatcherContract.PLUGIN_ID, store.getStringExtra("plugin_id"))
    }

    private class MemoryStorage : PluginGrantStorage {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun principal(cert: ByteArray = byteArrayOf(1, 2, 3)) = PhonePluginPrincipal(
        packageName = YoutubePatcherContract.PACKAGE,
        serviceComponent = android.content.ComponentName(YoutubePatcherContract.PACKAGE, "PatcherService"),
        uid = 10001,
        signingDigestSha256 = signingCertificateSha256(cert),
        descriptor = com.anezium.rokidbus.shared.plugin.PluginDescriptor(
            id = YoutubePatcherContract.PLUGIN_ID, displayName = "YouTube Patcher", apiVersion = 3,
            requestedCapabilities = emptySet(), receivePrefixes = emptyList(),
            settingsActivity = null, launchable = true,
        ),
    )

    private fun approved(principal: PhonePluginPrincipal, store: PluginGrantStore) =
        YoutubePatcherHandoff.approvedPrincipal(listOf(PhonePluginCandidate.Valid(principal)), store::stateFor)

    private fun install(cert: ByteArray = byteArrayOf(1, 2, 3), updateTime: Long = 1): ActivityInfo {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val signing = android.content.pm.SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(android.content.pm.Signature(cert)))
        val app = ApplicationInfo().apply {
            packageName = YoutubePatcherContract.PACKAGE
            uid = 10001
            enabled = true
        }
        shadowOf(manager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = YoutubePatcherContract.PACKAGE
            applicationInfo = app
            signingInfo = signing
            lastUpdateTime = updateTime
        })
        return ActivityInfo().apply {
            packageName = YoutubePatcherContract.PACKAGE
            name = YoutubePatcherContract.PATCH_ACTIVITY
            exported = true
            enabled = true
            applicationInfo = app
            shadowOf(manager).addOrUpdateActivity(this)
        }
    }

    @Test fun `activity presence alone cannot authorize automatic install`() {
        val context = RuntimeEnvironment.getApplication()
        install()
        context.getSharedPreferences("nexus_plugin_grants", 0).edit().clear().commit()
        assertNull(YoutubePatcherHandoff.authenticatedIdentity(context))
        assertEquals(PluginPermissionsActivity::class.java.name,
            YoutubePatcherHandoff.reviewIntent(context).component!!.className)
        val store = PluginGrantStore(MemoryStorage())
        assertNull(approved(principal(), store))
        store.approve(principal(), emptySet())
        assertEquals(principal(), approved(principal(), store))
    }

    @Test fun `same package impostor signer cannot inherit existing approval`() {
        val store = PluginGrantStore(MemoryStorage())
        val trusted = principal()
        store.approve(trusted, emptySet())
        val impostor = principal(byteArrayOf(9, 8, 7))
        assertEquals(trusted.packageName, impostor.packageName)
        assertNull(approved(impostor, store))
        install(byteArrayOf(9, 8, 7))
        assertNull(YoutubePatcherHandoff.identity(RuntimeEnvironment.getApplication().packageManager, trusted))
    }

    @Test fun `replacement between launch and result rejects even separately approved signer`() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val store = PluginGrantStore(MemoryStorage())
        val original = principal()
        store.approve(original, emptySet())
        install()
        val launched = YoutubePatcherHandoff.identity(manager, approved(original, store)!!)
        assertNotNull(launched)
        assertTrue(YoutubePatcherHandoff.acceptsResult(launched, YoutubePatcherHandoff.identity(manager, original)))
        val replacement = principal(byteArrayOf(9, 8, 7))
        store.approve(replacement, emptySet())
        install(byteArrayOf(9, 8, 7), 2)
        assertFalse(YoutubePatcherHandoff.acceptsResult(launched,
            YoutubePatcherHandoff.identity(manager, approved(replacement, store)!!)))
        install(updateTime = 3)
        assertFalse(YoutubePatcherHandoff.acceptsResult(launched, YoutubePatcherHandoff.identity(manager, original)))
        assertFalse(YoutubePatcherHandoff.acceptsResult(null, null))
    }

    @Test fun `revocation disabled denial invalid descriptor and wrong plugin id fail closed`() {
        val store = PluginGrantStore(MemoryStorage())
        val original = principal()
        store.approve(original, emptySet())
        store.setEnabled(original, false)
        assertNull(approved(original, store))
        store.deny(original)
        assertNull(approved(original, store))
        store.approve(original, emptySet())
        store.revoke(original)
        assertNull(approved(original, store))
        store.approve(original, emptySet())
        val renamed = original.copy(descriptor = original.descriptor.copy(id = "impostor"))
        store.approve(renamed, emptySet())
        assertNull(approved(renamed, store))
        assertNull(YoutubePatcherHandoff.approvedPrincipal(listOf(PhonePluginCandidate.Invalid(
            original.packageName, "Invalid", original.serviceComponent, "MULTIPLE_PLUGIN_SERVICES")), store::stateFor))
    }

    @Test fun `non exported disabled or different uid activity cannot authenticate`() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val info = install()
        assertNotNull(YoutubePatcherHandoff.identity(manager, principal()))
        info.exported = false
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(YoutubePatcherHandoff.identity(manager, principal()))
        info.exported = true
        info.enabled = false
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(YoutubePatcherHandoff.identity(manager, principal()))
        info.enabled = true
        info.applicationInfo.uid = 20002
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(YoutubePatcherHandoff.identity(manager, principal()))
    }

    @Test fun `result must have content URI and read grant and metadata has no authority`() {
        assertNull(YoutubePatcherHandoff.resultUri(null))
        val result = Intent().setData(Uri.parse("file:///output.apk"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(YoutubePatcherHandoff.resultUri(result))
        result.data = Uri.parse("content://patcher/output.apk")
        result.flags = 0
        assertNull(YoutubePatcherHandoff.resultUri(result))
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        result.putExtra(YoutubePatcherContract.EXTRA_PACKAGE_NAME, "forged.package")
        result.putExtra(YoutubePatcherContract.EXTRA_SHA256, "forged")
        assertEquals(result.data, YoutubePatcherHandoff.resultUri(result))
    }

    @Test fun `manual Done defaults false persists across instances and can be reset`() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("youtube_setup_checklist", 0).edit().clear().commit()
        val first = YoutubeSetupChecklist(context)
        assertFalse(first.signInDone)
        first.signInDone = true
        assertTrue(YoutubeSetupChecklist(context).signInDone)
        YoutubeSetupChecklist(context).signInDone = false
        assertFalse(first.signInDone)
    }
}
