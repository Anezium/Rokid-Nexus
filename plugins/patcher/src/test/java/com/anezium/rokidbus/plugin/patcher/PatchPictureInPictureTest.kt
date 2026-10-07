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
        assertEquals("Cancel patching", action.contentDescription.toString())
        assertEquals(R.drawable.ic_patch_cancel, action.icon.resId)
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

    @Test fun compactWindowShowsTheClockLineAndBarAndClosesInTheOutcomeColour() {
        val screen = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).setup()
        val activity = screen.get()
        val pip = PatchPictureInPicture(activity)
        val running = PatchJobState(id = "job", status = PatchJobStatus.RUNNING,
            progress = PatchProgress(PatchPhase.APPLY_PATCHES, 7.0 / 23, "Hide ads", 7, 23), elapsedMs = 134_000)
        pip.show(running)
        val views = requireNotNull(pip.compact)
        assertSame(views.root, (activity.findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0))
        assertEquals("PATCHER · YOUTUBE", views.label.text.toString())
        assertEquals("2:14", views.clock.text.toString())
        assertEquals("Applying patches · 7 of 23", views.line.text.toString())
        assertEquals(com.anezium.rokidbus.client.ui.NexusUi.INK, views.line.currentTextColor)
        assertEquals((7.0 / 23).toFloat(), requireNotNull(views.bar.shownFraction), 1e-6f)
        pip.update(running.copy(progress = PatchProgress(PatchPhase.COMPILE, substep = PatchSubstep.DEX, workTotal = 900), elapsedMs = 150_000))
        assertSame("the window updates in place", views, pip.compact)
        assertEquals("2:30", views.clock.text.toString())
        assertEquals("Compiling code · 900 classes", views.line.text.toString())
        assertNull(views.bar.shownFraction)
        pip.update(running.copy(status = PatchJobStatus.CANCELLED, elapsedMs = 151_000))
        assertEquals("Patch cancelled", views.line.text.toString())
        assertEquals(com.anezium.rokidbus.client.ui.NexusUi.AMBER, views.line.currentTextColor)
        assertEquals(1f, requireNotNull(views.bar.shownFraction), 0f)
        assertEquals(1f, views.dot.alpha, 0f)
        pip.update(running.copy(status = PatchJobStatus.FAILURE))
        assertEquals("Patch failed", views.line.text.toString())
        assertEquals(com.anezium.rokidbus.client.ui.NexusUi.DANGER, views.line.currentTextColor)
        pip.update(running.copy(status = PatchJobStatus.SUCCESS))
        assertEquals("Ready to install", views.line.text.toString())
        assertEquals(com.anezium.rokidbus.client.ui.NexusUi.GREEN, views.line.currentTextColor)
        pip.expanded()
        assertNull(pip.compact)
        screen.pause().stop().destroy()
    }
}
