package com.anezium.rokidbus.phone.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LensTranslationResponseTest {
    @Test
    fun `degrades largest translation and preserves remaining valid entries`() {
        val response = fitLensTranslationResponse(
            requestId = "request-1",
            translations = listOf(
                TranslationResult("large source", "x".repeat(2_000), "en"),
                TranslationResult("small source", "petite traduction", "en"),
            ),
            protocolVersion = 1,
            maxPayloadBytes = 500,
        )

        assertNotNull(response)
        val fitted = response!!
        assertEquals(1, fitted.degradedCount)
        val entries = fitted.payload.getJSONArray("translations")
        val degraded = entries.getJSONObject(0)
        assertEquals("large source", degraded.getString("dst"))
        assertTrue(degraded.getBoolean("fallback"))
        assertEquals(TranslationErrorCode.RESPONSE_TOO_LARGE.wireValue, degraded.getString("error"))
        assertFalse(entries.getJSONObject(1).getBoolean("fallback"))
    }

    @Test
    fun `returns null only when the fully degraded payload cannot fit`() {
        val response = fitLensTranslationResponse(
            requestId = "request-2",
            translations = listOf(
                TranslationResult("s".repeat(1_000), "translated", "en"),
            ),
            protocolVersion = 1,
            maxPayloadBytes = 100,
        )

        assertNull(response)
    }
}
