package com.anezium.rokidbus.glasses.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

class LocalCaptureProtocolTest {
    @Test fun `records round trip with big endian bounded lengths`() {
        val output = ByteArrayOutputStream()
        val body = ByteArray(4096) { it.toByte() }
        LocalCaptureProtocol.write(output, LocalCaptureProtocol.PACKET, body)
        LocalCaptureProtocol.write(output, LocalCaptureProtocol.KEEPALIVE)
        assertEquals(16, output.toByteArray()[1].toInt())
        val input = ByteArrayInputStream(output.toByteArray())
        assertArrayEquals(body, LocalCaptureProtocol.read(input)!!.body)
        assertEquals(LocalCaptureProtocol.KEEPALIVE, LocalCaptureProtocol.read(input)!!.type)
        assertNull(LocalCaptureProtocol.read(input))
        listOf(byteArrayOf(2, 0, 0), byteArrayOf(2, 16, 1), byteArrayOf(2, 0, 2, 1),
            byteArrayOf(16, 0, 1, 1), byteArrayOf(99, 0, 0), byteArrayOf(2, 0)).forEach {
            assertThrows(IOException::class.java) { LocalCaptureProtocol.read(ByteArrayInputStream(it)) }
        }
    }

    @Test fun `silence begins at block 25 and any threshold sample resumes immediately`() {
        val detector = LocalCaptureProtocol.SilenceDetector()
        repeat(24) { assertFalse(detector.accept(shortArrayOf(-15, 15))) }
        assertTrue(detector.accept(shortArrayOf(0)))
        repeat(100) { assertTrue(detector.accept(shortArrayOf(0))) }
        assertFalse(detector.accept(shortArrayOf(-16)))
        repeat(24) { assertFalse(detector.accept(shortArrayOf(0))) }
        assertFalse(detector.accept(shortArrayOf(16)))
        assertFalse(detector.accept(shortArrayOf(Short.MIN_VALUE)))
    }

    @Test fun `stereo downmix sums as integers and preserves mono bounds`() {
        assertArrayEquals(shortArrayOf(Short.MAX_VALUE, Short.MIN_VALUE, 0, 100),
            LocalCaptureProtocol.mono(shortArrayOf(32767, 32767, -32768, -32768, 30000, -30000, 200, 0)))
        assertThrows(IllegalArgumentException::class.java) { LocalCaptureProtocol.mono(shortArrayOf(1)) }
    }
}
