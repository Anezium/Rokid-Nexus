package com.anezium.rokidbus.client

internal data class QueuedOutgoing(
    val path: String,
    val id: String,
    val payload: ByteArray,
    val enqueuedAtMs: Long,
)

internal enum class QueueRejection {
    BINARY_NOT_RETAINED,
    MESSAGE_TOO_LARGE,
    EXPIRED,
}

internal data class QueueMutation(
    val accepted: Boolean,
    val droppedCount: Int = 0,
    val expiredCount: Int = 0,
    val rejection: QueueRejection? = null,
) {
    fun errorMessage(operation: String): String? {
        if (accepted && droppedCount == 0 && expiredCount == 0) return null
        return buildString {
            append(operation)
            append(" accepted=")
            append(accepted)
            append(" dropped=")
            append(droppedCount)
            append(" expired=")
            append(expiredCount)
            rejection?.let {
                append(" reason=")
                append(it.name)
            }
        }
    }
}

internal data class QueuePoll(
    val item: QueuedOutgoing?,
    val expiredCount: Int,
)

internal class BoundedOutgoingQueue(
    private val maxMessages: Int = DEFAULT_MAX_MESSAGES,
    private val maxBytes: Int = DEFAULT_MAX_BYTES,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val entries = ArrayDeque<QueuedOutgoing>()
    private var totalBytes = 0

    init {
        require(maxMessages > 0) { "maxMessages must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(ttlMs > 0) { "ttlMs must be positive" }
    }

    @Synchronized
    fun offerJson(path: String, id: String, payload: ByteArray): QueueMutation {
        val nowMs = clockMs()
        val expiredCount = removeExpired(nowMs)
        if (payload.size > maxBytes) {
            return QueueMutation(
                accepted = false,
                expiredCount = expiredCount,
                rejection = QueueRejection.MESSAGE_TOO_LARGE,
            )
        }

        entries.addLast(
            QueuedOutgoing(
                path = path,
                id = id,
                payload = payload,
                enqueuedAtMs = nowMs,
            ),
        )
        totalBytes += payload.size

        var droppedCount = 0
        while (entries.size > maxMessages || totalBytes > maxBytes) {
            removeFirst()
            droppedCount += 1
        }
        return QueueMutation(
            accepted = true,
            droppedCount = droppedCount,
            expiredCount = expiredCount,
        )
    }

    @Synchronized
    fun rejectBinary(): QueueMutation {
        val expiredCount = removeExpired(clockMs())
        return QueueMutation(
            accepted = false,
            expiredCount = expiredCount,
            rejection = QueueRejection.BINARY_NOT_RETAINED,
        )
    }

    @Synchronized
    fun poll(): QueuePoll {
        val expiredCount = removeExpired(clockMs())
        val item = entries.removeFirstOrNull()
        if (item != null) totalBytes -= item.payload.size
        return QueuePoll(item = item, expiredCount = expiredCount)
    }

    @Synchronized
    fun requeueFirst(item: QueuedOutgoing): QueueMutation {
        val nowMs = clockMs()
        val expiredCount = removeExpired(nowMs)
        if (isExpired(item, nowMs)) {
            return QueueMutation(
                accepted = false,
                expiredCount = expiredCount + 1,
                rejection = QueueRejection.EXPIRED,
            )
        }

        entries.addFirst(item)
        totalBytes += item.payload.size
        var droppedCount = 0
        // A concurrently queued newer item yields to the failed in-flight item.
        while (entries.size > maxMessages || totalBytes > maxBytes) {
            val removed = entries.removeLast()
            totalBytes -= removed.payload.size
            droppedCount += 1
        }
        return QueueMutation(
            accepted = true,
            droppedCount = droppedCount,
            expiredCount = expiredCount,
        )
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0
    }

    @Synchronized
    internal fun size(): Int = entries.size

    @Synchronized
    internal fun totalBytes(): Int = totalBytes

    private fun removeExpired(nowMs: Long): Int {
        var expiredCount = 0
        while (entries.firstOrNull()?.let { isExpired(it, nowMs) } == true) {
            removeFirst()
            expiredCount += 1
        }
        return expiredCount
    }

    private fun removeFirst() {
        val removed = entries.removeFirst()
        totalBytes -= removed.payload.size
    }

    private fun isExpired(item: QueuedOutgoing, nowMs: Long): Boolean =
        nowMs - item.enqueuedAtMs >= ttlMs

    private companion object {
        private const val DEFAULT_MAX_MESSAGES = 32
        private const val DEFAULT_MAX_BYTES = 512 * 1024
        private const val DEFAULT_TTL_MS = 30_000L
    }
}
