package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.GlassesFilesSendDigest
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy
import java.io.File
import java.io.InputStream
import java.util.concurrent.Executor

/** The download worker owns the source; bus callbacks only update its acknowledged window. */
internal class GlassesFilesDownload(
    val transferId: String,
    private val file: File,
    private val offeredLane: GlassesFilesLaneServer?,
    private val laneIo: Executor,
    private val send: (GlassesFilesResult) -> Boolean,
    private val sendBus: (Long, ByteArray) -> Boolean,
    private val finished: (GlassesFilesDownload) -> Unit,
    private val now: () -> Long,
    private val summary: (String) -> Unit = {},
) {
    private val lock = Object()
    private val resync = GlassesFilesResync()
    private var lane: GlassesFilesLaneServer? = null
    private var acked = 0L
    private var attempted = 0L
    private var resumePending: Long? = null
    private var cancelled = false
    private val size = file.length()

    fun onAck(id: String, staged: Long, viaLane: Boolean = false) {
        if (id != transferId) return
        synchronized(lock) {
            if (!resync.accepts(viaLane) || cancelled) return
            if (staged > acked) acked = staged
            lock.notifyAll()
        }
    }

    fun cancel(id: String) {
        if (id == transferId) cancelAll()
    }

    fun declineLane(id: String) {
        if (id == transferId) offeredLane?.decline()
    }

    fun cancelAll() {
        synchronized(lock) { cancelled = true; lock.notifyAll() }
        offeredLane?.close()
    }

    fun requestResync(id: String) {
        if (id != transferId) return
        val started = synchronized(lock) {
            if (cancelled) return
            // The phone can answer the hello before accept() has returned to the worker.
            if (!resync.laneOpened && offeredLane?.isConnected() == true) resync.opened()
            resync.begin(now()).also { lock.notifyAll() }
        }
        if (!started) return
        offeredLane?.close()
        send(GlassesFilesResult.LaneResync(transferId))
    }

    fun onResume(id: String, staged: Long) {
        if (id != transferId) return
        requestResync(id)
        val accepted = synchronized(lock) {
            if (cancelled || !resync.switched) return
            if (!resync.offer(staged, minOf(attempted, size))) return@synchronized false
            if (!resync.confirmed) {
                summary("files download bus resume staged=$staged")
                acked = staged
                resumePending = staged
                resync.confirm(staged)
                lock.notifyAll()
            }
            true
        }
        if (!accepted) {
            cancelAll()
            summary("files download failed error=${GlassesFilesError.INVALID_REQUEST.wireValue}")
            send(GlassesFilesResult.TransferFailed(transferId, GlassesFilesError.INVALID_REQUEST))
        } else {
            send(GlassesFilesResult.BusResume(transferId, staged))
        }
    }

    fun stream() {
        var input: InputStream? = null
        try {
            if (!send(GlassesFilesResult.DownloadReady(transferId, size, file.lastModified().coerceAtLeast(0), offeredLane?.endpoint))) {
                finish(GlassesFilesError.LINK_LOST)
                return
            }
            if (offeredLane != null) {
                if (offeredLane.accept()) {
                    summary("files lane connected")
                    synchronized(lock) {
                        if (!resync.laneOpened) resync.opened()
                        if (!resync.switched && !cancelled) lane = offeredLane
                    }
                    laneIo.execute { readLaneAcks(offeredLane) }
                } else {
                    summary("files lane not taken")
                    offeredLane.close()
                }
            }
            val digest = GlassesFilesSendDigest()
            input = file.inputStream()
            while (true) {
                val resume = synchronized(lock) { resumePending.also { resumePending = null } }
                if (resume != null) {
                    input?.close()
                    input = file.inputStream()
                    digest.rewind(resume)
                }
                if (!awaitAck { !resync.switched || resync.confirmed }) {
                    finish(waitFailure())
                    return
                }
                if (hasResume()) continue
                val viaLane = synchronized(lock) { resync.laneOpened && !resync.switched }
                val chunkSize = if (viaLane) GlassesFilesLane.CHUNK_BYTES else GlassesFilesContract.CHUNK_BYTES
                val buffer = ByteArray(digest.readLimit(chunkSize))
                val count = checkNotNull(input).read(buffer)
                if (hasResume()) continue
                if (count <= 0) {
                    if (digest.position != size) {
                        finish(GlassesFilesError.CHANGED)
                        return
                    }
                    // The control terminator must follow a full receiver acknowledgement.
                    if (!awaitAck { MediaSyncWindowPolicy.mayFinish(acked, size) }) {
                        finish(waitFailure())
                        return
                    }
                    if (hasResume()) continue
                    send(GlassesFilesResult.DownloadEnd(transferId, digest.finish()))
                    finish(null)
                    return
                }
                if (digest.position + count > size) {
                    finish(GlassesFilesError.CHANGED)
                    return
                }
                if (digest.replaying) {
                    digest.record(buffer, count)
                    continue
                }
                val window = if (viaLane) GlassesFilesLane.WINDOW_BYTES else GlassesFilesContract.ACK_WINDOW_BYTES
                if (!awaitAck { MediaSyncWindowPolicy.maySend(digest.position + count, acked, window) }) {
                    finish(waitFailure())
                    return
                }
                if (hasResume()) continue
                val sent = sendChunk(digest.position, buffer.copyOf(count), viaLane)
                if (!sent) {
                    if (viaLane) {
                        requestResync(transferId)
                        continue
                    }
                    finish(GlassesFilesError.LINK_LOST)
                    return
                }
                digest.record(buffer, count)
            }
        } catch (_: Exception) {
            finish(GlassesFilesError.IO_ERROR)
        } finally {
            runCatching { input?.close() }
            offeredLane?.close()
        }
    }

    private fun readLaneAcks(connected: GlassesFilesLaneServer) {
        try {
            while (true) {
                val frame = connected.read()
                if (frame is GlassesFilesLane.Frame.Ack) onAck(transferId, frame.stagedBytes, viaLane = true)
            }
        } catch (_: Exception) {
            val complete = synchronized(lock) { acked >= size }
            if (!complete) requestResync(transferId)
        } finally {
            connected.close()
        }
    }

    private fun sendChunk(offset: Long, bytes: ByteArray, viaLane: Boolean): Boolean {
        val connected = synchronized(lock) {
            if (cancelled || (viaLane && resync.switched)) return false
            attempted = maxOf(attempted, offset + bytes.size)
            lane
        }
        if (viaLane) return connected?.write(GlassesFilesLane.Frame.Data(offset, bytes)) == true
        repeat(3) {
            if (synchronized(lock) { cancelled }) return false
            if (sendBus(offset, bytes)) return true
            runCatching { Thread.sleep(250L) }
        }
        return false
    }

    private fun hasResume(): Boolean = synchronized(lock) { resumePending != null }

    private fun awaitAck(satisfied: () -> Boolean): Boolean {
        var deadline = now() + GlassesFilesContract.IDLE_TIMEOUT_MS
        while (true) {
            val idleLane = synchronized(lock) {
                if (cancelled) return false
                if (resync.expired(now())) return false
                if (resumePending != null || satisfied()) return true
                val remaining = deadline - now()
                if (remaining <= 0) {
                    if (!resync.laneOpened || resync.switched) return false
                    true
                } else {
                    lock.wait(minOf(remaining, 200L))
                    false
                }
            }
            if (idleLane) {
                requestResync(transferId)
                deadline = now() + GlassesFilesContract.IDLE_TIMEOUT_MS
            }
        }
    }

    private fun waitFailure(): GlassesFilesError? = synchronized(lock) {
        if (cancelled) null else GlassesFilesError.TIMEOUT
    }

    private fun finish(error: GlassesFilesError?) {
        offeredLane?.close()
        if (error != null) {
            summary("files download failed error=${error.wireValue}")
            send(GlassesFilesResult.TransferFailed(transferId, error))
        } else if (!synchronized(lock) { cancelled }) {
            summary("files download done size=$size")
        }
        finished(this)
    }
}
