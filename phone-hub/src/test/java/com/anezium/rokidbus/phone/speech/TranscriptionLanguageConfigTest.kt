package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptionLanguageConfigTest {
    @Test
    fun russianUsesProviderCodesAndAnAndroidLocaleTag() {
        val language = TranscriptionLanguage.fromId("ru")

        assertSame(TranscriptionLanguage.RUSSIAN, language)
        assertEquals("ru", language.openAiCode)
        assertEquals("ru", language.elevenLabsCode)
        assertEquals("ru-RU", language.azureLocale)
        assertEquals(listOf("ru-RU"), language.androidTagChain())
    }

    @Test
    fun unknownLanguageFallsBackToAutoAndKnownIdsRoundTrip() {
        assertSame(TranscriptionLanguage.AUTO, TranscriptionLanguage.fromId(null))
        assertSame(TranscriptionLanguage.AUTO, TranscriptionLanguage.fromId("unknown"))
        TranscriptionLanguage.values().forEach { language ->
            assertSame(language, TranscriptionLanguage.fromId(" ${language.id.uppercase()} "))
        }
    }

    @Test
    fun cantoneseUsesPromptOnlyForOpenAiAndProviderSpecificCodes() {
        val language = TranscriptionLanguage.CANTONESE

        assertNull(language.openAiCode)
        assertEquals("廣東話語音。請用繁體中文轉寫。", language.openAiPrompt)
        assertEquals("yue", language.elevenLabsCode)
        assertEquals("zh-HK", language.azureLocale)
        assertEquals(listOf("yue-Hant-HK", "yue-HK", "zh-HK"), language.androidTagChain())
    }

    @Test
    fun chineseScriptSteeringPromptsArePreserved() {
        assertEquals(
            "請使用繁體中文。",
            TranscriptionLanguage.CHINESE_TRADITIONAL.openAiPrompt,
        )
        assertEquals(
            "请使用简体中文。",
            TranscriptionLanguage.CHINESE_SIMPLIFIED.openAiPrompt,
        )
    }

    @Test
    fun everyForcedLanguageHasAProviderSteeringValue() {
        TranscriptionLanguage.values()
            .filterNot { it == TranscriptionLanguage.AUTO }
            .forEach { language ->
                assertTrue(
                    language.openAiCode != null || language.openAiPrompt != null,
                )
                assertFalse(language.elevenLabsCode.isNullOrBlank())
                assertFalse(language.azureLocale.isNullOrBlank())
            }
    }
}
