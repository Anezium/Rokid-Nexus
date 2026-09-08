package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.FileObserver
import android.os.SystemClock
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.MediaSyncMode
import com.anezium.rokidbus.shared.MediaSyncTrafficMonitor
import com.anezium.rokidbus.shared.MediaSyncTransferContract
import org.json.JSONObject
import com.anezium.rokidbus.shared.MediaSyncMediaFile
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Photo sync, glasses side.
 *
 * Lives in the MAIN hub process — never in `:camera`. Since the transport moved to the Bluetooth
 * bus there is no Wi-Fi involvement at all: no radio to enable, no group to negotiate, no command
 * bridge or accessibility fallback anywhere in the path. A session is now just "the link is up,
 * here is the catalog, pull what you need", with the politeness layer keeping the shared link
 * usable throughout.
 *
 * All work is serialised onto one daemon executor: triggers arrive from a broadcast receiver, a
 * file observer, the SPP reader thread and the CXR main thread, and none of them may block.
 */
internal object MediaSyncEngine {
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "RokidNexusMediaSync").apply { isDaemon = true }
    }

    /** Shared with [GlassesHub], which notes every envelope crossing the link in either direction. */
    val trafficMonitor = MediaSyncTrafficMonitor(SystemClock::elapsedRealtime)

    @Volatile private var appContext: Context? = null
    @Volatile private var catalog: MediaCatalog? = null
    @Volatile private var mode = MediaSyncMode.CHARGING
    @Volatile private var consented = false
    @Volatile private var linkUp = false
    @Volatile private var cameraSessionActive = false

    @Volatile private var session: Session? = null
    private var settlingFuture: ScheduledFuture<*>? = null
    private var deferredTrigger: MediaSyncTrigger? = null
    private var captureFuture: ScheduledFuture<*>? = null
    private var watchdogFuture: ScheduledFuture<*>? = null
    private var captureObservers: List<FileObserver> = emptyList()

    /** Last seen state of the capture directories, so the safety scan reacts to changes only. */
    @Volatile private var captureFingerprint: String? = null

    private class Session(val id: String, val sender: MediaSyncTransferSender)

    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_POWER_CONNECTED -> executor.execute {
                    attempt(MediaSyncTrigger.CHARGING_EDGE)
                }
                Intent.ACTION_POWER_DISCONNECTED -> logSync("charging edge=disconnected")
            }
        }
    }

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        this.appContext = appContext
        catalog = MediaCatalog()
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.registerReceiver(powerReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                appContext.registerReceiver(powerReceiver, filter)
            }
        }.onFailure { logError("mediaSync power receiver registration failed", it) }
        startCaptureObserver()
        // The fallback is independent from inotify and must still exist if the observer cannot
        // attach to shared storage during early boot.
        startAutoSafetyScan()
        // A glasses hub restart wipes consent, and the CXR transport does not bounce, so the phone
        // sees no edge on which to push it. Ask instead of waiting to be told.
        requestConfig()
        logSync("started")
    }

    /**
     * Watches the capture directories so automatic modes react to a capture the moment it is taken
     * rather than waiting for the next reconnect. The observer only ever nudges an attempt — the
     * stability gate decides whether a file is actually ready, so a half-written video is simply
     * not eligible yet and the settling re-check picks it up shortly after.
     */
    private fun startCaptureObserver() {
        captureObservers = MediaCatalog.DEFAULT_DIRECTORIES.mapNotNull { path ->
            val directory = File(path)
            if (!directory.isDirectory) return@mapNotNull null
            val observer = runCatching {
                @Suppress("DEPRECATION")
                object : FileObserver(directory.absolutePath, CAPTURE_EVENTS) {
                    override fun onEvent(event: Int, path: String?) {
                        if (path.isNullOrBlank()) return
                        onCaptureObserved()
                    }
                }
            }.onFailure { logError("mediaSync capture observer unavailable", it) }
                .getOrNull()
                ?: return@mapNotNull null
            runCatching { observer.startWatching() }
                .onFailure { logError("mediaSync capture observer start failed", it) }
            logSync("capture observer watching ${directory.absolutePath}")
            observer
        }
    }

    /**
     * The observer is the fast path, not a guarantee: inotify on this shared-storage mount misses
     * events written by other apps often enough to matter — measured here, a small capture fired
     * and a large one did not. A slow scan backs the observer up in every automatic mode while its
     * power condition is satisfied. This matters especially in CHARGING mode: if the glasses were
     * already plugged in, there will be no new power edge to recover a missed capture.
     */
    private fun startAutoSafetyScan() {
        captureFingerprint = readCaptureFingerprint()
        runCatching {
            executor.scheduleWithFixedDelay(
                {
                    // A periodic task that throws is cancelled by the executor for good, and
                    // silently: this scan is the backstop for missed inotify events, so one
                    // bad tick must cost one log line, not the feature.
                    runCatching { safetyScanTick() }
                        .onFailure { logError("mediaSync safety scan tick failed", it) }
                },
                AUTO_SCAN_INTERVAL_MS,
                AUTO_SCAN_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }.onFailure { logError("mediaSync safety scan unavailable", it) }
    }

    private fun safetyScanTick() {
        val context = appContext ?: return
        if (cameraSessionActive &&
            CameraSessionLivenessPolicy.shouldResetTracker(
                MediaSyncSkipReason.CAMERA_ACTIVE,
                isCameraProcessAlive(context),
            )
        ) {
            // A crashed :camera process cannot send its closing edge. Repair the stale
            // tracker before the camera guard below, without consuming the directory
            // fingerprint while a real camera session is still alive.
            logSync("camera session stale during safety scan; releasing")
            GlassesHub.resetCameraSession()
        }
        // The camera lease is durable even when the in-memory session edge was lost.
        // Sweep it independently so a crashed camera or restarted hub cannot strand
        // a Nexus-owned radio merely because there is no tracker flag left to reset.
        GlassesHub.requestWifiOwnershipReconciliation(
            context,
            "media_sync_safety_scan",
        )
        if (!MediaSyncSafetyScanPolicy.shouldScan(
                mode = mode,
                charging = isCharging(context),
                consented = consented,
                dataLinkUp = linkUp,
                sessionActive = session != null,
                cameraSessionActive = cameraSessionActive,
            )
        ) {
            return
        }
        // Only a directory that actually changed is worth a session. The glasses
        // cannot tell what the phone already holds — every stable capture looks
        // pending from here — so triggering on "there are files" would open a
        // session every minute forever, on the very link this feature is careful
        // not to crowd.
        val fingerprint = readCaptureFingerprint()
        if (fingerprint == captureFingerprint) return
        captureFingerprint = fingerprint
        logSync("safety scan noticed a capture change")
        attempt(MediaSyncTrigger.NEW_CAPTURE, quiet = true)
    }

    /** Cheap directory summary: what changed, not what is pending. */
    private fun readCaptureFingerprint(): String {
        val files = MediaCatalog.DEFAULT_DIRECTORIES.flatMap { path ->
            runCatching { File(path).listFiles()?.toList() }.getOrNull().orEmpty()
        }
            .filter { it.isFile && MediaSyncMediaFile.isSupported(it.name) }
        val newest = files.maxOfOrNull { it.lastModified() } ?: 0L
        val bytes = files.sumOf { it.length() }
        return "${files.size}:$newest:$bytes"
    }

    /** Debounced: one photo fires several file events and a video fires many. */
    private fun onCaptureObserved() {
        synchronized(this) {
            captureFuture?.cancel(false)
            captureFuture = executor.schedule(
                { attempt(MediaSyncTrigger.NEW_CAPTURE) },
                CAPTURE_DEBOUNCE_MS,
                TimeUnit.MILLISECONDS,
            )
        }
    }

    /** The phone hub pushes its settings on connect, on change, and when we ask. */
    fun onConfig(payload: JSONObject) {
        mode = MediaSyncMode.fromWireValue(payload.optString("syncMode")) ?: MediaSyncMode.CHARGING
        consented = payload.optBoolean("consented", false)
        logSync("config mode=${mode.wireValue} consented=$consented")
        executor.execute { attempt(MediaSyncTrigger.BUS_CONNECT) }
    }

    fun onTriggerRequest() {
        executor.execute { attempt(MediaSyncTrigger.MANUAL) }
    }

    /** Gate for maintenance work that must stay off the link while a transfer is live. */
    fun isSessionActive(): Boolean = session != null

    fun onLinkStateChanged(up: Boolean) {
        val changed = linkUp != up
        linkUp = up
        if (!changed) return
        if (up) {
            requestConfig()
            executor.execute { attempt(MediaSyncTrigger.BUS_CONNECT) }
        } else {
            executor.execute { finish(MediaSyncTransferContract.ABORT_LINK) }
        }
    }

    fun onCameraSessionChanged(active: Boolean) {
        cameraSessionActive = active
        if (active) session?.sender?.onCameraSessionOpened()
    }

    private fun requestConfig() {
        GlassesHub.sendToPhone(BusPaths.MEDIA_SYNC_CONFIG_REQUEST, JSONObject().put("version", 1))
    }

    /**
     * [quiet] is for the periodic safety scan: it runs whether or not anything happened, so its
     * "nothing to do" answer is the normal case and must not fill the log with it.
     */
    private fun attempt(
        trigger: MediaSyncTrigger,
        reconciled: Boolean = false,
        quiet: Boolean = false,
        fromSettlingRecheck: Boolean = false,
    ) {
        val context = appContext ?: return
        val catalog = catalog ?: return
        if (!consented) {
            logSync("skip trigger=$trigger reason=not_consented")
            return
        }
        val storageReadable = hasStoragePermission(context)
        val scan = if (storageReadable) catalog.scan() else MediaCatalog.CatalogScan(emptyList(), false)
        val plan = MediaSyncAttemptPolicy.plan(
            trigger = trigger,
            conditions = MediaSyncConditions(
                linkUp = linkUp,
                charging = isCharging(context),
                hasEligibleFiles = !scan.isEmpty,
                cameraSessionActive = cameraSessionActive,
                mode = mode,
                syncInProgress = session != null,
                storageReadable = storageReadable,
            ),
            hasSettlingFiles = scan.settling,
        )
        if (plan.scheduleSettlingRecheck) scheduleSettlingRecheck(trigger)
        when (val decision = plan.decision) {
            is MediaSyncTriggerDecision.Skip -> {
                if (MediaSyncDeferredRetryPolicy.shouldDefer(
                        trigger = trigger,
                        reason = decision.reason,
                        fromSettlingRecheck = fromSettlingRecheck,
                    )
                ) {
                    deferredTrigger = trigger
                }
                if (!reconciled &&
                    CameraSessionLivenessPolicy.shouldResetTracker(
                        decision.reason,
                        isCameraProcessAlive(context),
                    )
                ) {
                    // The :camera process died without closing its session; without this the stale
                    // flag would block every sync until the whole hub restarts.
                    logSync("camera session stale, camera process gone; releasing")
                    GlassesHub.resetCameraSession()
                    attempt(trigger, reconciled = true, quiet = quiet)
                    return
                }
                if (!quiet) {
                    logSync("skip trigger=$trigger reason=${decision.reason}")
                    reportState("idle", reason = decision.reason.name.lowercase())
                }
            }
            is MediaSyncTriggerDecision.Start -> begin(context, decision.trigger)
        }
    }

    /**
     * The stability gate needs two scans to clear a capture, so a freshly taken photo is never
     * eligible on the first look. Re-check once rather than making the wearer trigger again.
     */
    private fun scheduleSettlingRecheck(trigger: MediaSyncTrigger) {
        if (settlingFuture?.isDone == false) return
        settlingFuture = executor.schedule(
            {
                try {
                    attempt(trigger, fromSettlingRecheck = true)
                } finally {
                    settlingFuture = null
                }
            },
            MediaSyncStabilityGate.MIN_SAMPLE_GAP_MS + SETTLING_MARGIN_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun begin(context: Context, trigger: MediaSyncTrigger) {
        val catalog = catalog ?: return
        val sessionId = UUID.randomUUID().toString()
        val sender = MediaSyncTransferSender(
            sessionId = sessionId,
            catalog = catalog,
            deletionExecutor = AndroidMediaSyncDeletionExecutor(context, catalog, ::logSync),
            isCameraSessionActive = { cameraSessionActive },
            isLinkUp = { linkUp },
            trafficMonitor = trafficMonitor,
            send = GlassesHub::sendToPhone,
            logger = ::logSync,
            onFinished = { summary -> executor.execute { onSenderFinished(sessionId, summary) } },
        )
        session = Session(sessionId, sender)
        logSync("session begin trigger=$trigger id=$sessionId")
        reportState("preparing", reason = trigger.name.lowercase())
        armWatchdog(sessionId)
    }

    /** Ends a session the phone stopped driving — it went away without saying goodbye. */
    private fun armWatchdog(sessionId: String) {
        watchdogFuture?.cancel(false)
        watchdogFuture = executor.schedule(
            { if (session?.id == sessionId) finish("session_timeout") },
            SESSION_TIMEOUT_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** Inbound data-plane traffic, all of it addressed to the live session. */
    fun onTransferEnvelope(path: String, payload: JSONObject) {
        val current = session ?: return
        if (!MediaSyncTransferContract.isForSession(payload, current.id)) return
        armWatchdog(current.id)
        when (path) {
            BusPaths.MEDIA_SYNC_XFER_CATALOG_REQUEST -> current.sender.onCatalogRequest()
            BusPaths.MEDIA_SYNC_XFER_FILE_REQUEST -> {
                val name = MediaSyncTransferContract.name(payload) ?: return
                current.sender.onFileRequest(name, MediaSyncTransferContract.offset(payload))
            }
            BusPaths.MEDIA_SYNC_XFER_FILE_PROGRESS -> {
                // Straight through, not via the sender's executor: the streaming loop is blocked
                // waiting for this ack, so queueing it behind that loop would deadlock the file.
                val name = MediaSyncTransferContract.name(payload) ?: return
                current.sender.onProgressAck(name, MediaSyncTransferContract.staged(payload))
            }
            BusPaths.MEDIA_SYNC_XFER_FILE_ACK -> {
                val name = MediaSyncTransferContract.name(payload) ?: return
                current.sender.onFileAck(name, payload.optBoolean("ok"), payload.optBoolean("delete"))
            }
            BusPaths.MEDIA_SYNC_XFER_BYE -> current.sender.onBye()
            BusPaths.MEDIA_SYNC_XFER_ABORT -> executor.execute {
                finish(payload.optString("reason").ifBlank { "phone_abort" })
            }
            else -> Unit
        }
    }

    private fun onSenderFinished(sessionId: String, summary: MediaSyncServerSummary) {
        val current = session ?: return
        if (current.id != sessionId) return
        logSync(
            "session served files=${summary.filesServed} bytes=${summary.bytesServed} " +
                "deleted=${summary.filesDeleted} deletionRefused=${summary.deletionRefused}",
        )
        reportState(state = "ended", reason = summary.abortReason, summary = summary)
        finish(summary.abortReason ?: "completed", alreadyReported = true)
    }

    private fun finish(reason: String, alreadyReported: Boolean = false) {
        val current = session ?: return
        session = null
        val retryTrigger = deferredTrigger
        deferredTrigger = null
        watchdogFuture?.cancel(false)
        watchdogFuture = null
        runCatching { current.sender.close() }
        logSync("session end id=${current.id} reason=$reason")
        if (!alreadyReported) reportState("idle", reason)
        if (retryTrigger != null) {
            logSync("retrying deferred trigger=$retryTrigger after session")
            attempt(retryTrigger, quiet = true)
        }
    }

    private fun reportState(
        state: String,
        reason: String?,
        summary: MediaSyncServerSummary? = null,
    ) {
        val payload = JSONObject()
            .put("version", 1)
            .put("state", state)
            .apply {
                session?.let { put("sessionId", it.id) }
                reason?.let { put("reason", it) }
                summary?.let {
                    put("filesServed", it.filesServed)
                    put("bytesServed", it.bytesServed)
                    put("filesDeleted", it.filesDeleted)
                    put("deletionRefused", it.deletionRefused)
                }
            }
        GlassesHub.sendToPhone(BusPaths.MEDIA_SYNC_STATE, payload)
    }

    private fun hasStoragePermission(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private fun isCharging(context: Context): Boolean {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return false
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    /**
     * Null when liveness cannot be read: an unknown answer must never cancel a real session.
     * The `:camera` process is only ever inspected from the outside here — its statics stay
     * untouched, which is the rule that keeps that process healthy.
     */
    private fun isCameraProcessAlive(context: Context): Boolean? {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            ?: return null
        val cameraProcessName = "${context.packageName}$CAMERA_PROCESS_SUFFIX"
        val running = runCatching { manager.runningAppProcesses }
            .onFailure { logSync("camera liveness unavailable error=${it.message}") }
            .getOrNull()
            ?: return null
        return running.any { it.processName == cameraProcessName }
    }

    private fun logSync(message: String) = log("mediaSync $message")

    private const val SETTLING_MARGIN_MS = 500L
    private const val CAMERA_PROCESS_SUFFIX = ":camera"

    /** Generous: a paused transfer waiting out foreign traffic must not be reaped mid-file. */
    private const val SESSION_TIMEOUT_MS = 5 * 60_000L

    /**
     * A capture writes in bursts; one attempt after they settle is enough, and the stability gate
     * still has the final say on eligibility.
     */
    private const val CAPTURE_DEBOUNCE_MS = 2_000L

    @Suppress("DEPRECATION")
    private const val CAPTURE_EVENTS = FileObserver.CLOSE_WRITE or FileObserver.MOVED_TO

    /** Backs up the capture observer in automatic modes; a missed event costs at most one minute. */
    private const val AUTO_SCAN_INTERVAL_MS = 60_000L
}
