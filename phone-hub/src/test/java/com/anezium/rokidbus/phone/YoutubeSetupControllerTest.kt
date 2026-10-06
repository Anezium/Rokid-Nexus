package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.YoutubePackage
import com.anezium.rokidbus.shared.YoutubeSetupContract
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.time.Duration
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class YoutubeSetupControllerTest {
    private lateinit var controller: YoutubeSetupController
    private lateinit var apk: PreparedYoutubeApk
    private val sent = mutableListOf<BusEnvelope>()
    private var uploads = 0
    private var completion: ((Boolean) -> Unit)? = null
    private var connected = true

    @Before fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        val file = File.createTempFile("youtube", ".apk", context.cacheDir).apply { writeText("test apk") }
        apk = PreparedYoutubeApk(file,
            ArtifactArchiveInfo(YoutubeSetupContract.YOUTUBE, 200, listOf(byteArrayOf(1, 2, 3))),
            28, YoutubeApkSource.sha256(file))
        controller = YoutubeSetupController(context,
            connected = { connected }, installReady = { connected },
            send = { sent += it; null },
            upload = { _, callback -> uploads++; completion = callback; true },
            worker = ImmediateExecutor(), source = { _, _, _ -> apk })
        controller.start()
    }

    @After fun teardown() {
        controller.close()
        completion?.invoke(false)
        idle()
        apk.file.delete()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun requestId() = sent.last().payload.getString("requestId")
    private fun reply(id: String = requestId(), installed: Boolean = false, signer: String = YoutubeApkPolicy.signer(apk.archive)) {
        val apps = YoutubeSetupContract.PACKAGES.map { name ->
            if (name == apk.archive.packageName && installed) YoutubePackage(name, 200, signer, true)
            else YoutubePackage(name)
        }
        controller.handleRemote(BusEnvelope(path = NativeAppContract.RESULT_PATH,
            payload = YoutubeSetupContract.result(id, 32, apps)))
        idle()
    }

    private fun prepare() {
        controller.handle(Intent(YoutubeSetupController.IMPORT_YOUTUBE).setData(Uri.parse("content://test/youtube")))
        idle()
        assertTrue(YoutubeSetupStateStore.state.canInstall)
    }

    @Test fun `refresh times out and ignores late replies`() {
        controller.handle(Intent(YoutubeSetupController.REFRESH))
        val old = requestId()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(13))
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertNull(YoutubeSetupStateStore.state.inventory)
        reply(old, installed = true)
        assertNull(YoutubeSetupStateStore.state.inventory)
        controller.handle(Intent(YoutubeSetupController.REFRESH))
        reply()
        assertNotNull(YoutubeSetupStateStore.state.inventory)
    }

    @Test fun `installation requires fresh inventory and confirms package after SDK success`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        assertEquals(0, uploads)
        reply()
        assertEquals(1, uploads)
        assertTrue(YoutubeSetupStateStore.state.busy)
        completion!!(true)
        idle()
        assertTrue(YoutubeSetupStateStore.state.busy)
        reply(installed = true)
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertTrue(YoutubeSetupStateStore.state.message.contains("is installed"))
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertEquals(YoutubeInstalledApk(200, apk.archive.versionName, YoutubeApkPolicy.signer(apk.archive)),
            YoutubeSetupInstallHistory(RuntimeEnvironment.getApplication()).installed(apk.archive.packageName))
    }

    @Test fun `signer mismatch prevents any upload`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply(installed = true, signer = "b".repeat(64))
        assertEquals(0, uploads)
        assertEquals("This APK has a different signing key. Use an update signed with the original key; " +
            "Nexus will not remove the installed app. If YouTube Patcher produced the installed app, import the " +
            "key backup you exported from it; otherwise remove YouTube from the glasses by hand only if you " +
            "accept losing its data.", YoutubeSetupStateStore.state.message)
    }

    @Test fun `duplicate install requests cannot replace the active transfer`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        controller.handle(Intent(YoutubeSetupController.PREPARE_MICROG))
        assertEquals(1, uploads)
        completion!!(false)
        idle()
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertTrue(YoutubeSetupStateStore.state.message.contains("Installation failed"))
    }

    @Test fun `SDK success alone is not installation confirmation`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        completion!!(true)
        idle()
        reply(installed = false)
        assertTrue(YoutubeSetupStateStore.state.message.contains("could not be confirmed"))
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertNull(YoutubeSetupInstallHistory(RuntimeEnvironment.getApplication()).installed(apk.archive.packageName))
    }

    @Test fun `finished upload deadline cannot cancel a later launch`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        completion!!(false)
        idle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(175))
        controller.handle(Intent(YoutubeSetupController.OPEN_MICROG))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(6))
        assertTrue(YoutubeSetupStateStore.state.busy)
        assertTrue(YoutubeSetupStateStore.state.message.contains("Opening"))
    }

    @Test fun `changed staged APK cannot be uploaded`() {
        prepare()
        apk.file.appendText("changed")
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        assertEquals(0, uploads)
        assertFalse(YoutubeSetupStateStore.state.busy)
    }

    @Test fun `disconnect invalidates inventory and late install callbacks`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        connected = false
        controller.onLinkChanged(false, false)
        idle()
        completion!!(true)
        idle()
        assertNull(YoutubeSetupStateStore.state.inventory)
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertTrue(YoutubeSetupStateStore.state.message.contains("disconnected"))
    }

    @Test fun `link loss neither cancels a download nor overwrites the prepared message`() {
        controller.close()
        var cancelledDuringDownload: Boolean? = null
        lateinit var downloading: YoutubeSetupController
        downloading = YoutubeSetupController(RuntimeEnvironment.getApplication(),
            connected = { false }, installReady = { false },
            send = { sent += it; null },
            upload = { _, _ -> false },
            worker = ImmediateExecutor(), source = { _, cancelled, _ ->
                downloading.onLinkChanged(false, false)
                idle()
                cancelledDuringDownload = cancelled()
                apk
            })
        downloading.start()
        downloading.handle(Intent(YoutubeSetupController.PREPARE_MICROG))
        idle()
        assertEquals(false, cancelledDuringDownload)
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertTrue(YoutubeSetupStateStore.state.message.contains("ready"))
        downloading.onLinkChanged(false, false)
        idle()
        assertTrue(YoutubeSetupStateStore.state.message.contains("ready"))
        downloading.close()
    }

    @Test fun `upload timeout retains staged file and stale success cannot mark it installed`() {
        prepare()
        controller.handle(Intent(YoutubeSetupController.INSTALL))
        reply()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(181))
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertTrue(apk.file.exists())
        completion!!(true)
        idle()
        assertTrue(YoutubeSetupStateStore.state.message.contains("not confirmed"))
    }

    @Test fun `patcher result automatically prepares then installs with fresh confirmation`() {
        controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL)
            .setData(Uri.parse("content://patcher/output.apk"))
            .putExtra("packageName", YoutubeSetupContract.MICROG)
            .putExtra("sha256", "forged"))
        idle()
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertTrue(YoutubeSetupStateStore.state.busy)
        assertEquals(0, uploads)
        reply()
        assertEquals(1, uploads)
        completion!!(true)
        idle()
        reply(installed = true)
        assertTrue(YoutubeSetupStateStore.state.message.contains("is installed"))
    }

    @Test fun `MicroG primary action automatically installs after prepare`() {
        apk = apk.copy(archive = apk.archive.copy(packageName = YoutubeSetupContract.MICROG))
        controller.handle(Intent(YoutubeSetupController.INSTALL_MICROG))
        idle()
        assertEquals(0, uploads)
        assertTrue(YoutubeSetupStateStore.state.busy)
        reply()
        assertEquals(1, uploads)
        completion!!(true)
        idle()
        reply(installed = true)
        assertTrue(YoutubeSetupStateStore.state.message.contains("is installed"))
    }

    @Test fun `automatic result import retains signer and file integrity guards`() {
        controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL)
            .setData(Uri.parse("content://patcher/output.apk")))
        idle()
        reply(installed = true, signer = "b".repeat(64))
        assertEquals(0, uploads)
        assertTrue(YoutubeSetupStateStore.state.message.contains("different signing key"))
    }

    @Test fun `automatic install retains Wi-Fi requirement and prepared retry`() {
        connected = false
        controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL)
            .setData(Uri.parse("content://patcher/output.apk")))
        idle()
        assertEquals(0, uploads)
        assertTrue(YoutubeSetupStateStore.state.canInstall)
        assertTrue(YoutubeSetupStateStore.state.message.contains("Wi-Fi"))
        assertFalse(YoutubeSetupStateStore.state.busy)
    }

    @Test fun `non-content patcher result never prepares or uploads`() {
        controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL)
            .setData(Uri.parse("file:///untrusted.apk")))
        idle()
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertEquals(0, uploads)
        assertTrue(sent.isEmpty())
    }

    @Test fun `failed APK validation stops the automatic chain`() {
        controller.close()
        controller = YoutubeSetupController(RuntimeEnvironment.getApplication(),
            connected = { true }, installReady = { true },
            send = { sent += it; null },
            upload = { _, _ -> uploads++; true },
            worker = ImmediateExecutor(), source = { _, _, _ ->
                throw IllegalArgumentException("The APK package is not allowed.")
            })
        controller.start()
        controller.handle(Intent(YoutubeSetupController.PATCH_AND_INSTALL)
            .setData(Uri.parse("content://patcher/output.apk")))
        idle()
        assertFalse(YoutubeSetupStateStore.state.busy)
        assertFalse(YoutubeSetupStateStore.state.canInstall)
        assertFalse(YoutubeSetupStateStore.state.youtubeApkReady)
        assertEquals(0, uploads)
        assertTrue(sent.isEmpty())
        assertTrue(YoutubeSetupStateStore.state.message.contains("not allowed"))
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
