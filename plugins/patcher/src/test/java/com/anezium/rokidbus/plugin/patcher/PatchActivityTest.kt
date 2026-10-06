package com.anezium.rokidbus.plugin.patcher

import android.app.Activity
import android.content.Intent
import android.content.ComponentName
import android.os.Looper
import android.view.WindowManager
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
        fun cancelButton(view: android.view.View): android.view.View? {
            if (view is android.widget.Button && view.text == "Cancel patching") return view
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
        val first = Robolectric.buildActivity(PatchActivity::class.java, request).setup()
        first.pause().stop().destroy()
        val context = RuntimeEnvironment.getApplication()
        val output = File(context.filesDir, "results/patched-complete.apk").apply { parentFile!!.mkdirs(); writeBytes(byteArrayOf(1)) }
        store.change(job.id) { it.copy(status = PatchJobStatus.SUCCESS, result = output.name) }
        val reopened = Robolectric.buildActivity(PatchActivity::class.java, request).create()
        shadowOf(reopened.get()).setCallingActivity(ComponentName("example.hub", "example.hub.Setup"))
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

    @Test fun explicitRequestWithoutTargetFailsClosed() {
        val store = activeStore()
        val screen = Robolectric.buildActivity(PatchActivity::class.java,
            Intent(PatcherContract.ACTION_PATCH)).setup()
        assertEquals(Activity.RESULT_CANCELED, shadowOf(screen.get()).resultCode)
        assertEquals(PatchJobStatus.PREPARING, store.state.value.status)
        screen.pause().stop().destroy()
    }
}
