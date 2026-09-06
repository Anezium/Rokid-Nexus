package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EditableSurfaceContractTest {
    @Test
    fun `committed text at exactly the limit is kept whole`() {
        val atLimit = "a".repeat(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH)

        val payload = EditableSurfaceContract.committedPayload("s", atLimit, cancelled = false)

        assertEquals(atLimit, payload.getString("text"))
        assertEquals(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH, payload.getString("text").length)
    }

    @Test
    fun `committed text one code unit over the limit is truncated, not rejected`() {
        val overLimit = "a".repeat(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH + 1)

        val payload = EditableSurfaceContract.committedPayload("s", overLimit, cancelled = false)

        assertEquals(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH, payload.getString("text").length)
        assertEquals(overLimit.take(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH), payload.getString("text"))
    }

    @Test
    fun `a cancelled commit carries no text field regardless of length`() {
        val overLimit = "a".repeat(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH + 1)

        val payload = EditableSurfaceContract.committedPayload("s", overLimit, cancelled = true)

        assertFalse(payload.has("text"))
        assertTrue(payload.getBoolean("cancelled"))
    }

    @Test
    fun `parseCommitted also clamps an over-limit text field from an untrusted payload`() {
        val overLimit = "a".repeat(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH + 50)
        val payload = JSONObject()
            .put("surfaceId", "relay:reply")
            .put("cancelled", false)
            .put("text", overLimit)

        val result = EditableSurfaceContract.parseCommitted(payload)

        assertEquals(EditableSurfaceContract.MAX_TEXT_UTF16_LENGTH, result?.text?.length)
    }
}
