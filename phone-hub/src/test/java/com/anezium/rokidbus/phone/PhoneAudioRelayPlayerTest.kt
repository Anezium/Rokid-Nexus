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

    @Test fun `bluetooth output is admitted only when it is identified and is not the glasses`() {
        val a2dp = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        val glasses = "AA:BB:CC:DD:EE:01"
        assertEquals(AudioRelayRouteAdmission.PLAY, PhoneAudioRelayPlayer.routeAdmission(a2dp, "AA:BB:CC:DD:EE:02", glasses))
        assertEquals(AudioRelayRouteAdmission.REJECT, PhoneAudioRelayPlayer.routeAdmission(a2dp, "aa:bb:cc:dd:ee:01", glasses))
        assertEquals(AudioRelayRouteAdmission.REJECT, PhoneAudioRelayPlayer.routeAdmission(a2dp, "", glasses))
        assertEquals(AudioRelayRouteAdmission.REJECT, PhoneAudioRelayPlayer.routeAdmission(a2dp, null, glasses))
        assertEquals(AudioRelayRouteAdmission.REJECT, PhoneAudioRelayPlayer.routeAdmission(a2dp, "AA:BB:CC:DD:EE:02", null))
        assertEquals(AudioRelayRouteAdmission.PLAY, PhoneAudioRelayPlayer.routeAdmission(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "", null))
    }

    @Test fun `poll retries a rejected route only where the winning output is known`() {
        assertFalse(PhoneAudioRelayGate.pollClearsRouteRejection(30))
        assertFalse(PhoneAudioRelayGate.pollClearsRouteRejection(32))
        assertTrue(PhoneAudioRelayGate.pollClearsRouteRejection(33))
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
