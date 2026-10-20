package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.SystemClock
import android.provider.Settings
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.GlassesFileEntry
import com.anezium.rokidbus.shared.GlassesFilesAccess
import com.anezium.rokidbus.shared.GlassesFilesChunk
import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesOperation
import com.anezium.rokidbus.shared.GlassesFilesRequest
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Glasses half of the private file explorer behind `/core/files/request`.
 *
 * Requests and upload chunks are handled on one serial executor, in the order the bus delivered
 * them, so an upload's bytes land in the staging file exactly as sent. Downloads stream on their
 * own executor so a long read never holds up browsing, and their acks bypass both queues because
 * the streaming loop is blocked waiting for them.
 */
internal object GlassesFilesService {
    private val control = Executors.newSingleThreadExecutor { Thread(it, "glasses-files").apply { isDaemon = true } }
    private val streamer = Executors.newSingleThreadExecutor { Thread(it, "glasses-files-send").apply { isDaemon = true } }
    private val laneIo = Executors.newCachedThreadPool { Thread(it, "glasses-files-lane").apply { isDaemon = true } }
    private val timer = Executors.newSingleThreadScheduledExecutor {
        Thread(it, "glasses-files-timer").apply { isDaemon = true }
    }
    private val started = AtomicBoolean(false)

    @Volatile private var appContext: Context? = null
    private var store: GlassesFileStore? = null

    /** Confined to [control]. */
    private var upload: ActiveUpload? = null

    @Volatile private var download: ActiveDownload? = null

    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        control.execute {
            store = GlassesFileStore(Environment.getExternalStorageDirectory())
        }
        timer.scheduleWithFixedDelay(
            { control.execute(::expireIdleUpload) },
            IDLE_CHECK_MS,
            IDLE_CHECK_MS,
            TimeUnit.MILLISECONDS,
        )
    }

    /** True when [envelope] belongs to the explorer; invalid ones are answered, never routed on. */
    fun handle(envelope: BusEnvelope): Boolean {
        when (envelope.path) {
            GlassesFilesContract.DATA_PATH -> {
                val chunk = GlassesFilesContract.parseChunk(envelope.payload, envelope.binary)
                val bytes = envelope.binary
                if (chunk != null && bytes != null) control.execute { onUploadChunk(chunk, bytes) }
                return true
            }
            GlassesFilesContract.REQUEST_PATH -> Unit
            else -> return false
        }
        val request = if (envelope.binary == null) GlassesFilesContract.parseRequest(envelope.payload) else null
        if (request == null) {
            log("files request rejected id=${envelope.id}")
            return true
        }
        when (request) {
            is GlassesFilesRequest.DownloadProgress -> download?.onAck(request.transferId, request.stagedBytes)
            is GlassesFilesRequest.Cancel -> {
                download?.cancel(request.transferId)
                control.execute { cancelUpload(request.transferId) }
            }
            else -> control.execute { onRequest(request) }
        }
        return true
    }

    fun onLinkStateChanged(dataPlaneUp: Boolean) {
        if (dataPlaneUp) return
        download?.cancelAll()
        control.execute {
            upload?.let {
                log("files upload dropped reason=link_down name=${it.upload.name}")
                it.retire()
            }
            upload = null
        }
    }

    private fun onRequest(request: GlassesFilesRequest) {
        val store = store ?: return
        when (request) {
            is GlassesFilesRequest.ListFolder -> sendListing(store, request)
            is GlassesFilesRequest.MakeFolder -> sendResult(
                GlassesFilesResult.Operation(
                    request.requestId,
                    GlassesFilesOperation.MKDIR,
                    store.makeFolder(request.path, request.name),
                ),
            )
            is GlassesFilesRequest.Delete -> sendResult(
                GlassesFilesResult.Operation(
                    request.requestId,
                    GlassesFilesOperation.DELETE,
                    store.delete(request.path, request.directory),
                ),
            )
            is GlassesFilesRequest.GrantAccess -> grantAccess(request.requestId)
            is GlassesFilesRequest.UploadBegin -> beginUpload(store, request)
            is GlassesFilesRequest.UploadEnd -> finishUpload(request)
            is GlassesFilesRequest.DownloadBegin -> beginDownload(store, request)
            is GlassesFilesRequest.DownloadProgress,
            is GlassesFilesRequest.Cancel,
            -> Unit
        }
    }

    private fun sendListing(store: GlassesFileStore, request: GlassesFilesRequest.ListFolder) {
        val access = access()
        val result = when (val listing = store.list(request.path, request.offset)) {
            is GlassesFolderListing.Failed -> GlassesFilesResult.Listing(
                request.requestId, request.path, access, emptyList(), request.offset, 0, listing.error,
            )
            is GlassesFolderListing.Page -> GlassesFilesResult.Listing(
                request.requestId, request.path, access, listing.entries, listing.offset, listing.total,
            )
        }
        if (sendResult(result)) return
        // Without the data plane only a small control message gets through; send the largest
        // prefix that fits and let the phone page on from where it ends.
        val fitted = fitToControlBudget(result)
        if (fitted !== result) sendResult(fitted)
    }

    private fun grantAccess(requestId: String) {
        val context = appContext ?: return
        if (access() == GlassesFilesAccess.FULL) {
            sendResult(GlassesFilesResult.Operation(requestId, GlassesFilesOperation.GRANT_ACCESS, access = GlassesFilesAccess.FULL))
            return
        }
        val shell = runCatching { WirelessAdbShell.grantAllFilesAccess(context) }.getOrNull()
        log("files grant_access shell=${shell?.success} error=${shell?.error?.take(120)}")
        if (access() == GlassesFilesAccess.FULL) {
            sendResult(GlassesFilesResult.Operation(requestId, GlassesFilesOperation.GRANT_ACCESS, access = GlassesFilesAccess.FULL))
            return
        }
        // No privileged path is available: put Android's own switch in front of the wearer.
        val opened = runCatching {
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { logError("files grant_access settings failed", it) }.isSuccess
        sendResult(
            GlassesFilesResult.Operation(
                requestId,
                GlassesFilesOperation.GRANT_ACCESS,
                error = if (opened) null else GlassesFilesError.NOT_PERMITTED,
                access = GlassesFilesAccess.LIMITED,
                needsWearer = opened,
            ),
        )
    }

    private fun beginUpload(store: GlassesFileStore, request: GlassesFilesRequest.UploadBegin) {
        // The phone drives one transfer at a time; a new begin means it has given up on the old one,
        // whichever direction that was.
        upload?.retire()
        upload = null
        download?.cancelAll()
        if (store.alreadyPresent(request.path, request.name, request.sizeBytes)) {
            log("files upload skipped reason=already_present")
            sendResult(GlassesFilesResult.UploadDone(request.transferId, request.name, alreadyPresent = true))
            return
        }
        store.beginUpload(request.path, request.name, request.sizeBytes, request.transferId)
            .onSuccess { staged ->
                val lane = if (request.lane) appContext?.let { GlassesFilesLaneServer.open(it, request.transferId) } else null
                val active = ActiveUpload(request.transferId, staged, lane)
                upload = active
                log("files upload begin size=${request.sizeBytes} name=${staged.name} lane=${lane != null}")
                sendResult(GlassesFilesResult.UploadReady(request.transferId, staged.name, lane?.endpoint))
                if (lane != null) laneIo.execute { serveUploadLane(active, lane) }
            }
            .onFailure { failure ->
                val error = (failure as? GlassesFilesException)?.error ?: GlassesFilesError.INTERNAL
                log("files upload refused error=${error.wireValue}")
                sendResult(GlassesFilesResult.TransferFailed(request.transferId, error))
            }
    }

    private fun onUploadChunk(chunk: GlassesFilesChunk, bytes: ByteArray) {
        val active = upload?.takeIf { it.transferId == chunk.transferId } ?: return
        active.lastActivity = SystemClock.elapsedRealtime()
        val error = active.upload.write(chunk.offset, bytes)
        if (error != null) {
            failUpload(active, error)
            return
        }
        active.chunksSinceAck += 1
        if (MediaSyncWindowPolicy.shouldAck(
                active.chunksSinceAck,
                active.upload.stagedBytes,
                active.upload.sizeBytes,
                GlassesFilesContract.ACK_EVERY_CHUNKS,
            )
        ) {
            active.chunksSinceAck = 0
            val staged = active.upload.stagedBytes
            // Acks go back the way the bytes came: over the lane they cost no bus round trip.
            val lane = active.lane?.takeIf { it.isConnected() }
            val acked = (lane != null && lane.write(GlassesFilesLane.Frame.Ack(staged))) ||
                sendResult(GlassesFilesResult.UploadProgress(active.transferId, staged))
            if (acked && staged == active.upload.sizeBytes) active.finalAckSent = true
        }
    }

    /**
     * Feeds the upload from its lane, frame by frame, into the same serial path the bus chunks
     * take. A phone that never connects simply leaves the upload on the bus.
     */
    private fun serveUploadLane(active: ActiveUpload, lane: GlassesFilesLaneServer) {
        if (!lane.accept()) {
            log("files lane not taken")
            lane.close()
            return
        }
        log("files lane connected")
        try {
            while (true) {
                val frame = lane.read()
                if (frame is GlassesFilesLane.Frame.Data) {
                    control.execute { onUploadChunk(GlassesFilesChunk(active.transferId, frame.offset), frame.bytes) }
                }
            }
        } catch (_: Exception) {
            // The phone closes its end once the transfer is over; only a break before the last byte
            // was acknowledged fails it. After that, the bus terminator or the idle timeout decides.
            control.execute {
                if (upload === active && !active.finalAckSent) failUpload(active, GlassesFilesError.LINK_LOST)
            }
        } finally {
            lane.close()
        }
    }

    private fun finishUpload(request: GlassesFilesRequest.UploadEnd) {
        val active = upload?.takeIf { it.transferId == request.transferId } ?: run {
            sendResult(GlassesFilesResult.TransferFailed(request.transferId, GlassesFilesError.CANCELLED))
            return
        }
        upload = null
        active.lane?.close()
        val error = active.upload.finish(request.sha256)
        if (error != null) {
            log("files upload failed error=${error.wireValue}")
            sendResult(GlassesFilesResult.TransferFailed(active.transferId, error))
            return
        }
        log("files upload done size=${active.upload.sizeBytes} name=${active.upload.name}")
        appContext?.let { context ->
            // Readers that browse through MediaStore only see the file once it is scanned.
            runCatching {
                MediaScannerConnection.scanFile(context, arrayOf(active.upload.finalFile().path), null, null)
            }
        }
        sendResult(GlassesFilesResult.UploadDone(active.transferId, active.upload.name))
    }

    private fun cancelUpload(transferId: String) {
        val active = upload?.takeIf { it.transferId == transferId } ?: return
        upload = null
        active.retire()
        log("files upload cancelled")
    }

    private fun failUpload(active: ActiveUpload, error: GlassesFilesError) {
        if (upload === active) upload = null
        active.retire()
        log("files upload failed error=${error.wireValue}")
        sendResult(GlassesFilesResult.TransferFailed(active.transferId, error))
    }

    private fun expireIdleUpload() {
        val active = upload ?: return
        if (SystemClock.elapsedRealtime() - active.lastActivity < GlassesFilesContract.IDLE_TIMEOUT_MS) return
        failUpload(active, GlassesFilesError.TIMEOUT)
    }

    private fun beginDownload(store: GlassesFileStore, request: GlassesFilesRequest.DownloadBegin) {
        download?.cancelAll()
        upload?.retire()
        upload = null
        val (file, error) = store.openForDownload(request.path)
        if (file == null) {
            sendResult(GlassesFilesResult.TransferFailed(request.transferId, error ?: GlassesFilesError.INTERNAL))
            return
        }
        val lane = if (request.lane) appContext?.let { GlassesFilesLaneServer.open(it, request.transferId) } else null
        val active = ActiveDownload(request.transferId, file, lane)
        download = active
        streamer.execute { active.stream() }
    }

    private fun access(): GlassesFilesAccess =
        if (runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)) {
            GlassesFilesAccess.FULL
        } else {
            GlassesFilesAccess.LIMITED
        }

    private fun sendResult(result: GlassesFilesResult): Boolean {
        val payload = runCatching { GlassesFilesContract.encode(result) }
            .onFailure { logError("files result encode failed", it) }
            .getOrNull() ?: return false
        return GlassesHub.sendToPhone(GlassesFilesContract.RESULT_PATH, payload)
    }

    internal fun fitToControlBudget(result: GlassesFilesResult.Listing): GlassesFilesResult.Listing {
        var count = result.entries.size
        while (count >= 0) {
            val candidate = result.copy(entries = result.entries.take(count))
            val bytes = runCatching { GlassesFilesContract.encode(candidate) }.getOrNull()
                ?.let { BusEnvelope(path = GlassesFilesContract.RESULT_PATH, payload = it) }
                ?.let { com.anezium.rokidbus.shared.FrameProtocol.toJsonBytes(it).size }
            if (bytes != null && bytes <= BusConstants.CXR_CONTROL_MAX_BYTES) {
                return if (count == result.entries.size) result else candidate
            }
            count -= 1
        }
        return result.copy(entries = emptyList<GlassesFileEntry>())
    }

    private class ActiveUpload(val transferId: String, val upload: GlassesUpload, val lane: GlassesFilesLaneServer?) {
        var chunksSinceAck = 0
        var lastActivity = SystemClock.elapsedRealtime()

        /** Every byte is acknowledged (trivially so for an empty file). Confined to the control thread. */
        var finalAckSent = upload.sizeBytes == 0L

        /** Ends the upload without publishing anything: staging removed, lane closed. */
        fun retire() {
            upload.abort()
            lane?.close()
        }
    }

    /** Streams one file to the phone under the acknowledged window. */
    private class ActiveDownload(val transferId: String, val file: File, private val offeredLane: GlassesFilesLaneServer?) {
        private val lock = Object()
        @Volatile private var lane: GlassesFilesLaneServer? = null
        private var acked = 0L
        private val cancelled = AtomicBoolean(false)

        fun onAck(id: String, staged: Long) {
            if (id != transferId) return
            synchronized(lock) {
                if (staged > acked) acked = staged
                lock.notifyAll()
            }
        }

        fun cancel(id: String) {
            if (id == transferId) cancelAll()
        }

        fun cancelAll() {
            cancelled.set(true)
            // Closing the lane also unblocks a pending accept or a write stuck on a full socket.
            offeredLane?.close()
            synchronized(lock) { lock.notifyAll() }
        }

        fun stream() {
            val size = file.length()
            val ready = GlassesFilesResult.DownloadReady(transferId, size, file.lastModified().coerceAtLeast(0L), offeredLane?.endpoint)
            if (!send(ready)) {
                finish(GlassesFilesError.LINK_LOST)
                return
            }
            // The phone connects as soon as it reads the offer; if it cannot, the bus carries on.
            if (offeredLane != null) {
                if (offeredLane.accept()) {
                    lane = offeredLane
                    laneIo.execute { readLaneAcks(offeredLane) }
                    log("files lane connected")
                } else {
                    offeredLane.close()
                    log("files lane not taken")
                }
            }
            val window = if (lane != null) GlassesFilesLane.WINDOW_BYTES else GlassesFilesContract.ACK_WINDOW_BYTES
            val digest = GlassesFilesContract.newDigest()
            val buffer = ByteArray(if (lane != null) GlassesFilesLane.CHUNK_BYTES else GlassesFilesContract.CHUNK_BYTES)
            var position = 0L
            val error = runCatching {
                file.inputStream().use { input ->
                    while (!cancelled.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (position + read > size) return@use GlassesFilesError.CHANGED
                        if (!awaitAck { MediaSyncWindowPolicy.maySend(position + read, it, window) }) {
                            return@use waitFailure()
                        }
                        digest.update(buffer, 0, read)
                        if (!sendChunk(position, buffer.copyOf(read))) return@use GlassesFilesError.LINK_LOST
                        position += read
                    }
                    null
                }
            }.getOrElse { GlassesFilesError.IO_ERROR }
            when {
                cancelled.get() -> finish(null)
                error != null -> finish(error)
                position != size -> finish(GlassesFilesError.CHANGED)
                // The terminator rides control JSON while chunks ride the data plane; waiting for
                // the final ack keeps it from overtaking bytes still queued on the socket.
                !awaitAck { MediaSyncWindowPolicy.mayFinish(it, size) } ->
                    finish(if (cancelled.get()) null else waitFailure())
                else -> {
                    send(GlassesFilesResult.DownloadEnd(transferId, GlassesFilesContract.hex(digest)))
                    log("files download done size=$size")
                    finish(null)
                }
            }
        }

        /** Acks from the phone over the lane; a broken lane cancels the copy like a dropped link. */
        private fun readLaneAcks(connected: GlassesFilesLaneServer) {
            try {
                while (true) {
                    val frame = connected.read()
                    if (frame is GlassesFilesLane.Frame.Ack) onAck(transferId, frame.stagedBytes)
                }
            } catch (_: Exception) {
                if (download === this) {
                    laneBroken = true
                    synchronized(lock) { lock.notifyAll() }
                }
            } finally {
                // A streamer blocked writing to a lane that went silent only wakes when it closes.
                connected.close()
            }
        }

        @Volatile private var laneBroken = false

        private fun sendChunk(offset: Long, bytes: ByteArray): Boolean {
            lane?.let { connected ->
                return !laneBroken && connected.write(GlassesFilesLane.Frame.Data(offset, bytes))
            }
            val meta = GlassesFilesContract.chunkMeta(GlassesFilesChunk(transferId, offset))
            repeat(MAX_SEND_ATTEMPTS) {
                if (cancelled.get()) return false
                if (GlassesHub.sendToPhone(GlassesFilesContract.DATA_PATH, meta, bytes)) return true
                runCatching { Thread.sleep(SEND_RETRY_MS) }
            }
            return false
        }

        private fun awaitAck(satisfied: (Long) -> Boolean): Boolean {
            val deadline = SystemClock.elapsedRealtime() + GlassesFilesContract.IDLE_TIMEOUT_MS
            synchronized(lock) {
                while (!cancelled.get()) {
                    // A full ack that arrived before the lane closed still counts.
                    if (satisfied(acked)) return true
                    if (laneBroken) return false
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) return false
                    lock.wait(minOf(remaining, ACK_POLL_MS))
                }
            }
            return false
        }

        /** Why a wait for acks ended without them. */
        private fun waitFailure(): GlassesFilesError = when {
            cancelled.get() -> GlassesFilesError.CANCELLED
            laneBroken -> GlassesFilesError.LINK_LOST
            else -> GlassesFilesError.TIMEOUT
        }

        private fun finish(error: GlassesFilesError?) {
            offeredLane?.close()
            if (error != null) {
                log("files download failed error=${error.wireValue}")
                send(GlassesFilesResult.TransferFailed(transferId, error))
            }
            if (download === this) download = null
        }

        private fun send(result: GlassesFilesResult): Boolean = sendResult(result)
    }

    private const val IDLE_CHECK_MS = 5_000L
    private const val ACK_POLL_MS = 200L
    private const val MAX_SEND_ATTEMPTS = 3
    private const val SEND_RETRY_MS = 250L
}
