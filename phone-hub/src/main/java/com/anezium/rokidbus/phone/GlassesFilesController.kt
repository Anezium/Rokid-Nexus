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
import com.anezium.rokidbus.shared.GlassesFilesOperation
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
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
)

/**
 * Static edge between the explorer screen and the hub service, which share one process.
 *
 * Listings carry up to 200 entries; a broadcast per page would parcel all of them through the
 * system for no benefit, so the screen talks to the controller directly and gets callbacks on the
 * main thread instead.
 */
object GlassesFilesHub {
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
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val inbox = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-inbox").apply { isDaemon = true } }
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-transfer").apply { isDaemon = true } }
    private val timers = Executors.newSingleThreadScheduledExecutor { Thread(it, "glasses-files-timer").apply { isDaemon = true } }
    private val lock = Object()
    private val providerIo = Executors.newCachedThreadPool { Thread(it, "glasses-files-provider").apply { isDaemon = true } }

    // Guarded by [lock].
    private val transfers = ArrayList<GlassesTransferItem>()
    private val pending = HashMap<String, Pending>()
    private var active: ActiveTransfer? = null
    private var connected = false
    private var dataPlaneUp = false
    private var supported: Boolean? = null
    private var workerRunning = false
    private var lastPublishAt = 0L
    private var publishScheduled = false
    private val grants = GlassesGrantLedger(::releaseGrant)

    private val closed = AtomicBoolean(false)

    fun snapshot(): GlassesFilesSnapshot = synchronized(lock) {
        GlassesFilesSnapshot(connected && dataPlaneUp, supported, transfers.toList())
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
        publish(force = true)
        if (resume) kickWorker()
    }

    fun onCapabilities(glassesFilesSupported: Boolean) {
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
        val items = uris.take(MAX_FILES_PER_PICK).mapNotNull { uri ->
            val (name, size) = describe(context, uri) ?: return@mapNotNull null
            GlassesTransferItem(
                id = newId(),
                direction = GlassesTransferDirection.SEND,
                name = name,
                folderPath = folderPath,
                source = uri.toString(),
                totalBytes = size,
            )
        }
        synchronized(lock) {
            grants.adopt(newlyTaken)
            items.forEach { grants.hold(it.source) }
            transfers += items
            // Picks past the limit, failed lookups and duplicates give back what nothing kept.
            grants.settle(uris.map(Uri::toString))
        }
        if (items.isEmpty()) return
        publish(force = true)
        kickWorker()
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
        val running = synchronized(lock) {
            val dropped = transfers.filter { it.state == GlassesTransferState.QUEUED || it.state == GlassesTransferState.RUNNING }
            transfers.removeAll(dropped.toSet())
            dropped.forEach(::releaseHold)
            active?.takeIf { running -> dropped.any { it.id == running.item.id } }
        }
        running?.cancel()
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
        worker.shutdownNow()
        providerIo.shutdownNow()
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
        synchronized(lock) { active }?.onResult(result)
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

    private fun describe(context: Context, uri: Uri): Pair<String, Long?>? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
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

        abstract fun run(): Outcome

        open fun onResult(result: GlassesFilesResult) = Unit

        fun cancel() {
            synchronized(signal) {
                cancelled = true
                signal.notifyAll()
            }
            send(GlassesFilesRequest.Cancel(wireId))
        }

        fun fail(reason: GlassesTransferStop) {
            synchronized(signal) {
                if (stop == null) stop = reason
                signal.notifyAll()
            }
        }

        protected fun touch() {
            lastActivity = SystemClock.elapsedRealtime()
        }

        /** Waits until [ready] holds; null when it did, or how the wait ended otherwise. */
        protected fun await(ready: () -> Boolean): Outcome? {
            synchronized(signal) {
                while (true) {
                    if (cancelled) return Outcome.Cancelled
                    stop?.let { return Outcome.Stopped(it) }
                    if (ready()) return null
                    if (SystemClock.elapsedRealtime() - lastActivity > GlassesFilesContract.IDLE_TIMEOUT_MS) {
                        return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                    }
                    signal.wait(WAIT_POLL_MS)
                }
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

        override fun onResult(result: GlassesFilesResult) {
            synchronized(signal) {
                when (result) {
                    is GlassesFilesResult.UploadReady -> if (result.transferId == wireId) ready = result
                    is GlassesFilesResult.UploadProgress -> if (result.transferId == wireId && result.stagedBytes > acked) {
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
                if (send(GlassesFilesRequest.UploadBegin(wireId, item.folderPath, item.name, size)) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
                await { ready != null || terminal != null }?.let { return it }
                failedReason()?.let { return it }
                (terminal as? GlassesFilesResult.UploadDone)?.let {
                    return Outcome.Done(alreadyPresent = it.alreadyPresent)
                }
                val finalName = ready!!.name
                val digest: MessageDigest = GlassesFilesContract.newDigest()
                var position = 0L
                openReader(uri, staged).use { reader ->
                    val buffer = ByteArray(GlassesFilesContract.CHUNK_BYTES)
                    while (true) {
                        val count = when (val read = reader.read(buffer, ::isAborting)) {
                            is GlassesProviderReader.Read.Bytes -> read.count
                            GlassesProviderReader.Read.End -> break
                            GlassesProviderReader.Read.Aborted -> return abortOutcome() ?: Outcome.Cancelled
                            GlassesProviderReader.Read.Stalled,
                            GlassesProviderReader.Read.Failed,
                            -> return Outcome.Stopped(GlassesTransferStop.UNREADABLE)
                        }
                        if (count == 0) continue
                        if (position + count > size) return Outcome.Stopped(GlassesTransferStop.CHANGED)
                        await {
                            failedReason() != null ||
                                MediaSyncWindowPolicy.maySend(position + count, acked, GlassesFilesContract.ACK_WINDOW_BYTES)
                        }?.let { return it }
                        failedReason()?.let { return it }
                        digest.update(buffer, 0, count)
                        if (!sendChunk(position, buffer.copyOf(count))) return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                        position += count
                        progress(item.id, synchronized(signal) { acked }, size, rate)
                    }
                }
                if (position != size) return Outcome.Stopped(GlassesTransferStop.CHANGED)
                await { failedReason() != null || MediaSyncWindowPolicy.mayFinish(acked, size) }?.let { return it }
                failedReason()?.let { return it }
                progress(item.id, size, size, rate)
                if (send(GlassesFilesRequest.UploadEnd(wireId, GlassesFilesContract.hex(digest))) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
                await { terminal != null }?.let { return it }
                failedReason()?.let { return it }
                val done = terminal as GlassesFilesResult.UploadDone
                return Outcome.Done(savedAs = done.name.takeIf { it != item.name } ?: finalName.takeIf { it != item.name })
            } finally {
                staged?.delete()
            }
        }

        private fun isAborting(): Boolean = synchronized(signal) { cancelled || stop != null }

        /** Reads the private copy when there is one, the provider otherwise, abandonably either way. */
        private fun openReader(uri: Uri, staged: File?): GlassesProviderReader =
            GlassesProviderReader(providerIo, GlassesFilesContract.IDLE_TIMEOUT_MS, open = { registerCancel ->
                if (staged != null) {
                    staged.inputStream()
                } else {
                    val cancellation = CancellationSignal()
                    registerCancel { cancellation.cancel() }
                    val descriptor = appContext.contentResolver.openFileDescriptor(uri, "r", cancellation)
                        ?: throw IOException("provider returned no descriptor")
                    registerCancel { runCatching { descriptor.close() } }
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor)
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

        private fun sendChunk(offset: Long, bytes: ByteArray): Boolean {
            val meta = GlassesFilesContract.chunkMeta(GlassesFilesChunk(wireId, offset))
            repeat(MAX_SEND_ATTEMPTS) {
                if (sendRemote(BusEnvelope(path = GlassesFilesContract.DATA_PATH, payload = meta, binary = bytes)) == null) {
                    return true
                }
                SystemClock.sleep(SEND_RETRY_MS)
            }
            return false
        }
    }

    private inner class ActiveCopy(item: GlassesTransferItem) : ActiveTransfer(item) {
        // Written only on the inbox thread once [run] has opened them.
        @Volatile private var output: OutputStream? = null
        private val digest = GlassesFilesContract.newDigest()
        private var staged = 0L
        private var chunksSinceAck = 0
        private var expectedSize: Long? = item.totalBytes
        private var verified: Boolean? = null

        override fun onResult(result: GlassesFilesResult) {
            synchronized(signal) {
                when (result) {
                    is GlassesFilesResult.DownloadReady -> if (result.transferId == wireId) {
                        expectedSize = result.sizeBytes
                        when (GlassesDownloadAckPolicy.onReady(staged, result.sizeBytes)) {
                            GlassesReadyAction.ACK_STAGED -> {
                                chunksSinceAck = 0
                                send(GlassesFilesRequest.DownloadProgress(wireId, staged))
                            }
                            GlassesReadyAction.CHANGED -> if (stop == null) stop = GlassesTransferStop.CHANGED
                            GlassesReadyAction.NONE -> Unit
                        }
                    }
                    is GlassesFilesResult.DownloadEnd -> if (result.transferId == wireId) {
                        verified = staged == expectedSize && GlassesFilesContract.hex(digest) == result.sha256
                        terminal = result
                    }
                    is GlassesFilesResult.TransferFailed -> if (result.transferId == wireId) terminal = result
                    else -> return
                }
                touch()
                signal.notifyAll()
            }
        }

        fun onChunk(chunk: GlassesFilesChunk, bytes: ByteArray) {
            if (chunk.transferId != wireId) return
            val target = output ?: return
            synchronized(signal) {
                if (chunk.offset != staged || terminal != null) {
                    if (stop == null) stop = GlassesTransferStop.LINK_LOST
                    signal.notifyAll()
                    return
                }
                val written = runCatching { target.write(bytes) }.isSuccess
                if (!written) {
                    if (stop == null) stop = GlassesTransferStop.PHONE_FULL
                    signal.notifyAll()
                    return
                }
                digest.update(bytes)
                staged += bytes.size
                chunksSinceAck += 1
                touch()
                if (MediaSyncWindowPolicy.shouldAck(
                        chunksSinceAck, staged, expectedSize ?: Long.MAX_VALUE, GlassesFilesContract.ACK_EVERY_CHUNKS,
                    )
                ) {
                    chunksSinceAck = 0
                    send(GlassesFilesRequest.DownloadProgress(wireId, staged))
                }
            }
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
                if (send(GlassesFilesRequest.DownloadBegin(wireId, item.source)) != null) {
                    return Outcome.Stopped(GlassesTransferStop.LINK_LOST)
                }
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
        const val MAX_SEND_BYTES = 2L * 1024 * 1024 * 1024
    }
}
