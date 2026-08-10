package com.anezium.rokidbus.phone

import java.util.Locale

/** Same-process API for the phone TTS settings UI. */
object PhoneTtsUiApi {
    fun availableVoices(locale: Locale? = null): List<PhoneTtsVoiceOption> =
        BusHubService.availablePhoneTtsVoices(locale)

    fun speakSample(text: String, locale: Locale = Locale.getDefault()): Boolean =
        BusHubService.speakPhoneTtsSample(text, locale)
}
