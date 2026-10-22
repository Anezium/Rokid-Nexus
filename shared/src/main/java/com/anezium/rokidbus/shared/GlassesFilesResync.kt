package com.anezium.rokidbus.shared

/** One opened lane's irreversible switch to the bus. Call under the transfer's existing lock. */
class GlassesFilesResync {
    private var laneProven = false
    var laneOpened = false
        private set
    var switched = false
        private set
    var offset: Long? = null
        private set
    var confirmed = false
        private set
    private var startedAt = 0L

    fun opened() {
        check(!switched)
        proven()
        laneOpened = true
    }

    /** A sealed handshake can finish before the bus activates the lane. */
    fun proven() {
        laneProven = true
    }

    fun begin(now: Long): Boolean {
        if (!laneProven || switched) return false
        switched = true
        startedAt = now
        return true
    }

    /** A duplicate must agree with the first snapshot; it cannot move the receiver backwards. */
    fun offer(staged: Long, maximum: Long): Boolean {
        if (!switched || staged !in 0..maximum) return false
        if (offset != null) return offset == staged
        offset = staged
        return true
    }

    fun confirm(staged: Long): Boolean {
        if (offset != staged) return false
        confirmed = true
        return true
    }

    fun accepts(viaLane: Boolean): Boolean =
        if (viaLane) laneOpened && !switched else if (switched) offset != null else !laneOpened

    /** Stamp before handing bytes to the bus; an in-flight send keeps its old epoch. */
    val busEpoch: Int get() = if (switched) 1 else 0

    fun acceptsChunk(viaLane: Boolean, epoch: Int): Boolean =
        accepts(viaLane) && (viaLane || epoch == busEpoch)

    fun expired(now: Long): Boolean = switched && !confirmed &&
        now - startedAt >= GlassesFilesContract.IDLE_TIMEOUT_MS
}

/**
 * The sender reopens at zero on rewind and hashes the prefix without sending it again. The peer
 * sees nothing of that replay, so the sender repeats its echo while it lasts ([heartbeatDue]).
 */
class GlassesFilesSendDigest {
    private var digest = GlassesFilesContract.newDigest()
    var position = 0L
        private set
    private var resumeAt = 0L
    private var echoedAt = 0L
    val replaying: Boolean get() = position < resumeAt

    fun rewind(offset: Long, now: Long) {
        require(offset >= 0)
        digest = GlassesFilesContract.newDigest()
        position = 0L
        resumeAt = offset
        echoedAt = now
    }

    /** True once per [GlassesFilesContract.REPLAY_HEARTBEAT_MS] of replay; the caller then repeats its echo. */
    fun heartbeatDue(now: Long): Boolean {
        if (!replaying || now - echoedAt < GlassesFilesContract.REPLAY_HEARTBEAT_MS) return false
        echoedAt = now
        return true
    }

    fun readLimit(chunkBytes: Int): Int =
        if (replaying) minOf(chunkBytes.toLong(), resumeAt - position).toInt() else chunkBytes

    fun record(bytes: ByteArray, count: Int) {
        digest.update(bytes, 0, count)
        position += count
    }

    fun finish(): String = GlassesFilesContract.hex(digest)
}
