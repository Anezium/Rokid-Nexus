package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBadgeRefreshSchedulerTest {
    @Test
    fun `continuous launcher content events cannot starve the first read`() {
        val clock = Clock()
        val reads = mutableListOf<Long>()
        val scheduler = clock.scheduler { reads += clock.now }
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(50)
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(100)
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(150)
        assertEquals(listOf(150L), reads)
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun `late prompter row restoration is observed without a final accessibility event`() {
        val clock = Clock()
        var row: Int? = null
        var renderedRow: Int? = null
        val scheduler = clock.scheduler { renderedRow = row }
        scheduler.onEvent(windowChanged = true)
        clock.advanceTo(150)
        assertEquals(null, renderedRow)
        // The ROM restores the home row after the first read, with no further event.
        clock.advanceTo(500)
        row = 365
        clock.advanceTo(1_000)
        assertEquals(365, renderedRow)
    }

    @Test
    fun `window events neither duplicate nor postpone the bounded recovery batch`() {
        val clock = Clock()
        val reads = mutableListOf<Long>()
        val scheduler = clock.scheduler { reads += clock.now }
        scheduler.onEvent(windowChanged = true)
        clock.advanceTo(100)
        scheduler.onEvent(windowChanged = true)
        clock.advanceTo(60_000)
        assertEquals(listOf(150L, 1_000L, 3_000L, 10_000L, 30_000L), reads)
        assertTrue(clock.pending.isEmpty())
    }

    @Test
    fun `content-only transition retries a missing row without a battery percentage change`() {
        val clock = Clock()
        var row: Int? = null
        var renderedRow: Int? = null
        lateinit var scheduler: StatusBadgeRefreshScheduler
        scheduler = clock.scheduler {
            renderedRow = row
            scheduler.onRead(needsRecovery = row == null)
        }
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(150)
        row = 365
        clock.advanceTo(1_150)
        assertEquals(365, renderedRow)
    }

    @Test
    fun `a permanently missing row exhausts recovery instead of polling forever`() {
        val clock = Clock()
        var reads = 0
        lateinit var scheduler: StatusBadgeRefreshScheduler
        scheduler = clock.scheduler {
            reads++
            scheduler.onRead(needsRecovery = true)
        }
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(120_000)
        assertEquals(5, reads)
        assertTrue(clock.pending.isEmpty())
        scheduler.onRead(needsRecovery = true)
        assertTrue(clock.pending.isEmpty())
        scheduler.onRead(needsRecovery = false)
        scheduler.onRead(needsRecovery = true)
        assertEquals(4, clock.pending.size)
    }

    @Test
    fun `service teardown cancels every read and a new service can schedule again`() {
        val clock = Clock()
        var reads = 0
        val scheduler = clock.scheduler { reads++ }
        scheduler.onEvent(windowChanged = true)
        scheduler.cancel()
        clock.advanceTo(60_000)
        assertEquals(0, reads)
        assertTrue(clock.pending.isEmpty())
        scheduler.onEvent(windowChanged = false)
        clock.advanceTo(60_150)
        assertEquals(1, reads)
    }

    private class Clock {
        var now = 0L
            private set
        val pending = mutableMapOf<Runnable, Long>()

        fun scheduler(refresh: () -> Unit) = StatusBadgeRefreshScheduler(
            postDelayed = { callback, delay -> pending[callback] = now + delay },
            removeCallbacks = { callback -> pending.remove(callback); Unit },
            refresh = refresh,
        )

        fun advanceTo(target: Long) {
            while (true) {
                val next = pending.minByOrNull { it.value } ?: break
                if (next.value > target) break
                now = next.value
                pending.remove(next.key)
                next.key.run()
            }
            now = target
        }
    }
}
