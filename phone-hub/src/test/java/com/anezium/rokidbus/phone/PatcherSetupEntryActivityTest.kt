package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.os.Bundle
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatcherSetupEntryActivityTest {
    private fun request(target: String = PatcherContract.TARGET_YOUTUBE, hint: String? = PatcherContract.JOB_RUNNING) =
        Intent(PatcherContract.ACTION_OPEN_SETUP)
            .setComponent(ComponentName(PatcherContract.HUB_PACKAGE, PatcherContract.HUB_SETUP_ACTIVITY))
            .putExtra(PatcherContract.EXTRA_TARGET_ID, target)
            .putExtra(PatcherContract.EXTRA_JOB_STATE, hint)

    private fun approvePatcher(): PhonePluginPrincipal {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply { packageName = PatcherContract.PACKAGE; uid = 10001; enabled = true }
        manager.installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            signingInfo = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3)))) }
            lastUpdateTime = 1
        })
        manager.addOrUpdateActivity(ActivityInfo().apply {
            packageName = app.packageName; name = PatcherContract.PATCH_ACTIVITY
            applicationInfo = app; enabled = true; exported = true
        })
        manager.addResolveInfoForIntent(Intent(BusConstants.ACTION_PLUGIN), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = app.packageName; name = "${app.packageName}.PatcherPluginService"
                applicationInfo = app; exported = true
                metaData = Bundle().apply {
                    putString(BusConstants.META_PLUGIN_ID, PatcherContract.PLUGIN_ID)
                    putString(BusConstants.META_PLUGIN_DISPLAY_NAME, "Patcher")
                    putString(BusConstants.META_PLUGIN_API_VERSION, "3")
                    putString(BusConstants.META_PLUGIN_CAPABILITIES, "")
                    putString(BusConstants.META_PLUGIN_RECEIVE_PREFIXES, "/plugin/${PatcherContract.PLUGIN_ID}")
                }
            }
        })
        val principal = (PhonePluginDiscovery(context.packageManager).discover().single() as PhonePluginCandidate.Valid).principal
        PluginGrantStore(context).approve(principal, emptySet())
        return principal
    }

    private fun launch(intent: Intent, caller: String?): Pair<Intent?, Boolean> {
        val screen = Robolectric.buildActivity(PatcherSetupEntryActivity::class.java, intent)
        caller?.let { shadowOf(screen.get()).setCallingPackage(it) }
        screen.create()
        return shadowOf(screen.get()).nextStartedActivity to screen.get().isFinishing
    }

    @Test fun `only the approved Patcher's result-mode call for a hub-owned setup opens it`() {
        val identity = "patcher|identity"
        val good = request()
        assertEquals(PatcherHandoff.SetupEntry.OPEN, PatcherHandoff.setupEntry(good, PatcherContract.PACKAGE, identity))
        assertEquals(PatcherHandoff.SetupEntry.APPROVE, PatcherHandoff.setupEntry(good, PatcherContract.PACKAGE, null))
        // A plain startActivity has no calling package; any other app is refused before a screen opens.
        assertEquals(PatcherHandoff.SetupEntry.REFUSE, PatcherHandoff.setupEntry(good, null, identity))
        assertEquals(PatcherHandoff.SetupEntry.REFUSE, PatcherHandoff.setupEntry(good, "untrusted.app", identity))
        assertEquals(PatcherHandoff.SetupEntry.REFUSE, PatcherHandoff.setupEntry(Intent(good).setAction(Intent.ACTION_VIEW), PatcherContract.PACKAGE, identity))
        assertEquals(PatcherHandoff.SetupEntry.OPEN,
            PatcherHandoff.setupEntry(request(PatcherContract.TARGET_REDDIT), PatcherContract.PACKAGE, identity))
        assertEquals(PatcherHandoff.SetupEntry.REFUSE,
            PatcherHandoff.setupEntry(request(PatcherContract.TARGET_REDDIT), "untrusted.app", identity))
        // Only the fixed allowlist is reachable; unknown, empty and missing ids fail closed.
        assertEquals(PatcherHandoff.SetupEntry.REFUSE,
            PatcherHandoff.setupEntry(Intent(good).apply { removeExtra(PatcherContract.EXTRA_TARGET_ID) }, PatcherContract.PACKAGE, identity))
        for (target in listOf("other", "", "REDDIT", "reddit ", "../youtube")) {
            assertEquals(target, PatcherHandoff.SetupEntry.REFUSE,
                PatcherHandoff.setupEntry(request(target), PatcherContract.PACKAGE, identity))
        }
    }

    @Test fun `an untrusted caller is refused without opening any hub screen`() {
        approvePatcher()
        val (started, finishing) = launch(request(), "untrusted.app")
        assertNull(started)
        assertTrue(finishing)
        assertNull(launch(request(), null).first)
    }

    @Test fun `an unapproved Patcher is sent to its approval screen, not the setup`() {
        val (started, finishing) = launch(request(), PatcherContract.PACKAGE)
        assertEquals(PluginPermissionsActivity::class.java.name, started!!.component!!.className)
        assertTrue(finishing)
    }

    @Test fun `the approved Patcher opens the non-exported YouTube setup with only a vetted hint`() {
        approvePatcher()
        val (started, finishing) = launch(request(hint = PatcherContract.JOB_RUNNING), PatcherContract.PACKAGE)
        assertEquals(YoutubeSetupActivity::class.java.name, started!!.component!!.className)
        assertEquals(PatcherContract.JOB_RUNNING, started.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        assertTrue(finishing)
        val forged = launch(request(hint = "installed"), PatcherContract.PACKAGE).first!!
        assertNull(forged.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
    }

    @Test fun `the approved Patcher opens the non-exported Reddit setup, not YouTube's`() {
        approvePatcher()
        val (started, finishing) = launch(request(PatcherContract.TARGET_REDDIT), PatcherContract.PACKAGE)
        assertEquals(RedditSetupActivity::class.java.name, started!!.component!!.className)
        assertNull(started.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        assertTrue(finishing)
        // The same Reddit request from anyone else opens nothing.
        assertNull(launch(request(PatcherContract.TARGET_REDDIT), "untrusted.app").first)
        assertNull(launch(request(PatcherContract.TARGET_REDDIT), null).first)
        assertNull(launch(request("other"), PatcherContract.PACKAGE).first)
    }

    @Test fun `an unapproved Patcher asking for Reddit is sent to approval`() {
        val (started, _) = launch(request(PatcherContract.TARGET_REDDIT), PatcherContract.PACKAGE)
        assertEquals(PluginPermissionsActivity::class.java.name, started!!.component!!.className)
    }

    @Test fun `manifest exports only the filterless entry while the setup stays private`() {
        val document = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(java.io.File("src/main/AndroidManifest.xml"))
        val android = "http://schemas.android.com/apk/res/android"
        val activities = document.getElementsByTagName("activity").let { list -> (0 until list.length).map { list.item(it) as org.w3c.dom.Element } }
        fun activity(name: String) = activities.single { it.getAttributeNS(android, "name") == name }
        val entry = activity(".PatcherSetupEntryActivity")
        assertEquals("true", entry.getAttributeNS(android, "exported"))
        assertEquals(0, entry.getElementsByTagName("intent-filter").length)
        assertEquals("false", activity(".YoutubeSetupActivity").getAttributeNS(android, "exported"))
        assertEquals("false", activity(".RedditSetupActivity").getAttributeNS(android, "exported"))
        // Patcher reads this to know the hub opens both setups; it must match the allowlist exactly.
        val meta = (entry.getElementsByTagName("meta-data").item(0) as org.w3c.dom.Element)
        assertEquals(PatcherContract.META_SETUP_TARGETS, meta.getAttributeNS(android, "name"))
        assertEquals(PatcherContract.SETUP_TARGETS, PatcherContract.hubSetupTargets(meta.getAttributeNS(android, "value")))
        assertEquals(PatcherContract.HUB_SETUP_ACTIVITY, PatcherSetupEntryActivity::class.java.name)
    }
}
