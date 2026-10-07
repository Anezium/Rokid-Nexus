package com.anezium.rokidbus.plugin.patcher

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
    private var startedRealtime = 0L
    private var pendingStart: Intent? = null
    private var pendingStartId = 0
    private var latestStartId = 0

    override fun onCreate() {
        super.onCreate()
        store = PatchJobStore.get(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(CHANNEL, "Patching progress", NotificationManager.IMPORTANCE_LOW),
            // Outcomes may sound: the user left to wait for exactly this.
            NotificationChannel(RESULTS_CHANNEL, "Patch results", NotificationManager.IMPORTANCE_DEFAULT)))
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            val requested = intent.getStringExtra(JOB_ID)
            if (requested != null && requested == store.state.value.id && store.state.value.active && requested != runningId) {
                store.change(requested) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.", result = null) }
                pendingStart = null
                if (runningId != null) terminate(PatchJobStatus.CANCELLED, "Patching cancelled. You can retry.") else stopSelf()
            } else if (requested == runningId) terminate(PatchJobStatus.CANCELLED, "Patching cancelled. You can retry.")
            else if (runningId == null) stopSelf()
            return START_NOT_STICKY
        }
        val state = store.state.value
        latestStartId = startId
        if (runningId != null) {
            if (state.active && state.id != runningId && intent?.getStringExtra(JOB_ID) == state.id) {
                pendingStart = intent; pendingStartId = startId
            }
            return START_NOT_STICKY
        }
        if (!state.active || intent?.getStringExtra(JOB_ID) != state.id) { stopSelf(); return START_NOT_STICKY }
        runningId = state.id
        startedRealtime = SystemClock.elapsedRealtime()
        try {
            startForeground(NOTIFICATION, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:patch-job").apply { acquire(MAX_JOB_MS) }
            handler.postDelayed(deadline, MAX_JOB_MS)
            handler.postDelayed(heartbeat, 1000)
            task = executor.submit {
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
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
        val target = PatchTargets.require(state.targetId)
        val timings = PatchTimings()
        val work = store.work(state.workId)
        File(filesDir, "jobs").listFiles()?.filter { it != work }?.forEach { it.deleteRecursively() }
        try {
            val input = File(work, "input.zip")
            val size = runCatching { contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst() && !it.isNull(0)) it.getLong(0).takeIf { value -> value > 0 } else null
            } }.getOrNull()
            var lastUpdate = 0L
            timings.measure("read_copy_input") { contentResolver.openInputStream(uri).use { source ->
                requireNotNull(source) { "Cannot read selected file. Choose it again." }
                input.outputStream().use { output ->
                    PatchPolicy.copyBounded(source, output) { copied ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastUpdate >= 500) {
                            reportProgress(state.id, PatchProgress(PatchPhase.READ_INPUT, size?.takeIf { copied <= it }?.let { copied.toDouble() / it }))
                            lastUpdate = now
                        }
                    }
                    output.fd.sync()
                }
            } }
            val stock = ApkPreparer(target = target).prepare(input, File(work, "prepare"), timings) { reportProgress(state.id, it) }
            store.change(state.id) { it.copy(status = PatchJobStatus.READY, message = "Validated: stock ${target.displayName} ${target.versionLabel}",
                stock = stock.relativeTo(work).invariantSeparatorsPath) }
        } finally { runCatching { contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
    }

    private suspend fun patch(state: PatchJobState) {
        val target = PatchTargets.require(state.targetId)
        val timings = PatchTimings()
        val input = store.stock(state) ?: error("The stock APK is missing. Choose it again.")
        reportProgress(state.id, PatchProgress(PatchPhase.BUNDLE_LOAD))
        val loaded = timings.measureSuspend("bundle_load") { BundleStore(this, target).current() }
        require(loaded.hash == state.bundleHash) { "The bundle changed. Review the patches before retrying." }
        val selected = loaded.patches.filter { it.name in state.selected }.toSet()
        require(selected.size == state.selected.size) { "Some selected patches are no longer available." }
        val work = store.work(state.workId)
        require(File(work, "patch-work").deleteRecursively()) { "Cannot clear previous patch files. Choose the stock APK again." }
        val signed = PatchRuntime(target).patch(input, selected, work, SigningKey(File(filesDir, "signing/patcher.p12")), timings) { progress ->
            reportProgress(state.id, progress)
        }
        currentCoroutineContext().ensureActive()
        val directory = File(filesDir, "results").apply { mkdirs() }
        val result = File(directory, "patched-${UUID.randomUUID()}.apk")
        val pending = File(directory, ".${result.name}.partial")
        try {
            reportProgress(state.id, PatchProgress(PatchPhase.PUBLISH))
            timings.measure("publish_result") {
                java.io.RandomAccessFile(signed, "rw").use { it.fd.sync() }
                require(signed.renameTo(pending)) { "Cannot stage verified result." }
            }
            currentCoroutineContext().ensureActive()
            require(pending.renameTo(result)) { "Cannot save verified result." }
            signed.delete()
            store.change(state.id) { it.copy(status = PatchJobStatus.SUCCESS, message = "Patched and signed. Ready to install.", result = result.name,
                progress = PatchProgress(PatchPhase.HAND_OFF, 1.0), elapsedMs = SystemClock.elapsedRealtime() - startedRealtime) }
            val retained = directory.listFiles()?.filter { PatchPolicy.isResult(it.name) }.orEmpty()
            PatchPolicy.expiredResults(retained.associateWith { it.lastModified() }, System.currentTimeMillis(), result).forEach { it.delete() }
        } finally { pending.delete() }
    }

    private fun notification(state: PatchJobState): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, PatchActivity::class.java)
            .putExtra(com.anezium.rokidbus.shared.PatcherContract.EXTRA_TARGET_ID, state.targetId)
            .apply { if (state.status == PatchJobStatus.SUCCESS && !state.delivered) putExtra(PatchActivity.EXTRA_READY_JOB_ID, state.id) }
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notice = PatchPresentation.notice(state, PatchTargets.find(state.targetId) ?: PatchTargets.default)
        return NotificationCompat.Builder(this, if (state.active) CHANNEL else RESULTS_CHANNEL)
            .setSmallIcon(com.anezium.rokidbus.client.R.drawable.ic_plugin_bolt)
            .setContentTitle(notice.title).setContentText(notice.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notice.text))
            .setContentIntent(open).setOnlyAlertOnce(true)
            .setOngoing(state.active).setAutoCancel(!state.active)
            .apply {
                if (state.active) {
                    setSubText(PatchPresentation.elapsed(state.elapsedMs))
                    setProgress(100, ((state.progress.fraction ?: 0.0) * 100).toInt(), state.progress.fraction == null)
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
        handler.removeCallbacks(heartbeat)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null; runningId = null; task = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (store.state.value.id == id && store.state.value.status != PatchJobStatus.READY) notifyState(store.state.value)
        val next = pendingStart
        pendingStart = null
        if (next != null && store.state.value.active && next.getStringExtra(JOB_ID) == store.state.value.id)
            onStartCommand(next, 0, pendingStartId)
        else stopSelfResult(latestStartId)
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
    private fun reportProgress(id: String, progress: PatchProgress) {
        store.progress(id, progress, SystemClock.elapsedRealtime() - startedRealtime)
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            val current = store.state.value
            if (current.active && current.id == runningId) {
                store.tick(current.id, SystemClock.elapsedRealtime() - startedRealtime)
                notifyState(store.state.value)
                handler.postDelayed(this, 1000)
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { terminate(PatchJobStatus.INTERRUPTED, "Android stopped the patch service after its time limit. Retry when you are ready.") }
    override fun onDestroy() {
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(heartbeat)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        executor.shutdownNow()
        runningId?.let { id ->
            store.change(id) { it.copy(status = PatchJobStatus.INTERRUPTED,
                message = "The last patch was interrupted. Retry when you are ready.", result = null) }
            Process.killProcess(Process.myPid())
        }
        super.onDestroy()
    }

    companion object {
        const val JOB_ID = "job_id"
        const val CANCEL = "cancel_patch"
        private const val CHANNEL = "patch-jobs"
        private const val RESULTS_CHANNEL = "patch-results"
        private const val NOTIFICATION = 41
        private const val MAX_JOB_MS = 60L * 60 * 1000
    }
}
