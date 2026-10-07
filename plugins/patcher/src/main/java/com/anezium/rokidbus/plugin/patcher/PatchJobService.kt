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
import java.util.concurrent.atomic.AtomicBoolean

class PatchJobService : Service() {
    private lateinit var store: PatchJobStore
    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private var task: Future<*>? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var runningId: String? = null
    private var startedRealtime = 0L
    private var latestStartId = 0
    @Volatile private var workerTid = 0
    private val diagnostics by lazy { PatchExecutionDiagnostics(this) }
    private var measuredStep: String? = null
    private var measuredStarted = 0L
    private val phaseTimings = PatchTimings()
    private var workerExited = AtomicBoolean(true)

    internal var runJob: suspend (PatchJobState, Uri?) -> Unit = { state, source ->
        if (state.status == PatchJobStatus.PREPARING) prepare(state, requireNotNull(source)) else patch(state)
    }

    override fun onCreate() {
        super.onCreate()
        PatchVisibility.install(application)
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
                if (runningId != null) terminate(PatchJobStatus.CANCELLED, "Patching cancelled. You can retry.") else stopSelf()
            } else if (requested == runningId && requested == store.state.value.id && store.state.value.active)
                terminate(PatchJobStatus.CANCELLED, "Patching cancelled. You can retry.")
            else if (runningId == null) stopSelf()
            return START_NOT_STICKY
        }
        val state = store.state.value
        latestStartId = startId
        if (runningId != null) {
            if (state.active && state.id != runningId && intent?.getStringExtra(JOB_ID) == state.id) {
                try {
                    promote(state)
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE,
                        message = "The previous patch is still stopping. Wait a moment, then retry.", result = null) }
                } catch (e: Exception) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE,
                        message = PatchErrors.reason(e, "Patching could not start. Come back to this screen and try again.")) }
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                notifyState(store.state.value)
            }
            return START_NOT_STICKY
        }
        if (!state.active || intent?.getStringExtra(JOB_ID) != state.id) { stopSelf(); return START_NOT_STICKY }
        PatchStorage.cleanBundleCache(cacheDir)
        runningId = state.id
        diagnostics.reset()
        workerExited = AtomicBoolean(true)
        startedRealtime = SystemClock.elapsedRealtime()
        try {
            promote(state)
            wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:patch-job").apply { acquire(MAX_JOB_MS) }
            handler.postDelayed(deadline, MAX_JOB_MS)
            handler.postDelayed(heartbeat, 1000)
            val exited = workerExited
            task = executor.submit {
                exited.set(false)
                try {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
                    workerTid = Process.myTid()
                    diagnostics.sample(state.progress.phase, workerTid)
                    runBlocking { runJob(state, intent.data) }
                } catch (_: CancellationException) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.") }
                } catch (_: InterruptedException) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.CANCELLED, message = "Patching cancelled. You can retry.") }
                } catch (_: OutOfMemoryError) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = "Not enough memory to patch this APK. Close other apps and retry.") }
                } catch (e: Exception) {
                    store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = PatchErrors.reason(e)) }
                } finally {
                    try { store.cleanStorage(); PatchStorage.cleanBundleCache(cacheDir) }
                    catch (e: Exception) { PatchErrors.reason(e) }
                    finally { exited.set(true); handler.post { finishJob(state.id) } }
                }
            }
        } catch (e: Exception) {
            store.change(state.id) { it.copy(status = PatchJobStatus.FAILURE, message = PatchErrors.reason(e, "Patching could not start. Come back to this screen and try again.")) }
            finishJob(state.id)
        }
        return START_NOT_STICKY
    }

    private suspend fun prepare(state: PatchJobState, uri: Uri) {
        val target = PatchTargets.require(state.targetId)
        val timings = PatchTimings()
        val work = store.work(state.workId)
        PatchStorage(filesDir).sweep(state.workId)
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
                stock = PatchStorage(filesDir).retain(stock)) }
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
                    PatchPresentation.notificationPercent(state.progress)?.let { setProgress(100, it, false) }
                    val cancel = PendingIntent.getService(this@PatchJobService, 1,
                        Intent(this@PatchJobService, PatchJobService::class.java).setAction(CANCEL).putExtra(JOB_ID, state.id),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
                    addAction(0, "Cancel", cancel)
                }
            }.build()
    }

    private fun notifyState(state: PatchJobState) {
        if (state.status == PatchJobStatus.SUCCESS && state.delivered) {
            clearResultNotification(this)
            return
        }
        if (!state.active && PatchVisibility.hasResumedActivity) return
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED) return
        runCatching { getSystemService(NotificationManager::class.java).notify(NOTIFICATION, notification(state)) }
    }

    private fun promote(state: PatchJobState) {
        startForeground(NOTIFICATION, notification(state), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        // Android can silently refuse promotion under background restrictions.
        check(foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) {
            "Android stopped patching in the background. Open Patcher and try again."
        }
    }

    private fun retainForeground(state: PatchJobState): Boolean {
        if (foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC != 0) return true
        return try { promote(state); true } catch (e: Exception) {
            terminate(PatchJobStatus.INTERRUPTED,
                PatchErrors.reason(e, "Android stopped patching in the background. Open Patcher and try again."), allowProcessKill = false)
            false
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        val current = store.state.value
        if (current.active && runningId != null) retainForeground(current)
    }

    private fun finishJob(id: String) {
        if (runningId != id) return
        measuredStep?.let { phaseTimings.end(it, measuredStarted,
            if (store.state.value.status in setOf(PatchJobStatus.SUCCESS, PatchJobStatus.READY)) "ok" else "failed") }
        measuredStep = null
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(heartbeat)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null; runningId = null; task = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (store.state.value.id == id && store.state.value.status != PatchJobStatus.READY) notifyState(store.state.value)
        stopSelfResult(latestStartId)
    }

    private fun terminate(status: PatchJobStatus, reason: String, allowProcessKill: Boolean = true) {
        val id = runningId ?: return
        store.change(id) { it.copy(status = status, message = reason, result = null) }
        task?.cancel(true)
        handler.removeCallbacks(deadline)
        handler.removeCallbacks(heartbeat)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        val exited = workerExited
        // Future.isDone becomes true on cancel even if upstream code ignores the interrupt.
        fun reap() {
            if (exited.get()) { finishJob(id); return }
            if (allowProcessKill && PatchVisibility.mode == "hidden") {
                val current = store.state.value
                if (current.active) store.change(current.id) { it.copy(status = PatchJobStatus.INTERRUPTED,
                    message = "The last patch was interrupted. Retry when you are ready.", result = null) }
                // A hidden screen can still own the hub's pending activity result.
                PatchActivity.finishHiddenHubActivity()
                Process.killProcess(Process.myPid()); return
            }
            handler.postDelayed({ reap() }, CANCEL_GRACE_MS)
        }
        handler.postDelayed({ reap() }, CANCEL_GRACE_MS)
    }

    private val deadline = Runnable { terminate(PatchJobStatus.INTERRUPTED, "Patching took more than an hour and was stopped. Try again when you are ready.") }
    private fun reportProgress(id: String, progress: PatchProgress) {
        if (!store.progress(id, progress, SystemClock.elapsedRealtime() - startedRealtime)) return
        val step = "substep_" + (progress.substep?.name ?: progress.phase.name).lowercase()
        if (step != measuredStep) {
            measuredStep?.let { phaseTimings.end(it, measuredStarted) }
            measuredStep = step; measuredStarted = phaseTimings.start()
        }
        diagnostics.sample(store.state.value.progress.phase, workerTid)
    }
    private val heartbeat = object : Runnable {
        override fun run() {
            val current = store.state.value
            if (current.active && current.id == runningId) {
                if (!retainForeground(current)) return
                store.tick(current.id, SystemClock.elapsedRealtime() - startedRealtime)
                notifyState(store.state.value)
                diagnostics.sample(store.state.value.progress.phase, workerTid)
                handler.postDelayed(this, 1000)
            }
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { terminate(PatchJobStatus.INTERRUPTED, "Android stopped the patch after its time limit. Try again when you are ready.") }
    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        val current = store.state.value
        if (current.active && current.id == runningId)
            store.change(current.id) { it.copy(status = PatchJobStatus.INTERRUPTED,
                message = "The last patch was interrupted. Retry when you are ready.", result = null) }
        task?.cancel(true)
        if (wakeLock?.isHeld == true) wakeLock?.release()
        executor.shutdownNow()
        super.onDestroy()
    }

    companion object {
        internal fun clearResultNotification(context: android.content.Context) {
            context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION)
        }

        const val JOB_ID = "job_id"
        const val CANCEL = "cancel_patch"
        private const val CHANNEL = "patch-jobs"
        private const val RESULTS_CHANNEL = "patch-results"
        private const val NOTIFICATION = 41
        private const val CANCEL_GRACE_MS = 3000L
        private const val MAX_JOB_MS = 60L * 60 * 1000
    }
}
