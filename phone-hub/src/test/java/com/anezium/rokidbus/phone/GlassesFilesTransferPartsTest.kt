package com.anezium.rokidbus.phone

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class GlassesFilesTransferPartsTest {
    private val io = Executors.newCachedThreadPool()

    @After
    fun tearDown() {
        io.shutdownNow()
    }

    @Test
    fun `two quick completions of one uri release its grant exactly once`() {
        val released = mutableListOf<String>()
        val ledger = GlassesGrantLedger { released += it }
        ledger.adopt(listOf("content://a"))
        // Intake holds the pick, queues both items, then lets go of the pick.
        repeat(2) { ledger.hold("content://a") }
        repeat(2) { ledger.hold("content://a") }
        repeat(2) { ledger.drop("content://a") }

        ledger.drop("content://a")
        assertEquals(emptyList<String>(), released)
        ledger.drop("content://a")
        assertEquals(listOf("content://a"), released)
    }

    @Test
    fun `a duplicate beyond the cutoff does not release the accepted copy`() {
        val released = mutableListOf<String>()
        val ledger = GlassesGrantLedger { released += it }
        ledger.adopt(listOf("content://a", "content://b"))
        // "a" was accepted; it was also picked again past the limit and "b" failed its lookup.
        val pick = listOf("content://a", "content://a", "content://b")
        pick.forEach(ledger::hold)
        ledger.hold("content://a")
        pick.forEach(ledger::drop)

        assertEquals(listOf("content://b"), released)
    }

    @Test
    fun `grants the app already held are never released`() {
        val released = mutableListOf<String>()
        val ledger = GlassesGrantLedger { released += it }
        ledger.adopt(listOf("content://new"))
        ledger.hold("content://old")
        ledger.hold("content://new")

        ledger.drop("content://old")
        ledger.dropAll()

        assertEquals(listOf("content://new"), released)
    }

    @Test
    fun `shutdown releases grants still held by queued or stopped items`() {
        val released = mutableListOf<String>()
        val ledger = GlassesGrantLedger { released += it }
        ledger.adopt(listOf("content://a", "content://b"))
        ledger.hold("content://a")
        ledger.hold("content://b")

        ledger.dropAll()

        assertEquals(setOf("content://a", "content://b"), released.toSet())
    }

    @Test
    fun `a late ready with a smaller size acknowledges the bytes already staged`() {
        assertEquals(GlassesReadyAction.ACK_STAGED, GlassesDownloadAckPolicy.onReady(stagedBytes = 1, announcedBytes = 1))
        assertEquals(GlassesReadyAction.CHANGED, GlassesDownloadAckPolicy.onReady(stagedBytes = 3, announcedBytes = 2))
        assertEquals(GlassesReadyAction.NONE, GlassesDownloadAckPolicy.onReady(stagedBytes = 0, announcedBytes = 0))
        assertEquals(GlassesReadyAction.NONE, GlassesDownloadAckPolicy.onReady(stagedBytes = 32_768, announcedBytes = 98_304))
    }

    @Test
    fun `reader returns bytes then end`() {
        val reader = GlassesProviderReader(io, stallMillis = 5_000, open = { ByteArrayInputStream(byteArrayOf(1, 2, 3)) })
        val buffer = ByteArray(8)
        assertEquals(GlassesProviderReader.Read.Bytes(3), reader.read(buffer) { false })
        assertEquals(GlassesProviderReader.Read.End, reader.read(buffer) { false })
    }

    @Test
    fun `a cancel unblocks a read stuck in the provider and closes it`() {
        val stream = BlockingStream(firstChunk = 4)
        val reader = GlassesProviderReader(io, stallMillis = 60_000, open = { stream }, pollMillis = 20)
        val buffer = ByteArray(8)
        assertEquals(GlassesProviderReader.Read.Bytes(4), reader.read(buffer) { false })

        val cancelled = AtomicBoolean(false)
        io.execute {
            Thread.sleep(100)
            cancelled.set(true)
        }
        val started = System.nanoTime()
        assertEquals(GlassesProviderReader.Read.Aborted, reader.read(buffer) { cancelled.get() })

        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
        assertTrue(stream.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a provider that stops answering is abandoned after the stall budget`() {
        val stream = BlockingStream(firstChunk = 0)
        val reader = GlassesProviderReader(io, stallMillis = 150, open = { stream }, pollMillis = 20)

        assertEquals(GlassesProviderReader.Read.Stalled, reader.read(ByteArray(8)) { false })
        assertTrue(stream.closed.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a cancel during a blocked open runs the provider's cancel hook`() {
        val hookRan = CountDownLatch(1)
        val opened = CountDownLatch(1)
        val reader = GlassesProviderReader(
            io,
            stallMillis = 60_000,
            pollMillis = 20,
            open = { register ->
                register { hookRan.countDown() }
                opened.countDown()
                // Like openFileDescriptor on a stalled cloud provider: returns only once cancelled.
                if (!hookRan.await(5, TimeUnit.SECONDS)) error("never cancelled")
                throw IOException("cancelled")
            },
        )
        val cancelled = AtomicBoolean(false)
        io.execute {
            opened.await(2, TimeUnit.SECONDS)
            cancelled.set(true)
        }

        assertEquals(GlassesProviderReader.Read.Aborted, reader.read(ByteArray(8)) { cancelled.get() })
        assertTrue(hookRan.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `a cancel hook registered after the reader closed still runs`() {
        val resume = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val hookRan = CountDownLatch(1)
        val reader = GlassesProviderReader(
            io,
            stallMillis = 60_000,
            pollMillis = 20,
            open = { register ->
                entered.countDown()
                // Descheduled before registering, while the worker cancels and walks away. Provider
                // IPC does not stop on interruption, so neither does this stand-in.
                while (true) {
                    try {
                        if (resume.await(5, TimeUnit.SECONDS)) break
                    } catch (_: InterruptedException) {
                        continue
                    }
                }
                register { hookRan.countDown() }
                throw IOException("cancelled")
            },
        )
        val cancelled = AtomicBoolean(false)
        io.execute {
            entered.await(2, TimeUnit.SECONDS)
            cancelled.set(true)
        }

        assertEquals(GlassesProviderReader.Read.Aborted, reader.read(ByteArray(8)) { cancelled.get() })
        resume.countDown()
        assertTrue(hookRan.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun `metadata intake skips a provider that does not answer and cancels its query`() {
        val cancelledQuery = CountDownLatch(1)
        val started = System.nanoTime()
        val described = GlassesPickIntake.describeAll(listOf("content://slow", "content://ok"), io, 200) { source, register ->
            if (source == "content://slow") {
                val hang = CountDownLatch(1)
                register {
                    cancelledQuery.countDown()
                    hang.countDown()
                }
                hang.await(5, TimeUnit.SECONDS)
                null
            } else {
                "book.pdf" to 3L
            }
        }

        assertEquals(listOf("content://ok" to ("book.pdf" to 3L)), described)
        assertTrue(cancelledQuery.await(2, TimeUnit.SECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2_000)
    }

    /** Yields [firstChunk] bytes once, then blocks every read until closed. */
    private class BlockingStream(private var firstChunk: Int) : InputStream() {
        val closed = CountDownLatch(1)

        override fun read(): Int = throw UnsupportedOperationException()

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (firstChunk > 0) {
                val count = minOf(firstChunk, len)
                firstChunk = 0
                return count
            }
            closed.await()
            throw IOException("closed")
        }

        override fun close() {
            closed.countDown()
        }
    }
}
