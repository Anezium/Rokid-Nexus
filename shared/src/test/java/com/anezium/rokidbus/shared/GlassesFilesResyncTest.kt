package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesFilesResyncTest {
    @Test
    fun `only an opened lane can switch and duplicates cannot extend the confirmation budget`() {
        val resync = GlassesFilesResync()
        assertFalse(resync.begin(0))
        assertTrue(resync.accepts(viaLane = false))
        resync.opened()
        assertFalse(resync.accepts(viaLane = false))
        assertTrue(resync.begin(100))
        assertFalse(resync.accepts(viaLane = true))
        assertFalse(resync.offer(11, 10))
        assertTrue(resync.offer(7, 10))
        assertTrue(resync.accepts(viaLane = false))
        assertFalse(resync.begin(20_000))
        assertFalse(resync.offer(8, 10))
        assertFalse(resync.confirm(8))
        assertFalse(resync.expired(30_099))
        assertTrue(resync.expired(30_100))
        assertTrue(resync.confirm(7))
        assertFalse(resync.expired(60_000))
        assertFalse(resync.accepts(viaLane = true))
    }

    @Test
    fun `rewind hashes the prefix once and resends only from the exact receiver offset`() {
        val source = ByteArray(300_001) { (it * 31).toByte() }
        val sender = GlassesFilesSendDigest()
        sender.record(source, 256_000)
        sender.rewind(123_457)
        var resent = 0L
        while (sender.position < source.size) {
            val limit = minOf(sender.readLimit(32_768), source.size - sender.position.toInt())
            val replaying = sender.replaying
            sender.record(source.copyOfRange(sender.position.toInt(), sender.position.toInt() + limit), limit)
            if (!replaying) resent += limit
        }
        assertEquals(source.size.toLong() - 123_457, resent)
        assertEquals(GlassesFilesContract.hex(GlassesFilesContract.newDigest().apply { update(source) }), sender.finish())
    }
}
