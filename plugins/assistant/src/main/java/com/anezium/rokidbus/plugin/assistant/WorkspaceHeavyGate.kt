package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.delay
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * One heavy page operation at a time: an indexing read, an inspection, or a view render. A native
 * render that outlives its question keeps the slot until it actually ends. A waiting view asks
 * indexing to stop at its next page boundary instead of queueing more work behind it.
 */
internal class WorkspaceHeavyGate {
    private val slot = Semaphore(1)
    private val waitingViews = AtomicInteger()

    val viewWaiting: Boolean get() = waitingViews.get() > 0

    /** Blocks an indexing thread until the slot is free; interruption abandons the wait. */
    fun <T> runIndexing(block: () -> T): T {
        slot.acquire()
        try {
            return block()
        } finally {
            slot.release()
        }
    }

    /** Waits for the slot as long as the caller's deadline allows; the caller must [release] it. */
    suspend fun acquireForView() {
        waitingViews.incrementAndGet()
        try {
            while (!slot.tryAcquire()) delay(POLL_MS)
        } finally {
            waitingViews.decrementAndGet()
        }
    }

    fun release() = slot.release()

    private companion object {
        const val POLL_MS = 10L
    }
}
