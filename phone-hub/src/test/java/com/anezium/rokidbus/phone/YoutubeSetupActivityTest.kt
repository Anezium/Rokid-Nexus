package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.content.pm.Signature
import android.content.pm.SigningInfo
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.PatcherContract
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowToast
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class YoutubeSetupActivityTest {
    private lateinit var controller: YoutubeSetupController
    private lateinit var apk: PreparedYoutubeApk
    private lateinit var screen: ActivityController<YoutubeSetupActivity>
    private val sent = mutableListOf<BusEnvelope>()

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("youtube", ".apk", context.cacheDir).apply { writeText("test apk") }
        apk = PreparedYoutubeApk(file,
            ArtifactArchiveInfo(YoutubeSetupContract.YOUTUBE, 200, listOf(byteArrayOf(1, 2, 3))),
            28, YoutubeApkSource.sha256(file))
        controller = YoutubeSetupController(context,
            connected = { true }, installReady = { true },
            send = { sent += it; null },
            upload = { _, _ -> true },
            worker = ImmediateExecutor(), source = { _, _, _ -> apk })
        controller.start()
        screen = Robolectric.buildActivity(YoutubeSetupActivity::class.java).setup()
        idle()
        assertEquals(1, sent.size)
        reply()
        assertFalse(YoutubeSetupStateStore.state.busy)
    }

    @After fun teardown() {
        screen.pause().stop().destroy()
        controller.close()
        idle()
        apk.file.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun reply() {
        val id = sent.last().payload.getString("requestId")
        controller.handleRemote(BusEnvelope(path = NativeAppContract.RESULT_PATH,
            payload = YoutubeSetupContract.result(id, 32, YoutubeSetupContract.PACKAGES.map { YoutubePackage(it) })))
        idle()
    }

    private fun views(root: View = screen.get().window.decorView): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } }
            ?: emptyList())

    private fun text(label: String): List<TextView> =
        views().filterIsInstance<TextView>().filter { it.text.toString() == label }

    /** Step headers can share a label with their action ("Patch and install"). */
    private fun button(label: String): List<android.widget.Button> =
        views().filterIsInstance<android.widget.Button>().filter { it.text.toString() == label }

    private fun go(route: YoutubeSetupActivity.Route) {
        if (route == YoutubeSetupActivity.Route.OVERVIEW) {
            screen.get().onBackPressed()
        } else {
            fun row() = views().singleOrNull { it.contentDescription?.startsWith(route.title + ":") == true }
            if (row() == null) { screen.get().onBackPressed(); idle() }
            requireNotNull(row()).performClick()
        }
        idle()
    }

    private fun launchPicker(): Intent {
        go(YoutubeSetupActivity.Route.ADVANCED)
        text("CHOOSE PATCHED YOUTUBE APK").single().performClick()
        val started = shadowOf(screen.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        return started.intent
    }

    private fun pickerResult(picker: Intent) = shadowOf(screen.get()).receiveResult(picker, Activity.RESULT_OK,
        Intent().setData(Uri.parse("content://picker/youtube.apk")))

    private fun approvePatcher(): PhonePluginPrincipal {
        val context = RuntimeEnvironment.getApplication()
        val manager = shadowOf(context.packageManager)
        val app = ApplicationInfo().apply {
            packageName = PatcherContract.PACKAGE
            uid = 10001
            enabled = true
        }
        val signing = SigningInfo().also { shadowOf(it).setSignatures(arrayOf(Signature(byteArrayOf(1, 2, 3)))) }
        manager.installPackage(PackageInfo().apply {
            packageName = app.packageName
            applicationInfo = app
            signingInfo = signing
            lastUpdateTime = 1
        })
        manager.addOrUpdateActivity(ActivityInfo().apply {
            packageName = app.packageName
            name = PatcherContract.PATCH_ACTIVITY
            applicationInfo = app
            enabled = true
            exported = true
        })
        manager.addResolveInfoForIntent(Intent(BusConstants.ACTION_PLUGIN), ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = app.packageName
                name = "${app.packageName}.PatcherPluginService"
                applicationInfo = app
                exported = true
                metaData = Bundle().apply {
                    putString(BusConstants.META_PLUGIN_ID, PatcherContract.PLUGIN_ID)
                    putString(BusConstants.META_PLUGIN_DISPLAY_NAME, "Patcher")
                    putString(BusConstants.META_PLUGIN_API_VERSION, "3")
                    putString(BusConstants.META_PLUGIN_CAPABILITIES, "")
                    putString(BusConstants.META_PLUGIN_RECEIVE_PREFIXES, "/plugin/${PatcherContract.PLUGIN_ID}")
                }
            }
        })
        val candidate = PhonePluginDiscovery(context.packageManager).discover().single()
        assertTrue(candidate.toString(), candidate is PhonePluginCandidate.Valid)
        val principal = (candidate as PhonePluginCandidate.Valid).principal
        PluginGrantStore(context).approve(principal, emptySet())
        assertNotNull(PatcherHandoff.authenticatedIdentity(context))
        screen.pause().stop().restart().resume()
        idle()
        reply()
        return principal
    }

    private fun launchPatch(): org.robolectric.shadows.ShadowActivity.IntentForResult {
        button("Patch and install").single().performClick()
        return requireNotNull(shadowOf(screen.get()).nextStartedActivityForResult).also {
            assertEquals(PatcherHandoff.patchIntent().component, it.intent.component)
            assertEquals(PatcherContract.ACTION_PATCH, it.intent.action)
        }
    }

    private fun patchResult(code: Int, result: Int, data: Intent? = null) {
        YoutubeSetupActivity::class.java.getDeclaredMethod("onActivityResult",
            Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Intent::class.java)
            .apply { isAccessible = true }.invoke(screen.get(), code, result, data)
        idle()
    }

    private fun resultApk() = Intent().setData(Uri.parse("content://patcher/youtube.apk"))
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        .putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)

    private fun hint(value: String) = Intent().putExtra(PatcherContract.EXTRA_JOB_STATE, value)

    @Test fun `overview lists the four steps, keyboard and Advanced, and each screen returns to it`() {
        YoutubeSetupActivity.Route.values().drop(1).forEach { route ->
            assertEquals(1, views().count { it.contentDescription?.startsWith(route.title + ":") == true })
        }
        assertEquals("off by default", false, views().filterIsInstance<android.widget.Switch>().single().isChecked)
        assertTrue("Advanced actions live on their own screen", text("CHOOSE PATCHED YOUTUBE APK").isEmpty())
        assertEquals("the controller report sits under the steps", 1, text("Glasses apps refreshed.").size)
        assertEquals("without an approved Patcher the shortcut asks for it", 1, text("Get or approve Patcher").size)
        go(YoutubeSetupActivity.Route.SIGNIN)
        assertEquals(1, text(YoutubeSetupActivity.Route.SIGNIN.title).size)
        assertEquals("the ordinal lives in the subtitle", 1, text("Step 4 of 4 · YouTube").size)
        assertTrue(text("Get or approve Patcher").isEmpty())
        screen.get().onBackPressed()
        idle()
        go(YoutubeSetupActivity.Route.ADVANCED)
        assertEquals(1, text("Patcher · YouTube").size)
        assertEquals(1, text("CHOOSE PATCHED YOUTUBE APK").size)
        assertTrue("no repeat shortcut off the overview", text("Get or approve Patcher").isEmpty())
        go(YoutubeSetupActivity.Route.OVERVIEW)
        assertEquals(1, text("Get or approve Patcher").size)
        assertFalse(screen.get().isFinishing)
        screen.get().onBackPressed()
        assertTrue("Back at the root returns to Patcher", screen.get().isFinishing)
    }

    @Test fun `opening the download page never completes the source step`() {
        go(YoutubeSetupActivity.Route.SOURCE)
        assertEquals(1, text("To do — download it, then choose it in Patcher").size)
        text("DOWNLOAD YOUTUBE ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}").single().performClick()
        assertEquals(Intent.ACTION_VIEW, shadowOf(screen.get()).nextStartedActivity.action)
        go(YoutubeSetupActivity.Route.OVERVIEW)
        go(YoutubeSetupActivity.Route.SOURCE)
        assertEquals(1, text("To do — download it, then choose it in Patcher").size)
    }

    @Test fun `keyboard switch persists the phone-owned opt-in`() {
        val toggle = views().filterIsInstance<android.widget.Switch>().single()
        toggle.performClick()
        assertTrue(YoutubeKeyboardSettings(screen.get()).autoOpen)
        go(YoutubeSetupActivity.Route.MICROG)
        go(YoutubeSetupActivity.Route.OVERVIEW)
        assertTrue(views().filterIsInstance<android.widget.Switch>().single().isChecked)
        assertFalse(YoutubeKeyboardSettings(screen.get()).redditAutoOpen)
    }

    @Test fun `pending patch disables patching and imports but leaves the other setup steps usable`() {
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        launchPatch()
        assertFalse(button("Patch and install").single().isEnabled)
        assertTrue(text("OPEN PATCHER").single().isEnabled)
        assertEquals(1, text("Waiting for Patcher — open it to check progress or use the result.").size)
        listOf("Patcher in Store", "Refresh glasses apps").forEach { assertTrue(it, text(it).single().isEnabled) }
        go(YoutubeSetupActivity.Route.MICROG)
        assertTrue(text("Install MicroG").single().isEnabled)
        listOf("Check for MicroG updates", "MicroG source").forEach { assertTrue(it, text(it).single().isEnabled) }
        go(YoutubeSetupActivity.Route.SOURCE)
        assertTrue(text("DOWNLOAD YOUTUBE ${YoutubeApkPolicy.STOCK_YOUTUBE_VERSION}").single().isEnabled)
        assertTrue("the source is chosen in the job, not swapped beside it", text("Choose the file in Patcher").isEmpty())
        assertTrue(text("Open running job").single().isEnabled)
        go(YoutubeSetupActivity.Route.SIGNIN)
        listOf("KEYBOARD & REMOTE", "Mark sign-in done").forEach { assertTrue(it, text(it).single().isEnabled) }
        go(YoutubeSetupActivity.Route.ADVANCED)
        assertTrue(text("ADD ROKID PATCHES TO MORPHE").single().isEnabled)
        assertFalse(text("CHOOSE PATCHED YOUTUBE APK").single().isEnabled)
        assertEquals(1, text("Locked while YouTube is being patched in Patcher.").size)
        go(YoutubeSetupActivity.Route.OVERVIEW)
        assertTrue(text("Open running job").single().isEnabled)
    }

    @Test fun `a picker opened before the patch started cannot import beside the job`() {
        val picker = launchPicker()
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        launchPatch()
        val before = sent.size
        pickerResult(picker)
        idle()
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertNull(YoutubeSetupStateStore.state.preparedLabel)
        assertEquals(before, sent.size)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Locked while YouTube is being patched"))
    }

    @Test fun `cancelled patch result after onStart enables patching without starting an install`() {
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val request = launchPatch()
        screen.pause().stop().restart()
        idle()
        val before = sent.size
        patchResult(request.requestCode, Activity.RESULT_CANCELED)
        screen.resume()
        idle()
        assertTrue(button("Patch and install").single().isEnabled)
        assertTrue(text("OPEN PATCHER").isEmpty())
        assertEquals(before, sent.size)
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertFalse(YoutubeSetupStateStore.state.canInstall)
    }

    @Test fun `an authenticated cancel keeps the running-job hint and the source locked`() {
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val request = launchPatch()
        patchResult(request.requestCode, Activity.RESULT_CANCELED, hint(PatcherContract.JOB_RUNNING))
        assertEquals(1, text("Patching in Patcher — open the running job to follow it.").size)
        go(YoutubeSetupActivity.Route.ADVANCED)
        assertFalse(text("CHOOSE PATCHED YOUTUBE APK").single().isEnabled)
        go(YoutubeSetupActivity.Route.OVERVIEW)
        assertEquals(1, text("Open running job").size)
        text("Open running job").single().performClick()
        val reopened = requireNotNull(shadowOf(screen.get()).nextStartedActivityForResult)
        assertEquals(PatcherHandoff.patchIntent().component, reopened.intent.component)
        assertNotEquals(request.requestCode, reopened.requestCode)
        // The job ended in Patcher: the next authenticated answer unlocks the import again.
        patchResult(reopened.requestCode, Activity.RESULT_CANCELED, hint(PatcherContract.JOB_SOURCE_READY))
        assertEquals(1, text("Patch now").size)
        go(YoutubeSetupActivity.Route.ADVANCED)
        assertTrue(text("CHOOSE PATCHED YOUTUBE APK").single().isEnabled)
    }

    @Test fun `a hint from a Patcher that changed during the hand-off is ignored`() {
        val principal = approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val request = launchPatch()
        PluginGrantStore(screen.get()).revoke(principal)
        patchResult(request.requestCode, Activity.RESULT_CANCELED, hint(PatcherContract.JOB_READY))
        assertTrue(text("Patched APK ready in Patcher — install it on the glasses.").isEmpty())
        assertTrue(text("Install on glasses").isEmpty())
    }

    @Test fun `the entry hint offers the ready result and survives recreation`() {
        approvePatcher()
        screen.pause().stop().destroy()
        screen = Robolectric.buildActivity(YoutubeSetupActivity::class.java,
            YoutubeSetupActivity.intent(RuntimeEnvironment.getApplication(), PatcherContract.JOB_READY)).setup()
        idle()
        reply()
        assertEquals(1, text("Install patched APK").size)
        go(YoutubeSetupActivity.Route.PATCH)
        val saved = Bundle()
        screen.pause().stop().saveInstanceState(saved).destroy()
        screen = Robolectric.buildActivity(YoutubeSetupActivity::class.java).create(saved).start().resume()
        idle()
        reply()
        assertEquals("the route survives", 1, text(YoutubeSetupActivity.Route.PATCH.title).size)
        text("Install on glasses").single().performClick()
        assertEquals(PatcherContract.ACTION_PATCH, shadowOf(screen.get()).nextStartedActivityForResult.intent.action)
    }

    @Test fun `opening a pending patch preserves the job and rejects results from the old request`() {
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val old = launchPatch()
        screen.pause().stop().restart().resume()
        idle()
        assertFalse(button("Patch and install").single().isEnabled)
        assertTrue(text("OPEN PATCHER").single().isEnabled)
        text("OPEN PATCHER").single().performClick()
        val retry = requireNotNull(shadowOf(screen.get()).nextStartedActivityForResult)
        assertEquals(PatcherHandoff.patchIntent().component, retry.intent.component)
        assertNotEquals(old.requestCode, retry.requestCode)
        patchResult(old.requestCode, Activity.RESULT_OK, resultApk())
        patchResult(old.requestCode, Activity.RESULT_CANCELED)
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertFalse(button("Patch and install").single().isEnabled)
        patchResult(retry.requestCode, Activity.RESULT_CANCELED)
        assertTrue(button("Patch and install").single().isEnabled)
    }

    @Test fun `onResume recovers a pending patch when approval disappears and rejects the late APK`() {
        val principal = approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val request = launchPatch()
        screen.pause().stop()
        PluginGrantStore(screen.get()).revoke(principal)
        screen.restart().resume()
        idle()
        assertEquals("Patcher stopped — retry.", ShadowToast.getTextOfLatestToast())
        assertTrue(text("Get or approve Patcher").single().isEnabled)
        patchResult(request.requestCode, Activity.RESULT_OK, resultApk())
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertTrue(text("Get or approve Patcher").single().isEnabled)
    }

    @Test fun `recreation preserves the live patch request and its validated result boundary`() {
        approvePatcher()
        go(YoutubeSetupActivity.Route.PATCH)
        val request = launchPatch()
        val saved = Bundle()
        screen.pause().stop().saveInstanceState(saved).destroy()
        screen = Robolectric.buildActivity(YoutubeSetupActivity::class.java).create(saved).start().resume()
        idle()
        assertFalse(button("Patch and install").single().isEnabled)
        assertTrue(text("OPEN PATCHER").single().isEnabled)
        patchResult(request.requestCode, Activity.RESULT_OK, resultApk())
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertTrue(text("OPEN PATCHER").isEmpty())
    }

    @Test fun `picker result delivered after onStart is imported while glasses are connected`() {
        val picker = launchPicker()
        screen.pause().stop()
        // Android restarts the activity before it delivers the result.
        screen.restart()
        idle()
        assertEquals(1, sent.size)
        assertFalse(YoutubeSetupStateStore.state.busy)
        pickerResult(picker)
        screen.resume()
        idle()
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertEquals(apk.label, YoutubeSetupStateStore.state.preparedLabel)
        screen.pause().stop().restart().resume()
        idle()
        assertEquals("the next return must refresh again", 2, sent.size)
    }

    @Test fun `result arriving while busy is reported without ending the operation in flight`() {
        val picker = launchPicker()
        screen.pause().stop()
        controller.handle(Intent(YoutubeSetupController.REFRESH))
        screen.restart()
        idle()
        assertEquals(2, sent.size)
        pickerResult(picker)
        screen.resume()
        idle()
        assertTrue(YoutubeSetupStateStore.state.busy)
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Wait for it to finish, then choose the APK again"))
        reply()
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertEquals("Glasses apps refreshed.", YoutubeSetupStateStore.state.message)
    }

    @Test fun `message updates keep the screen and expanded sections survive re-renders`() {
        go(YoutubeSetupActivity.Route.MICROG)
        val title = text(YoutubeSetupActivity.Route.MICROG.title).single()
        text("More").single().performClick()
        val source = text("MicroG source").single()
        assertEquals(View.VISIBLE, (source.parent.parent as View).visibility)
        assertEquals(1, text("Less").size)
        val state = YoutubeSetupStateStore.state
        YoutubeSetupStateStore.update(state.copy(message = "Downloading MicroG… 40%"))
        assertSame(title, text(YoutubeSetupActivity.Route.MICROG.title).single())
        assertSame(source, text("MicroG source").single())
        assertEquals(1, text("Downloading MicroG… 40%").size)
        YoutubeSetupStateStore.update(state.copy(inventory = null, message = "Checking"))
        assertNotSame(title, text(YoutubeSetupActivity.Route.MICROG.title).single())
        assertEquals(View.VISIBLE, (text("MicroG source").single().parent.parent as View).visibility)
        assertEquals("only the glasses inventory completes MicroG", 1,
            text("Needs attention — refresh glasses apps").size)
    }

    @Test fun `MicroG is done only from glasses inventory and asks for the icon build otherwise`() {
        go(YoutubeSetupActivity.Route.MICROG)
        assertEquals(1, text("To do").size)
        assertEquals(1, text("Install MicroG").size)
        fun inventory(microG: YoutubePackage) {
            val state = YoutubeSetupStateStore.state
            val current = requireNotNull(state.inventory)
            YoutubeSetupStateStore.update(state.copy(inventory = current.copy(apps = current.apps.map {
                if (it.packageName == YoutubeSetupContract.MICROG) microG else it
            })))
        }
        inventory(YoutubePackage(YoutubeSetupContract.MICROG, 10, "signer", launchable = false))
        assertEquals(1, text("Needs attention").size)
        assertEquals(1, text("Update MicroG").size)
        inventory(YoutubePackage(YoutubeSetupContract.MICROG, 10, "signer", launchable = true))
        assertEquals(1, text("Done").size)
        assertEquals(1, text("Open MicroG").size)
        YoutubeSetupStateStore.update(YoutubeSetupStateStore.state.copy(latestMicroGVersionCode = 11))
        assertEquals(1, text("Update available").size)
        assertEquals(1, text("Update MicroG").size)
    }

    @Test fun `sign-in Done is a manual mark that can be reset`() {
        go(YoutubeSetupActivity.Route.SIGNIN)
        assertEquals(1, text("To do").size)
        text("Mark sign-in done").single().performClick()
        assertEquals(1, text("Done (marked by you)").size)
        assertTrue(YoutubeSetupChecklist(screen.get()).signInDone)
        text("Mark sign-in to do").single().performClick()
        assertFalse(YoutubeSetupChecklist(screen.get()).signInDone)
        text("KEYBOARD & REMOTE").single().performClick()
        val remote = shadowOf(screen.get()).nextStartedActivity
        assertEquals(RemoteInputActivity::class.java.name, remote.component!!.className)
        assertTrue(remote.getBooleanExtra(RemoteInputActivity.EXTRA_SECURE_SESSION, false))
    }

    @Test fun `confirmed YouTube shows its installed version and a secondary reinstall action`() {
        val archive = apk.archive.copy(versionName = YoutubeApkPolicy.STOCK_YOUTUBE_VERSION)
        YoutubeSetupInstallHistory(screen.get()).confirmed(archive)
        val state = YoutubeSetupStateStore.state
        val inventory = requireNotNull(state.inventory)
        YoutubeSetupStateStore.update(state.copy(inventory = inventory.copy(apps = inventory.apps.map {
            if (it.packageName == archive.packageName)
                YoutubePackage(it.packageName, archive.versionCode, YoutubeApkPolicy.signer(archive), true)
            else it
        })))
        go(YoutubeSetupActivity.Route.PATCH)
        assertEquals(1, text("Done — YouTube ${archive.versionName} is installed.").size)
        val reinstall = text("Reinstall / update").single()
        assertTrue(reinstall.isEnabled)
        assertTrue(views().filterIsInstance<TextView>().none {
            it.text.contains("Approve Patcher first") || it is android.widget.Button && it.text.toString() == "Patch and install"
        })
        reinstall.performClick()
        assertEquals(PatcherHandoff.reviewIntent(screen.get()).component,
            shadowOf(screen.get()).nextStartedActivity.component)
    }

    @Test fun `an approved Patcher offers a patch update once YouTube is confirmed`() {
        approvePatcher()
        assertEquals(1, text("Patch now").size)
        val archive = apk.archive.copy(versionName = YoutubeApkPolicy.STOCK_YOUTUBE_VERSION)
        YoutubeSetupInstallHistory(screen.get()).confirmed(archive)
        val state = YoutubeSetupStateStore.state
        val inventory = requireNotNull(state.inventory)
        YoutubeSetupStateStore.update(state.copy(inventory = inventory.copy(apps = inventory.apps.map {
            if (it.packageName == archive.packageName)
                YoutubePackage(it.packageName, archive.versionCode, YoutubeApkPolicy.signer(archive), true)
            else it
        })))
        text("Patch an update").single().performClick()
        assertEquals(PatcherContract.ACTION_PATCH, shadowOf(screen.get()).nextStartedActivityForResult.intent.action)
    }

    @Test fun `a waiting patched APK leads step 3 with Install on glasses and says when to connect them`() {
        val picker = launchPicker()
        pickerResult(picker)
        idle()
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertTrue("Advanced offers the install in place", text("INSTALL PREPARED APK").single().isEnabled)
        go(YoutubeSetupActivity.Route.OVERVIEW)
        assertEquals(1, text("Install patched APK").size)
        go(YoutubeSetupActivity.Route.PATCH)
        val label = requireNotNull(YoutubeSetupStateStore.state.preparedLabel)
        assertEquals(1, text("Ready to install — $label is patched and waiting.").size)
        val install = text("Install on glasses").single()
        assertTrue(install.isEnabled)
        assertTrue(button("Patch and install").isEmpty())
        assertTrue(text("RETRY PREPARED INSTALL").isEmpty())
        assertEquals(View.GONE, (text("Patch again").single().parent.parent as View).visibility)
        // The glasses dropped: the line says what to do next and the primary action stays put.
        YoutubeSetupStateStore.update(YoutubeSetupStateStore.state.copy(inventory = null,
            message = "The glasses disconnected. Reconnect and refresh before continuing."))
        assertEquals(1, text("Ready to install — $label is patched and waiting. Connect the glasses, then install.").size)
        assertEquals(1, text("Install on glasses").size)
        val before = sent.size
        text("Install on glasses").single().performClick()
        idle()
        assertEquals("install asks the glasses for their apps first", before + 1, sent.size)
    }

    private class ImmediateExecutor : AbstractExecutorService() {
        private var stopped = false
        override fun execute(command: Runnable) = command.run()
        override fun shutdown() { stopped = true }
        override fun shutdownNow(): MutableList<Runnable> { stopped = true; return mutableListOf() }
        override fun isShutdown() = stopped
        override fun isTerminated() = stopped
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = stopped
    }
}
