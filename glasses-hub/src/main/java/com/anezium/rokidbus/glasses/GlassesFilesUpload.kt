package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesError
import com.anezium.rokidbus.shared.GlassesFilesResync

/** All writes and resync snapshots run on the service's serial control executor. */
internal class GlassesFilesUpload(
    val transferId: String,
    val upload: GlassesUpload,
    val lane: GlassesFilesLaneServer?,
    now: Long,
) {
    val resync = GlassesFilesResync()
    var chunksSinceAck = 0
    var lastActivity = now
    var finalAckSent = upload.sizeBytes == 0L

    fun write(offset: Long, bytes: ByteArray, viaLane: Boolean): GlassesFilesError? =
        if (resync.accepts(viaLane)) upload.write(offset, bytes) else null

    fun beginResync(now: Long): Long? {
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
