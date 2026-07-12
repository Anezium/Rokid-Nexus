package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTranslationRequestParserTest {
    @Test
    fun `normalizes and deduplicates strings while preserving envelope id`() {
        val payload = JSONObject()
            .put("id", REQUEST_ID)
            .put("targetLang", "FR")
            .put("mode", "latin")
            .put("strings", JSONArray().put("  hello\n world ").put("hello world").put(" "))

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertTrue(result is LensTranslationParseResult.Success)
        val request = (result as LensTranslationParseResult.Success).request
        assertEquals(REQUEST_ID, request.id)
        assertEquals(LensWireContract.DEFAULT_TARGET_LANG, request.targetLang)
        assertEquals(LensRecognizerMode.LATIN, request.mode)
        assertEquals(listOf("hello world"), request.strings)
    }

    @Test
    fun `uses protocol defaults when optional fields are absent`() {
        val payload = JSONObject().put("strings", JSONArray().put("hello"))

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertTrue(result is LensTranslationParseResult.Success)
        val request = (result as LensTranslationParseResult.Success).request
        assertEquals(REQUEST_ID, request.id)
        assertEquals(LensWireContract.DEFAULT_TARGET_LANG, request.targetLang)
        assertEquals(LensRecognizerMode.LATIN, request.mode)
    }

    @Test
    fun `parses every recognized mode vocabulary value`() {
        LensRecognizerMode.entries.forEach { mode ->
            val payload = JSONObject()
                .put(LensWireContract.RECOGNIZER_MODE_FIELD, mode.wireValue.lowercase())
                .put("strings", JSONArray().put("hello"))

            val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

            assertTrue(mode.name, result is LensTranslationParseResult.Success)
            assertEquals(mode, (result as LensTranslationParseResult.Success).request.mode)
        }
    }

    @Test
    fun `unknown mode falls back to latin for forward compatibility`() {
        val payload = JSONObject()
            .put(LensWireContract.RECOGNIZER_MODE_FIELD, "FUTURE_SCRIPT")
            .put("strings", JSONArray().put("hello"))

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertTrue(result is LensTranslationParseResult.Success)
        assertEquals(
            LensRecognizerMode.LATIN,
            (result as LensTranslationParseResult.Success).request.mode,
        )
    }

    @Test
    fun `rejects payload id that diverges from envelope id`() {
        val payload = JSONObject()
            .put("id", "different-id")
            .put("strings", JSONArray().put("hello"))

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertFailure(result, TranslationErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `rejects non string entries instead of coercing them`() {
        val payload = JSONObject().put("strings", JSONArray().put("hello").put(7))

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertFailure(result, TranslationErrorCode.INVALID_REQUEST)
    }

    @Test
    fun `rejects too many source strings`() {
        val strings = JSONArray()
        repeat(LensWireContract.MAX_STRING_COUNT + 1) { strings.put("text-$it") }
        val payload = JSONObject().put("strings", strings)

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertFailure(result, TranslationErrorCode.REQUEST_TOO_LARGE)
    }

    @Test
    fun `rejects oversized aggregate source text`() {
        val strings = JSONArray()
        repeat(17) { index -> strings.put("$index-${"a".repeat(998)}") }
        val payload = JSONObject().put("strings", strings)

        val result = LensTranslationRequestParser.parse(REQUEST_ID, payload)

        assertFailure(result, TranslationErrorCode.REQUEST_TOO_LARGE)
    }

    @Test
    fun `rejects malformed language and non string mode fields`() {
        val badLanguage = JSONObject()
            .put("targetLang", "not_a_language")
            .put("strings", JSONArray().put("hello"))
        val badMode = JSONObject()
            .put(LensWireContract.RECOGNIZER_MODE_FIELD, 7)
            .put("strings", JSONArray().put("hello"))

        assertFailure(
            LensTranslationRequestParser.parse(REQUEST_ID, badLanguage),
            TranslationErrorCode.INVALID_REQUEST,
        )
        assertFailure(
            LensTranslationRequestParser.parse(REQUEST_ID, badMode),
            TranslationErrorCode.INVALID_REQUEST,
        )
    }

    private fun assertFailure(
        result: LensTranslationParseResult,
        expected: TranslationErrorCode,
    ) {
        assertTrue(result is LensTranslationParseResult.Failure)
        assertEquals(expected, (result as LensTranslationParseResult.Failure).error)
        assertEquals(REQUEST_ID, result.replyId)
    }

    private companion object {
        private const val REQUEST_ID = "4afad91f-731d-4b4e-9ec4-4bbeb4b2f845"
    }
}

