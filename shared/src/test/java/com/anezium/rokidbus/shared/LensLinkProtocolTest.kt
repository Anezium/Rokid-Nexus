package com.anezium.rokidbus.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class LensLinkProtocolTest {
    @Test
    fun `packet round trips`() {
        val packet = LensLinkPacket(
            type = LensLinkPacketType.FROZEN_IMAGE,
            requestId = 987654321L,
            seq = 7,
            meta = "{\"stage\":\"FAST\"}",
            payload = ByteArray(4096) { (it * 31).toByte() },
        )
        val output = ByteArrayOutputStream()

        LensLinkProtocol.write(output, packet)
        val decoded = LensLinkProtocol.read(ByteArrayInputStream(output.toByteArray()))!!

        assertEquals(packet.type, decoded.type)
        assertEquals(packet.requestId, decoded.requestId)
        assertEquals(packet.seq, decoded.seq)
        assertEquals(packet.meta, decoded.meta)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test(expected = EOFException::class)
    fun `truncated packet is rejected`() {
        val output = ByteArrayOutputStream()
        LensLinkProtocol.write(
            output,
            LensLinkPacket(LensLinkPacketType.PROBE, 1L, payload = ByteArray(128)),
        )
        val truncated = output.toByteArray().copyOf(output.size() - 3)
        LensLinkProtocol.read(ByteArrayInputStream(truncated))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage version is rejected`() {
        val header = ByteArray(LensLinkProtocol.HEADER_BYTES)
        header[0] = 99
        header[1] = LensLinkPacketType.HELLO.id.toByte()
        LensLinkProtocol.read(ByteArrayInputStream(header))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `oversized length in header is rejected before allocation`() {
        val header = ByteBuffer.allocate(LensLinkProtocol.HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(LensLinkProtocol.VERSION.toByte())
            .put(LensLinkPacketType.PROBE.id.toByte())
            .putLong(1L)
            .putInt(0)
            .putInt(0)
            .putInt(LensLinkProtocol.MAX_PAYLOAD_BYTES + 1)
            .array()
        LensLinkProtocol.read(ByteArrayInputStream(header))
    }

    @Test
    fun `clean eof returns null`() {
        assertNull(LensLinkProtocol.read(ByteArrayInputStream(ByteArray(0))))
    }
}
