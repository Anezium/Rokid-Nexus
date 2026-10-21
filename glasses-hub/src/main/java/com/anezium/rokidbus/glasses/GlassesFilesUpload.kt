package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesContract
import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesLaneUpgrade
import com.anezium.rokidbus.shared.MediaSyncWindowPolicy

/**
 * One upload as the glasses receive it: its staging file, its lane, and the single lane-to-bus
 * resync. All writes and resync snapshots run on the service's serial control executor, so the
 * state needs no lock; each event method returns what the service has to send, and nothing else.
 */
internal class GlassesFilesUpload(
    val transferId: String,
    val upload: GlassesUpload,
    var lane: GlassesFilesLaneServer?,
    now: Long,
) {
    sealed interface Signal {
        /** The frozen offset the phone must echo before its lane carries bytes from there. */
        data class LaneResume(val staged: Long) : Signal

        /** The resync snapshot; [first] is false for a repeat of the frozen offset. */
        data class BusResume(val staged: Long, val first: Boolean) : Signal
    }

    sealed interface Chunk {
        data object Ignored : Chunk
        data class Failed(val error: GlassesFilesError) : Chunk

        /** [ack] is the offset to acknowledge, over [laneAck] when set and on the bus otherwise. */
        data class Staged(val ack: Long?, val laneAck: GlassesFilesLaneServer?) : Chunk
    }

    sealed interface Tick {
        data object Idle : Tick
        data object Expired : Tick
        data class Resume(val signal: Signal.BusResume) : Tick
    }

    val resync = GlassesFilesResync()
    private val upgrade = GlassesFilesLaneUpgrade()
    var laneUpgradeConfirmed = true
        private set
    var chunksSinceAck = 0
    var lastActivity = now
    var finalAckSent = upload.sizeBytes == 0L

    fun onChunk(offset: Long, bytes: ByteArray, viaLane: Boolean, busEpoch: Int, now: Long): Chunk {
        if (!resync.acceptsChunk(viaLane, busEpoch)) return Chunk.Ignored
        lastActivity = now
        upload.write(offset, bytes)?.let { return Chunk.Failed(it) }
        chunksSinceAck += 1
        if (!MediaSyncWindowPolicy.shouldAck(chunksSinceAck, upload.stagedBytes, upload.sizeBytes, GlassesFilesContract.ACK_EVERY_CHUNKS)) {
            return Chunk.Staged(null, null)
        }
        chunksSinceAck = 0
        // Acks go back the way the bytes came: over the lane they cost no bus round trip.
        return Chunk.Staged(upload.stagedBytes, lane?.takeIf { viaLane && !resync.switched && it.isConnected() })
    }

    fun ackSent(staged: Long) {
        if (staged == upload.sizeBytes) finalAckSent = true
    }

    /** The phone's proven hotspot lane asks to take over from the completed staging offset. */
    fun onLaneSwitch(now: Long): Signal? {
        if (lane?.isConnected() != true || resync.switched) return resume(now)
        lastActivity = now
        return upgradeToLane()?.let { Signal.LaneResume(it) }
    }

    fun onLaneResume(staged: Long): Boolean = confirmLane(staged)

    fun onLaneDeclined() {
        lane?.decline()
        if (lane?.isConnected() != true) lane = null
    }

    /**
     * The phone closes its end once the transfer is over; only a break before the last byte was
     * acknowledged needs resync. After that, the bus terminator or idle timeout decides.
     */
    fun onLaneEof(now: Long): Signal.BusResume? = if (finalAckSent) null else resume(now)

    fun onResyncRequest(now: Long): Signal.BusResume? = resume(now)

    fun onBusResume(staged: Long): Boolean = !resync.switched || resync.confirm(staged)

    /** The camera takes the Wi-Fi: a lane that carried or could carry bytes moves to the bus. */
    fun onCameraTakeover(now: Long): Signal.BusResume? {
        val signal = if (lane != null) resume(now) else null
        lane?.close()
        return signal
    }

    fun onTick(now: Long): Tick {
        if (!resync.expired(now) && now - lastActivity < GlassesFilesContract.IDLE_TIMEOUT_MS) return Tick.Idle
        if (resync.laneOpened && !resync.switched && !finalAckSent) {
            return resume(now)?.let { Tick.Resume(it) } ?: Tick.Idle
        }
        return Tick.Expired
    }

    /** Verifies the digest and publishes the file; the lane has nothing left to carry. */
    fun finish(sha256: String): GlassesFilesError? {
        lane?.close()
        return upload.finish(sha256)
    }

    fun upgradeToLane(): Long? {
        if (resync.switched || (resync.laneOpened && upgrade.offset == null)) return null
        if (!upgrade.snapshot(upload.stagedBytes, upload.sizeBytes)) return upgrade.offset
        if (!resync.laneOpened) { resync.opened(); laneUpgradeConfirmed = false }
        chunksSinceAck = 0
        return upgrade.offset
    }

    fun confirmLane(staged: Long): Boolean {
        if (resync.switched) return true
        if (upgrade.offset != staged) return false
        laneUpgradeConfirmed = true
        return true
    }

    fun write(offset: Long, bytes: ByteArray, viaLane: Boolean, busEpoch: Int = 0): GlassesFilesError? =
        if (resync.acceptsChunk(viaLane, busEpoch)) upload.write(offset, bytes) else null

    fun beginResync(now: Long): Long? {
        if (lane?.isProven == true) resync.proven()
        if (resync.begin(now)) {
            lane?.close()
            resync.offer(upload.stagedBytes, upload.sizeBytes)
            chunksSinceAck = 0
            lastActivity = now
        }
        return resync.offset
    }

    fun retire() {
        upload.abort()
        lane?.close()
    }

    private fun resume(now: Long): Signal.BusResume? {
        val first = !resync.switched
        val staged = beginResync(now) ?: return null
        return Signal.BusResume(staged, first)
    }
}
