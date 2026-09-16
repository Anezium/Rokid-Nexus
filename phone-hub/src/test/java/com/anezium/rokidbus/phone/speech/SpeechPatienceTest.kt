package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class SpeechPatienceTest {
    @Test
    fun verifyWireValues() {
        assertEquals("quick", SpeechPatience.QUICK.wireValue)
        assertEquals("normal", SpeechPatience.NORMAL.wireValue)
        assertEquals("patient", SpeechPatience.PATIENT.wireValue)
    }

    @Test
    fun verifyConfigMapping() {
        val quick = SpeechPatience.QUICK.voiceActivityConfig()
        assertEquals(5_000L, quick.initialNoSpeechTimeoutMs)
        assertEquals(1_500L, quick.silenceAfterSpeechMs)

        val normal = SpeechPatience.NORMAL.voiceActivityConfig()
        assertEquals(8_000L, normal.initialNoSpeechTimeoutMs)
        assertEquals(2_500L, normal.silenceAfterSpeechMs)

        val patient = SpeechPatience.PATIENT.voiceActivityConfig()
        assertEquals(15_000L, patient.initialNoSpeechTimeoutMs)
        assertEquals(4_000L, patient.silenceAfterSpeechMs)
    }

    @Test
    fun verifyAndroidSilenceMsIsUpperBound() {
        assertEquals(7_000L, SpeechPatience.QUICK.androidSilenceMs)
        assertEquals(10_000L, SpeechPatience.NORMAL.androidSilenceMs)
        assertEquals(17_000L, SpeechPatience.PATIENT.androidSilenceMs)
    }
}
