package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.content.IntentFilter
import android.os.Looper
import android.view.WindowManager
import android.widget.Button
import android.widget.TextView
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatchActivityTest {
    private fun activeStore(): PatchJobStore {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "patch-job.json").delete()
        val store = PatchJobStore(context.filesDir)
        PatchJobStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, store)
        store.prepare()
        return store
    }

    @Test fun backgroundDestroyAndRecreationDoNotCancelTheJob() {
        val store = activeStore()
        val original = store.state.value
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(screen.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        screen.pause().stop()
        assertEquals(0, screen.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        screen.destroy()
        assertEquals(original, store.state.value)
        val reopened = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(original, store.state.value)
        reopened.pause().stop().destroy()
    }

    @Test fun backClosesOnlyTheActivity() {
        val store = activeStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        screen.get().onBackPressed()
        assertTrue(screen.get().isFinishing)
        assertEquals(PatchJobStatus.PREPARING, store.state.value.status)
        assertNull(shadowOf(screen.get()).nextStartedService)
        screen.pause().stop().destroy()
    }

    @Test fun cancelButtonRequestsOnlyTheCurrentJob() {
        val store = activeStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        // Checking the file (step 1) offers a plain Cancel; the patch card's is "Cancel patching".
        fun cancelButton(view: android.view.View): android.view.View? {
            if (view is android.widget.Button && view.text == "Cancel") return view
            if (view is android.view.ViewGroup) for (i in 0 until view.childCount) cancelButton(view.getChildAt(i))?.let { return it }
            return null
        }
        requireNotNull(cancelButton(screen.get().findViewById(android.R.id.content))).performClick()
        val cancel = shadowOf(screen.get()).nextStartedService
        assertEquals(PatchJobService.CANCEL, cancel.action)
        assertEquals(store.state.value.id, cancel.getStringExtra(PatchJobService.JOB_ID))
        assertEquals(PatchJobService::class.java.name, cancel.component!!.className)
        screen.pause().stop().destroy()
    }

    @Test fun recreatedResultActivityReturnsTheStoredUriWithReadGrantAndTarget() {
        assumeTrue("Android FileProvider root matching requires POSIX paths", File.separatorChar == '/')
        val store = activeStore()
        val job = store.state.value
        val request = Intent(PatcherContract.ACTION_PATCH).putExtra(PatcherContract.EXTRA_TARGET_ID, job.targetId)
            .putExtra(PatchActivity.EXTRA_READY_JOB_ID, job.id)
        val first = Robolectric.buildActivity(PatchActivity::class.java, request).setup()
        first.pause().stop().destroy()
        val context = RuntimeEnvironment.getApplication()
        val output = File(context.filesDir, "results/patched-complete.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
        val reopened = Robolectric.buildActivity(PatchActivity::class.java, request).create()
        shadowOf(reopened.get()).setCallingActivity(ComponentName(com.anezium.rokidbus.client.HubTarget.PHONE.packageName, "Setup"))
        shadowOf(reopened.get()).setCallingPackage(com.anezium.rokidbus.client.HubTarget.PHONE.packageName)
        assertNotNull(reopened.get().callingActivity)
        reopened.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = shadowOf(reopened.get())
        assertEquals(Activity.RESULT_OK, activity.resultCode)
        assertEquals("content", activity.resultIntent.data!!.scheme)
        assertTrue(activity.resultIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(activity.resultIntent.data, activity.resultIntent.clipData!!.getItemAt(0).uri)
        assertEquals(job.targetId, activity.resultIntent.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        reopened.pause().stop().destroy()
    }

    private fun views(root: android.view.View): List<android.view.View> =
        listOf(root) + ((root as? android.view.ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
    private fun text(activity: Activity, label: String): TextView? =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>().singleOrNull { it !is Button && it.text.toString() == label }
    private fun button(activity: Activity, label: String): Button? =
        views(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().singleOrNull { it.text.toString() == label }
    /** A validated stock file and a started patch job, the way the service leaves them. */
    private fun runningStore(): Pair<PatchJobStore, PatchJobState> {
        val store = activeStore()
        val job = store.state.value
        File(store.work(job.workId), "prepare/stock.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(job.id) { it.copy(status = PatchJobStatus.READY, stock = "prepare/stock.apk") }
        return store to store.patch("hash", listOf("Rokid controls"))
    }

    @Test fun pausedVisiblePipKeepsTheScreenOnUntilItIsHiddenOrTheJobEnds() {
        val (store, job) = runningStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        val activity = screen.get()
        fun screenAwake() = activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0
        assertTrue(screenAwake())
        activity.enterPictureInPictureMode(PatchPictureInPicture.params(true))
        activity.onPictureInPictureModeChanged(true, android.content.res.Configuration())
        screen.pause()
        assertTrue(activity.isInPictureInPictureMode)
        assertTrue(screenAwake())
        screen.stop()
        assertFalse(screenAwake())
        assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
        screen.start()
        assertTrue(screenAwake())
        store.change(job.id) { it.copy(status = PatchJobStatus.CANCELLED) }
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screenAwake())
        assertFalse(activity.isFinishing)
        screen.stop().destroy()
    }

    @Test fun everyTerminalStateClearsTheVisibleRunningScreenFlag() {
        PatchJobStatus.entries.filter { !PatchJobState(status = it).active }.forEach { status ->
            val (store, job) = runningStore()
            val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
            assertTrue(screen.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
            store.change(job.id) { it.copy(status = status) }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, screen.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            screen.pause().stop().destroy()
        }
    }

    @Test fun runningPatchShowsALiveBlockThatUpdatesInPlace() {
        val (store, job) = runningStore()
        store.progress(job.id, PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23), 134_000)
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = screen.get()
        assertNotNull(text(activity, "Patching ${PatchTargets.default.displayName}"))
        val clock = requireNotNull(text(activity, "2:14"))
        val phase = requireNotNull(text(activity, "Applying patches · 7 of 23"))
        assertNotNull(text(activity, "✓  Hide ads"))
        assertEquals(listOf("Load", "Patch", "Build", "Sign", "Save"), PatchPresentation.patchStages.map { it.label }.onEach { assertNotNull(it, text(activity, it)) })
        assertNotNull(button(activity, "Cancel patching"))
        assertNull(button(activity, "Patch ${PatchTargets.default.displayName}"))
        store.progress(job.id, PatchProgress(PatchPhase.COMPILE), 150_000)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("2:30", clock.text.toString())
        assertEquals("Compiling the patched code", phase.text.toString())
        assertNull(text(activity, "✓  Hide ads"))
        screen.pause().stop().destroy()
    }

    @Test fun interruptedPatchExplainsItselfAndOffersRetry() {
        val (store, job) = runningStore()
        store.change(job.id) { it.copy(status = PatchJobStatus.INTERRUPTED, message = "The last patch was interrupted. Retry when you are ready.", result = null) }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = screen.get()
        assertNotNull(text(activity, "Patch interrupted"))
        assertNotNull(text(activity, "The last patch was interrupted. Retry when you are ready."))
        assertNotNull(button(activity, "Retry patch"))
        assertNull(button(activity, "Cancel patching"))
        screen.pause().stop().destroy()
    }

    @Test fun cancelledPatchOffersToPatchAgain() {
        val (store, job) = runningStore()
        store.change(job.id) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.", result = null) }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(text(screen.get(), "Patch cancelled"))
        assertNotNull(button(screen.get(), "Patch ${PatchTargets.default.displayName}"))
        screen.pause().stop().destroy()
    }

    @Test fun fileCheckFailureStaysOnTheStockStep() {
        val store = activeStore()
        store.change(store.state.value.id) { it.copy(status = PatchJobStatus.FAILURE, message = "This is not a stock APK.") }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        val activity = screen.get()
        assertNotNull(text(activity, "This is not a stock APK."))
        assertNotNull(button(activity, "Choose APK or bundle"))
        assertNotNull(text(activity, "Patch"))
        assertNull(text(activity, "Patch failed"))
        assertNull(button(activity, "Retry patch"))
        screen.pause().stop().destroy()
    }

    @Test fun explicitRequestWithoutTargetFailsClosed() {
        val store = activeStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java,
            Intent(PatcherContract.ACTION_PATCH)).setup()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(screen.get()).resultCode)
        assertEquals(PatchJobStatus.PREPARING, store.state.value.status)
        screen.pause().stop().destroy()
    }
    @Test fun stockPickerStartsInDownloadsWithTheSupportedMimeTypes() {
        activeStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        PatchActivity::class.java.getDeclaredMethod("picker", Int::class.javaPrimitiveType,
            String::class.java, String::class.java, String::class.java).apply { isAccessible = true }
            .invoke(screen.get(), 1, Intent.ACTION_OPEN_DOCUMENT, "*/*", null)
        val picker = shadowOf(screen.get()).nextStartedActivity
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, picker.action)
        assertEquals(android.provider.DocumentsContract.buildRootUri("com.android.providers.downloads.documents", "downloads"),
            picker.getParcelableExtra<android.net.Uri>(android.provider.DocumentsContract.EXTRA_INITIAL_URI))
        assertTrue(picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)!!.contains("application/zip"))
        screen.pause().stop().destroy()
    }

    @Test fun onlyTheActualNexusCallingPackageCanReceiveAResult() {
        val store = activeStore()
        val request = Intent(PatcherContract.ACTION_PATCH).putExtra(PatcherContract.EXTRA_TARGET_ID, store.state.value.targetId)
        val screen = Robolectric.buildActivity(PatchActivity::class.java, request).setup()
        val shadow = shadowOf(screen.get())
        val hub = com.anezium.rokidbus.client.HubTarget.PHONE.packageName
        shadow.setCallingActivity(ComponentName(hub, "Setup"))
        shadow.setCallingPackage("untrusted.app")
        assertFalse(screen.get().canReturnToHub())
        shadow.setCallingPackage(hub)
        assertTrue(screen.get().canReturnToHub())
        screen.get().intent.action = null
        assertFalse(screen.get().canReturnToHub())
        screen.pause().stop().destroy()
    }

    @Test fun aNewHubLaunchNeverFinishesWithAnOldDeliveredResult() {
        val (store, job) = runningStore()
        val output = File(RuntimeEnvironment.getApplication().filesDir, "results/patched-complete.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - 54 * 60_000L)
        }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
        store.markDelivered(job.id)
        val request = Intent(PatcherContract.ACTION_PATCH).putExtra(PatcherContract.EXTRA_TARGET_ID, job.targetId)
        val screen = Robolectric.buildActivity(PatchActivity::class.java, request)
        val hub = com.anezium.rokidbus.client.HubTarget.PHONE.packageName
        shadowOf(screen.get()).setCallingActivity(ComponentName(hub, "Setup"))
        shadowOf(screen.get()).setCallingPackage(hub)
        screen.setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.get().isFinishing)
        assertNotNull(button(screen.get(), "Patch again"))
        assertNotNull(button(screen.get(), "Use this result"))
        assertNotNull(text(screen.get(), "Patched 54 min ago · Already sent to Nexus"))
        assertNull(text(screen.get(), "Patched 54 min ago · Nexus is waiting for it"))
        screen.pause().stop().destroy()
    }

    @Test fun aSavedUndeliveredResultKeepsTheWaitingCopyAndExplicitChoice() {
        val (store, job) = runningStore()
        val output = File(RuntimeEnvironment.getApplication().filesDir, "results/patched-complete.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
            setLastModified(System.currentTimeMillis() - 42 * 60_000L)
        }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
        val request = Intent(PatcherContract.ACTION_PATCH).putExtra(PatcherContract.EXTRA_TARGET_ID, job.targetId)
        val screen = Robolectric.buildActivity(PatchActivity::class.java, request)
        val hub = com.anezium.rokidbus.client.HubTarget.PHONE.packageName
        shadowOf(screen.get()).setCallingActivity(ComponentName(hub, "Setup"))
        shadowOf(screen.get()).setCallingPackage(hub)
        screen.setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.get().isFinishing)
        assertFalse(store.state.value.delivered)
        assertNotNull(text(screen.get(), "Patched 42 min ago · Nexus is waiting for it"))
        assertNotNull(button(screen.get(), "Use this result"))
        assertNotNull(button(screen.get(), "Patch again"))
        screen.pause().stop().destroy()
    }

    @Test fun standaloneOpenNexusUsesTheExistingLauncherWithoutClaimingAWaitingCaller() {
        val hub = com.anezium.rokidbus.client.HubTarget.PHONE.packageName
        val launcher = ComponentName(hub, "$hub.MainActivity")
        val manager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        manager.addActivityIfNotPresent(launcher).exported = true
        manager.addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
        val (store, job) = runningStore()
        val output = File(RuntimeEnvironment.getApplication().filesDir, "results/patched-complete.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
        store.markDelivered(job.id)
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.get().canReturnToHub())
        assertNull(button(screen.get(), "Use this result"))
        assertTrue(views(screen.get().findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .none { it.text.contains("Nexus is waiting for it") })
        requireNotNull(button(screen.get(), "Open Nexus")).performClick()
        assertEquals(launcher, shadowOf(screen.get()).nextStartedActivity.component)
        assertFalse(screen.get().isFinishing)
        screen.pause().stop().destroy()
    }

    @Test @Config(sdk = [34]) fun deniedNotificationPermissionStillStartsTheUserRequestedForegroundJob() {
        val (store, _) = runningStore()
        val old = store.state.value
        store.change(old.id) { it.copy(status = PatchJobStatus.CANCELLED) }
        val screen = Robolectric.buildActivity(PatchActivity::class.java).setup()
        val activity = screen.get()
        shadowOf(RuntimeEnvironment.getApplication()).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        val compatible = listOf(app.morphe.patcher.patch.bytecodePatch(name = "Permission test"))
        val loaded = BundleStore.Loaded(File("fixture.mpp"), "fixture", "hash", null, compatible)
        PatchActivity::class.java.getDeclaredField("bundle").apply { isAccessible = true }.set(activity, loaded)
        PatchActivity::class.java.getDeclaredField("busy").apply { isAccessible = true }.setBoolean(activity, false)
        PatchActivity::class.java.getDeclaredField("choices").apply { isAccessible = true }.set(activity, mutableMapOf(compatible.first().name!! to true))
        PatchActivity::class.java.getDeclaredMethod("startPatch").apply { isAccessible = true }.invoke(activity)
        val request = requireNotNull(shadowOf(activity).lastRequestedPermission)
        assertArrayEquals(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), request.requestedPermissions)
        assertEquals(PatchJobStatus.CANCELLED, store.state.value.status)
        activity.onRequestPermissionsResult(request.requestCode, request.requestedPermissions, intArrayOf(android.content.pm.PackageManager.PERMISSION_DENIED))
        assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
        assertNotEquals(old.id, store.state.value.id)
        val started = shadowOf(activity).nextStartedService
        assertEquals(PatchJobService::class.java.name, started.component!!.className)
        assertEquals(store.state.value.id, started.getStringExtra(PatchJobService.JOB_ID))
        screen.pause().stop().destroy()
    }

    @Test fun screensTellTheHonestDurationAndNeverPromiseScreenOffPatching() {
        shadowOf(RuntimeEnvironment.getApplication().packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        fun bodies(activity: Activity) = views(activity.findViewById(android.R.id.content)).filterIsInstance<TextView>()
            .filter { it !is Button }.map { it.text.toString() }
        fun honest(activity: Activity) {
            val advice = bodies(activity).filter { it.contains("6–7 minutes") }
            assertEquals(1, advice.size)
            assertTrue(advice.single(), advice.single().contains("small window"))
            assertTrue(bodies(activity).none { it.contains("few minutes") || it.contains("turn the display") || it.contains("display off") })
        }
        val (store, job) = runningStore()
        val running = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        honest(running.get())
        assertTrue(bodies(running.get()).single { it.contains("6–7 minutes") }.contains("slows it down"))
        running.pause().stop().destroy()
        // A validated file and no job yet: the idle card says what to expect before the first tap.
        val ready = activeStore()
        File(ready.work(ready.state.value.workId), "prepare/stock.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        ready.change(ready.state.value.id) { it.copy(status = PatchJobStatus.READY, stock = "prepare/stock.apk") }
        val idle = Robolectric.buildActivity(PatchActivity::class.java).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(button(idle.get(), "Patch ${PatchTargets.default.displayName}"))
        honest(idle.get())
        idle.pause().stop().destroy()
    }
}
