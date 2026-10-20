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
                it.upload.abort()
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
                GlassesFilesResult.Operation(request.requestId, GlassesFilesOperation.DELETE, store.delete(request.path)),
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
        // The phone drives one transfer at a time; a new begin means it has given up on the old one.
        upload?.upload?.abort()
        upload = null
        store.beginUpload(request.path, request.name, request.sizeBytes, request.transferId)
            .onSuccess { staged ->
                upload = ActiveUpload(request.transferId, staged)
                log("files upload begin size=${request.sizeBytes} name=${staged.name}")
                sendResult(GlassesFilesResult.UploadReady(request.transferId, staged.name))
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
            sendResult(GlassesFilesResult.UploadProgress(active.transferId, active.upload.stagedBytes))
        }
    }

    private fun finishUpload(request: GlassesFilesRequest.UploadEnd) {
        val active = upload?.takeIf { it.transferId == request.transferId } ?: run {
            sendResult(GlassesFilesResult.TransferFailed(request.transferId, GlassesFilesError.CANCELLED))
            return
        }
        upload = null
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
        active.upload.abort()
        upload = null
        log("files upload cancelled")
    }

    private fun failUpload(active: ActiveUpload, error: GlassesFilesError) {
        active.upload.abort()
        if (upload === active) upload = null
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
        val (file, error) = store.openForDownload(request.path)
        if (file == null) {
            sendResult(GlassesFilesResult.TransferFailed(request.transferId, error ?: GlassesFilesError.INTERNAL))
            return
        }
        val active = ActiveDownload(request.transferId, file)
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

    private class ActiveUpload(val transferId: String, val upload: GlassesUpload) {
        var chunksSinceAck = 0
        var lastActivity = SystemClock.elapsedRealtime()
    }

    /** Streams one file to the phone under the acknowledged window. */
    private class ActiveDownload(val transferId: String, val file: File) {
        private val lock = Object()
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
            synchronized(lock) { lock.notifyAll() }
        }

        fun stream() {
            val size = file.length()
            if (!send(GlassesFilesResult.DownloadReady(transferId, size, file.lastModified().coerceAtLeast(0L)))) {
                finish(GlassesFilesError.LINK_LOST)
                return
            }
            val digest = GlassesFilesContract.newDigest()
            val buffer = ByteArray(GlassesFilesContract.CHUNK_BYTES)
            var position = 0L
            val error = runCatching {
                file.inputStream().use { input ->
                    while (!cancelled.get()) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (position + read > size) return@use GlassesFilesError.CHANGED
                        if (!awaitAck { MediaSyncWindowPolicy.maySend(position + read, it, GlassesFilesContract.ACK_WINDOW_BYTES) }) {
                            return@use if (cancelled.get()) GlassesFilesError.CANCELLED else GlassesFilesError.TIMEOUT
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
                    finish(if (cancelled.get()) null else GlassesFilesError.TIMEOUT)
                else -> {
                    send(GlassesFilesResult.DownloadEnd(transferId, GlassesFilesContract.hex(digest)))
                    log("files download done size=$size")
                    finish(null)
                }
            }
        }

        private fun sendChunk(offset: Long, bytes: ByteArray): Boolean {
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
                    if (satisfied(acked)) return true
                    val remaining = deadline - SystemClock.elapsedRealtime()
                    if (remaining <= 0) return false
                    lock.wait(minOf(remaining, ACK_POLL_MS))
                }
            }
            return false
        }

        private fun finish(error: GlassesFilesError?) {
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
