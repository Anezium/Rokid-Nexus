package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesLane
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesResult
import com.anezium.rokidbus.shared.GlassesFilesSendDigest
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy
import com.anezium.rokidbus.shared.GlassesFilesLaneUpgrade
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
    private val sendBus: (Long, ByteArray, Int) -> Boolean,
    private val finished: (GlassesFilesDownload) -> Unit,
    private val now: () -> Long,
    private val summary: (String) -> Unit = {},
    private val openSource: (File) -> InputStream = { it.inputStream() },
) {
    private val lock = Object()
    private val resync = GlassesFilesResync()
    private var lane: GlassesFilesLaneServer? = null
    private var hotspotLane: GlassesFilesLaneServer? = null
    private val upgrade = GlassesFilesLaneUpgrade()

    fun canOfferHotspot(): Boolean = synchronized(lock) { !cancelled && !resync.laneOpened && hotspotLane == null }

    fun offerHotspotLane(offered: GlassesFilesLaneServer): Boolean {
        synchronized(lock) {
            if (!canOfferHotspot()) return false
            hotspotLane = offered
        }
        laneIo.execute {
            if (offered.accept()) {
                laneIo.execute { readLaneAcks(offered) }
            } else {
                offered.close()
            }
        }
        return true
    }

    fun upgradeToLane(id: String, staged: Long) {
        if (id != transferId) return
        if (synchronized(lock) { hotspotLane?.isProven == true && hotspotLane?.isConnected() != true || resync.switched }) {
            requestResync(id)
            return
        }
        val accepted = synchronized(lock) {
            val connected = hotspotLane?.takeIf { it.isConnected() } ?: return
            if (cancelled || resync.switched) return
            val first = upgrade.offset == null
            if (!upgrade.snapshot(staged, minOf(attempted, size))) return@synchronized false
            if (first) {
                resync.opened()
                lane = connected
                acked = staged
                resumePending = Resume(staged, viaLane = true)
                lock.notifyAll()
            }
            true
        }
        if (!accepted) {
            cancelAll()
            send(GlassesFilesResult.TransferFailed(transferId, GlassesFilesError.INVALID_REQUEST))
            return
        }
        send(GlassesFilesResult.LaneResume(transferId, staged))
    }
    /** A rewind the worker still has to take, and which echo it repeats while rehashing the prefix. */
    private class Resume(val offset: Long, val viaLane: Boolean)

    private var acked = 0L
    private var attempted = 0L
    private var resumePending: Resume? = null
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
        hotspotLane?.close()
    }

    /**
     * The camera takes the Wi-Fi. A proven hotspot lane resyncs like any lost lane; one still
     * waiting for the phone is closed so it stops listening on an interface that is going away,
     * and the transfer, which never left the bus, continues there.
     */
    fun onCameraTakeover() {
        requestResync(transferId)
        hotspotLane?.close()
    }

    fun requestResync(id: String) {
        if (id != transferId) return
        val started = synchronized(lock) {
            if (cancelled) return
            // The phone can answer the hello before accept() has returned to the worker.
            if (offeredLane?.isProven == true || hotspotLane?.isProven == true) resync.proven()
            resync.begin(now()).also { lock.notifyAll() }
        }
        if (!started) return
        offeredLane?.close()
        hotspotLane?.close()
        lane?.close()
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
                resumePending = Resume(staged, viaLane = false)
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
                        if (!resync.laneOpened && !resync.switched) resync.opened()
                        if (!resync.switched && !cancelled) lane = offeredLane
                    }
                    laneIo.execute { readLaneAcks(offeredLane) }
                } else {
                    summary("files lane not taken")
                    offeredLane.close()
                }
            }
            val digest = GlassesFilesSendDigest()
            var echo: GlassesFilesResult? = null
            input = openSource(file)
            while (true) {
                val resume = synchronized(lock) { resumePending.also { resumePending = null } }
                if (resume != null) {
                    input?.close()
                    input = openSource(file)
                    digest.rewind(resume.offset, now())
                    echo = if (resume.viaLane) GlassesFilesResult.LaneResume(transferId, resume.offset)
                        else GlassesFilesResult.BusResume(transferId, resume.offset)
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
                    // A snapshot below the full size means the receiver lost acknowledged bytes
                    // after all; one at the full size, or a resync still waiting for its snapshot,
                    // has nothing left to resend and the size-and-digest terminator decides.
                    if (synchronized(lock) { !MediaSyncWindowPolicy.mayFinish(acked, size) || resumePending?.let { it.offset != size } == true }) continue
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
                    // The phone sees nothing of the rehash; the repeated echo keeps its idle budget alive.
                    if (digest.heartbeatDue(now()) && !send(checkNotNull(echo))) {
                        finish(if (synchronized(lock) { cancelled }) null else GlassesFilesError.LINK_LOST)
                        return
                    }
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
                    if (needsResume()) continue
                    if (viaLane) {
                        requestResync(transferId)
                        continue
                    }
                    finish(if (synchronized(lock) { cancelled }) null else GlassesFilesError.LINK_LOST)
                    return
                }
                digest.record(buffer, count)
            }
        } catch (_: Exception) {
            finish(GlassesFilesError.IO_ERROR)
        } finally {
            runCatching { input?.close() }
            offeredLane?.close()
            hotspotLane?.close()
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
        val (connected, epoch) = synchronized(lock) {
            if (cancelled || resumePending != null || !resync.accepts(viaLane)) return false
            attempted = maxOf(attempted, offset + bytes.size)
            lane to resync.busEpoch
        }
        if (viaLane) return connected?.write(GlassesFilesLane.Frame.Data(offset, bytes)) == true
        repeat(3) {
            if (synchronized(lock) { cancelled }) return false
            if (sendBus(offset, bytes, epoch)) return true
            runCatching { Thread.sleep(250L) }
        }
        return false
    }

    private fun hasResume(): Boolean = synchronized(lock) { resumePending != null }
    private fun needsResume(): Boolean = synchronized(lock) { resync.switched && !resync.confirmed || resumePending != null }

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
        hotspotLane?.close()
        if (error != null) {
            summary("files download failed error=${error.wireValue}")
            send(GlassesFilesResult.TransferFailed(transferId, error))
        } else if (!synchronized(lock) { cancelled }) {
            summary("files download done size=$size")
        }
        finished(this)
    }
}
