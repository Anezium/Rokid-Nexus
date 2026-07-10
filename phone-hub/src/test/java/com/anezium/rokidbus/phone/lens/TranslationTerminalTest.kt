package com.anezium.rokidbus.phone.lens

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationTerminalTest {
    @Test
    fun `cancel prevents a later final callback`() {
        val terminal = TranslationTerminal()
        val callbackCalled = AtomicBoolean(false)

        assertTrue(terminal.cancel())
        assertFalse(terminal.complete { callbackCalled.set(true) })

        assertFalse(callbackCalled.get())
        assertEquals(TranslationTerminalState.CANCELED, terminal.currentState())
    }

    @Test
    fun `completion is delivered exactly once`() {
        val terminal = TranslationTerminal()
        var callbackCount = 0

        assertTrue(terminal.complete { callbackCount += 1 })
        assertFalse(terminal.complete { callbackCount += 1 })
        assertFalse(terminal.cancel())

        assertEquals(1, callbackCount)
        assertEquals(TranslationTerminalState.COMPLETED, terminal.currentState())
    }

    @Test
    fun `cancel does not return while a winning callback is still running`() {
        val terminal = TranslationTerminal()
        val callbackStarted = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val callbackFinished = CountDownLatch(1)
        val cancelReturned = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val completion = executor.submit<Boolean> {
                terminal.complete {
                    callbackStarted.countDown()
                    releaseCallback.await(2, TimeUnit.SECONDS)
                    callbackFinished.countDown()
                }
            }
            assertTrue(callbackStarted.await(2, TimeUnit.SECONDS))

            val cancellation = executor.submit<Boolean> {
                terminal.cancel().also { cancelReturned.countDown() }
            }
            assertFalse(cancelReturned.await(100, TimeUnit.MILLISECONDS))

            releaseCallback.countDown()

            assertTrue(completion.get(2, TimeUnit.SECONDS))
            assertFalse(cancellation.get(2, TimeUnit.SECONDS))
            assertTrue(callbackFinished.await(2, TimeUnit.SECONDS))
            assertTrue(cancelReturned.await(2, TimeUnit.SECONDS))
        } finally {
            releaseCallback.countDown()
            executor.shutdownNow()
        }
    }
}
