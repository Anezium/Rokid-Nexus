package com.anezium.rokidbus.plugin.youtubepatcher

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.*
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future

class PatchJobService : Service() {
    private lateinit var store: PatchJobStore
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var runningId: String? = null

    override fun onCreate() {
        super.onCreate()
        store = PatchJobStore.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "APK patching", NotificationManager.IMPORTANCE_LOW))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (intent.getStringExtra(JOB_ID) == runningId) terminate(PatchJobStatus.CANCELLED, "Patching cancelled. You can retry.")
            else if (runningId == null) stopSelf()
            return START_NOT_STICKY
        }
        val state = store.state.value
        if (runningId != null) return START_NOT_STICKY
        if (!state.active || intent?.getStringExtra(JOB_ID) != state.id) { stopSelf(); return START_NOT_STICKY }
        runningId = state.id
        try {
            startForeground(NOTIFICATION, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:patch-job").apply { acquire(MAX_JOB_MS) }
            handler.postDelayed(deadline, MAX_JOB_MS)
            task = executor.submit {
                Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                try {
                    runBlocking { if (state.status == PatchJobStatus.PREPARING) prepare(state, requireNotNull(intent.data)) else patch(state) }
                } catch (_: CancellationException) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.") }
                } catch (_: InterruptedException) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.") }
                } catch (_: OutOfMemoryError) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = "Not enough memory to patch this APK. Close other apps and retry.") }
                } catch (e: Exception) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = e.message ?: "Patching failed. Retry with a supported stock APK.") }
                } finally {
                    handler.post { finishJob(state.id) }
                }
            }
        } catch (e: Exception) {
            store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = "Cannot start background patching: ${e.message}") }
            finishJob(state.id)
        }
        return START_NOT_STICKY
    }

    private suspend fun prepare(state: PatchJobState, uri: Uri) {
        val work = store.work(state.id)
        File(filesDir, "jobs").listFiles()?.filter { it != work }?.forEach { it.deleteRecursively() }
        try {
            val input = File(work, "input.zip")
            contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "Cannot read selected file. Choose it again." }
                input.outputStream().use { PatchPolicy.copyBounded(source, it); it.fd.sync() }
            }
            val stock = ApkPreparer().prepare(input, File(work, "prepare"))
            store.change(state.id) { it.copy(status = PatchJobStatus.READY, message = "Validated: stock YouTube ${PatchPolicy.VERSION}",
                stock = stock.relativeTo(work).invariantSeparatorsPath) }
        } finally { runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    private suspend fun patch(state: PatchJobState) {
        val input = store.stock(state) ?: error("The stock APK is missing. Choose it again.")
        val loaded = BundleStore(this).current()
        require(loaded.hash == state.bundleHash) { "The bundle changed. Review the patches before retrying." }
        val selected = loaded.patches.filter { it.name in state.selected }.toSet()
        require(selected.size == state.selected.size) { "Some selected patches are no longer available." }
        val signed = PatchRuntime().patch(input, selected, store.work(state.id), SigningKey(File(filesDir, "signing/youtube.p12"))) { message ->
            store.change(state.id) { it.copy(message = message) }
            handler.post { if (runningId == state.id && store.state.value.active) notifyState(store.state.value) }
        }
        currentCoroutineContext().ensureActive()
        val directory = File(filesDir, "results").apply { mkdirs() }
        val result = File(directory, "youtube-${UUID.randomUUID()}.apk")
        val pending = File(directory, ".${result.name}.partial")
        try {
            signed.inputStream().use { inputStream -> pending.outputStream().use {
                PatchPolicy.copyBounded(inputStream, it); it.fd.sync()
            } }
            currentCoroutineContext().ensureActive()
            require(pending.renameTo(result)) { "Cannot save verified result." }
            signed.delete()
            store.change(state.id) { it.copy(status = PatchJobStatus.SUCCESS, message = "Patched and signed. Ready to install.", result = result.name) }
        } finally { pending.delete() }
    }

    private fun notification(state: PatchJobState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PatchActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(com.anezium.rokidbus.client.R.drawable.ic_plugin_bolt)
            .setContentTitle(if (state.status == PatchJobStatus.SUCCESS) "Ready to install" else "YouTube Patcher")
            .setContentText(state.message).setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(state.active).setAutoCancel(!state.active)
            .apply {
                if (state.active) {
                    setProgress(100, 0, true)
                    val cancel = PendingIntent.getService(this@PatchJobService, 1,
                        Intent(this@PatchJobService, PatchJobService::class.java).setAction(CANCEL).putExtra(JOB_ID, state.id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, "Cancel", cancel)
                }
            }.build()
    }

    private fun notifyState(state: PatchJobState) {
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state)) }
    }

    private fun finishJob(id: String) {
        if (runningId != id) return
        handler.removeCallbacks(deadline)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null; runningId = null; task = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (store.state.value.status != PatchJobStatus.READY) notifyState(store.state.value)
        stopSelf()
    }

    private fun terminate(status: PatchJobStatus, reason: String) {
        val id = runningId ?: return
        store.change(id) { it.copy(status = status, message = reason, result = null) }
        task?.cancel(true)
        finishJob(id)
        // Upstream synchronous patches can ignore interrupts. Only explicit cancellation
        // or a service deadline may terminate the isolated patcher process.
        Process.killProcess(Process.myPid())
    }

    private val deadline = Runnable { terminate(PatchJobStatus.FAILURE, "Patching exceeded the one-hour limit. Retry with a supported stock APK.") }
    override fun onTimeout(startId: Int, fgsType: Int) { terminate(PatchJobStatus.INTERRUPTED, "Android stopped the patch service after its time limit. Retry when you are ready.") }
    override fun onDestroy() {
        handler.removeCallbacks(deadline)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        executor.shutdownNow()
        runningId?.let { id -> store.change(id) { it.copy(status = PatchJobStatus.INTERRUPTED,
            message = "The last patch was interrupted. Retry when you are ready.", result = null) } }
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = "job_id"
        const val CANCEL = "cancel_patch"
        private const val CHANNEL = "patch-jobs"
        private const val NOTIFICATION = 41
        private const val MAX_JOB_MS = 60L * 60 * 1000
    }
}
