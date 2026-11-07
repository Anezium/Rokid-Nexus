package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test

class AudioRelayJitterBufferTest {
    private val buffer = AudioRelayJitterBuffer()
    private fun offer(seq: Long) = buffer.offer(seq, listOf(byteArrayOf(seq.toByte())))
    @Test fun `prime needs two frames and underrun primes again`() {
        offer(0); assertNull(buffer.poll())
        offer(1); assertEquals(0L, buffer.poll()!!.seq); assertEquals(1L, buffer.poll()!!.seq)
        assertNull(buffer.poll()); offer(2); assertNull(buffer.poll())
        offer(3); assertEquals(2L, buffer.poll()!!.seq)
    }
    @Test fun `overflow drops oldest and gaps skip missing sequences`() {
        repeat(10) { offer(it.toLong()) }
        assertEquals(8, buffer.depth()); assertEquals(2L, buffer.poll()!!.seq)
        repeat(7) { buffer.poll() }; assertNull(buffer.poll())
        offer(20); offer(25)
        assertEquals(20L, buffer.poll()!!.seq); assertEquals(25L, buffer.poll()!!.seq)
        offer(20); assertEquals(0, buffer.depth())
    }
}
