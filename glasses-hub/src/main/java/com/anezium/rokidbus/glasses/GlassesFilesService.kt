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
    private var upload: GlassesFilesUpload? = null
    private var hotspotTransferId: String? = null

    @Volatile private var download: GlassesFilesDownload? = null

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
            is GlassesFilesRequest.HotspotRelease -> GlassesHub.releaseFilesHotspot(request.batchId)
            is GlassesFilesRequest.LaneResume -> {
                download?.upgradeToLane(request.transferId, request.stagedBytes)
                control.execute {
                    val active = upload?.takeIf { it.transferId == request.transferId } ?: return@execute
                    if (!active.onLaneResume(request.stagedBytes)) failUpload(active, GlassesFilesError.INVALID_REQUEST)
                }
            }
            is GlassesFilesRequest.DownloadProgress -> download?.onAck(request.transferId, request.stagedBytes)
            is GlassesFilesRequest.Cancel -> {
                download?.cancel(request.transferId)
                control.execute { cancelUpload(request.transferId) }
            }
            is GlassesFilesRequest.LaneDeclined -> {
                download?.declineLane(request.transferId)
                control.execute { upload?.takeIf { it.transferId == request.transferId }?.onLaneDeclined() }
            }
            is GlassesFilesRequest.LaneResync -> {
                download?.requestResync(request.transferId)
                control.execute {
                    val active = upload?.takeIf { it.transferId == request.transferId } ?: return@execute
                    active.onResyncRequest(SystemClock.elapsedRealtime())?.let { sendSignal(active, it) }
                }
            }
            is GlassesFilesRequest.BusResume -> {
                download?.onResume(request.transferId, request.stagedBytes)
                control.execute {
                    val active = upload?.takeIf { it.transferId == request.transferId } ?: return@execute
                    if (!active.onBusResume(request.stagedBytes, SystemClock.elapsedRealtime())) failUpload(active, GlassesFilesError.INVALID_REQUEST)
                }
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
            is GlassesFilesRequest.HotspotOffer -> offerHotspot(request)
            is GlassesFilesRequest.LaneSwitch -> {
                val active = upload?.takeIf { it.transferId == request.transferId } ?: return
                active.onLaneSwitch(SystemClock.elapsedRealtime())?.let { sendSignal(active, it) }
            }
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
            is GlassesFilesRequest.HotspotRelease,
            is GlassesFilesRequest.LaneResume,
            is GlassesFilesRequest.DownloadProgress,
            is GlassesFilesRequest.Cancel,
            is GlassesFilesRequest.LaneDeclined,
            is GlassesFilesRequest.LaneResync,
            is GlassesFilesRequest.BusResume,
            -> Unit
        }
    }

    fun onCameraStarted(ended: (String) -> Unit = { sendResult(GlassesFilesResult.HotspotFailed(it)) }) {
        control.execute {
            upload?.takeIf { it.transferId == hotspotTransferId }?.let { active ->
                active.onCameraTakeover(SystemClock.elapsedRealtime())?.let { sendSignal(active, it) }
            }
            download?.takeIf { it.transferId == hotspotTransferId }?.let { active ->
                active.requestResync(active.transferId)
            }
            hotspotTransferId?.let(ended)
        }
    }

    private fun offerHotspot(request: GlassesFilesRequest.HotspotOffer) {
        val sending = download?.takeIf { it.transferId == request.transferId }
        val receiving = upload?.takeIf { it.transferId == request.transferId }
        if (receiving == null && sending == null) return
        if (receiving?.resync?.laneOpened == true || receiving?.lane != null || sending?.canOfferHotspot() == false) return
        hotspotTransferId = request.transferId
        GlassesHub.joinFilesHotspot(request) { address, gateway ->
            control.execute {
                if ((receiving != null && upload !== receiving) || (sending != null && download !== sending)) return@execute
                val lane = if (address != null && gateway != null) appContext?.let {
                    GlassesFilesLaneServer.open(it, request.transferId, address)
                } else null
                if (lane == null || gateway == null) {
                    sendResult(GlassesFilesResult.HotspotFailed(request.transferId))
                    GlassesHub.releaseFilesHotspot(request.batchId)
                    return@execute
                }
                if (receiving != null) {
                    receiving.lane = lane
                    laneIo.execute { serveUploadLane(receiving, lane, upgrade = true) }
                } else if (sending?.offerHotspotLane(lane) != true) {
                    lane.close()
                    return@execute
                }
                sendResult(GlassesFilesResult.HotspotReady(request.transferId, lane.endpoint, gateway))
            }
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
                val active = GlassesFilesUpload(request.transferId, staged, lane, SystemClock.elapsedRealtime())
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

    private fun onUploadChunk(chunk: GlassesFilesChunk, bytes: ByteArray, viaLane: Boolean = false, owner: GlassesFilesUpload? = null) {
        val active = upload?.takeIf { it.transferId == chunk.transferId } ?: return
        if (owner != null && owner !== active) return
        when (val outcome = active.onChunk(chunk.offset, bytes, viaLane, chunk.busEpoch, SystemClock.elapsedRealtime())) {
            GlassesFilesUpload.Chunk.Ignored -> Unit
            is GlassesFilesUpload.Chunk.Failed -> failUpload(active, outcome.error)
            is GlassesFilesUpload.Chunk.Staged -> {
                val staged = outcome.ack ?: return
                val acked = (outcome.laneAck?.write(GlassesFilesLane.Frame.Ack(staged)) == true) ||
                    sendResult(GlassesFilesResult.UploadProgress(active.transferId, staged))
                if (acked) active.ackSent(staged)
            }
        }
    }

    private fun sendSignal(active: GlassesFilesUpload, signal: GlassesFilesUpload.Signal) {
        when (signal) {
            is GlassesFilesUpload.Signal.LaneResume -> sendResult(GlassesFilesResult.LaneResume(active.transferId, signal.staged))
            is GlassesFilesUpload.Signal.BusResume -> {
                if (signal.first) log("files upload bus resume staged=${signal.staged}")
                sendResult(GlassesFilesResult.BusResume(active.transferId, signal.staged))
            }
        }
    }

    /**
     * Feeds the upload from its lane, frame by frame, into the same serial path the bus chunks
     * take. A phone that never connects simply leaves the upload on the bus.
     */
    private fun serveUploadLane(active: GlassesFilesUpload, lane: GlassesFilesLaneServer, upgrade: Boolean = false) {
        if (!lane.accept()) {
            log("files lane not taken")
            lane.close()
            return
        }
        log("files lane connected")
        control.execute {
            if (!upgrade && upload === active && !active.resync.laneOpened && !active.resync.switched) active.resync.opened()
        }
        try {
            while (true) {
                val frame = lane.read()
                if (frame is GlassesFilesLane.Frame.Data) {
                    control.execute { onUploadChunk(GlassesFilesChunk(active.transferId, frame.offset), frame.bytes, viaLane = true, owner = active) }
                }
            }
        } catch (_: Exception) {
            control.execute {
                if (upload === active) active.onLaneEof(SystemClock.elapsedRealtime())?.let { sendSignal(active, it) }
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
        val error = active.finish(request.sha256)
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

    private fun failUpload(active: GlassesFilesUpload, error: GlassesFilesError) {
        if (upload === active) upload = null
        active.retire()
        log("files upload failed error=${error.wireValue}")
        sendResult(GlassesFilesResult.TransferFailed(active.transferId, error))
    }

    private fun expireIdleUpload() {
        if (upload != null || download != null) GlassesHub.touchFilesHotspot()
        val active = upload ?: return
        when (val tick = active.onTick(SystemClock.elapsedRealtime())) {
            GlassesFilesUpload.Tick.Idle -> Unit
            is GlassesFilesUpload.Tick.Resume -> sendSignal(active, tick.signal)
            GlassesFilesUpload.Tick.Expired -> failUpload(active, GlassesFilesError.TIMEOUT)
        }
    }

    private fun beginDownload(store: GlassesFileStore, request: GlassesFilesRequest.DownloadBegin) {
        download?.cancelAll()
        upload?.retire()
        upload = null
        val (file, error) = store.openForDownload(request.path)
        if (file == null) {
            log("files download failed error=${(error ?: GlassesFilesError.INTERNAL).wireValue}")
            sendResult(GlassesFilesResult.TransferFailed(request.transferId, error ?: GlassesFilesError.INTERNAL))
            return
        }
        val lane = if (request.lane) appContext?.let { GlassesFilesLaneServer.open(it, request.transferId) } else null
        val active = GlassesFilesDownload(
            request.transferId, file, lane, laneIo,
            send = ::sendResult,
            sendBus = { offset, bytes, epoch ->
                val meta = GlassesFilesContract.chunkMeta(GlassesFilesChunk(request.transferId, offset, epoch))
                GlassesHub.sendToPhone(GlassesFilesContract.DATA_PATH, meta, bytes)
            },
            finished = { if (download === it) download = null },
            now = SystemClock::elapsedRealtime,
            summary = ::log,
        )
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

    private const val IDLE_CHECK_MS = 5_000L
}
