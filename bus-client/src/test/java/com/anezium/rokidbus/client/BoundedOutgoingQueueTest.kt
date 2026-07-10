package com.anezium.rokidbus.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedOutgoingQueueTest {
    private var nowMs = 0L

    @Test
    fun countOverflowDropsOldestAndRetainsFifoOrder() {
        val queue = queue(maxMessages = 3)

        queue.offerJson("/path", "one", bytes(1))
        queue.offerJson("/path", "two", bytes(1))
        queue.offerJson("/path", "three", bytes(1))
        val result = queue.offerJson("/path", "four", bytes(1))

        assertTrue(result.accepted)
        assertEquals(1, result.droppedCount)
        assertEquals(listOf("two", "three", "four"), drainIds(queue))
    }

    @Test
    fun byteOverflowDropsOldestMessagesFirst() {
        val queue = queue(maxBytes = 7)

        queue.offerJson("/path", "one", bytes(3))
        queue.offerJson("/path", "two", bytes(3))
        val result = queue.offerJson("/path", "three", bytes(3))

        assertEquals(1, result.droppedCount)
        assertEquals(6, queue.totalBytes())
        assertEquals(listOf("two", "three"), drainIds(queue))
    }

    @Test
    fun expiredMessagesAreRemovedBeforePolling() {
        val queue = queue(ttlMs = 30_000L)
        queue.offerJson("/path", "old", bytes(2))
        nowMs = 30_000L

        val result = queue.poll()

        assertNull(result.item)
        assertEquals(1, result.expiredCount)
        assertEquals(0, queue.totalBytes())
    }

    @Test
    fun retainedMessagesRemainFifo() {
        val queue = queue()
        listOf("one", "two", "three").forEach { id ->
            queue.offerJson("/path", id, id.toByteArray())
        }

        assertEquals(listOf("one", "two", "three"), drainIds(queue))
    }

    @Test
    fun failedSendCanBeRequeuedAtHeadExactlyOnce() {
        val queue = queue()
        queue.offerJson("/path", "one", bytes(1))
        queue.offerJson("/path", "two", bytes(1))
        val failed = queue.poll().item!!

        val result = queue.requeueFirst(failed)

        assertTrue(result.accepted)
        assertEquals(2, queue.size())
        assertEquals(listOf("one", "two"), drainIds(queue))
    }

    @Test
    fun binaryMessagesAreRejectedWithoutBeingRetained() {
        val queue = queue()

        val result = queue.rejectBinary()

        assertFalse(result.accepted)
        assertEquals(QueueRejection.BINARY_NOT_RETAINED, result.rejection)
        assertEquals(0, queue.size())
        assertEquals(0, queue.totalBytes())
    }

    @Test
    fun singleJsonMessageOverByteLimitIsRejectedWithoutDroppingRetainedData() {
        val queue = queue(maxBytes = 4)
        queue.offerJson("/path", "kept", bytes(2))

        val result = queue.offerJson("/path", "oversized", bytes(5))

        assertFalse(result.accepted)
        assertEquals(QueueRejection.MESSAGE_TOO_LARGE, result.rejection)
        assertEquals(listOf("kept"), drainIds(queue))
    }

    @Test
    fun expiredFailedSendIsNotRequeued() {
        val queue = queue(ttlMs = 10L)
        queue.offerJson("/path", "old", bytes(1))
        val failed = queue.poll().item!!
        nowMs = 10L

        val result = queue.requeueFirst(failed)

        assertFalse(result.accepted)
        assertEquals(QueueRejection.EXPIRED, result.rejection)
        assertEquals(1, result.expiredCount)
        assertEquals(0, queue.size())
    }

    @Test
    fun errorDescriptionDoesNotContainPathIdOrPayload() {
        val queue = queue(maxBytes = 1)
        val result = queue.offerJson("/private/path", "secret-id", "secret".toByteArray())

        val message = result.errorMessage("json queue")!!

        assertFalse(message.contains("/private/path"))
        assertFalse(message.contains("secret-id"))
        assertFalse(message.contains("secret"))
        assertTrue(message.contains("MESSAGE_TOO_LARGE"))
    }

    private fun queue(
        maxMessages: Int = 32,
        maxBytes: Int = 512 * 1024,
        ttlMs: Long = 30_000L,
    ): BoundedOutgoingQueue = BoundedOutgoingQueue(
        maxMessages = maxMessages,
        maxBytes = maxBytes,
        ttlMs = ttlMs,
        clockMs = { nowMs },
    )

    private fun bytes(size: Int): ByteArray = ByteArray(size) { 0x41 }

    private fun drainIds(queue: BoundedOutgoingQueue): List<String> = buildList {
        while (true) {
            val item = queue.poll().item ?: break
            add(item.id)
        }
    }
}
