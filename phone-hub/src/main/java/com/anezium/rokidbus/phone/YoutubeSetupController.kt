package com.anezium.rokidbus.phone

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.NativeAppContract
import com.anezium.rokidbus.shared.NativeAppLaunchRequest
import com.anezium.rokidbus.shared.YoutubeInventory
import com.anezium.rokidbus.shared.YoutubeSetupContract
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ExecutorService

internal class YoutubeSetupController(
    private val context: Context,
    private val connected: () -> Boolean,
    private val installReady: () -> Boolean,
    private val send: (BusEnvelope) -> String?,
    private val upload: (File, (Boolean) -> Unit) -> Boolean,
    private val worker: ExecutorService = Executors.newSingleThreadExecutor(),
    private val source: (android.net.Uri?, () -> Boolean, (String) -> Unit) -> PreparedYoutubeApk =
        YoutubeApkSource(context)::prepare,
) : AutoCloseable {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0L
    private var closed = false
    private var prepared: PreparedYoutubeApk? = null
    private val uploadingFiles = mutableSetOf<File>()
    private var activeUploadGeneration: Long? = null
    private var inventoryId: String? = null
    private var inventoryCallback: ((YoutubeInventory) -> Unit)? = null
    private var launchId: String? = null
    private var state = YoutubeSetupState()

    fun start() {
        worker.execute { YoutubeApkSource(context).clearAbandonedFiles() }
        YoutubeSetupStateStore.update(state)
        YoutubeSetupCommands.attach(::handle)
    }

    fun handle(intent: Intent) {
        if (closed || state.busy) return
        when (intent.action) {
            REFRESH -> refresh()
            PREPARE_MICROG -> prepare(null)
            INSTALL_MICROG -> prepare(null, autoInstall = true)
            IMPORT_YOUTUBE -> intent.data?.takeIf { it.scheme == "content" }?.let { prepare(it) }
            PATCH_AND_INSTALL -> {
                val uri = intent.data?.takeIf { it.scheme == "content" }
                if (uri == null) fail("The patcher did not return a readable APK. Try patching again.")
                else prepare(uri, autoInstall = true)
            }
            INSTALL -> install()
            OPEN_MICROG -> open(YoutubeSetupContract.MICROG)
            OPEN_YOUTUBE -> {
                val app = state.inventory?.apps?.firstOrNull {
                    it.packageName != YoutubeSetupContract.MICROG && it.launchable
                }
                if (app != null) open(app.packageName) else fail("Refresh the glasses apps before opening YouTube.")
            }
        }
    }

    fun handleRemote(envelope: BusEnvelope): Boolean {
        if (envelope.path != NativeAppContract.RESULT_PATH || envelope.binary != null) return false
        YoutubeSetupContract.parseResult(envelope.payload)?.let { result ->
            main.post {
                if (closed || inventoryId != result.requestId) return@post
                inventoryId = null
                val callback = inventoryCallback
                inventoryCallback = null
                state = state.copy(inventory = result)
                callback?.invoke(result)
            }
            return true
        }
        val result = NativeAppContract.parseLaunchResult(envelope.payload) ?: return false
        // The prefix separates this screen's replies from the general native-apps screen.
        if (!result.requestId.startsWith("youtube-")) return false
        main.post {
            if (closed || launchId != result.requestId) return@post
            launchId = null
            publish(state.copy(busy = false, message = if (result.success) {
                if (result.packageName == YoutubeSetupContract.MICROG)
                    "MicroG is open on the glasses. Choose Add account there, then use Keyboard & remote below."
                else "YouTube is open on the glasses. Check your account and enable automatic skips in Morphe > SponsorBlock."
            } else "The app could not be opened. For MicroG, install the variant with a launcher icon."))
        }
        return true
    }

    fun onLinkChanged(controlConnected: Boolean, cxrConnected: Boolean) {
        main.post {
            if (closed) return@post
            val uploadActive = activeUploadGeneration != null
            // A download or import needs no glasses link: it keeps running and is only
            // told about the link through the stale inventory it will refresh later.
            val preparing = state.busy && inventoryId == null && launchId == null && !uploadActive
            if (!cxrConnected) {
                activeUploadGeneration = null
                uploadingFiles.filter { it != prepared?.file }.forEach { it.delete() }
                uploadingFiles.clear()
            }
            val uploadLost = !cxrConnected && uploadActive
            val requestLost = !controlConnected && (inventoryId != null || launchId != null)
            if (uploadLost) generation++
            if (!controlConnected || uploadLost) {
                inventoryId = null
                inventoryCallback = null
                launchId = null
            }
            // Every link tick arrives here; report the loss once instead of overwriting a
            // prepared-APK message on each of them.
            if (uploadLost || requestLost || !controlConnected && state.inventory != null) {
                publish(state.copy(busy = preparing, inventory = null,
                    message = if (preparing) state.message
                        else "The glasses disconnected. Reconnect and refresh before continuing."))
            }
        }
    }

    private fun refresh() {
        requestInventory { publish(state.copy(busy = false, message = "Glasses apps refreshed.")) }
    }

    private fun requestInventory(callback: (YoutubeInventory) -> Unit) {
        if (!connected()) { fail("Connect the glasses and start Nexus on them first."); return }
        val id = "youtube-${UUID.randomUUID()}"
        inventoryId = id
        inventoryCallback = callback
        publish(state.copy(busy = true, inventory = null, message = "Checking apps on the glasses…"))
        val error = send(BusEnvelope(path = NativeAppContract.REQUEST_PATH, payload = YoutubeSetupContract.request(id)))
        if (error != null) {
            inventoryId = null
            inventoryCallback = null
            fail("The request could not reach the glasses.")
            return
        }
        main.postDelayed({
            if (inventoryId == id) {
                inventoryId = null
                inventoryCallback = null
                fail("The glasses did not answer. Update the Nexus glasses hub, reconnect, and refresh.")
            }
        }, 12_000)
    }

    private fun prepare(uri: android.net.Uri?, autoInstall: Boolean = false) {
        prepared?.file?.takeUnless(uploadingFiles::contains)?.delete()
        prepared = null
        val operation = ++generation
        publish(state.copy(busy = true, preparedLabel = null, canInstall = false, message = "Preparing APK…"))
        worker.execute {
            val result = runCatching {
                source(uri, { generation != operation }) { message ->
                    main.post { if (generation == operation) publish(state.copy(message = message)) }
                }
            }
            main.post {
                if (generation != operation) { result.getOrNull()?.file?.delete(); return@post }
                result.fold(onSuccess = { apk ->
                    prepared = apk
                    publish(state.copy(busy = false, preparedLabel = apk.label, canInstall = true,
                        youtubeApkReady = state.youtubeApkReady || apk.archive.packageName != YoutubeSetupContract.MICROG,
                        message = listOfNotNull("${apk.label} is ready. Install it on the glasses below.",
                            YoutubeApkPolicy.versionNotice(apk.archive)).joinToString(" ")))
                    if (autoInstall) install()
                }, onFailure = {
                    fail(if (it is IllegalArgumentException || it is IllegalStateException) it.message.orEmpty()
                        else "Could not prepare the APK. Check your connection or choose the file again.")
                })
            }
        }
    }

    private fun install() {
        val apk = prepared ?: return
        if (!installReady()) { fail("Connect the glasses through Hi Rokid and turn on phone Wi-Fi first."); return }
        requestInventory { inventory ->
            val error = YoutubeApkPolicy.updateError(apk.archive, apk.minSdk, inventory)
            if (error != null) { fail(error); return@requestInventory }
            val operation = ++generation
            publish(state.copy(busy = true, message = "Verifying ${apk.label} before transfer…"))
            worker.execute {
                val verified = runCatching { YoutubeApkSource.sha256(apk.file) == apk.sha256 }.getOrDefault(false)
                main.post transfer@{
                    if (operation != generation) return@transfer
                    if (!verified) { fail("The prepared APK changed or is missing. Prepare it again."); return@transfer }
                    if (!installReady()) { fail("The glasses connection or phone Wi-Fi is unavailable."); return@transfer }
                    publish(state.copy(message = "Installing ${apk.label} on the glasses. Keep both devices connected…"))
                    uploadingFiles += apk.file
                    activeUploadGeneration = operation
                    val accepted = upload(apk.file) { success ->
                        main.post completed@{
                            uploadingFiles -= apk.file
                            if (activeUploadGeneration == operation) activeUploadGeneration = null
                            if (operation != generation) {
                                if (prepared?.file != apk.file) apk.file.delete()
                                return@completed
                            }
                            if (!success) { fail("Installation failed. Keep Wi-Fi on and check the glasses, then retry."); return@completed }
                            prepared = null
                            apk.file.delete()
                            state = state.copy(preparedLabel = null, canInstall = false)
                            requestInventory { refreshed ->
                                val installed = refreshed.apps.single { it.packageName == apk.archive.packageName }
                                if (installed.versionCode != apk.archive.versionCode || installed.signer != YoutubeApkPolicy.signer(apk.archive)) {
                                    fail("The installed APK could not be confirmed. Refresh before continuing.")
                                } else {
                                    YoutubeSetupInstallHistory(context).confirmed(apk.archive)
                                    publish(state.copy(busy = false, message = "${apk.label} is installed on the glasses."))
                                }
                            }
                        }
                    }
                    if (!accepted) {
                        uploadingFiles -= apk.file
                        activeUploadGeneration = null
                        fail("Another glasses app operation is running. Wait for it to finish, then retry.")
                        return@transfer
                    }
                    main.postDelayed({
                        if (generation == operation && activeUploadGeneration == operation) {
                            activeUploadGeneration = null
                            generation++
                            fail("Installation was not confirmed. Reconnect the glasses and refresh before retrying.")
                        }
                    }, 180_000)
                }
            }
        }
    }

    private fun open(packageName: String) {
        if (!connected()) { fail("Connect the glasses first."); return }
        val id = "youtube-${UUID.randomUUID()}"
        launchId = id
        publish(state.copy(busy = true, message = "Opening on the glasses…"))
        if (send(BusEnvelope(path = NativeAppContract.REQUEST_PATH,
                payload = NativeAppContract.launchRequest(NativeAppLaunchRequest(id, packageName)))) != null) {
            launchId = null
            fail("The app could not be opened on the glasses.")
        }
        main.postDelayed({
            if (launchId == id) { launchId = null; fail("The glasses did not confirm opening the app. Refresh and retry.") }
        }, 12_000)
    }

    private fun fail(message: String) = publish(state.copy(busy = false, message = message))
    private fun publish(next: YoutubeSetupState) {
        state = next
        YoutubeSetupStateStore.update(next)
    }

    override fun close() {
        closed = true
        YoutubeSetupCommands.detach()
        generation++
        main.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        inventoryId = null
        inventoryCallback = null
        activeUploadGeneration = null
        prepared?.file?.takeUnless(uploadingFiles::contains)?.delete()
        prepared = null
        YoutubeSetupStateStore.update(YoutubeSetupState(message = "Nexus stopped. Reopen setup to continue."))
    }

    companion object {
        const val REFRESH = "com.anezium.rokidbus.phone.youtube.REFRESH"
        const val PREPARE_MICROG = "com.anezium.rokidbus.phone.youtube.PREPARE_MICROG"
        const val IMPORT_YOUTUBE = "com.anezium.rokidbus.phone.youtube.IMPORT_YOUTUBE"
        const val INSTALL_MICROG = "com.anezium.rokidbus.phone.youtube.INSTALL_MICROG"
        const val PATCH_AND_INSTALL = "com.anezium.rokidbus.phone.youtube.PATCH_AND_INSTALL"
        const val INSTALL = "com.anezium.rokidbus.phone.youtube.INSTALL"
        const val OPEN_MICROG = "com.anezium.rokidbus.phone.youtube.OPEN_MICROG"
        const val OPEN_YOUTUBE = "com.anezium.rokidbus.phone.youtube.OPEN_YOUTUBE"
    }
}
