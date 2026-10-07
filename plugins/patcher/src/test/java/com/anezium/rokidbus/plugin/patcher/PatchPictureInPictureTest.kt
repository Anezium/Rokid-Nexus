package com.anezium.rokidbus.plugin.patcher

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PatchPictureInPictureTest {
    @Test fun onlyRunningJobsCanEnterAndTheExperimentCanBeDisabled() {
        PatchJobStatus.entries.forEach { status ->
            assertEquals(status == PatchJobStatus.RUNNING, PatchPictureInPicture.shouldEnter(status, true, true))
            assertFalse(PatchPictureInPicture.shouldEnter(status, false, true))
            assertFalse(PatchPictureInPicture.shouldEnter(status, true, false))
        }
        assertTrue(PatchPictureInPicture.params(true).isAutoEnterEnabled)
        assertFalse(PatchPictureInPicture.params(false).isAutoEnterEnabled)
        assertEquals(android.util.Rational(16, 9), PatchPictureInPicture.params(true).aspectRatio)
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
