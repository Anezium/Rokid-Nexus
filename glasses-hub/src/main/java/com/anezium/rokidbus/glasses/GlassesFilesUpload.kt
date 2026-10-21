package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesResync
import com.anezium.rokidbus.shared.GlassesFilesLaneUpgrade

/** All writes and resync snapshots run on the service's serial control executor. */
internal class GlassesFilesUpload(
    val transferId: String,
    val upload: GlassesUpload,
    var lane: GlassesFilesLaneServer?,
    now: Long,
) {
    val resync = GlassesFilesResync()
    private val upgrade = GlassesFilesLaneUpgrade()
    var laneUpgradeConfirmed = true
        private set

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
    var chunksSinceAck = 0
    var lastActivity = now
    var finalAckSent = upload.sizeBytes == 0L

    fun write(offset: Long, bytes: ByteArray, viaLane: Boolean): GlassesFilesError? =
        if (resync.accepts(viaLane) && (!resync.switched || resync.confirmed)) upload.write(offset, bytes) else null

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
}
