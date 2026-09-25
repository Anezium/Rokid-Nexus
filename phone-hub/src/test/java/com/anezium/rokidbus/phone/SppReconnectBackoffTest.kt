package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class SppReconnectBackoffTest {
    @Test fun readyCxrIdentityWakesWaitingConnectionLoopImmediately() {
        val backoff = SppReconnectBackoff(initialDelayMs = 30_000L)
        val attempt = backoff.beginAttempt()
        val started = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        var worker: Thread? = null
        val retry = executor.submit {
            worker = Thread.currentThread()
            started.countDown()
            backoff.awaitRetry(attempt)
        }
        try {
            assertTrue(started.await(1, TimeUnit.SECONDS))
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1)
            while (worker!!.state != Thread.State.TIMED_WAITING && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.TIMED_WAITING, worker!!.state)
            val identity = SppCxrIdentity(schedule = { _, _ -> ({}) }, onReady = { backoff.reset() })
            identity.onConnectionChanged(true)
            assertFalse(retry.isDone)
            identity.onDeviceInfo("test-peer", null)
            retry.get(1, TimeUnit.SECONDS)
            assertEquals(30_000L, backoff.retryDelayMs())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test fun readyIdentityBetweenFailureAndWaitCannotBeLost() {
        val backoff = SppReconnectBackoff(initialDelayMs = 30_000L)
        val attempt = backoff.beginAttempt()
        backoff.reset()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { backoff.awaitRetry(attempt) }.get(1, TimeUnit.SECONDS)
            assertEquals(30_000L, backoff.retryDelayMs())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }

    @Test fun retriesAreBoundedAndReadyIdentityResetsDelay() {
        val backoff = SppReconnectBackoff(initialDelayMs = 1L, maximumDelayMs = 4L)
        for (expected in listOf(1L, 2L, 4L, 4L)) {
            assertEquals(expected, backoff.retryDelayMs())
            backoff.awaitRetry(backoff.beginAttempt())
        }
        val attempt = backoff.beginAttempt()
        backoff.reset()
        backoff.awaitRetry(attempt)
        assertEquals(1L, backoff.retryDelayMs())
        backoff.awaitRetry(backoff.beginAttempt())
        assertEquals(2L, backoff.retryDelayMs())
        backoff.connected()
        assertEquals(1L, backoff.retryDelayMs())
    }

    @Test fun readyIdentityAlsoWakesBluetoothAvailabilityWait() {
        val backoff = SppReconnectBackoff()
        val attempt = backoff.beginAttempt()
        backoff.reset()
        val executor = Executors.newSingleThreadExecutor()
        try {
            executor.submit { backoff.await(attempt, 10_000L) }.get(1, TimeUnit.SECONDS)
            assertEquals(1_000L, backoff.retryDelayMs())
        } finally {
            executor.shutdownNow()
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS))
        }
    }
}
