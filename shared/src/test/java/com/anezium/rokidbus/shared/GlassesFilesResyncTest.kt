package com.anezium.rokidbus.shared

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesFilesResyncTest {
    @Test fun `snapshot rejects old bus epoch before and after confirmation but admits resumed bytes`() {
        val resync = GlassesFilesResync()
        assertTrue(resync.acceptsChunk(false, 0))
        assertFalse(resync.acceptsChunk(false, 1))
        resync.proven()
        assertTrue(resync.begin(0))
        assertFalse(resync.acceptsChunk(false, 1))
        assertTrue(resync.offer(12, 100))
        assertFalse(resync.acceptsChunk(false, 0))
        assertTrue(resync.acceptsChunk(false, 1))
        assertTrue(resync.confirm(12))
        assertFalse(resync.acceptsChunk(false, 0))
        assertTrue(resync.acceptsChunk(false, 1))
    }

    @Test fun `a proven pending lane can resync without ever accepting lane data`() {
        val resync = GlassesFilesResync()
        resync.proven()
        assertTrue(resync.accepts(viaLane = false))
        assertFalse(resync.accepts(viaLane = true))
        assertTrue(resync.begin(0))
        assertFalse(resync.accepts(viaLane = false))
        assertTrue(resync.offer(12, 100))
        assertTrue(resync.accepts(viaLane = false))
        assertTrue(resync.confirm(12))
        assertFalse(resync.accepts(viaLane = true))
    }

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
        sender.rewind(123_457, now = 0)
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

    @Test
    fun `the replay heartbeat is due once per interval and only while the prefix is replaying`() {
        val sender = GlassesFilesSendDigest()
        sender.record(ByteArray(65_536), 65_536)
        assertFalse(sender.heartbeatDue(100_000))
        sender.rewind(65_536, now = 1_000)
        assertFalse(sender.heartbeatDue(1_000 + GlassesFilesContract.REPLAY_HEARTBEAT_MS - 1))
        assertTrue(sender.heartbeatDue(1_000 + GlassesFilesContract.REPLAY_HEARTBEAT_MS))
        assertFalse(sender.heartbeatDue(1_000 + GlassesFilesContract.REPLAY_HEARTBEAT_MS + 1))
        assertTrue(sender.heartbeatDue(1_000 + 2 * GlassesFilesContract.REPLAY_HEARTBEAT_MS))
        sender.record(ByteArray(32_768), 32_768)
        assertTrue(sender.replaying)
        assertTrue(sender.heartbeatDue(1_000 + 3 * GlassesFilesContract.REPLAY_HEARTBEAT_MS))
        sender.record(ByteArray(32_768), 32_768)
        assertFalse(sender.replaying)
        assertFalse(sender.heartbeatDue(1_000 + 10 * GlassesFilesContract.REPLAY_HEARTBEAT_MS))
    }
}
