package com.anezium.rokidbus.phone

import org.junit.Assert.*
import org.junit.Test

class AudioRelayJitterBufferTest {
    private val buffer = AudioRelayJitterBuffer()
    private fun offer(seq: Long) = buffer.offer(seq, listOf(byteArrayOf(seq.toByte())))
    @Test fun `prime needs four frames and underrun primes again`() {
        (0L..2L).forEach { offer(it); assertNull(buffer.poll()) }
        offer(3); (0L..3L).forEach { assertEquals(it, buffer.poll()!!.seq) }
        assertNull(buffer.poll()); (4L..6L).forEach { offer(it); assertNull(buffer.poll()) }
        offer(7); assertEquals(4L, buffer.poll()!!.seq)
    }
    @Test fun `overflow drops oldest and gaps skip missing sequences`() {
        repeat(10) { offer(it.toLong()) }
        assertEquals(8, buffer.depth()); assertEquals(2L, buffer.poll()!!.seq)
        repeat(7) { buffer.poll() }; assertNull(buffer.poll())
        listOf(20L, 25L, 26L, 27L).forEach(::offer)
        assertEquals(20L, buffer.poll()!!.seq); assertEquals(25L, buffer.poll()!!.seq)
        offer(20); assertEquals(2, buffer.depth())
    }
}
