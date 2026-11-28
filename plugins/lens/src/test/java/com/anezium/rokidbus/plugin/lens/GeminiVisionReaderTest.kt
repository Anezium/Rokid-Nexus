package com.anezium.rokidbus.plugin.lens

import java.net.SocketTimeoutException
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiVisionReaderTest {
    private fun geminiResponse(reply: String, vararg extraParts: JSONObject): String =
        JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject().put(
                        "content",
                        JSONObject().put(
                            "parts",
                            JSONArray().apply {
                                extraParts.forEach(::put)
                                put(JSONObject().put("text", reply))
                            },
                        ),
                    ),
                ),
            )
            .toString()

    @Test
    fun `maps normalized box_2d onto the frozen bitmap`() {
        val reply = """
            [{"text":"สวัสดี","translation":"Hello","srcLang":"TH",
              "lines":2,"box_2d":[100,250,300,750]}]
        """.trimIndent()

        val parsed = GeminiVisionResponseParser.parse(geminiResponse(reply), width = 800, height = 600)

        assertEquals(1, parsed.size)
        val paragraph = parsed.single()
        assertEquals("สวัสดี", paragraph.source)
        assertEquals("Hello", paragraph.translation)
        assertEquals("th", paragraph.srcLang)
        assertEquals(2, paragraph.lineCount)
        assertEquals(FrozenLayoutRect(left = 200, top = 60, right = 600, bottom = 180), paragraph.bounds)
    }

    @Test
    fun `drops malformed paragraphs but keeps the valid ones`() {
        val reply = """
            [
              {"text":"ok","translation":"ok fr","box_2d":[0,0,500,500]},
              {"text":"   ","translation":"blank","box_2d":[0,0,500,500]},
              {"text":"inverted","translation":"x","box_2d":[500,500,100,100]},
              {"text":"short box","translation":"x","box_2d":[0,0,500]},
              {"text":"no box","translation":"x"},
              {"text":"string box","translation":"x","box_2d":["a",0,1,1]},
              "not an object"
            ]
        """.trimIndent()

        val parsed = GeminiVisionResponseParser.parse(geminiResponse(reply), width = 1000, height = 1000)

        assertEquals(listOf("ok"), parsed.map { it.source })
    }

    @Test
    fun `clamps out of range coordinates and collapses whitespace`() {
        val reply = """[{"text":"two\n  lines","translation":"deux lignes","box_2d":[-20,-5,1200,1001]}]"""

        val paragraph = GeminiVisionResponseParser.parse(geminiResponse(reply), width = 400, height = 200).single()

        assertEquals("two lines", paragraph.source)
        assertEquals(FrozenLayoutRect(0, 0, 400, 200), paragraph.bounds)
        assertEquals(1, paragraph.lineCount)
    }

    @Test
    fun `skips thought parts before the JSON reply`() {
        val thought = JSONObject().put("text", "Let me look at the sign.").put("thought", true)
        val reply = """[{"text":"Exit","translation":"Sortie","box_2d":[0,0,10,10]}]"""

        val parsed = GeminiVisionResponseParser.parse(geminiResponse(reply, thought), width = 100, height = 100)

        assertEquals(listOf("Exit"), parsed.map { it.source })
    }

    @Test
    fun `an empty array means no legible text`() {
        assertTrue(GeminiVisionResponseParser.parse(geminiResponse("[]"), 100, 100).isEmpty())
    }

    @Test
    fun `reports why Gemini returned nothing usable`() {
        val blocked = """{"promptFeedback":{"blockReason":"SAFETY"}}"""
        val noParts = """{"candidates":[{"finishReason":"MAX_TOKENS","content":{}}]}"""

        assertEquals(
            "blocked_safety",
            assertThrows(GeminiVisionException::class.java) {
                GeminiVisionResponseParser.parse(blocked, 100, 100)
            }.reason,
        )
        assertEquals(
            "max_tokens",
            assertThrows(GeminiVisionException::class.java) {
                GeminiVisionResponseParser.parse(noParts, 100, 100)
            }.reason,
        )
        assertEquals(
            "malformed",
            assertThrows(GeminiVisionException::class.java) {
                GeminiVisionResponseParser.parse(geminiResponse("```json\n[]\n```"), 100, 100)
            }.reason,
        )
    }

    @Test
    fun `names a token cutoff even when partial text came back`() {
        val truncated = JSONObject()
            .put(
                "candidates",
                JSONArray().put(
                    JSONObject()
                        .put("finishReason", "MAX_TOKENS")
                        .put(
                            "content",
                            JSONObject().put("parts", JSONArray().put(JSONObject().put("text", "[{\"text\":\"Ex"))),
                        ),
                ),
            )
            .toString()

        assertEquals(
            "max_tokens",
            assertThrows(GeminiVisionException::class.java) {
                GeminiVisionResponseParser.parse(truncated, 100, 100)
            }.reason,
        )
    }

    @Test
    fun `caps the paragraph count`() {
        val entries = JSONArray().apply {
            repeat(GEMINI_VISION_MAX_PARAGRAPHS + 5) { index ->
                put(
                    JSONObject()
                        .put("text", "line $index")
                        .put("translation", "ligne $index")
                        .put("box_2d", JSONArray(listOf(0, 0, 10, 10))),
                )
            }
        }

        val parsed = GeminiVisionResponseParser.parse(geminiResponse(entries.toString()), 100, 100)

        assertEquals(GEMINI_VISION_MAX_PARAGRAPHS, parsed.size)
    }

    @Test
    fun `request carries the image inline and never the API key`() {
        val jpeg = byteArrayOf(1, 2, 3, 4)

        val body = JSONObject(String(buildGeminiVisionRequestBody(jpeg, "en"), Charsets.UTF_8))

        val parts = body.getJSONArray("contents").getJSONObject(0).getJSONArray("parts")
        val inline = parts.getJSONObject(0).getJSONObject("inline_data")
        assertEquals("image/jpeg", inline.getString("mime_type"))
        assertEquals(Base64.getEncoder().encodeToString(jpeg), inline.getString("data"))
        assertTrue(parts.getJSONObject(1).getString("text").contains("\"en\""))
        val config = body.getJSONObject("generationConfig")
        assertEquals("application/json", config.getString("response_mime_type"))
        assertEquals("ARRAY", config.getJSONObject("response_schema").getString("type"))
        assertFalse(body.toString().contains("key"))
    }

    @Test
    fun `maps HTTP failures to short log reasons`() {
        assertEquals("quota", geminiVisionHttpReason(429))
        assertEquals("key_rejected", geminiVisionHttpReason(403))
        assertEquals("server_503", geminiVisionHttpReason(503))
    }

    @Test
    fun `retries on the rescue model only when the chosen one is gone or overloaded`() {
        assertTrue(geminiVisionRetriesOnRescue(GeminiVisionException("model_unavailable")))
        assertTrue(geminiVisionRetriesOnRescue(GeminiVisionException("server_503")))
        assertTrue(geminiVisionRetriesOnRescue(SocketTimeoutException()))
        assertFalse(geminiVisionRetriesOnRescue(GeminiVisionException("quota")))
        assertFalse(geminiVisionRetriesOnRescue(GeminiVisionException("key_rejected")))
        assertFalse(geminiVisionRetriesOnRescue(GeminiVisionException(GEMINI_VISION_SKIPPED)))
        assertFalse(geminiVisionRetriesOnRescue(GeminiVisionException("canceled")))
        assertTrue(geminiStatusRetriesOnRescue(404))
        assertTrue(geminiStatusRetriesOnRescue(503))
        assertFalse(geminiStatusRetriesOnRescue(429))
        assertFalse(geminiStatusRetriesOnRescue(403))
        assertTrue(LENS_GEMINI_MODEL_DEFAULT in LENS_GEMINI_MODELS)
    }

    @Test
    fun `overlay keeps untranslated paragraphs as source fallbacks`() {
        val bounds = FrozenLayoutRect(0, 0, 10, 10)
        val overlay = geminiVisionOverlay(
            listOf(
                GeminiVisionParagraph("Thai", "Translated", "th", bounds, 1),
                GeminiVisionParagraph("Untranslated", " ", "th", bounds, 1),
                GeminiVisionParagraph("Thai", "Second reading ignored", "th", bounds, 1),
            ),
        )

        assertEquals(3, overlay.paragraphs.size)
        assertEquals("Translated", overlay.translations.getValue("Thai").dst)
        assertFalse(overlay.translations.getValue("Thai").fallback)
        val fallback = overlay.translations.getValue("Untranslated")
        assertTrue(fallback.fallback)
        assertEquals("Untranslated", fallback.dst)
        assertEquals(TranslationEngine.GEMINI, fallback.engine)
    }

    @Test
    fun `readable scripts caption names every on-device recognizer and the Gemini setting`() {
        val caption = lensReadableScriptsCaption()

        listOf("Latin-alphabet languages", "Japanese", "Chinese", "Korean", "Devanagari", "Thai")
            .forEach { assertTrue(it, caption.contains(it)) }
        assertTrue(caption.contains(GEMINI_VISION_SETTING_LABEL))
    }
}
