package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast
import java.io.File

/** Patcher's home, its hub hand-off and the per-app patch screen's locks. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class PatcherAppFlowTest {
    private fun freshStore(): PatchJobStore {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "patch-job.json").delete()
        val store = PatchJobStore(context.filesDir)
        PatchJobStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, store)
        return store
    }

    /** A validated stock file for [target], the way the service leaves it. */
    private fun sourceReady(target: PatchTarget = PatchTargets.youtube): PatchJobStore {
        val store = freshStore()
        store.selectTarget(target.id)
        val job = store.prepare(target.id)
        File(store.work(job.workId), "prepare/stock.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(job.id) { it.copy(status = PatchJobStatus.READY, stock = "prepare/stock.apk") }
        return store
    }

    private fun running(target: PatchTarget = PatchTargets.youtube): PatchJobStore =
        sourceReady(target).also { it.patch("hash", listOf("Rokid controls")) }

    /** [declared] null is a hub that predates the setup declaration and opens YouTube only. */
    private fun registerHubEntry(declared: String? = "youtube,reddit") {
        val app = ApplicationInfo().apply { packageName = PatcherContract.HUB_PACKAGE; enabled = true }
        shadowOf(RuntimeEnvironment.getApplication().packageManager).addOrUpdateActivity(ActivityInfo().apply {
            packageName = app.packageName; name = PatcherContract.HUB_SETUP_ACTIVITY
            applicationInfo = app; enabled = true; exported = true
            declared?.let { metaData = android.os.Bundle().apply { putString(PatcherContract.META_SETUP_TARGETS, it) } }
        })
    }

    private fun hubRequest(screen: Activity, target: String) =
        requireNotNull(shadowOf(screen).nextStartedActivityForResult).also { started ->
            assertEquals(ComponentName(PatcherContract.HUB_PACKAGE, PatcherContract.HUB_SETUP_ACTIVITY), started.intent.component)
            assertEquals(PatcherContract.ACTION_OPEN_SETUP, started.intent.action)
            assertEquals(target, started.intent.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
            assertNull(started.intent.data)
        }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun views(root: View): List<View> =
        listOf(root) + ((root as? ViewGroup)?.let { group -> (0 until group.childCount).flatMap { views(group.getChildAt(it)) } } ?: emptyList())
    private fun all(activity: Activity) = views(activity.findViewById(android.R.id.content))
    private fun button(activity: Activity, label: String): Button =
        all(activity).filterIsInstance<Button>().single { it.text.toString() == label }
    private fun texts(activity: Activity) = all(activity).filterIsInstance<TextView>().map { it.text.toString() }

    private fun home() = Robolectric.buildActivity(PatcherHomeActivity::class.java).setup().also { idle() }

    @Test fun `home shows two equal app cards, signing maintenance and the canonical uninstall`() {
        freshStore()
        val screen = home()
        val shown = texts(screen.get())
        assertTrue(shown.containsAll(listOf("YouTube", "Reddit", "PREVIEW", "SET UP YOUTUBE", "SET UP REDDIT",
            "Signing key", "Uninstall Patcher")))
        assertTrue("Reddit never needs MicroG", shown.any { it.contains("no MicroG needed") })
        // Short mono status lines; the sentences are sans body copy.
        assertTrue(shown.containsAll(listOf("Glasses setup · 4 steps", "Glasses setup · 3 steps")))
        assertTrue(button(screen.get(), "Export key").isEnabled)
        screen.pause().stop().destroy()
    }

    @Test fun `Reddit asks the hub for its setup without touching a held YouTube job`() {
        val store = sourceReady()
        val before = store.state.value
        val stock = File(store.work(before.workId), "prepare/stock.apk")
        registerHubEntry()
        val screen = home()
        button(screen.get(), "SET UP REDDIT").performClick()
        val started = hubRequest(screen.get(), PatcherContract.TARGET_REDDIT)
        // YouTube's prepared file is invisible to Reddit's hint and stays selected and on disk.
        assertEquals(PatcherContract.JOB_IDLE, started.intent.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        // Robolectric records the same start in both queues; nothing else was opened.
        assertEquals(started.intent.component, shadowOf(screen.get()).nextStartedActivity.component)
        assertNull(shadowOf(screen.get()).nextStartedActivity)
        assertEquals(before, store.state.value)
        assertEquals(PatcherContract.TARGET_YOUTUBE, store.state.value.targetId)
        assertTrue(stock.isFile)
        screen.pause().stop().destroy()
    }

    @Test fun `an older hub keeps Reddit patching here and still opens YouTube's setup`() {
        freshStore()
        registerHubEntry(declared = null)
        val screen = home()
        button(screen.get(), "SET UP REDDIT").performClick()
        // A plain start of Patcher's own screen; the hub is never asked.
        assertEquals(-1, shadowOf(screen.get()).nextStartedActivityForResult.requestCode)
        val started = shadowOf(screen.get()).nextStartedActivity
        assertEquals(PatchActivity::class.java.name, started.component!!.className)
        assertEquals(PatcherContract.TARGET_REDDIT, started.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        assertNotEquals(PatcherContract.ACTION_PATCH, started.action)
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Update Nexus to set up Reddit"))
        button(screen.get(), "SET UP YOUTUBE").performClick()
        hubRequest(screen.get(), PatcherContract.TARGET_YOUTUBE)
        screen.pause().stop().destroy()
    }

    @Test fun `YouTube asks the hub for its setup in result mode with the job hint`() {
        sourceReady()
        registerHubEntry()
        val screen = home()
        button(screen.get(), "SET UP YOUTUBE").performClick()
        val started = hubRequest(screen.get(), PatcherContract.TARGET_YOUTUBE)
        assertEquals(PatcherContract.JOB_SOURCE_READY, started.intent.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        screen.pause().stop().destroy()
    }

    @Test fun `without a hub entry YouTube still patches here and says why`() {
        freshStore()
        val screen = home()
        button(screen.get(), "SET UP YOUTUBE").performClick()
        val started = shadowOf(screen.get()).nextStartedActivity
        assertEquals(PatchActivity::class.java.name, started.component!!.className)
        assertEquals(PatcherContract.TARGET_YOUTUBE, started.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Update Nexus"))
        screen.pause().stop().destroy()
    }

    @Test fun `a running job locks the other app and the key, and reopens itself`() {
        val store = running()
        registerHubEntry()
        val screen = home()
        val reddit = button(screen.get(), "SET UP REDDIT")
        assertFalse(reddit.isEnabled)
        assertTrue(texts(screen.get()).contains("Locked while YouTube is being patched"))
        assertTrue(texts(screen.get()).containsAll(listOf("Patching", "Adding the glasses controls. Leaving this screen does not stop it.")))
        // A late tap reaching the listener still cannot switch the job's target.
        reddit.performClick()
        assertNull(shadowOf(screen.get()).nextStartedActivity)
        assertEquals(PatcherContract.TARGET_YOUTUBE, store.state.value.targetId)
        assertFalse(button(screen.get(), "Import key").isEnabled)
        assertFalse(button(screen.get(), "Export key").isEnabled)
        button(screen.get(), "OPEN RUNNING JOB").performClick()
        val reopened = shadowOf(screen.get()).nextStartedActivity
        assertEquals(PatchActivity::class.java.name, reopened.component!!.className)
        assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
        screen.pause().stop().destroy()
    }

    @Test fun `job hints describe only the requested app`() {
        val store = running()
        assertEquals(PatcherContract.JOB_RUNNING, AppTargets.jobHint(store.state.value, PatchTargets.youtube, false))
        assertEquals(PatcherContract.JOB_IDLE, AppTargets.jobHint(store.state.value, PatchTargets.reddit, false))
        val ready = sourceReady()
        assertEquals(PatcherContract.JOB_SOURCE_READY, AppTargets.jobHint(ready.state.value, PatchTargets.youtube, false))
        val success = ready.state.value.copy(status = PatchJobStatus.SUCCESS, result = "patched-x.apk")
        assertEquals(PatcherContract.JOB_READY, AppTargets.jobHint(success, PatchTargets.youtube, true))
        assertEquals("a missing file is not a ready result", PatcherContract.JOB_SOURCE_READY,
            AppTargets.jobHint(success, PatchTargets.youtube, false))
    }

    @Test fun `a picker opened before the job started cannot swap its source`() {
        val store = running()
        val before = store.state.value
        val screen = Robolectric.buildActivity(PatchActivity::class.java,
            Intent().putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)).setup()
        idle()
        PatchActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(screen.get(), 1, Activity.RESULT_OK, Intent().setData(Uri.parse("content://picker/other.apk")))
        idle()
        assertEquals(before, store.state.value)
        assertNull(shadowOf(screen.get()).nextStartedService)
        assertTrue(texts(screen.get()).contains("Locked while YouTube is being patched. Your file was not changed."))
        screen.pause().stop().destroy()
    }

    @Test fun `leaving a Nexus request tells the hub what is still running`() {
        running()
        val request = Intent(PatcherContract.ACTION_PATCH).putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)
        val screen = Robolectric.buildActivity(PatchActivity::class.java, request).create()
        shadowOf(screen.get()).setCallingActivity(ComponentName(PatcherContract.HUB_PACKAGE, "Setup"))
        shadowOf(screen.get()).setCallingPackage(PatcherContract.HUB_PACKAGE)
        screen.start().resume()
        idle()
        screen.get().onBackPressed()
        assertTrue(screen.get().isFinishing)
        assertEquals(Activity.RESULT_CANCELED, shadowOf(screen.get()).resultCode)
        assertEquals(PatcherContract.JOB_RUNNING, shadowOf(screen.get()).resultIntent.getStringExtra(PatcherContract.EXTRA_JOB_STATE))
        assertNull(shadowOf(screen.get()).resultIntent.data)
        screen.pause().stop().destroy()
    }

    @Test fun `opening the other app asks before discarding the held file`() {
        val store = sourceReady(PatchTargets.reddit)
        val held = store.state.value
        val keep = Robolectric.buildActivity(PatchActivity::class.java,
            Intent().putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)).setup()
        idle()
        val dialog = ShadowDialog.getLatestDialog() as AlertDialog
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick()
        idle()
        assertTrue(keep.get().isFinishing)
        assertEquals(held, store.state.value)
        keep.pause().stop().destroy()
        val switch = Robolectric.buildActivity(PatchActivity::class.java,
            Intent().putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)).setup()
        idle()
        (ShadowDialog.getLatestDialog() as AlertDialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick()
        idle()
        assertEquals(PatcherContract.TARGET_YOUTUBE, store.state.value.targetId)
        assertNull(store.state.value.stock)
        assertFalse(switch.get().isFinishing)
        switch.pause().stop().destroy()
    }

    @Test fun `a key import started while a job runs is refused and leaves the key alone`() {
        val store = running()
        val keyFile = File(RuntimeEnvironment.getApplication().filesDir, "signing/patcher.p12").apply { delete() }
        val screen = home()
        PatcherHomeActivity::class.java.getDeclaredField("backupPassword").apply { isAccessible = true }
            .set(screen.get(), "test-only-password".toCharArray())
        PatcherHomeActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(screen.get(), 3, Activity.RESULT_OK, Intent().setData(Uri.parse("content://picker/backup.ypk")))
        idle()
        assertFalse(keyFile.exists())
        assertFalse(store.keyMaintenance.value)
        assertEquals(PatchJobStatus.RUNNING, store.state.value.status)
        assertTrue(texts(screen.get()).contains("Wait for the running patch to finish, then try again."))
        screen.pause().stop().destroy()
    }

    @Test fun `an in-flight key operation refuses a new source and Patch from the patch screen`() {
        val store = sourceReady()
        val held = store.state.value
        val lease = store.beginKeyMaintenance()
        val home = home()
        assertFalse(button(home.get(), "Import key").isEnabled)
        assertFalse(button(home.get(), "Export key").isEnabled)
        home.pause().stop().destroy()
        val screen = Robolectric.buildActivity(PatchActivity::class.java,
            Intent().putExtra(PatcherContract.EXTRA_TARGET_ID, PatcherContract.TARGET_YOUTUBE)).setup()
        idle()
        // A late picker result: no preparation starts and the held file stays.
        PatchActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
            .invoke(screen.get(), 1, Activity.RESULT_OK, Intent().setData(Uri.parse("content://picker/other.apk")))
        idle()
        assertEquals(held, store.state.value)
        assertNull(shadowOf(screen.get()).nextStartedService)
        assertTrue(texts(screen.get()).contains(PatchJobStore.KEY_BUSY))
        // Releasing the lease, as the import's finally does on success or failure, allows the retry.
        store.endKeyMaintenance(lease)
        assertEquals(PatchJobStatus.RUNNING, store.patch("hash", listOf("Rokid controls")).status)
        screen.pause().stop().destroy()
    }

    @Test fun `a finished or failed key import releases the lock so a job can start`() {
        val store = sourceReady()
        val app = RuntimeEnvironment.getApplication()
        val keyFile = File(app.filesDir, "signing/patcher.p12").apply { delete() }
        // A test-only key, never the release signer.
        val fixture = SigningKey(File(app.cacheDir, "fixture/key.p12"))
        val backup = File(app.cacheDir, "fixture/backup.ypk").apply { parentFile!!.mkdirs(); outputStream().use { fixture.export(it, "test-only-password".toCharArray()) } }
        val screen = home()
        fun import(password: String) {
            PatcherHomeActivity::class.java.getDeclaredField("backupPassword").apply { isAccessible = true }
                .set(screen.get(), password.toCharArray())
            PatcherHomeActivity::class.java.getDeclaredMethod("onActivityResult", Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType, Intent::class.java).apply { isAccessible = true }
                .invoke(screen.get(), 3, Activity.RESULT_OK, Intent().setData(Uri.fromFile(backup)))
            assertTrue("the import holds the store lock while it runs", store.keyMaintenance.value)
            assertThrows(IllegalStateException::class.java) { store.patch("hash", listOf("Rokid controls")) }
            val deadline = System.currentTimeMillis() + 30_000
            while (store.keyMaintenance.value && System.currentTimeMillis() < deadline) { idle(); Thread.sleep(20) }
            idle()
            assertFalse(store.keyMaintenance.value)
        }
        import("wrong-password-123")
        assertFalse(keyFile.exists())
        import("test-only-password")
        assertEquals(fixture.fingerprint(), SigningKey(keyFile).fingerprint())
        assertTrue(texts(screen.get()).contains("Signing key imported."))
        assertEquals(PatchJobStatus.RUNNING, store.patch("hash", listOf("Rokid controls")).status)
        screen.pause().stop().destroy()
    }
}
