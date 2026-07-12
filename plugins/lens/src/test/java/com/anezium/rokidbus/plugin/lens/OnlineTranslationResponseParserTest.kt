package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class OnlineTranslationResponseParserTest {
    @Test
    fun `parses Google nested segments and detected language`() {
        val response = """
            [[["Bon", "Good", null, null, 10], ["jour", " morning", null, null, 10]], null, "en"]
        """.trimIndent()

        val parsed = OnlineTranslationResponseParser.parseGoogle(response)

        assertEquals("Bonjour", parsed.dst)
        assertEquals("en", parsed.srcLang)
    }

    @Test
    fun `parses DeepL translations in response order`() {
        val response = """
            {
              "translations": [
                {"detected_source_language": "EN", "text": "Bonjour"},
                {"detected_source_language": "DE", "text": "SÃ©curitÃ©"}
              ]
            }
        """.trimIndent()

        val parsed = OnlineTranslationResponseParser.parseDeepL(response, expectedCount = 2)

        assertEquals(listOf("Bonjour", "SÃ©curitÃ©"), parsed.map { it.dst })
        assertEquals(listOf("en", "de"), parsed.map { it.srcLang })
    }

    @Test
    fun `parses strict Gemini JSON reply`() {
        val response = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "[{\"dst\":\"Bonjour\",\"srcLang\":\"en\"},{\"dst\":\"SÃ©curitÃ© Gerber\",\"srcLang\":\"en\"}]"
                  }]
                }
              }]
            }
        """.trimIndent()

        val parsed = OnlineTranslationResponseParser.parseGemini(response, expectedCount = 2)

        assertEquals(listOf("Bonjour", "SÃ©curitÃ© Gerber"), parsed.map { it.dst })
        assertEquals(listOf("en", "en"), parsed.map { it.srcLang })
    }

    @Test
    fun `rejects Gemini reply wrapped in markdown fences`() {
        val response = """
            {
              "candidates": [{
                "content": {
                  "parts": [{
                    "text": "```json\n[{\"dst\":\"Bonjour\",\"srcLang\":\"en\"}]\n```"
                  }]
                }
              }]
            }
        """.trimIndent()

        assertThrows(TranslationResponseParseException::class.java) {
            OnlineTranslationResponseParser.parseGemini(response, expectedCount = 1)
        }
    }
}

