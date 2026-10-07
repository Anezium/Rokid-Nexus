package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.shared.PatcherContract
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.android.controller.ActivityController
import org.robolectric.shadows.ShadowActivity
import org.robolectric.shadows.ShadowPendingIntent
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32], shadows = [PatchResultHandoffTest.TaskActivity::class,
    PatchResultHandoffTest.ResultProvider::class, PatchResultHandoffTest.HubReturnToken::class])
class PatchResultHandoffTest {
    @Implements(PendingIntent::class)
    class HubReturnToken : ShadowPendingIntent() {
        var sendOptions: Bundle? = null
        var sentAfterResult = false

        @Implementation override fun send(context: Context, code: Int, intent: Intent?,
            onFinished: PendingIntent.OnFinished?, handler: Handler?, permission: String?, options: Bundle?) {
            sendOptions = options
            val activity = context as Activity
            sentAfterResult = activity.isFinishing && shadowOf(activity).resultCode == Activity.RESULT_OK
            super.send(context, code, intent, onFinished, handler, permission, options)
        }
    }

    @Implements(Activity::class)
    class TaskActivity : ShadowActivity() {
        var currentTask = 10
        @Implementation override fun getTaskId(): Int = currentTask
    }

    // FileProvider's canonical root matching assumes POSIX paths. Keep task/result
    // regressions runnable on Windows; the real provider has separate POSIX coverage.
    @Implements(FileProvider::class)
    class ResultProvider {
        companion object {
            @JvmStatic @Implementation
            fun getUriForFile(context: Context, authority: String, file: File): Uri =
                Uri.parse("content://$authority/results/${file.name}")
        }
    }

    private val hub = HubTarget.PHONE.packageName
    private val launcher get() = ComponentName(hub, "$hub.MainActivity")
    private val setup get() = ComponentName(hub, "$hub.YoutubeSetupActivity")

    @Before fun registerHubLauncher() {
        val manager = shadowOf(RuntimeEnvironment.getApplication().packageManager)
        manager.addActivityIfNotPresent(launcher).exported = true
        manager.addIntentFilterForActivity(launcher, IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        })
    }

    private fun runningStore(): PatchJobStore {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "patch-job.json").delete()
        val store = PatchJobStore(context.filesDir)
        installStore(store)
        val job = store.prepare()
        store.change(job.id) { it.copy(status = PatchJobStatus.RUNNING, progress = PatchProgress(PatchPhase.APPLY_PATCHES)) }
        return store
    }

    private fun installStore(store: PatchJobStore) {
        PatchJobStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, store)
    }

    private fun returnToken(creator: String = hub): PendingIntent = PendingIntent.getActivity(
        RuntimeEnvironment.getApplication(), 10, Intent().setComponent(setup)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_CANCEL_CURRENT,
    ).also { shadowOf(it).setCreatorPackage(creator) }

    private fun request(store: PatchJobStore, withReturn: Boolean = true) = Intent(PatcherContract.ACTION_PATCH)
        .putExtra(PatcherContract.EXTRA_TARGET_ID, store.state.value.targetId)
        .apply { if (withReturn) putExtra(PatcherContract.EXTRA_RETURN_TO_HUB, returnToken()) }

    private fun screen(intent: Intent, callingPackage: String? = hub): ActivityController<PatchActivity> =
        Robolectric.buildActivity(PatchActivity::class.java, intent).also {
            shadowOf(it.get()).setIsTaskRoot(false)
            if (callingPackage != null) {
                shadowOf(it.get()).setCallingActivity(ComponentName(callingPackage, "$callingPackage.YoutubeSetupActivity"))
                shadowOf(it.get()).setCallingPackage(callingPackage)
            }
        }

    private fun succeed(store: PatchJobStore) {
        val output = File(RuntimeEnvironment.getApplication().filesDir, "results/patched-complete.apk").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        store.change(store.state.value.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
    }

    private fun assertDelivered(screen: ActivityController<PatchActivity>, store: PatchJobStore) {
        val activity = shadowOf(screen.get())
        assertTrue(screen.get().isFinishing)
        assertTrue(store.state.value.delivered)
        assertEquals(Activity.RESULT_OK, activity.resultCode)
        val data = requireNotNull(activity.resultIntent)
        assertEquals("content", data.data!!.scheme)
        assertEquals(store.state.value.targetId, data.getStringExtra(PatcherContract.EXTRA_TARGET_ID))
        assertEquals("application/vnd.android.package-archive", data.type)
        assertTrue(data.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertEquals(data.data, data.clipData!!.getItemAt(0).uri)
    }

    private fun assertHubBroughtForward(activity: PatchActivity) {
        val token = requireNotNull(activity.intent.getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        assertTrue((shadowOf(token) as HubReturnToken).sentAfterResult)
        val launch = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(setup, launch.component)
        assertNull(launch.action)
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT, launch.flags)
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    private fun assertLegacyHubBroughtForward(activity: PatchActivity) {
        val launch = requireNotNull(shadowOf(activity).nextStartedActivity)
        assertEquals(launcher, launch.component)
        assertEquals(Intent.ACTION_MAIN, launch.action)
        assertTrue(launch.hasCategory(Intent.CATEGORY_LAUNCHER))
        assertTrue(launch.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(0, launch.flags and (Intent.FLAG_ACTIVITY_CLEAR_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_MULTIPLE_TASK))
        assertNull(shadowOf(activity).nextStartedActivity)
    }

    @Test fun anOlderHubWithoutTheReturnExtraUsesTheLauncherFallback() {
        val store = runningStore()
        val screen = screen(request(store, withReturn = false)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        (shadowOf(screen.get()) as TaskActivity).currentTask = 20
        succeed(store)
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(screen, store)
        assertLegacyHubBroughtForward(screen.get())
        screen.pause().stop().destroy()
    }

    @Test fun anUntrustedMalformedOrCancelledReturnTokenNeverFallsBackToTheLauncher() {
        listOf("untrusted", "cancelled", "malformed").forEach { kind ->
            val token = when (kind) {
                "untrusted" -> returnToken("untrusted.app")
                "cancelled" -> returnToken().also { it.cancel() }
                else -> "malformed"
            }
            val store = runningStore()
            val request = request(store, withReturn = false).apply {
                if (token is PendingIntent) putExtra(PatcherContract.EXTRA_RETURN_TO_HUB, token)
                else putExtra(PatcherContract.EXTRA_RETURN_TO_HUB, token as String)
            }
            val screen = screen(request).setup()
            shadowOf(Looper.getMainLooper()).idle()
            (shadowOf(screen.get()) as TaskActivity).currentTask = 20
            succeed(store)
            shadowOf(Looper.getMainLooper()).idle()
            assertDelivered(screen, store)
            assertNull(shadowOf(screen.get()).nextStartedActivity)
            screen.pause().stop().destroy()
        }
    }

    @Test @Config(sdk = [34]) fun theSenderOptsInToForegroundLaunchPrivilegesOnAndroid14() {
        val store = runningStore()
        val screen = screen(request(store)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        (shadowOf(screen.get()) as TaskActivity).currentTask = 20
        succeed(store)
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(screen, store)
        val launch = requireNotNull(shadowOf(screen.get()).nextStartedActivityForResult)
        assertEquals(setup, launch.intent.component)
        val token = requireNotNull(screen.get().intent.getParcelableExtra<PendingIntent>(PatcherContract.EXTRA_RETURN_TO_HUB))
        val options = ActivityOptions::class.java.getDeclaredMethod("fromBundle", Bundle::class.java)
            .invoke(null, (shadowOf(token) as HubReturnToken).sendOptions) as ActivityOptions
        assertEquals(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            options.pendingIntentBackgroundActivityStartMode)
        screen.pause().stop().destroy()
    }

    @Test fun notificationResumeAfterTaskReparentingReturnsTheResultAndBringsNexusForward() {
        val store = runningStore()
        val screen = screen(request(store)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        screen.pause().stop()
        (shadowOf(screen.get()) as TaskActivity).currentTask = 20
        succeed(store)
        screen.newIntent(Intent().putExtra(PatchActivity.EXTRA_READY_JOB_ID, store.state.value.id))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.get().isFinishing)
        assertNull(shadowOf(screen.get()).nextStartedActivity)
        screen.start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(screen, store)
        assertHubBroughtForward(screen.get())
        screen.pause().stop().destroy()
    }

    @Test fun successInTheCallersTaskReturnsWithoutLaunchingTheHub() {
        val store = runningStore()
        val screen = screen(request(store)).setup()
        shadowOf(Looper.getMainLooper()).idle()
        succeed(store)
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(screen, store)
        assertNull(shadowOf(screen.get()).nextStartedActivity)
        screen.pause().stop().destroy()
    }

    @Test fun notificationRecreationRestoresTheLaunchingTaskAndReadsTheResultFromDisk() {
        val store = runningStore()
        val request = request(store)
        val first = screen(request).setup()
        shadowOf(Looper.getMainLooper()).idle()
        first.pause().stop()
        (shadowOf(first.get()) as TaskActivity).currentTask = 20
        val saved = Bundle()
        first.saveInstanceState(saved).destroy()
        succeed(store)
        val restoredStore = PatchJobStore(RuntimeEnvironment.getApplication().filesDir)
        installStore(restoredStore)
        val reopened = screen(Intent(request).putExtra(PatchActivity.EXTRA_READY_JOB_ID, restoredStore.state.value.id))
        (shadowOf(reopened.get()) as TaskActivity).currentTask = 20
        reopened.create(saved).start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(reopened, restoredStore)
        assertHubBroughtForward(reopened.get())
        reopened.pause().stop().destroy()
    }

    @Test fun recreatedTaskRootWithoutSavedStateStillBringsTheAuthenticatedCallerForward() {
        val store = runningStore()
        succeed(store)
        val screen = screen(request(store).putExtra(PatchActivity.EXTRA_READY_JOB_ID, store.state.value.id))
        shadowOf(screen.get()).setIsTaskRoot(true)
        screen.setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertDelivered(screen, store)
        assertHubBroughtForward(screen.get())
        screen.pause().stop().destroy()
    }

    @Test fun standaloneAndUntrustedNotificationOpensNeverBringNexusForward() {
        listOf(null, "untrusted.app").forEach { caller ->
            val store = runningStore()
            succeed(store)
            val intent = request(store).putExtra(PatchActivity.EXTRA_READY_JOB_ID, store.state.value.id)
            if (caller == null) intent.action = null
            val screen = screen(intent, caller)
            shadowOf(screen.get()).setIsTaskRoot(true)
            screen.setup()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(screen.get().isFinishing)
            assertFalse(store.state.value.delivered)
            assertNull(shadowOf(screen.get()).nextStartedActivity)
            screen.pause().stop().destroy()
        }
    }

    @Test fun failureCancellationAndInterruptionRemainVisibleInTheSeparateTask() {
        listOf(PatchJobStatus.FAILURE, PatchJobStatus.CANCELLED, PatchJobStatus.INTERRUPTED).forEach { status ->
            val store = runningStore()
            val screen = screen(request(store)).setup()
            shadowOf(Looper.getMainLooper()).idle()
            (shadowOf(screen.get()) as TaskActivity).currentTask = 20
            shadowOf(screen.get()).setIsTaskRoot(true)
            store.change(store.state.value.id) { it.copy(status = status, message = "Review this outcome.") }
            screen.newIntent(Intent().putExtra(PatchActivity.EXTRA_READY_JOB_ID, store.state.value.id))
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(screen.get().isFinishing)
            assertFalse(store.state.value.delivered)
            assertNull(shadowOf(screen.get()).nextStartedActivity)
            screen.get().onBackPressed()
            assertEquals(Activity.RESULT_CANCELED, shadowOf(screen.get()).resultCode)
            assertNull(shadowOf(screen.get()).nextStartedActivity)
            screen.pause().stop().destroy()
        }
    }

    @Test fun previouslyDeliveredResultDoesNotBringNexusForwardOnANewNotificationOpen() {
        val store = runningStore()
        succeed(store)
        store.markDelivered(store.state.value.id)
        val screen = screen(request(store).putExtra(PatchActivity.EXTRA_READY_JOB_ID, store.state.value.id))
        shadowOf(screen.get()).setIsTaskRoot(true)
        screen.setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(screen.get().isFinishing)
        assertNull(shadowOf(screen.get()).nextStartedActivity)
        screen.pause().stop().destroy()
    }
}
