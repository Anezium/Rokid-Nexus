package com.anezium.rokidbus.plugin.meta

import java.util.concurrent.Executor
import org.junit.Assert.*
import org.junit.Test

class OwnedWhatsAppSessionTest {
    private class Queue : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(task: Runnable) { tasks.addLast(task) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Engine : OwnedWhatsAppEngine {
        var cancelled = false
        var closed = false
        override fun cancel() { cancelled = true }
        override fun close() { closed = true }
    }

    @Test fun oldOwnerWorkDoesNotRunAfterBackgroundAndReacquire() {
        val worker = Queue()
        val session = OwnedWhatsAppSession({ _: String -> Engine() }, worker, Executor { it.run() }, { it() }, { 0L })
        val owner = Any()
        session.acquire(owner)
        var calls = 0
        session.call("private", owner, { calls++ }, { calls++ })
        session.release(owner)
        session.acquire(owner)
        worker.drain()
        assertEquals(0, calls)
    }

    @Test fun closeOfOldEngineCannotCancelNewAcquisition() {
        val worker = Queue()
        val cancellation = Queue()
        val opened = mutableListOf<Engine>()
        val session = OwnedWhatsAppSession({ _: String -> Engine().also { opened += it } }, worker, cancellation, { it() }, { 0L })
        val first = Any()
        session.acquire(first)
        session.call("private", first, {}, {})
        worker.drain()
        session.release(first)
        val second = Any()
        session.acquire(second)
        session.call("private", second, {}, {})
        worker.drain()
        cancellation.drain()
        assertEquals(2, opened.size)
        assertTrue(opened[0].cancelled && opened[0].closed)
        assertFalse(opened[1].cancelled || opened[1].closed)
    }

    @Test fun retainedOwnerSharesEngineAndLastReleaseCancelsIt() {
        val worker = Queue()
        val opened = Engine()
        var factories = 0
        val session = OwnedWhatsAppSession({ _: String -> factories++; opened }, worker, Executor { it.run() }, { it() }, { 0L })
        val owner = Any()
        session.acquire(owner)
        session.call("private", owner, {}, {})
        worker.drain()
        session.acquire(owner)
        session.call("private", owner, {}, {})
        worker.drain()
        assertEquals(1, factories)
        assertFalse(opened.cancelled)
        session.release(owner)
        assertTrue(opened.cancelled)
        worker.drain()
        assertTrue(opened.closed)
    }

    @Test fun expiredQueuedCallNeverExecutesWork() {
        val worker = Queue()
        var now = 0L
        var executed = false
        var error: Throwable? = null
        val session = OwnedWhatsAppSession({ _: String -> Engine() }, worker, Executor { it.run() }, { it() }, { now })
        val owner = Any()
        session.acquire(owner)
        session.call("private", owner, { executed = true }, { error = it.exceptionOrNull() })
        now = 10_000
        worker.drain()
        assertFalse(executed)
        assertTrue(error is SessionCallNotStarted)
    }

    @Test fun settingsReleaseRunsCleanupWhenAnotherOwnerRetainsEngine() {
        val worker = Queue()
        val opened = Engine()
        val session = OwnedWhatsAppSession({ _: String -> opened }, worker, Executor { it.run() }, { it() }, { 0L })
        val settings = Any()
        val other = Any()
        session.acquire(settings)
        session.acquire(other)
        session.call("private", settings, {}, {})
        worker.drain()
        var cleaned = false
        session.release(settings) { cleaned = it === opened }
        worker.drain()
        assertTrue(cleaned)
        assertFalse(opened.cancelled || opened.closed)
    }

    @Test fun nativeOpenFailureIsKnownToPrecedeSubmission() {
        val worker = Queue()
        val session = OwnedWhatsAppSession<Engine>({ error("synthetic open failure") }, worker, Executor { it.run() }, { it() }, { 0L })
        val owner = Any()
        session.acquire(owner)
        var executed = false
        var error: Throwable? = null
        session.call("private", owner, { executed = true }, { error = it.exceptionOrNull() })
        worker.drain()
        assertFalse(executed)
        assertTrue(error is SessionCallNotStarted)
    }
}
