package com.anezium.rokidbus.glasses.audio

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CaptureExitGuardTest {
    @Test fun `EOF or timeout arms halt before a stalled teardown`() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val halted = AtomicInteger()
        var deadline = 0L
        var forcedExit: (() -> Unit)? = null
        val guard = CaptureExitGuard({ entered.countDown(); check(release.await(3, TimeUnit.SECONDS)) },
            { delay, task -> deadline = delay; forcedExit = task }, { halted.incrementAndGet(); Unit })
        val stopper = Thread { guard.stop() }
        try {
            stopper.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            assertEquals(1000L, deadline)
            forcedExit!!.invoke()
            assertEquals(1, halted.get())
            assertTrue("Teardown is still blocked while forced exit runs", stopper.isAlive)
        } finally { release.countDown(); stopper.join(2000) }
    }

    @Test fun `concurrent EOF and watchdog share one uncancelled exit deadline`() {
        val stops = AtomicInteger()
        val timers = AtomicInteger()
        val halts = AtomicInteger()
        var forcedExit: (() -> Unit)? = null
        val guard = CaptureExitGuard({ stops.incrementAndGet(); Unit },
            { _, task -> timers.incrementAndGet(); forcedExit = task }, { halts.incrementAndGet(); Unit })
        val callers = List(2) { Thread { guard.stop() } }
        callers.forEach { it.start() }; callers.forEach { it.join() }
        assertEquals(1, stops.get()); assertEquals(1, timers.get())
        forcedExit!!.invoke()
        assertEquals(1, halts.get())
    }
}
