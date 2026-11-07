package com.anezium.rokidbus.phone

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import com.google.common.collect.ImmutableList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.AudioDeviceInfoBuilder

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PhoneSpeakerRouteAttributesTest {
    @Test fun `relay queries media winning output while default TTS still queries assistant output`() {
        val app = RuntimeEnvironment.getApplication()
        val audio = app.getSystemService(AudioManager::class.java)
        val speaker = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER).build()
        val wired = AudioDeviceInfoBuilder.newBuilder().setType(AudioDeviceInfo.TYPE_WIRED_HEADPHONES).build()
        val media = PhoneAudioRelayPlayer.audioAttributes()
        assertEquals(AudioAttributes.USAGE_MEDIA, media.usage)
        assertEquals(AudioAttributes.CONTENT_TYPE_SPEECH, media.contentType)
        shadowOf(audio).setAudioDevicesForAttributes(phoneTtsAudioAttributes(), ImmutableList.of(speaker))
        shadowOf(audio).setAudioDevicesForAttributes(media, ImmutableList.of(wired))
        val probe = PhoneSpeakerRouteProbe(app) { null }
        assertEquals(PhoneTtsRoute.PHONE_SPEAKER, probe.classifyRoute())
        assertEquals(PhoneTtsRoute.EXTERNAL_SINK, probe.classifyRoute(media))
    }
}
