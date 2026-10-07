package com.anezium.rokidbus.phone

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
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

    private fun launchPicker(): Intent {
        text("CHOOSE PATCHED YOUTUBE APK").single().performClick()
        val started = shadowOf(screen.get()).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, started.intent.action)
        return started.intent
    }

    private fun pickerResult(picker: Intent) = shadowOf(screen.get()).receiveResult(picker, Activity.RESULT_OK,
        Intent().setData(Uri.parse("content://picker/youtube.apk")))

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
        val title = text("1. MicroG on the glasses").single()
        text("More").first().performClick()
        val source = text("MICROG SOURCE").single()
        assertEquals(View.VISIBLE, (source.parent as View).visibility)
        val state = YoutubeSetupStateStore.state
        YoutubeSetupStateStore.update(state.copy(message = "Downloading MicroG… 40%"))
        assertSame(title, text("1. MicroG on the glasses").single())
        assertSame(source, text("MICROG SOURCE").single())
        assertEquals(1, text("Downloading MicroG… 40%").size)
        YoutubeSetupStateStore.update(state.copy(inventory = null, message = "Checking"))
        assertNotSame(title, text("1. MicroG on the glasses").single())
        assertEquals(View.VISIBLE, (text("MICROG SOURCE").single().parent as View).visibility)
        assertEquals(View.GONE, (text("KEYBOARD & REMOTE").single().parent as View).visibility)
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

        assertEquals(1, text("Done — YouTube ${archive.versionName} is installed.").size)
        val reinstall = text("Reinstall / update").single()
        assertTrue(reinstall.isEnabled)
        assertTrue(views().filterIsInstance<TextView>().none {
            it.text.contains("Approve Patcher first") || it.text.toString() == "PATCH AND INSTALL"
        })
        reinstall.performClick()
        assertEquals(PatcherHandoff.reviewIntent(screen.get()).component,
            shadowOf(screen.get()).nextStartedActivity.component)
    }

    @Test fun `a waiting patched APK leads step 3 with Install on glasses and says when to connect them`() {
        val picker = launchPicker()
        pickerResult(picker)
        idle()
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        val label = requireNotNull(YoutubeSetupStateStore.state.preparedLabel)
        assertEquals(1, text("Ready to install — $label is patched and waiting.").size)
        val install = text("INSTALL ON GLASSES").single()
        assertTrue(install.isEnabled)
        assertTrue(text("PATCH AND INSTALL").isEmpty())
        assertTrue(text("RETRY PREPARED INSTALL").isEmpty())
        assertEquals(View.GONE, (text("PATCH AGAIN").single().parent as View).visibility)
        // The glasses dropped: the line says what to do next and the primary action stays put.
        YoutubeSetupStateStore.update(YoutubeSetupStateStore.state.copy(inventory = null,
            message = "The glasses disconnected. Reconnect and refresh before continuing."))
        assertEquals(1, text("Ready to install — $label is patched and waiting. Connect the glasses, then install.").size)
        assertEquals(1, text("INSTALL ON GLASSES").size)
        val before = sent.size
        text("INSTALL ON GLASSES").single().performClick()
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
