package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteOrder
import java.util.Base64
import android.media.AudioDeviceInfo
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class PhoneAudioRelayPlayerTest {
    @Test fun `unknown initial route waits and built in speaker is never admitted for PCM`() {
        assertEquals(AudioRelayRouteAdmission.WAIT, PhoneAudioRelayPlayer.routeAdmission(null, "glasses"))
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val wired = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
        assertEquals(AudioRelayRouteAdmission.REJECT, PhoneAudioRelayPlayer.routeAdmission(speaker, "glasses"))
        assertEquals(AudioRelayRouteAdmission.PLAY, PhoneAudioRelayPlayer.routeAdmission(wired, "glasses"))
    }

    @Test fun `real glasses Opus CSDs pass validation and configure the decoder byte for byte`() {
        val encoded = listOf("T3B1c0hlYWQBATgBgLsAAAAAAA==", "oC5jAAAAAAA=", "ALTEBAAAAAA=")
        val wire = C.ready(C.OPUS, encoded.map { Base64.getDecoder().decode(it) })
        val parsed = C.validateReady(wire)
        assertTrue(parsed is C.ValidationResult.Valid)
        val ready = (parsed as C.ValidationResult.Valid).value
        val format = PhoneAudioRelayPlayer.decoderFormat(ready)
        assertEquals("audio/opus", format.getString("mime"))
        assertEquals(48000, format.getInteger("sample-rate")); assertEquals(1, format.getInteger("channel-count"))
        encoded.forEachIndexed { index, base64 ->
            val buffer = format.getByteBuffer("csd-$index")!!.duplicate()
            val bytes = ByteArray(buffer.remaining()).also(buffer::get)
            assertArrayEquals(Base64.getDecoder().decode(base64), bytes)
        }
        assertEquals(6_500_000L, format.getByteBuffer("csd-1")!!.duplicate().order(ByteOrder.LITTLE_ENDIAN).long)
        assertEquals(80_000_000L, format.getByteBuffer("csd-2")!!.duplicate().order(ByteOrder.LITTLE_ENDIAN).long)
    }
}
