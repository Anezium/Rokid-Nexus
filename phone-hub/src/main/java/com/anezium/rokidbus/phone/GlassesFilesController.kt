package com.anezium.rokidbus.phone

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFileEntry
import com.anezium.rokidbus.shared.GlassesFilesAccess
import com.anezium.rokidbus.shared.GlassesFilesChunk
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesLaneEndpoint
import com.anezium.rokidbus.shared.GlassesFilesOperation
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesSendDigest
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy
import com.anezium.rokidbus.shared.GlassesFilesHotspotPolicy
import com.anezium.rokidbus.shared.GlassesFilesLaneUpgrade
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** What a listing request came back with, as the explorer screen needs it. */
sealed interface GlassesListingOutcome {
    data class Listed(
        val path: String,
        val access: GlassesFilesAccess,
        val entries: List<GlassesFileEntry>,
        val offset: Int,
        val total: Int,
    ) : GlassesListingOutcome
    data class Failed(val path: String, val error: GlassesFilesError, val access: GlassesFilesAccess?) : GlassesListingOutcome
    data object TimedOut : GlassesListingOutcome
    data object Disconnected : GlassesListingOutcome
    data object Unsupported : GlassesListingOutcome
}

/** The hub-wide state the explorer screen mirrors. */
data class GlassesFilesSnapshot(
    val connected: Boolean,
    /** Null until the glasses have announced their capabilities on this connection. */
    val supported: Boolean?,
    val transfers: List<GlassesTransferItem>,
    /** Picked files whose metadata is still being read; Cancel all covers them too. */
    val preparing: Int = 0,
)

/**
 * Static edge between the explorer screen and the hub service, which share one process.
 *
 * Listings carry up to 200 entries; a broadcast per page would parcel all of them through the
 * system for no benefit, so the screen talks to the controller directly and gets callbacks on the
 * main thread instead.
 */
object GlassesFilesHub {
    @Volatile internal var requestHotspotPermission: (() -> Unit)? = null
    @Volatile internal var controller: GlassesFilesController? = null
        set(value) {
            field = value
            publish(value?.snapshot() ?: GlassesFilesSnapshot(connected = false, supported = null, transfers = emptyList()))
        }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(GlassesFilesSnapshot) -> Unit>()
    @Volatile private var last = GlassesFilesSnapshot(connected = false, supported = null, transfers = emptyList())

    fun addListener(listener: (GlassesFilesSnapshot) -> Unit) {
        listeners += listener
        listener(last)
    }

    fun removeListener(listener: (GlassesFilesSnapshot) -> Unit) {
        listeners -= listener
    }

    fun snapshot(): GlassesFilesSnapshot = last

    fun list(path: String, offset: Int, callback: (GlassesListingOutcome) -> Unit) {
        val target = controller
        if (target == null) {
            main.post { callback(GlassesListingOutcome.Disconnected) }
            return
        }
        target.list(path, offset) { outcome -> main.post { callback(outcome) } }
    }

    fun makeFolder(path: String, name: String, callback: (GlassesFilesError?) -> Unit) {
        val target = controller
        if (target == null) {
            main.post { callback(GlassesFilesError.LINK_LOST) }
            return
        }
        target.makeFolder(path, name) { error -> main.post { callback(error) } }
    }

    fun delete(path: String, directory: Boolean, callback: (GlassesFilesError?) -> Unit) {
        val target = controller
        if (target == null) {
            main.post { callback(GlassesFilesError.LINK_LOST) }
            return
        }
        target.delete(path, directory) { error -> main.post { callback(error) } }
    }

    fun grantAccess(callback: (GlassesFilesResult.Operation?) -> Unit) {
        val target = controller
        if (target == null) {
            main.post { callback(null) }
            return
        }
        target.grantAccess { result -> main.post { callback(result) } }
    }

    /** False when the hub service is not up; the caller then still owns the grants it took. */
    fun send(context: Context, folderPath: String, uris: List<Uri>, newlyTaken: Set<String>): Boolean {
        val target = controller ?: return false
        target.enqueueSends(context, folderPath, uris, newlyTaken)
        return true
    }

    fun copyToPhone(entryPath: String, name: String, sizeBytes: Long) =
        controller?.enqueueCopy(entryPath, name, sizeBytes)

    fun cancel(id: String) = controller?.cancel(id)

    fun cancelAll() = controller?.cancelAll()

    /** Whether a transfer started now would most likely take the Wi-Fi lane. */
    fun laneLikely(): Boolean = controller?.laneLikely() == true

    fun retry(id: String) = controller?.retry(id)

    fun dismiss(id: String) = controller?.dismiss(id)

    internal fun publish(snapshot: GlassesFilesSnapshot) {
        last = snapshot
        main.post { listeners.forEach { it(snapshot) } }
    }
}

/**
 * Phone half of the glasses file explorer, owned by [BusHubService].
 *
 * Incoming results are processed on one serial inbox in bus order, which is also where copied
 * bytes are written, so a download's chunks land in sequence. Transfers run one at a time on a
 * separate worker that blocks on the inbox's signals; sends stream from the phone's content
 * provider under the glasses' acknowledged window.
 */
internal class GlassesFilesController(
    context: Context,
    private val sendRemote: (BusEnvelope) -> String?,
    private val cameraActive: () -> Boolean = { false },
    private val openUpload: (Uri, (() -> Unit) -> Unit) -> InputStream = { uri, registerCancel ->
        val cancellation = CancellationSignal()
        registerCancel { cancellation.cancel() }
        val descriptor = context.applicationContext.contentResolver.openFileDescriptor(uri, "r", cancellation)
            ?: throw IOException("provider returned no descriptor")
        registerCancel { runCatching { descriptor.close() } }
        ParcelFileDescriptor.AutoCloseInputStream(descriptor)
    },
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val inbox = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-inbox").apply { isDaemon = true } }
    private val intake = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-intake").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-transfer").apply { isDaemon = true } }
    private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "glasses-files-timer").apply { isDaemon = true } }
    private val lock = Object()
    private val laneIo = Executors.newCachedThreadPool { Thread(it, "glasses-files-lane").apply { isDaemon = true } }
    private val providerIo = Executors.newCachedThreadPool { Thread(it, "glasses-files-provider").apply { isDaemon = true } }

    // Guarded by [lock].
    private val transfers = ArrayList<GlassesTransferItem>()
    private val pending = HashMap<String, Pending>()
    private var active: ActiveTransfer? = null
    private var connected = false
    private var dataPlaneUp = false
    private var supported: Boolean? = null
    @Volatile private var laneSupported = false
    @Volatile private var hotspotSupported = false
    private val hotspot = GlassesFilesHotspotHost(appContext, timers, ::send)
    private var workerRunning = false
    private var lastPublishAt = 0L
    private var publishScheduled = false
    private val grants = GlassesGrantLedger(::releaseGrant)

    /** Bumped by Cancel all; an intake that started under an older epoch is discarded. */
    private var cancelEpoch = 0L
    private val pendingIntakes = HashSet<PendingIntake>()

    private class PendingIntake(val epoch: Long, val size: Int) {
        val hooks = CancelHooks()
    }

    private val closed = AtomicBoolean(false)

    fun snapshot(): GlassesFilesSnapshot = synchronized(lock) {
        GlassesFilesSnapshot(connected && dataPlaneUp, supported, transfers.toList(), pendingIntakes.sumOf { it.size })
    }

    fun onLinkStateChanged(controlUp: Boolean, dataUp: Boolean) {
        val resume: Boolean
        synchronized(lock) {
            val wasUp = connected && dataPlaneUp
            connected = controlUp || dataUp
            dataPlaneUp = dataUp
            val isUp = connected && dataPlaneUp
            if (!isUp) {
                active?.fail(GlassesTransferStop.LINK_LOST)
                pending.values.toList().forEach { it.expire(GlassesFilesError.LINK_LOST) }
                pending.clear()
            }
            resume = !wasUp && isUp
        }
        if (!controlUp && !dataUp) hotspot.close()
        publish(force = true)
        if (resume) kickWorker()
    }

    fun laneLikely(): Boolean = laneSupported && GlassesFilesLaneClient.isOnWifi(appContext)

    fun onCapabilities(glassesFilesSupported: Boolean, laneSupported: Boolean = false, hotspotSupported: Boolean = false) {
        this.laneSupported = laneSupported
        this.hotspotSupported = hotspotSupported
        synchronized(lock) { supported = glassesFilesSupported }
        publish(force = true)
    }

    /** True when [envelope] is explorer traffic; the caller records and drops it either way. */
    fun handleRemote(envelope: BusEnvelope): Boolean {
        when (envelope.path) {
            GlassesFilesContract.DATA_PATH -> {
                val chunk = GlassesFilesContract.parseChunk(envelope.payload, envelope.binary)
                val bytes = envelope.binary
                if (chunk != null && bytes != null) inbox.execute { onDownloadChunk(chunk, bytes) }
                return true
            }
            GlassesFilesContract.RESULT_PATH -> {
                val result = if (envelope.binary == null) GlassesFilesContract.parseResult(envelope.payload) else null
                if (result != null) inbox.execute { onResult(result) }
                return true
            }
            else -> return false
        }
    }

    fun list(path: String, offset: Int, callback: (GlassesListingOutcome) -> Unit) {
        val unavailable = synchronized(lock) {
            when {
                !connected -> GlassesListingOutcome.Disconnected
                supported == false -> GlassesListingOutcome.Unsupported
                else -> null
            }
        }
        if (unavailable != null) return callback(unavailable)
        val requestId = newId()
        request(
            requestId = requestId,
            request = GlassesFilesRequest.ListFolder(requestId, path, offset),
            timeoutMs = LISTING_TIMEOUT_MS,
            onTimeout = { error ->
                callback(if (error == GlassesFilesError.LINK_LOST) GlassesListingOutcome.Disconnected else GlassesListingOutcome.TimedOut)
            },
        ) { result ->
            val listing = result as? GlassesFilesResult.Listing ?: return@request
            val error = listing.error
            callback(
                if (error == null) {
                    GlassesListingOutcome.Listed(listing.path, listing.access, listing.entries, listing.offset, listing.total)
                } else {
                    GlassesListingOutcome.Failed(listing.path, error, listing.access)
                },
            )
        }
    }

    fun makeFolder(path: String, name: String, callback: (GlassesFilesError?) -> Unit) {
        val requestId = newId()
        request(requestId, GlassesFilesRequest.MakeFolder(requestId, path, name), OPERATION_TIMEOUT_MS, callback) {
            callback((it as? GlassesFilesResult.Operation)?.error)
        }
    }

    fun delete(path: String, directory: Boolean, callback: (GlassesFilesError?) -> Unit) {
        val requestId = newId()
        request(requestId, GlassesFilesRequest.Delete(requestId, path, directory), OPERATION_TIMEOUT_MS, callback) {
            callback((it as? GlassesFilesResult.Operation)?.error)
        }
    }

    fun grantAccess(callback: (GlassesFilesResult.Operation?) -> Unit) {
        val requestId = newId()
        // The glasses may have to reach their own ADB helper first, which takes a few seconds.
        request(requestId, GlassesFilesRequest.GrantAccess(requestId), GRANT_TIMEOUT_MS, { callback(null) }) {
            callback(it as? GlassesFilesResult.Operation)
        }
    }

    /** [newlyTaken]: the grants the screen acquired for this pick, as opposed to ones already held. */
    fun enqueueSends(context: Context, folderPath: String, uris: List<Uri>, newlyTaken: Set<String>) {
        val sources = uris.map(Uri::toString)
        val pick = synchronized(lock) {
            if (closed.get()) return
            grants.adopt(newlyTaken)
            // The whole pick holds its grants while metadata is read, so neither a shutdown nor a
            // parallel pick can release a grant this intake is about to queue.
            sources.forEach(grants::hold)
            PendingIntake(cancelEpoch, minOf(sources.size, MAX_FILES_PER_PICK)).also { pendingIntakes += it }
        }
        publish(force = true)
        // A cloud provider can take its time answering a metadata query; never on the main thread.
        intake.execute {
            val described = GlassesPickIntake.describeAll(
                sources.take(MAX_FILES_PER_PICK),
                providerIo,
                INTAKE_QUERY_TIMEOUT_MS,
                pick.hooks,
            ) { source, registerCancel -> describe(context, Uri.parse(source), registerCancel) }
            val items = described.map { (source, metadata) ->
                GlassesTransferItem(
                    id = newId(),
                    direction = GlassesTransferDirection.SEND,
                    name = metadata.first,
                    folderPath = folderPath,
                    source = source,
                    totalBytes = metadata.second,
                )
            }
            val kept = synchronized(lock) {
                pendingIntakes -= pick
                // close() already released every grant this controller owned.
                if (closed.get()) return@execute
                // A Cancel all while this pick was being read covers it: nothing from it is queued.
                val keep = pick.epoch == cancelEpoch
                if (keep) {
                    items.forEach { grants.hold(it.source) }
                    transfers += items
                }
                // Picks past the limit, failed lookups, duplicates and cancelled picks give back
                // what nothing kept.
                sources.forEach(grants::drop)
                keep && items.isNotEmpty()
            }
            publish(force = true)
            if (!kept) return@execute
            publish(force = true)
            kickWorker()
        }
    }

    fun enqueueCopy(entryPath: String, name: String, sizeBytes: Long) {
        val item = GlassesTransferItem(
            id = newId(),
            direction = GlassesTransferDirection.COPY,
            name = name,
            folderPath = GlassesFilesContract.parent(entryPath),
            source = entryPath,
            totalBytes = sizeBytes,
        )
        synchronized(lock) { transfers += item }
        publish(force = true)
        kickWorker()
    }

    fun cancel(id: String) {
        val running = synchronized(lock) {
            val item = transfers.firstOrNull { it.id == id } ?: return
            transfers.removeAll { it.id == id }
            releaseHold(item)
            active?.takeIf { it.item.id == id }
        }
        running?.cancel()
        publish(force = true)
    }

    fun cancelAll() {
        val (running, intakes) = synchronized(lock) {
            val dropped = transfers.filter { it.state == GlassesTransferState.QUEUED || it.state == GlassesTransferState.RUNNING }
            transfers.removeAll(dropped.toSet())
            dropped.forEach(::releaseHold)
            cancelEpoch += 1
            active?.takeIf { running -> dropped.any { it.id == running.item.id } } to pendingIntakes.toList()
        }
        running?.cancel()
        // Stop their outstanding metadata queries too; their holds are dropped when they return.
        intakes.forEach { it.hooks.cancel() }
        publish(force = true)
    }

    fun retry(id: String) {
        synchronized(lock) {
            val index = transfers.indexOfFirst { it.id == id && it.state == GlassesTransferState.STOPPED }
            if (index < 0) return
            transfers[index] = transfers[index].copy(
                state = GlassesTransferState.QUEUED,
                doneBytes = 0L,
                stop = null,
                stoppedAtPercent = null,
                remainingSeconds = null,
                viaWifi = false,
            )
        }
        publish(force = true)
        kickWorker()
    }

    fun dismiss(id: String) {
        synchronized(lock) {
            val item = transfers.firstOrNull { it.id == id && it.state != GlassesTransferState.RUNNING } ?: return
            transfers.remove(item)
            releaseHold(item)
        }
        publish(force = true)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        val running = synchronized(lock) {
            // The queue lives in memory only, so nothing could ever retry these: drop their grants.
            transfers.clear()
            grants.dropAll()
            active
        }
        running?.cancel()
        inbox.shutdownNow()
        intake.shutdownNow()
        worker.shutdownNow()
        providerIo.shutdownNow()
        hotspot.close()
        laneIo.shutdownNow()
        timers.shutdownNow()
        cancelNotification()
    }

    private fun request(
        requestId: String,
        request: GlassesFilesRequest,
        timeoutMs: Long,
        onTimeout: (GlassesFilesError) -> Unit,
        onResult: (GlassesFilesResult) -> Unit,
    ) {
        val pendingRequest = Pending(onResult, onTimeout)
        synchronized(lock) { pending[requestId] = pendingRequest }
        pendingRequest.timeout = timers.schedule({
            val expired = synchronized(lock) { pending.remove(requestId) }
            expired?.expire(GlassesFilesError.TIMEOUT)
        }, timeoutMs, TimeUnit.MILLISECONDS)
        if (send(request) != null) {
            val failed = synchronized(lock) { pending.remove(requestId) }
            failed?.expire(GlassesFilesError.LINK_LOST)
        }
    }

    private fun onResult(result: GlassesFilesResult) {
        if (result is GlassesFilesResult.HotspotFailed) hotspot.onPeerFailed(result.transferId)
        val requestId = when (result) {
            is GlassesFilesResult.Listing -> result.requestId
            is GlassesFilesResult.Operation -> result.requestId
            else -> null
        }
        if (requestId != null) {
            val waiting = synchronized(lock) { pending.remove(requestId) } ?: return
            waiting.timeout?.cancel(false)
            waiting.onResult(result)
            return
        }
        synchronized(lock) { active }?.receiveResult(result)
    }

    private fun onDownloadChunk(chunk: GlassesFilesChunk, bytes: ByteArray) {
        val download = synchronized(lock) { active } as? ActiveCopy ?: return
        download.onChunk(chunk, bytes)
    }

    private fun kickWorker() {
        synchronized(lock) {
            if (workerRunning || closed.get()) return
            workerRunning = true
        }
        worker.execute(::drainQueue)
    }

    private fun drainQueue() {
        while (!closed.get()) {
            // Picking, claiming and marking the item happen under one lock: a cancel can then only
            // land before the claim (the item is gone) or after it (it finds [active] to stop).
            // Clearing [workerRunning] in the same section closes the window where an enqueue sees
            // a worker that is already on its way out.
            val transfer = synchronized(lock) {
                val candidate = if (connected && dataPlaneUp) {
                    transfers.firstOrNull { it.state == GlassesTransferState.QUEUED }
                } else {
                    null
                }
                if (candidate == null) {
                    workerRunning = false
                    null
                } else {
                    val claimed = when (candidate.direction) {
                        GlassesTransferDirection.SEND -> ActiveSend(candidate)
                        GlassesTransferDirection.COPY -> ActiveCopy(candidate)
                    }
                    active = claimed
                    update(candidate.id) { it.copy(state = GlassesTransferState.RUNNING, doneBytes = 0L) }
                    claimed
                }
            }
            if (transfer == null) {
                publish(force = true)
                return
            }
            val next = transfer.item
            publish(force = true)
            val outcome = runCatching { transfer.run() }.getOrElse {
                Log.w(TAG, "glasses file transfer crashed", it)
                Outcome.Stopped(GlassesTransferStop.REFUSED)
            }
            // Every unsuccessful exit tells the glasses, so they drop their staging file or stop
            // streaming now instead of after the idle timeout. Harmless if they already gave up.
            if (outcome is Outcome.Stopped) send(GlassesFilesRequest.Cancel(transfer.wireId))
            transfer.retireHotspot()
            synchronized(lock) {
                active = null
                val now = System.currentTimeMillis()
                update(next.id) { item ->
                    when (outcome) {
                        is Outcome.Done -> item.copy(
                            state = GlassesTransferState.DONE,
                            doneBytes = item.totalBytes ?: item.doneBytes,
                            savedAs = outcome.savedAs,
                            alreadyPresent = outcome.alreadyPresent,
                            remainingSeconds = null,
                            finishedAtMillis = now,
                        )
                        is Outcome.Stopped -> item.copy(
                            state = GlassesTransferState.STOPPED,
                            stop = outcome.reason,
                            stoppedAtPercent = item.percent,
                            remainingSeconds = null,
                            finishedAtMillis = now,
                        )
                        Outcome.Cancelled -> item
                    }
                }
                // A finished row lingers on screen but no longer needs its source.
                transfers.firstOrNull { it.id == next.id && it.state == GlassesTransferState.DONE }?.let {
                    if (it.direction == GlassesTransferDirection.SEND) grants.drop(it.source)
                }
            }
            publish(force = true)
            scheduleDoneCleanup()
        }
    }

    /** Done rows linger long enough to be seen, then leave the card. */
    private fun scheduleDoneCleanup() {
        timers.schedule({
            val changed = synchronized(lock) {
                val cutoff = System.currentTimeMillis() - DONE_LINGER_MS
                transfers.removeAll { it.state == GlassesTransferState.DONE && it.finishedAtMillis <= cutoff }
            }
            if (changed) publish(force = true)
        }, DONE_LINGER_MS + 100, TimeUnit.MILLISECONDS)
    }

    private fun update(id: String, change: (GlassesTransferItem) -> GlassesTransferItem) {
        val index = transfers.indexOfFirst { it.id == id }
        if (index >= 0) transfers[index] = change(transfers[index])
    }

    private fun progress(id: String, doneBytes: Long, totalBytes: Long?, rate: GlassesTransferRate) {
        val now = SystemClock.elapsedRealtime()
        synchronized(rate) {
            rate.record(now, doneBytes)
            synchronized(lock) {
                update(id) {
                    it.copy(
                        doneBytes = doneBytes,
                        totalBytes = totalBytes ?: it.totalBytes,
                        remainingSeconds = rate.remainingSeconds(now, doneBytes, totalBytes ?: it.totalBytes),
                    )
                }
            }
        }
        publish(force = false)
    }

    private fun publish(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        synchronized(lock) {
            if (!force && now - lastPublishAt < PUBLISH_INTERVAL_MS) {
                if (!publishScheduled) {
                    publishScheduled = true
                    timers.schedule({
                        synchronized(lock) { publishScheduled = false }
                        publish(force = true)
                    }, PUBLISH_INTERVAL_MS, TimeUnit.MILLISECONDS)
                }
                return
            }
            lastPublishAt = now
        }
        val snapshot = snapshot()
        GlassesFilesHub.publish(snapshot)
        updateNotification(snapshot.transfers)
    }

    private fun send(request: GlassesFilesRequest): String? {
        val payload = runCatching { GlassesFilesContract.encode(request) }.getOrNull() ?: return "INVALID"
        return sendRemote(BusEnvelope(path = GlassesFilesContract.REQUEST_PATH, payload = payload))
    }

    private fun describe(context: Context, uri: Uri, registerCancel: (() -> Unit) -> Unit): Pair<String, Long?>? = runCatching {
        val cancellation = CancellationSignal()
        registerCancel { cancellation.cancel() }
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
            cancellation,
        )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val rawName = cursor.getString(0)?.trim().orEmpty()
                val size = if (cursor.isNull(1)) null else cursor.getLong(1).takeIf { it >= 0 }
                val name = sanitizeName(rawName.ifEmpty { "file" })
                name to size
            }
    }.getOrNull()

    private fun sanitizeName(raw: String): String {
        val cleaned = raw.map { if (it == '/' || it == '\\' || it.isISOControl()) '_' else it }.joinToString("")
        var name = cleaned.takeIf { it != "." && it != ".." } ?: "file"
        while (name.toByteArray(Charsets.UTF_8).size > GlassesFilesContract.MAX_NAME_BYTES) name = name.dropLast(1)
        return name.ifEmpty { "file" }
    }

    /** Called under [lock] when an item leaves the queue; finished rows already gave theirs back. */
    private fun releaseHold(item: GlassesTransferItem) {
        if (item.direction == GlassesTransferDirection.SEND && item.state != GlassesTransferState.DONE) {
            grants.drop(item.source)
        }
    }

    private fun releaseGrant(source: String) {
        runCatching {
            appContext.contentResolver.releasePersistableUriPermission(
                Uri.parse(source),
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun newId(): String = "files_" + UUID.randomUUID().toString().replace("-", "")

    private sealed interface StageResult {
        data class Staged(val sizeBytes: Long) : StageResult
        data class Ended(val outcome: Outcome) : StageResult
    }

    private sealed interface Outcome {
        data class Done(val savedAs: String? = null, val alreadyPresent: Boolean = false) : Outcome
        data class Stopped(val reason: GlassesTransferStop) : Outcome
        data object Cancelled : Outcome
    }

    private class Pending(val onResult: (GlassesFilesResult) -> Unit, private val onTimeout: (GlassesFilesError) -> Unit) {
        var timeout: ScheduledFuture<*>? = null
        private val done = AtomicBoolean(false)

        fun expire(error: GlassesFilesError) {
            if (!done.compareAndSet(false, true)) return
            timeout?.cancel(false)
            onTimeout(error)
        }
    }

    /** Signals from the inbox and link thread to a worker blocked inside [run]. */
    private abstract inner class ActiveTransfer(val item: GlassesTransferItem) {
        /**
         * Fresh for every attempt, unlike [GlassesTransferItem.id]: a retry must never accept a
         * late chunk or terminal result still in flight from the attempt it replaced.
         */
        val wireId: String = newId()
        protected val signal = Object()
        protected var acked = 0L
        protected var terminal: GlassesFilesResult? = null
        protected var stop: GlassesTransferStop? = null
        protected var cancelled = false
        protected var lastActivity = SystemClock.elapsedRealtime()
        protected val rate = GlassesTransferRate()
        @Volatile protected var lane: GlassesFilesLaneClient? = null
        protected val resync = GlassesFilesResync()
        private var laneOpening = false
        private var laneAttempted = false
        private var hotspotAttempted = false
        private var hotspotDeadline = 0L
        private var sharedNetwork = false
        protected var upgradePending = false
        protected val upgrade = GlassesFilesLaneUpgrade()

        protected abstract fun onLaneFrame(frame: GlassesFilesLane.Frame)
        protected abstract fun switchToLane()
        protected open fun laneUpgradeComplete(): Boolean = !upgradePending

        fun retireHotspot() {
            synchronized(signal) { hotspotDeadline = 0L }
            hotspot.finish(wireId)
        }

        protected fun maybeHotspot() {
            val remaining = synchronized(lock) {
                transfers.firstOrNull { it.id == item.id }?.let { (it.totalBytes ?: 0L) - it.doneBytes } ?: 0L
            }
            if (!GlassesFilesHotspotPolicy.shouldStart(remaining, sharedNetwork || resync.laneOpened,
                    cameraActive = cameraActive(), permissionGranted = true, peerSupported = hotspotSupported && laneSupported)) return
            synchronized(signal) {
                if (hotspotAttempted || resync.switched || cancelled || stop != null || terminal != null) return
                hotspotAttempted = true
                hotspotDeadline = SystemClock.elapsedRealtime() + GlassesFilesHotspotPolicy.SETUP_MS
            }
            if (!GlassesFilesHotspotHost.permitted(appContext)) Handler(Looper.getMainLooper()).post { GlassesFilesHub.requestHotspotPermission?.invoke() }
            awaitHotspotPermission()
        }

        private fun awaitHotspotPermission() {
            synchronized(signal) {
                if (hotspotDeadline == 0L || SystemClock.elapsedRealtime() >= hotspotDeadline || cancelled || stop != null || terminal != null) return
            }
            if (cameraActive()) return
            if (!GlassesFilesHotspotHost.permitted(appContext)) {
                timers.schedule(::awaitHotspotPermission, 250L, TimeUnit.MILLISECONDS)
                return
            }
            hotspot.acquire(wireId, synchronized(signal) { hotspotDeadline }) { inbox.execute { hotspotFailed() } }
            val remaining = synchronized(signal) { (hotspotDeadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L) }
            timers.schedule({ inbox.execute {
                if (synchronized(signal) { hotspotDeadline != 0L && (!resync.laneOpened || !laneUpgradeComplete()) }) hotspotFailed()
            } }, remaining, TimeUnit.MILLISECONDS)
        }

        private fun hotspotFailed() {
            synchronized(signal) {
                if (hotspotDeadline == 0L) return
                hotspotDeadline = 0L
            }
            if (resync.laneOpened && !bytesComplete()) onLaneLost()
            hotspot.close()
        }

        protected fun markWifiBytes() {
            synchronized(lock) {
                if (synchronized(signal) { cancelled || stop != null || resync.switched }) return
                update(item.id) { it.copy(viaWifi = true) }
            }
        }

        private val deferredLaneResults = ArrayList<GlassesFilesResult>()

        /** Only worth asking for when the glasses can offer one and the phone is on Wi-Fi. */
        protected fun wantsLane(): Boolean = laneSupported && GlassesFilesLaneClient.isOnWifi(appContext)

        /** True once every byte made it, so a lane the peer closes afterwards is no failure. */
        protected abstract fun bytesComplete(): Boolean

        /**
         * Connects the offered lane; on failure the transfer quietly stays on the bus and the
         * glasses are told so, so they stop waiting for it. An opened lane can switch once to bus.
         */
        protected fun openLane(endpoint: GlassesFilesLaneEndpoint, onFrame: (GlassesFilesLane.Frame) -> Unit,
            hotspotGateway: String? = null) {
            if (hotspotGateway == null) sharedNetwork = GlassesFilesLaneClient.sharesSubnet(appContext, endpoint.host)
            synchronized(signal) { laneOpening = true; laneAttempted = true }
            val client = GlassesFilesLaneClient.connect(appContext, wireId, endpoint, hotspotGateway)
            if (client == null) {
                val deferred = synchronized(signal) {
                    laneOpening = false
                    deferredLaneResults.toList().also {
                        deferredLaneResults.clear()
                        // A peer snapshot already received must stop the upload worker before it
                        // resumes bus writes; the inbox will resolve the frozen offset below.
                        if (it.isNotEmpty()) upgradePending = true
                    }
                }
                send(GlassesFilesRequest.LaneDeclined(wireId))
                deferred.forEach { result -> inbox.execute { onResult(result) } }
                if (hotspotGateway != null) inbox.execute { hotspotFailed() }
                return
            }
            lane = client
            // A cancel or failure that landed while connecting found no lane to close; close it here.
            val stopped = synchronized(signal) {
                laneOpening = false
                if (cancelled || stop != null || terminal != null || resync.switched ||
                    (hotspotGateway != null && (hotspotDeadline == 0L || SystemClock.elapsedRealtime() >= hotspotDeadline))) true else {
                    if (hotspotGateway == null) resync.opened()
                    false
                }
            }
            if (stopped) {
                client.close()
                return
            }
            if (hotspotGateway != null) inbox.execute {
                Log.i(TAG, "glasses files hotspot lane proven setupMs=${SystemClock.elapsedRealtime() - (hotspotDeadline - GlassesFilesHotspotPolicy.SETUP_MS)}")
                switchToLane()
            }
            val deferred = synchronized(signal) { deferredLaneResults.toList().also { deferredLaneResults.clear() } }
            deferred.forEach { result -> inbox.execute { onResult(result) } }
            laneIo.execute {
                try {
                    while (true) onFrame(client.read())
                } catch (_: Exception) {
                    val wasClosed = client.isClosed
                    client.close()
                    if (!wasClosed) inbox.execute { if (!bytesComplete()) onLaneLost() }
                }
            }
        }

        abstract fun run(): Outcome

        open fun onResult(result: GlassesFilesResult) = Unit

        fun receiveResult(result: GlassesFilesResult) {
            if (result is GlassesFilesResult.HotspotFailed && result.transferId == wireId) {
                hotspotFailed()
                return
            }
            if (result is GlassesFilesResult.HotspotReady && result.transferId == wireId) {
                synchronized(signal) {
                    if (hotspotDeadline == 0L || SystemClock.elapsedRealtime() >= hotspotDeadline || laneOpening || resync.laneOpened || resync.switched || cancelled || stop != null || terminal != null) return
                    laneOpening = true
                }
                laneIo.execute { openLane(result.lane, ::onLaneFrame, result.gateway) }
                return
            }
            synchronized(signal) {
                val matchingResync = when (result) {
                    is GlassesFilesResult.LaneResync -> result.transferId == wireId
                    is GlassesFilesResult.BusResume -> result.transferId == wireId
                    else -> false
                }
                if (laneOpening && matchingResync) {
                    deferredLaneResults += result
                    return
                }
            }
            onResult(result)
        }

        protected abstract fun onLaneLost()

        /** The authenticated bus can confirm peer acceptance even when the sealed answer was lost. */
        protected fun peerAcceptedLane(): Boolean = synchronized(signal) {
            if (!laneAttempted && !resync.laneOpened) return false
            resync.proven()
            true
        }

        protected fun beginResync(): Boolean {
            lane?.close()
            val started = synchronized(signal) {
                if (cancelled || stop != null || terminal != null) return false
                resync.begin(SystemClock.elapsedRealtime()).also {
                    if (it) { touch(); upgradePending = false }
                    signal.notifyAll()
                }
            }
            if (!started) return false
            synchronized(rate) { rate.reset() }
            synchronized(lock) { update(item.id) { it.copy(viaWifi = false, remainingSeconds = null) } }
            publish(force = true)
            return true
        }

        fun cancel() {
            lane?.close()
            synchronized(signal) {
                cancelled = true
                signal.notifyAll()
            }
            // A worker blocked writing to a lane that went silent only wakes when its socket closes;
            // closing outside the signal monitor keeps this from waiting on an ack writer.
            lane?.close()
            send(GlassesFilesRequest.Cancel(wireId))
        }

        fun fail(reason: GlassesTransferStop) {
            lane?.close()
            synchronized(signal) {
                if (stop == null) stop = reason
                signal.notifyAll()
            }
            lane?.close()
        }

        protected fun touch() {
            lastActivity = SystemClock.elapsedRealtime()
        }

        /** Waits until [ready] holds; null when it did, or how the wait ended otherwise. */
        protected fun await(ready: () -> Boolean): Outcome? {
            while (true) {
                val idleLane = synchronized(signal) {
                    if (cancelled) return Outcome.Cancelled
                    stop?.let { return Outcome.Stopped(it) }
                    if (resync.expired(SystemClock.elapsedRealtime())) return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                    if (ready()) return null
                    if (SystemClock.elapsedRealtime() - lastActivity > GlassesFilesContract.IDLE_TIMEOUT_MS) {
                        if (!resync.laneOpened || resync.switched || bytesComplete()) return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                        true
                    } else {
                        signal.wait(WAIT_POLL_MS)
                        false
                    }
                }
                if (idleLane) onLaneLost()
            }
        }

        /** The cancel or link loss already recorded, without waiting for anything. */
        protected fun abortOutcome(): Outcome? = synchronized(signal) {
            when {
                cancelled -> Outcome.Cancelled
                stop != null -> Outcome.Stopped(stop!!)
                else -> null
            }
        }

        protected fun failedReason(): Outcome.Stopped? =
            (terminal as? GlassesFilesResult.TransferFailed)?.let { Outcome.Stopped(GlassesTransferStop.from(it.error)) }
    }

    private inner class ActiveSend(item: GlassesTransferItem) : ActiveTransfer(item) {
        private var ready: GlassesFilesResult.UploadReady? = null
        private var attempted = 0L
        private var resumePending: UploadResume? = null

        override fun switchToLane() {
            synchronized(signal) {
                if (cancelled || stop != null || terminal != null || resync.laneOpened) return
                resync.opened()
                upgradePending = true
                touch()
            }
            if (send(GlassesFilesRequest.LaneSwitch(wireId)) != null) onLaneLost()
        }

        override fun onLaneFrame(frame: GlassesFilesLane.Frame) {
            if (frame is GlassesFilesLane.Frame.Ack) {
                val advanced = synchronized(signal) {
                    if (!resync.accepts(viaLane = true)) return
                    val advanced = frame.stagedBytes > acked
                    if (frame.stagedBytes > acked) {
                        acked = frame.stagedBytes
                    }
                    touch()
                    signal.notifyAll()
                    advanced
                }
                if (advanced) markWifiBytes()
            }
        }

        override fun onLaneLost() {
            if (beginResync() && send(GlassesFilesRequest.LaneResync(wireId)) != null) fail(GlassesTransferStop.LINK_LOST)
        }

        override fun onResult(result: GlassesFilesResult) {
            if (result is GlassesFilesResult.LaneResume && result.transferId == wireId) {
                synchronized(signal) {
                    if (!upgradePending || resync.switched) return
                    if (!upgrade.snapshot(result.stagedBytes, minOf(sizeBytes, attempted))) {
                        stop = GlassesTransferStop.LINK_LOST
                    } else {
                        acked = result.stagedBytes
                        resumePending = UploadResume(result.stagedBytes, toLane = true)
                        upgradePending = false
                        Log.i(TAG, "glasses files upload lane resume staged=${result.stagedBytes}")
                    }
                    touch()
                    signal.notifyAll()
                }
                return
            }
            if (result is GlassesFilesResult.LaneResync && result.transferId == wireId) {
                if (peerAcceptedLane()) onLaneLost()
                return
            }
            if (result is GlassesFilesResult.BusResume && result.transferId == wireId) {
                if (!peerAcceptedLane()) return
                beginResync()
                synchronized(signal) {
                    if (!resync.switched || cancelled || stop != null) return
                    val first = resync.offset == null
                    if (!resync.offer(result.stagedBytes, minOf(sizeBytes, attempted))) {
                        stop = GlassesTransferStop.LINK_LOST
                    } else if (first) {
                        acked = result.stagedBytes
                        resumePending = UploadResume(result.stagedBytes, toLane = false)
                        Log.i(TAG, "glasses files upload bus resume staged=${result.stagedBytes}")
                    }
                    signal.notifyAll()
                }
                return
            }
            synchronized(signal) {
                when (result) {
                    is GlassesFilesResult.UploadReady -> if (result.transferId == wireId) ready = result
                    is GlassesFilesResult.UploadProgress -> if (result.transferId == wireId &&
                        resync.accepts(viaLane = false) && result.stagedBytes > acked
                    ) {
                        acked = result.stagedBytes
                    }
                    is GlassesFilesResult.UploadDone -> if (result.transferId == wireId) terminal = result
                    is GlassesFilesResult.TransferFailed -> if (result.transferId == wireId) terminal = result
                    else -> return
                }
                touch()
                signal.notifyAll()
            }
        }

        override fun run(): Outcome {
            val uri = Uri.parse(item.source)
            var staged: File? = null
            try {
                val size = item.totalBytes ?: run {
                    // The provider would not say how big the file is, and the glasses need to know
                    // up front; a private copy answers the question.
                    val copy = File(appContext.cacheDir, "glasses-files-$wireId")
                    staged = copy
                    when (val result = stageUnknownSize(uri, copy)) {
                        is StageResult.Staged -> result.sizeBytes
                        is StageResult.Ended -> return result.outcome
                    }
                }
                if (size > MAX_SEND_BYTES) return Outcome.Stopped(GlassesTransferStop.REFUSED)
                synchronized(lock) { update(item.id) { it.copy(totalBytes = size) } }
                abortOutcome()?.let { return it }
                touch()
                if (send(GlassesFilesRequest.UploadBegin(wireId, item.folderPath, item.name, size, lane = wantsLane())) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
                await { ready != null || terminal != null }?.let { return it }
                failedReason()?.let { return it }
                (terminal as? GlassesFilesResult.UploadDone)?.let {
                    return Outcome.Done(alreadyPresent = it.alreadyPresent)
                }
                val finalName = ready!!.name
                sizeBytes = size
                ready!!.lane?.let { offer -> openLane(offer, ::onLaneFrame) }
                if (!resync.laneOpened) maybeHotspot()
                val digest = GlassesFilesSendDigest()
                var reader = openReader(uri, staged)
                try {
                    while (true) {
                        val resume = synchronized(signal) { resumePending.also { resumePending = null } }
                        if (resume != null) {
                            reader.close()
                            reader = openReader(uri, staged)
                            if (resume.toLane && synchronized(signal) { resync.switched }) continue
                            digest.rewind(resume.offset)
                            if (send(if (resume.toLane) GlassesFilesRequest.LaneResume(wireId, resume.offset) else GlassesFilesRequest.BusResume(wireId, resume.offset)) != null) {
                                return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                            }
                            if (!resume.toLane) synchronized(signal) { resync.confirm(resume.offset) }
                            progress(item.id, resume.offset, size, rate)
                        }
                        await { failedReason() != null || resumePending != null || (!upgradePending && (!resync.switched || resync.confirmed)) }
                            ?.let { return it }
                        failedReason()?.let { return it }
                        if (hasResume()) continue
                        val viaLane = synchronized(signal) { resync.laneOpened && !resync.switched }
                        val chunkSize = if (viaLane) GlassesFilesLane.CHUNK_BYTES else GlassesFilesContract.CHUNK_BYTES
                        val buffer = ByteArray(digest.readLimit(chunkSize))
                        val count = when (val read = reader.read(buffer) { isAborting() || needsResume() }) {
                            is GlassesProviderReader.Read.Bytes -> read.count
                            GlassesProviderReader.Read.End -> -1
                            GlassesProviderReader.Read.Aborted -> {
                                if (needsResume()) continue
                                return abortOutcome() ?: Outcome.Cancelled
                            }
                            GlassesProviderReader.Read.Stalled,
                            GlassesProviderReader.Read.Failed,
                            -> return Outcome.Stopped(GlassesTransferStop.UNREADABLE)
                        }
                        if (needsResume()) continue
                        if (count == 0) continue
                        if (count < 0) {
                            if (digest.position != size) return Outcome.Stopped(GlassesTransferStop.CHANGED)
                            await { failedReason() != null || resumePending != null ||
                                MediaSyncWindowPolicy.mayFinish(acked, size) }?.let { return it }
                            failedReason()?.let { return it }
                            if (needsResume()) continue
                            break
                        }
                        if (digest.position + count > size) return Outcome.Stopped(GlassesTransferStop.CHANGED)
                        if (digest.replaying) {
                            digest.record(buffer, count)
                            continue
                        }
                        val window = if (viaLane) GlassesFilesLane.WINDOW_BYTES else GlassesFilesContract.ACK_WINDOW_BYTES
                        await {
                            failedReason() != null || resumePending != null ||
                                MediaSyncWindowPolicy.maySend(digest.position + count, acked, window)
                        }?.let { return it }
                        failedReason()?.let { return it }
                        if (needsResume()) continue
                        if (!sendChunk(digest.position, buffer.copyOf(count), viaLane)) {
                            if (needsResume()) continue
                            if (viaLane) {
                                onLaneLost()
                                continue
                            }
                            return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                        }
                        digest.record(buffer, count)
                        progress(item.id, synchronized(signal) { acked }, size, rate)
                    }
                } finally {
                    reader.close()
                }
                progress(item.id, size, size, rate)
                if (send(GlassesFilesRequest.UploadEnd(wireId, digest.finish())) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
                await { terminal != null }?.let { return it }
                failedReason()?.let { return it }
                val done = terminal as GlassesFilesResult.UploadDone
                return Outcome.Done(savedAs = done.name.takeIf { it != item.name } ?: finalName.takeIf { it != item.name })
            } finally {
                lane?.close()
                staged?.delete()
            }
        }

        @Volatile private var sizeBytes = Long.MAX_VALUE

        override fun bytesComplete(): Boolean = synchronized(signal) { acked >= sizeBytes }

        private fun isAborting(): Boolean = synchronized(signal) { cancelled || stop != null }
        private fun hasResume(): Boolean = synchronized(signal) { resumePending != null }
        private fun needsResume(): Boolean = synchronized(signal) { upgradePending || resync.switched && !resync.confirmed || resumePending != null }

        /** Reads the private copy when there is one, the provider otherwise, abandonably either way. */
        private fun openReader(uri: Uri, staged: File?): GlassesProviderReader =
            GlassesProviderReader(providerIo, GlassesFilesContract.IDLE_TIMEOUT_MS, open = { registerCancel ->
                if (staged != null) {
                    staged.inputStream()
                } else {
                    openUpload(uri, registerCancel)
                }
            })

        /**
         * Copies a provider stream of unknown length into [target], bounded by the send limit and
         * abandoned on a cancel, a dropped link or a provider that stops answering.
         */
        private fun stageUnknownSize(uri: Uri, target: File): StageResult {
            val result = openReader(uri, null).use { reader ->
                runCatching {
                    target.outputStream().use { output ->
                        val buffer = ByteArray(GlassesFilesContract.CHUNK_BYTES)
                        var copied = 0L
                        while (true) {
                            val count = when (val read = reader.read(buffer, ::isAborting)) {
                                is GlassesProviderReader.Read.Bytes -> read.count
                                GlassesProviderReader.Read.End -> break
                                GlassesProviderReader.Read.Aborted ->
                                    return@runCatching StageResult.Ended(abortOutcome() ?: Outcome.Cancelled)
                                GlassesProviderReader.Read.Stalled,
                                GlassesProviderReader.Read.Failed,
                                -> return@runCatching StageResult.Ended(Outcome.Stopped(GlassesTransferStop.UNREADABLE))
                            }
                            copied += count
                            if (copied > MAX_SEND_BYTES) {
                                return@runCatching StageResult.Ended(Outcome.Stopped(GlassesTransferStop.REFUSED))
                            }
                            output.write(buffer, 0, count)
                            progress(item.id, copied, null, rate)
                        }
                        StageResult.Staged(copied)
                    }
                }.getOrElse { StageResult.Ended(Outcome.Stopped(GlassesTransferStop.UNREADABLE)) }
            }
            rate.reset()
            return result
        }

        private fun sendChunk(offset: Long, bytes: ByteArray, viaLane: Boolean): Boolean {
            val epoch = synchronized(signal) {
                // The worker must process the snapshot rewind before a buffer can acquire epoch 1.
                if (cancelled || stop != null || upgradePending || resumePending != null ||
                    (resync.switched && !resync.confirmed) || (viaLane && resync.switched)) return false
                attempted = maxOf(attempted, offset + bytes.size)
                resync.busEpoch
            }
            if (viaLane) return lane?.write(GlassesFilesLane.Frame.Data(offset, bytes)) == true
            val meta = GlassesFilesContract.chunkMeta(GlassesFilesChunk(wireId, offset, epoch))
            repeat(MAX_SEND_ATTEMPTS) {
                if (sendRemote(BusEnvelope(path = GlassesFilesContract.DATA_PATH, payload = meta, binary = bytes)) == null) {
                    return true
                }
                SystemClock.sleep(SEND_RETRY_MS)
            }
            return false
        }
    }

    private data class UploadResume(val offset: Long, val toLane: Boolean)

    private inner class ActiveCopy(item: GlassesTransferItem) : ActiveTransfer(item) {
        // Written only on the inbox thread once [run] has opened them.
        @Volatile private var output: OutputStream? = null
        private val receiver = GlassesFilesDownloadReceiver(resync) { output }
        private val staged: Long get() = receiver.staged
        private var chunksSinceAck = 0
        private var expectedSize: Long? = item.totalBytes
        private var verified: Boolean? = null
        private var readyArrived = false
        private var laneOffer: GlassesFilesLaneEndpoint? = null
        private var laneUpgradeConfirmed = true

        override fun laneUpgradeComplete(): Boolean = laneUpgradeConfirmed

        override fun switchToLane() {
            val offset = synchronized(signal) {
                if (cancelled || stop != null || terminal != null || resync.laneOpened) return
                if (!upgrade.snapshot(staged, expectedSize ?: Long.MAX_VALUE)) return
                resync.opened()
                laneUpgradeConfirmed = false
                Log.i(TAG, "glasses files download lane resume staged=$staged")
                chunksSinceAck = 0
                touch()
                staged
            }
            if (send(GlassesFilesRequest.LaneResume(wireId, offset)) != null) onLaneLost()
        }

        override fun onLaneFrame(frame: GlassesFilesLane.Frame) {
            if (frame is GlassesFilesLane.Frame.Data) inbox.execute {
                onChunk(GlassesFilesChunk(wireId, frame.offset), frame.bytes, viaLane = true)
            }
        }


        override fun bytesComplete(): Boolean = synchronized(signal) { expectedSize?.let { staged >= it } == true }

        override fun onLaneLost() {
            if (!beginResync()) return
            val offset = synchronized(signal) {
                resync.offer(staged, expectedSize ?: Long.MAX_VALUE)
                chunksSinceAck = 0
                resync.offset
            } ?: return
            Log.i(TAG, "glasses files download bus resume staged=$offset")
            if (send(GlassesFilesRequest.BusResume(wireId, offset)) != null) fail(GlassesTransferStop.LINK_LOST)
        }

        /** Acks go back over the lane when the bytes came that way. */
        private fun ack() {
            val viaLane = lane?.takeUnless { resync.switched }
            if (viaLane == null || !viaLane.write(GlassesFilesLane.Frame.Ack(staged))) {
                send(GlassesFilesRequest.DownloadProgress(wireId, staged))
            }
        }

        override fun onResult(result: GlassesFilesResult) {
            if (result is GlassesFilesResult.LaneResync && result.transferId == wireId) {
                if (peerAcceptedLane()) onLaneLost()
                return
            }
            synchronized(signal) {
                when (result) {
                    is GlassesFilesResult.LaneResume -> if (result.transferId == wireId && upgrade.offset != null) {
                        if (result.stagedBytes != upgrade.offset) stop = GlassesTransferStop.LINK_LOST
                        else laneUpgradeConfirmed = true
                    }
                    is GlassesFilesResult.BusResume -> if (result.transferId == wireId && resync.switched) {
                        if (!resync.confirm(result.stagedBytes)) stop = GlassesTransferStop.LINK_LOST
                    }
                    is GlassesFilesResult.DownloadReady -> if (result.transferId == wireId) {
                        expectedSize = result.sizeBytes
                        laneOffer = result.lane
                        readyArrived = true
                        when (GlassesDownloadAckPolicy.onReady(staged, result.sizeBytes)) {
                            GlassesReadyAction.ACK_STAGED -> {
                                chunksSinceAck = 0
                                ack()
                            }
                            GlassesReadyAction.CHANGED -> if (stop == null) stop = GlassesTransferStop.CHANGED
                            GlassesReadyAction.NONE -> Unit
                        }
                    }
                    is GlassesFilesResult.DownloadEnd -> if (result.transferId == wireId) {
                        verified = (laneUpgradeConfirmed || resync.confirmed) && (!resync.switched || resync.confirmed) && receiver.verify(expectedSize, result.sha256)
                        terminal = result
                    }
                    is GlassesFilesResult.TransferFailed -> if (result.transferId == wireId) terminal = result
                    else -> return
                }
                touch()
                signal.notifyAll()
            }
        }

        fun onChunk(chunk: GlassesFilesChunk, bytes: ByteArray, viaLane: Boolean = false) {
            if (chunk.transferId != wireId) return
            synchronized(signal) {
                if (!resync.acceptsChunk(viaLane, chunk.busEpoch) || cancelled || stop != null) return
                when (if (terminal != null) GlassesFilesDownloadReceiver.Write.OUT_OF_ORDER else receiver.write(chunk.offset, bytes, viaLane, chunk.busEpoch)) {
                    GlassesFilesDownloadReceiver.Write.IGNORED -> return
                    GlassesFilesDownloadReceiver.Write.OUT_OF_ORDER -> {
                        stop = GlassesTransferStop.LINK_LOST
                        signal.notifyAll()
                        return
                    }
                    GlassesFilesDownloadReceiver.Write.NO_SPACE -> {
                        stop = GlassesTransferStop.PHONE_FULL
                        signal.notifyAll()
                        return
                    }
                    GlassesFilesDownloadReceiver.Write.WRITTEN -> Unit
                }
                chunksSinceAck += 1
                touch()
                if (MediaSyncWindowPolicy.shouldAck(
                        chunksSinceAck, staged, expectedSize ?: Long.MAX_VALUE, GlassesFilesContract.ACK_EVERY_CHUNKS,
                    )
                ) {
                    chunksSinceAck = 0
                    ack()
                }
            }
            if (viaLane) markWifiBytes()
            progress(item.id, synchronized(signal) { staged }, expectedSize, rate)
        }

        override fun run(): Outcome {
            val resolver = appContext.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, item.name)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/Nexus/")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = runCatching { resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) }.getOrNull()
                ?: return Outcome.Stopped(GlassesTransferStop.PHONE_FULL)
            var published = false
            try {
                output = runCatching { resolver.openOutputStream(uri, "w") }.getOrNull()
                    ?: return Outcome.Stopped(GlassesTransferStop.PHONE_FULL)
                touch()
                if (send(GlassesFilesRequest.DownloadBegin(wireId, item.source, lane = wantsLane())) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
                await { readyArrived || terminal != null }?.let { return it }
                synchronized(signal) { laneOffer }?.let { offer -> openLane(offer, ::onLaneFrame) }
                if (!resync.laneOpened) maybeHotspot()
                await { terminal != null }?.let { return it }
                failedReason()?.let { return it }
                if (verified != true) return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                val closedOk = runCatching { output?.close() }.isSuccess
                output = null
                if (!closedOk) return Outcome.Stopped(GlassesTransferStop.PHONE_FULL)
                val publishedRows = runCatching {
                    resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
                }.getOrDefault(0)
                if (publishedRows <= 0) return Outcome.Stopped(GlassesTransferStop.PHONE_FULL)
                published = true
                val savedName = runCatching {
                    resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    }
                }.getOrNull()
                return Outcome.Done(savedAs = savedName?.takeIf { it != item.name })
            } finally {
                lane?.close()
                runCatching { output?.close() }
                output = null
                if (!published) runCatching { resolver.delete(uri, null, null) }
            }
        }
    }

    private fun updateNotification(items: List<GlassesTransferItem>) {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val running = items.firstOrNull { it.state == GlassesTransferState.RUNNING }
        val queued = items.count { it.state == GlassesTransferState.QUEUED }
        val stopped = items.firstOrNull { it.state == GlassesTransferState.STOPPED }
        if (running == null && queued == 0 && stopped == null) {
            cancelNotification()
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Glasses files", NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(
            appContext,
            0,
            Intent(appContext, GlassesFilesActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nexus_status)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
        if (running != null) {
            val sends = items.count { it.direction == GlassesTransferDirection.SEND && it.state != GlassesTransferState.DONE }
            val title = when {
                running.direction == GlassesTransferDirection.COPY -> "Copying to phone"
                sends > 1 -> "Sending $sends files to glasses"
                else -> "Sending to glasses"
            }
            val percent = running.percent
            val detail = listOfNotNull(
                running.name,
                percent?.let { "$it%" },
                "Wi-Fi".takeIf { running.viaWifi },
                running.remainingSeconds?.let { GlassesFilesFormat.remaining(it).lowercase() },
            ).joinToString(" · ")
            builder.setContentTitle(title)
                .setContentText(detail)
                .setOngoing(true)
                .setProgress(100, percent ?: 0, percent == null)
        } else if (stopped != null && queued == 0) {
            builder.setContentTitle(
                if (stopped.direction == GlassesTransferDirection.SEND) "Sending stopped" else "Copying stopped",
            )
                .setContentText("${stopped.name} · ${stopped.stop?.label?.lowercase().orEmpty()}")
                .setOngoing(false)
                .setAutoCancel(true)
        } else {
            builder.setContentTitle("Waiting for the glasses")
                .setContentText("$queued file${if (queued == 1) "" else "s"} queued")
                .setOngoing(false)
        }
        runCatching { manager.notify(NOTIFICATION_TAG, NOTIFICATION_ID, builder.build()) }
    }

    private fun cancelNotification() {
        runCatching {
            appContext.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_TAG, NOTIFICATION_ID)
        }
    }

    private companion object {
        const val TAG = "GlassesFiles"
        const val CHANNEL_ID = "glasses_files"
        const val NOTIFICATION_TAG = "glasses-files"
        const val NOTIFICATION_ID = 7
        const val LISTING_TIMEOUT_MS = 10_000L
        const val OPERATION_TIMEOUT_MS = 10_000L
        const val GRANT_TIMEOUT_MS = 30_000L
        const val PUBLISH_INTERVAL_MS = 250L
        const val DONE_LINGER_MS = 4_000L
        const val WAIT_POLL_MS = 250L
        const val MAX_SEND_ATTEMPTS = 3
        const val SEND_RETRY_MS = 250L
        const val MAX_FILES_PER_PICK = 20
        const val INTAKE_QUERY_TIMEOUT_MS = 10_000L
        const val MAX_SEND_BYTES = 2L * 1024 * 1024 * 1024
    }
}
