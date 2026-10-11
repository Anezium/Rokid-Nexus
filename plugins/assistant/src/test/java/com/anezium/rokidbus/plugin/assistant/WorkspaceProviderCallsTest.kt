package com.anezium.rokidbus.plugin.assistant

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.Closeable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class WorkspaceProviderCallsTest {
    @Test
    fun `blocked call and blocked cancellation cannot retain the deadline caller`() = runBlocking {
        val calls = WorkspaceProviderCalls(200)
        val provider = CountDownLatch(1)
        val cancellation = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val cancelling = CountDownLatch(1)
        try {
            val started = System.nanoTime()
            try {
                calls.await("blocked", cancel = { cancelling.countDown(); cancellation.await() }) {
                    entered.countDown()
                    provider.await()
                }
                error("Expected a deadline")
            } catch (_: TimeoutCancellationException) { }
            assertEquals(0L, entered.count)
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1_000)
            assertTrue(cancelling.await(1, TimeUnit.SECONDS))
            assertEquals("ready", calls.await("healthy") { "ready" })
        } finally {
            provider.countDown()
            cancellation.countDown()
            calls.close()
        }
    }

    @Test
    fun `abandoned calls keep their slots and cannot accumulate more workers`() = runBlocking {
        val calls = WorkspaceProviderCalls(200)
        val release = CountDownLatch(1)
        val started = AtomicInteger()
        try {
            for (key in listOf("first", "second")) {
                try {
                    calls.await(key) { started.incrementAndGet(); release.await() }
                    error("Expected a deadline")
                } catch (_: TimeoutCancellationException) { }
            }
            repeat(10) { attempt ->
                try {
                    calls.await(if (attempt % 2 == 0) "first" else "new-$attempt") { started.incrementAndGet() }
                    error("Expected a bounded rejection")
                } catch (_: WorkspaceProviderCheckException) { }
            }
            assertEquals(2, started.get())
        } finally { release.countDown(); calls.close() }
    }

    @Test
    fun `late resources are closed without reaching the cancelled caller`() = runBlocking {
        val calls = WorkspaceProviderCalls(200)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val delivered = AtomicBoolean()
        try {
            try {
                calls.await("late") { release.await(); Closeable { closed.countDown() } }
                delivered.set(true)
            } catch (_: TimeoutCancellationException) { }
            release.countDown()
            assertTrue(closed.await(1, TimeUnit.SECONDS))
            assertFalse(delivered.get())
        } finally { release.countDown(); calls.close() }
    }

    @Test
    fun `healthy concurrent calls for one tree wait without starting another provider call`() = runBlocking {
        val calls = WorkspaceProviderCalls(2_000)
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val secondStarted = AtomicBoolean()
        try {
            val first = async { calls.await("tree") { entered.countDown(); release.await(); "first" } }
            withTimeout(1_000) { while (entered.count != 0L) delay(5) }
            val second = async { calls.await("tree") { secondStarted.set(true); "second" } }
            delay(30)
            assertFalse(secondStarted.get())
            release.countDown()
            assertEquals("first", first.await())
            assertEquals("second", second.await())
        } finally { release.countDown(); calls.close() }
    }
}
