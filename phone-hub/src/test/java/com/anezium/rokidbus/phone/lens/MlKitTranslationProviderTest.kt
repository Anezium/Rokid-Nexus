package com.anezium.rokidbus.phone.lens

import com.google.mlkit.nl.translate.TranslateLanguage
import org.junit.Assert.assertEquals
import org.junit.Test

class MlKitTranslationProviderTest {
    @Test
    fun `recognizer mode supplies fallback source language`() {
        val expected = mapOf(
            LensRecognizerMode.LATIN to TranslateLanguage.ENGLISH,
            LensRecognizerMode.JAPANESE to TranslateLanguage.JAPANESE,
            LensRecognizerMode.CHINESE to TranslateLanguage.CHINESE,
            LensRecognizerMode.KOREAN to TranslateLanguage.KOREAN,
            LensRecognizerMode.DEVANAGARI to TranslateLanguage.HINDI,
        )

        expected.forEach { (mode, language) ->
            assertEquals(mode.name, language, mlKitSourceLanguage(null, mode))
            assertEquals(mode.name, language, mlKitSourceLanguage("und", mode))
        }
    }

    @Test
    fun `language identification still wins over recognizer fallback`() {
        assertEquals(
            TranslateLanguage.KOREAN,
            mlKitSourceLanguage("ko", LensRecognizerMode.CHINESE),
        )
    }
}
