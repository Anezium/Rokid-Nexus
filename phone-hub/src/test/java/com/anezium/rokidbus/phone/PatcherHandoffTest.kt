package com.anezium.rokidbus.phone

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatcherHandoffTest {
    @Test fun `hub return token targets the non-exported waiting setup without clearing its task`() {
        val screen = Robolectric.buildActivity(YoutubeSetupActivity::class.java).create()
        val request = PatcherHandoff.patchIntent(screen.get())
        val token = requireNotNull(request.getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        assertEquals(screen.get().packageName, token.creatorPackage)
        assertTrue(token.isActivity)
        assertTrue(token.isImmutable)
        assertTrue(shadowOf(token).flags and PendingIntent.FLAG_ONE_SHOT != 0)
        val returnIntent = shadowOf(token).savedIntent
        assertEquals(ComponentName(screen.get(), YoutubeSetupActivity::class.java), returnIntent.component)
        assertFalse(screen.get().packageManager.getActivityInfo(returnIntent.component!!, 0).exported)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, returnIntent.flags)
        assertNull(returnIntent.action)
        assertTrue(returnIntent.categories.isNullOrEmpty())
        // An immutable token cannot be redirected by the receiving app.
        token.send(screen.get(), 0, Intent(Intent.ACTION_MAIN).setClassName("untrusted.app", "Other"))
        assertEquals(returnIntent.component, shadowOf(screen.get()).nextStartedActivity.component)
        assertThrows(PendingIntent.CanceledException::class.java) { token.send() }
        screen.destroy()
    }

    @Test fun `an unused one-shot return token survives setup recreation and a later patch gets a fresh token`() {
        val first = Robolectric.buildActivity(YoutubeSetupActivity::class.java).create()
        val token = requireNotNull(PatcherHandoff.patchIntent(first.get())
            .getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        val saved = android.os.Bundle()
        first.saveInstanceState(saved).destroy()
        val recreated = Robolectric.buildActivity(YoutubeSetupActivity::class.java).create(saved)
        val restoredToken = requireNotNull(PatcherHandoff.patchIntent(recreated.get())
            .getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        assertEquals(token, restoredToken)
        restoredToken.send(recreated.get(), 0, null)
        assertEquals(ComponentName(recreated.get(), YoutubeSetupActivity::class.java),
            shadowOf(recreated.get()).nextStartedActivity.component)
        assertThrows(PendingIntent.CanceledException::class.java) { token.send() }
        val nextToken = requireNotNull(PatcherHandoff.patchIntent(recreated.get())
            .getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        assertTrue(shadowOf(token).isCanceled)
        assertFalse(shadowOf(nextToken).isCanceled)
        nextToken.send(recreated.get(), 0, null)
        assertEquals(ComponentName(recreated.get(), YoutubeSetupActivity::class.java),
            shadowOf(recreated.get()).nextStartedActivity.component)
        recreated.destroy()
    }

    @Test fun `handoff uses only the explicit patch component and targeted Store entry`() {
        val intent = PatcherHandoff.patchIntent()
        assertEquals(PatcherContract.ACTION_PATCH, intent.action)
        assertEquals(PatcherContract.TARGET_YOUTUBE, intent.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        assertEquals(PatcherContract.PACKAGE, intent.component!!.packageName)
        assertEquals(PatcherContract.PATCH_ACTIVITY, intent.component!!.className)
        val store = PatcherHandoff.storeIntent(RuntimeEnvironment.getApplication())
        assertEquals(StorePluginDetailActivity::class.java.name, store.component!!.className)
        assertEquals(PatcherContract.PLUGIN_ID, store.getStringExtra("plugin_id"))
    }

    private class MemoryStorage : PluginGrantStorage {
        private var value: String? = null
        override fun read() = value
        override fun write(value: String) { this.value = value }
    }

    private fun principal(cert: ByteArray = byteArrayOf(1, 2, 3)) = PhonePluginPrincipal(
        packageName = PatcherContract.PACKAGE,
        serviceComponent = android.content.ComponentName(PatcherContract.PACKAGE, "PatcherService"),
        uid = 10001,
        signingDigestSha256 = signingCertificateSha256(cert),
        descriptor = com.anezium.rokidbus.shared.plugin.PluginDescriptor(
            id = PatcherContract.PLUGIN_ID, displayName = "Patcher", apiVersion = 3,
            requestedCapabilities = emptySet(), receivePrefixes = emptyList(),
            settingsActivity = null, launchable = true,
        ),
    )

    private fun approved(principal: PhonePluginPrincipal, store: PluginGrantStore) =
        PatcherHandoff.approvedPrincipal(listOf(PhonePluginCandidate.Valid(principal)), store::stateFor)

    private fun install(cert: ByteArray = byteArrayOf(1, 2, 3), updateTime: Long = 1): ActivityInfo {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val signing = android.content.pm.SigningInfo()
        shadowOf(signing).setSignatures(arrayOf(android.content.pm.Signature(cert)))
        val app = ApplicationInfo().apply {
            packageName = PatcherContract.PACKAGE
            uid = 10001
            enabled = true
        }
        shadowOf(manager).installPackage(android.content.pm.PackageInfo().apply {
            packageName = PatcherContract.PACKAGE
            applicationInfo = app
            signingInfo = signing
            lastUpdateTime = updateTime
        })
        return ActivityInfo().apply {
            packageName = PatcherContract.PACKAGE
            name = PatcherContract.PATCH_ACTIVITY
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
        assertNull(PatcherHandoff.authenticatedIdentity(context))
        assertEquals(PluginPermissionsActivity::class.java.name,
            PatcherHandoff.reviewIntent(context).component!!.className)
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
        assertNull(PatcherHandoff.identity(RuntimeEnvironment.getApplication().packageManager, trusted))
    }

    @Test fun `replacement between launch and result rejects even separately approved signer`() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val store = PluginGrantStore(MemoryStorage())
        val original = principal()
        store.approve(original, emptySet())
        install()
        val launched = PatcherHandoff.identity(manager, approved(original, store)!!)
        assertNotNull(launched)
        assertTrue(PatcherHandoff.acceptsResult(launched, PatcherHandoff.identity(manager, original)))
        val replacement = principal(byteArrayOf(9, 8, 7))
        store.approve(replacement, emptySet())
        install(byteArrayOf(9, 8, 7), 2)
        assertFalse(PatcherHandoff.acceptsResult(launched,
            PatcherHandoff.identity(manager, approved(replacement, store)!!)))
        install(updateTime = 3)
        assertFalse(PatcherHandoff.acceptsResult(launched, PatcherHandoff.identity(manager, original)))
        assertFalse(PatcherHandoff.acceptsResult(null, null))
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
        assertNull(PatcherHandoff.approvedPrincipal(listOf(PhonePluginCandidate.Invalid(
            original.packageName, "Invalid", original.serviceComponent, "MULTIPLE_PLUGIN_SERVICES")), store::stateFor))
    }

    @Test fun `non exported disabled or different uid activity cannot authenticate`() {
        val manager = RuntimeEnvironment.getApplication().packageManager
        val info = install()
        assertNotNull(PatcherHandoff.identity(manager, principal()))
        info.exported = false
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(PatcherHandoff.identity(manager, principal()))
        info.exported = true
        info.enabled = false
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(PatcherHandoff.identity(manager, principal()))
        info.enabled = true
        info.applicationInfo.uid = 20002
        shadowOf(manager).addOrUpdateActivity(info)
        assertNull(PatcherHandoff.identity(manager, principal()))
    }

    @Test fun `result must have content URI and read grant and metadata has no authority`() {
        assertNull(PatcherHandoff.resultUri(null))
        val result = Intent().setData(Uri.parse("file:///output.apk"))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(PatcherHandoff.resultUri(result))
        result.data = Uri.parse("content://patcher/output.apk")
        result.flags = 0
        assertNull(PatcherHandoff.resultUri(result))
        result.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(PatcherHandoff.resultUri(result))
        result.putExtra(PatcherContract.EXTRA_TARGET_ID, "second")
        assertNull(PatcherHandoff.resultUri(result))
        result.putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)
        result.putExtra(PatcherContract.EXTRA_PACKAGE_NAME, "forged.package")
        result.putExtra(PatcherContract.EXTRA_SHA256, "forged")
        assertEquals(result.data, PatcherHandoff.resultUri(result))
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
