package com.anezium.rokidbus.glasses.audio

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusCodecConfigTest {
    private val head = "OpusHead".toByteArray() + byteArrayOf(1, 1, 0x38, 0x01, 0x80.toByte(), 0xbb.toByte(), 0, 0, 0, 0, 0)

    private fun le(value: Long) = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()
    private fun section(marker: String, body: ByteArray) = marker.toByteArray() + le(body.size.toLong()) + body

    @Test fun `codec2 marker sections map to the three decoder inputs`() {
        val config = ByteArrayOutputStream().apply {
            write(section("AOPUSHDR", head))
            write(section("AOPUSDLY", le(6_500_000)))
            write(section("AOPUSPRL", le(80_000_000)))
        }.toByteArray()
        val csd = OpusCodecConfig.csd(config)
        assertArrayEquals(head, csd[0])
        assertArrayEquals(le(6_500_000), csd[1])
        assertArrayEquals(le(80_000_000), csd[2])
    }

    @Test fun `bare OpusHead derives delay from pre-skip and default pre-roll`() {
        val csd = OpusCodecConfig.csd(head)
        assertArrayEquals(head, csd[0])
        assertArrayEquals(le(312L * 1_000_000_000L / 48_000L), csd[1])
        assertArrayEquals(le(80_000_000), csd[2])
    }

    @Test fun `missing delay sections are derived`() {
        val csd = OpusCodecConfig.csd(section("AOPUSHDR", head))
        assertArrayEquals(le(312L * 1_000_000_000L / 48_000L), csd[1])
        assertArrayEquals(le(80_000_000), csd[2])
    }

    @Test fun `malformed configs are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { OpusCodecConfig.csd("garbage!".toByteArray()) }
        assertThrows(IllegalArgumentException::class.java) {
            OpusCodecConfig.csd("AOPUSHDR".toByteArray() + le(999) + head)
        }
        assertThrows(IllegalArgumentException::class.java) { OpusCodecConfig.csd(section("AOPUSHDR", ByteArray(19))) }
    }
}
