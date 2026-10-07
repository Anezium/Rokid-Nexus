package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchPictureInPictureTest {
    @Test fun pipIsAvailableByDefaultOnlyForSupportedRunningJobs() {
        PatchJobStatus.entries.forEach { status ->
            assertEquals(status == PatchJobStatus.RUNNING, PatchPictureInPicture.shouldEnter(status, true))
            assertFalse(PatchPictureInPicture.shouldEnter(status, false))
        }
        assertTrue(PatchPictureInPicture.params(true).isAutoEnterEnabled)
        assertFalse(PatchPictureInPicture.params(false).isAutoEnterEnabled)
        assertEquals(android.util.Rational(16, 9), PatchPictureInPicture.params(true).aspectRatio)
    }

    @Test fun cancelUsesTheServiceActionForTheCurrentJobAndTerminalStatesRemoveIt() {
        val screen = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = screen.get()
        org.robolectric.Shadows.shadowOf(activity.packageManager)
            .setSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE, true)
        val pip = PatchPictureInPicture(activity)
        val state = PatchJobState(id = "current-job", status = PatchJobStatus.RUNNING)
        val params = pip.params(state)
        assertTrue(params.isAutoEnterEnabled)
        val action = params.actions.single()
        assertEquals("Cancel", action.title.toString())
        val cancel = org.robolectric.Shadows.shadowOf(action.actionIntent).savedIntent
        assertEquals(PatchJobService::class.java.name, cancel.component!!.className)
        assertEquals(PatchJobService.CANCEL, cancel.action)
        assertEquals(state.id, cancel.getStringExtra(PatchJobService.JOB_ID))
        PatchJobStatus.entries.filter { it != PatchJobStatus.RUNNING }.forEach { status ->
            val terminal = pip.params(state.copy(status = status))
            assertFalse(terminal.isAutoEnterEnabled)
            assertTrue(terminal.actions.isEmpty())
        }
        activity.finish()
        assertFalse(pip.params(state).isAutoEnterEnabled)
        screen.pause().stop().destroy()
    }

    @Test fun terminalPipMovesBehindTheUserWithoutFinishingTheResultActivity() {
        val screen = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = screen.get()
        val pip = PatchPictureInPicture(activity)
        activity.enterPictureInPictureMode(PatchPictureInPicture.params(true))
        assertTrue(activity.isInPictureInPictureMode)
        pip.update(PatchJobState(status = PatchJobStatus.SUCCESS))
        assertFalse(activity.isInPictureInPictureMode)
        assertFalse(activity.isFinishing)
        screen.pause().stop().destroy()
    }
    @Test fun pipReparentingRefreshesTheNotificationReturnTaskWithoutEndingTheHubResult() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        java.io.File(context.filesDir, "patch-job.json").delete()
        val store = PatchJobStore(context.filesDir)
        PatchJobStore::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(null, store)
        val prepare = store.prepare()
        java.io.File(store.work(prepare.id), "stock.apk").writeBytes(byteArrayOf(1))
        store.change(prepare.id) { it.copy(status = PatchJobStatus.READY, stock = "stock.apk") }
        val job = store.patch("hash", listOf("patch"))
        val screen = org.robolectric.Robolectric.buildActivity(PatchActivity::class.java).setup()
        val activity = screen.get()
        val task = PatchActivity::class.java.getDeclaredField("liveTaskId").apply { isAccessible = true }
        task.set(null, -17)
        activity.onPictureInPictureModeChanged(true, android.content.res.Configuration())
        assertEquals(activity.taskId, task.get(null))
        assertFalse(activity.isFinishing)
        assertEquals(job, store.state.value)
        activity.onPictureInPictureModeChanged(false, android.content.res.Configuration())
        assertEquals(activity.taskId, task.get(null))
        assertFalse(activity.isFinishing)
        screen.pause().stop().destroy()
    }

}
