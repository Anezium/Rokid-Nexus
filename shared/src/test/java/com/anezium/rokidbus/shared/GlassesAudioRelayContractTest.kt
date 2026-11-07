package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlassesAudioRelayContractTest {
    private fun valid(value: C.ValidationResult<*>) = assertTrue(value.toString(), value is C.ValidationResult.Valid)
    private fun invalid(value: C.ValidationResult<*>) = assertTrue(value.toString(), value is C.ValidationResult.Invalid)
    private fun opus() = C.ready(C.OPUS, listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))

    @Test fun `start accepts known codecs and rejects malformed negotiation`() {
        valid(C.validateStart(C.start()))
        valid(C.validateStart(C.start(listOf(C.OPUS, C.PCM16))))
        listOf(emptyList(), listOf("aac"), listOf(C.OPUS, C.OPUS)).forEach { invalid(C.validateStart(C.start(it))) }
        invalid(C.validateStart(null))
        invalid(C.validateStart(C.start().put("v", 2)))
        invalid(C.validateStart(C.start().put("v", "1")))
        invalid(C.validateStart(C.start().put("keepaliveMs", 1)))
    }

    @Test fun `ready requires bounded codec configuration and all opus csds`() {
        valid(C.validateReady(opus()))
        valid(C.validateReady(C.ready(C.PCM16)))
        invalid(C.validateReady(null))
        invalid(C.validateReady(opus().put("v", 0)))
        invalid(C.validateReady(opus().put("codec", "aac")))
        listOf("sampleRate", "channels", "frameMs", "packetsPerFrame").forEach {
            invalid(C.validateReady(opus().put(it, 0)))
            invalid(C.validateReady(opus().put(it, "1")))
        }
        (0..2).forEach {
            invalid(C.validateReady(opus().apply { remove("csd$it") }))
            invalid(C.validateReady(opus().put("csd$it", "!")))
            invalid(C.validateReady(opus().put("csd$it", "")))
        }
        invalid(C.validateReady(C.ready(C.PCM16).put("csd0", "AQ==")))
        invalid(C.validateReady(opus().put("csd0", "A".repeat(12000))))
    }

    @Test fun `status and error reject unknown versions types and bounds`() {
        valid(C.validateStatus(C.status(-1, true)))
        valid(C.validateStatus(C.status(Int.MAX_VALUE.toLong(), false)))
        invalid(C.validateStatus(C.status(-2, false)))
        invalid(C.validateStatus(C.status(Long.MAX_VALUE, false)))
        invalid(C.validateStatus(C.status(0, false).put("silent", "false")))
        invalid(C.validateStatus(C.status(0, false).put("v", 2)))
        C.ERROR_CODES.forEach { valid(C.validateError(C.error(it))) }
        invalid(C.validateError(C.error("UNKNOWN")))
        invalid(C.validateError(C.error("BUSY").put("v", 2)))
        invalid(C.validateError(JSONObject()))
    }

    @Test fun `frames round trip one through four packets up to the byte limit`() {
        (1..4).forEach { count ->
            val packets = (1..count).map { ByteArray(it * 100) { index -> index.toByte() } }
            val result = C.unpack(C.pack(packets)) as C.ValidationResult.Valid
            packets.zip(result.value).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
        }
        assertEquals(4096, C.pack(listOf(ByteArray(4094))).size)
        valid(C.unpack(C.pack(listOf(ByteArray(4094)))))
    }

    @Test fun `frames reject empty packets overflow truncation and excess packets`() {
        listOf(emptyList(), listOf(byteArrayOf()), List(5) { byteArrayOf(1) }, listOf(ByteArray(4095))).forEach {
            assertThrows(IllegalArgumentException::class.java) { C.pack(it) }
        }
        listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 0), byteArrayOf(0, 2, 1),
            byteArrayOf(-1, -1, 1), ByteArray(4097), ByteArray(15) { if (it % 3 == 1) 1 else 0 }).forEach {
            invalid(C.unpack(it))
        }
        assertEquals("ar-0", C.frameId(0))
        val frame = C.frame(0, 123, listOf(byteArrayOf(1)))
        valid(C.validateFrame(frame))
        invalid(C.validateFrame(frame.copy(id = "wrong")))
        invalid(C.validateFrame(frame.copy(v = 2)))
        invalid(C.validateFrame(frame.copy(binary = null)))
        invalid(C.validateFrame(frame.copy(payload = JSONObject().put("seq", 0).put("ms", -1))))
        assertEquals("ar-2147483647", C.frameId(Int.MAX_VALUE.toLong()))
        assertThrows(IllegalArgumentException::class.java) { C.frameId(-1) }
    }

    @Test fun `constants match relay v1`() {
        assertEquals(1, C.VERSION)
        assertEquals(listOf(3000L, 5000L, 2000L, 6000L, 1000L, 5000L), listOf(C.START_TIMEOUT_MS,
            C.CAPTURE_ATTACH_TIMEOUT_MS, C.KEEPALIVE_INTERVAL_MS, C.PEER_TIMEOUT_MS,
            C.LOCAL_KEEPALIVE_INTERVAL_MS, C.LOCAL_KEEPALIVE_TIMEOUT_MS))
        assertEquals(listOf(4, 8, 16, 25), listOf(C.JITTER_PRIME_FRAMES, C.JITTER_MAX_FRAMES, C.SILENCE_PEAK, C.SILENCE_HOLD_BLOCKS))
        assertEquals("3fd6b1e2-9a47-4c0b-8e5d-71c2a9f0b4d8", BusConstants.AUDIO_SPP_UUID.toString())
        assertEquals("RokidBusAudio", BusConstants.AUDIO_SERVICE_NAME)
        assertNotEquals(BusConstants.SPP_UUID, BusConstants.AUDIO_SPP_UUID)
        assertFalse(BusPaths.isAudioRelayPath("/core/audio-relayish/frame"))
        assertTrue(BusPaths.isAudioRelayPath(BusPaths.CORE_AUDIO_RELAY_FRAME))
    }
}
