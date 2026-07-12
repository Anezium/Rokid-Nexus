package com.anezium.rokidbus.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class CameraLinkProtocolTest {
    @Test
    fun `video packet round trips`() {
        val packet = CameraLinkPacket(
            type = CameraLinkPacketType.VIDEO_FRAME,
            requestId = 42L,
            seq = 7,
            captureNanos = 123_456_789L,
            flags = CameraLinkPacketFlags.KEY_FRAME,
            meta = "{\"width\":720}",
            payload = ByteArray(4096) { (it * 13).toByte() },
        )
        val output = ByteArrayOutputStream()
        CameraLinkProtocol.write(output, packet)
        val decoded = CameraLinkProtocol.read(ByteArrayInputStream(output.toByteArray()))!!
        assertEquals(packet.type, decoded.type)
        assertEquals(packet.requestId, decoded.requestId)
        assertEquals(packet.seq, decoded.seq)
        assertEquals(packet.captureNanos, decoded.captureNanos)
        assertEquals(packet.flags, decoded.flags)
        assertEquals(packet.meta, decoded.meta)
        assertArrayEquals(packet.payload, decoded.payload)
    }

    @Test(expected = EOFException::class)
    fun `truncated packet is rejected`() {
        val output = ByteArrayOutputStream()
        CameraLinkProtocol.write(output, CameraLinkPacket(CameraLinkPacketType.FROZEN_IMAGE, payload = ByteArray(64)))
        CameraLinkProtocol.read(ByteArrayInputStream(output.toByteArray().copyOf(output.size() - 1)))
    }

    @Test
    fun `clean eof returns null`() {
        assertNull(CameraLinkProtocol.read(ByteArrayInputStream(ByteArray(0))))
    }
}
