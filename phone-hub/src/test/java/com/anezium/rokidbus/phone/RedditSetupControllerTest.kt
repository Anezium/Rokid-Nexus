package com.anezium.rokidbus.phone

import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.RedditSetupContract
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class RedditSetupControllerTest {
    @Test fun `Reddit setup rejects a YouTube inventory and signer mismatch before any transfer`() {
        val context = RuntimeEnvironment.getApplication()
        val sent = mutableListOf<BusEnvelope>()
        val file = File.createTempFile("reddit", ".apk", context.cacheDir).apply { writeText("checked APK fixture") }
        val apk = PreparedYoutubeApk(file,
            ArtifactArchiveInfo(RedditSetupContract.REDDIT, 2614001, listOf(byteArrayOf(1, 2, 3))),
            29, YoutubeApkSource.sha256(file))
        var uploads = 0
        val controller = YoutubeSetupController(context, { true }, { true },
            { sent += it; null }, { _, _ -> uploads++; true }, target = NativeSetupTarget.REDDIT,
            worker = ImmediateExecutor(), source = { _, _, _ -> apk })
        try {
            controller.start()
            controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL).setData(Uri.parse("content://patcher/result")))
            shadowOf(Looper.getMainLooper()).idle()
            val request = sent.last().payload
            assertNotNull(RedditSetupContract.requestId(request))
            val id = request.getString("requestId")
            assertFalse(controller.handleRemote(BusEnvelope(path = NativeAppContract.RESULT_PATH,
                payload = YoutubeSetupContract.result(id, 32, YoutubeSetupContract.PACKAGES.map { YoutubePackage(it) }))))
            assertTrue(controller.handleRemote(BusEnvelope(path = NativeAppContract.RESULT_PATH,
                payload = RedditSetupContract.result(id, 32, listOf(YoutubePackage(RedditSetupContract.REDDIT,
                    2614001, "a".repeat(64), true))))))
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0, uploads)
            assertTrue(RedditSetupStateStore.state.message.contains("different signing key"))
            assertTrue(RedditSetupStateStore.state.canInstall)
            assertTrue(file.isFile)
        } finally {
            controller.close()
            file.delete()
        }
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
