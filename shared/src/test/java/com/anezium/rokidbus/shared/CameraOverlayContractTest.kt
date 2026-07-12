package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOverlayContractTest {
    @Test
    fun `structured normalized overlay parses`() {
        val payload = JSONObject()
            .put("version", 1)
            .put("sessionId", "session")
            .put("seq", 9)
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("text", "Bonjour")
                        .put("role", "translation")
                        .put(
                            "box",
                            JSONObject()
                                .put("left", 0.1)
                                .put("top", 0.2)
                                .put("right", 0.8)
                                .put("bottom", 0.4),
                        ),
                ),
            )
        val parsed = CameraOverlayContract.parse(payload, requireRequestId = false)!!
        assertEquals("session", parsed.sessionId)
        assertEquals(9L, parsed.seq)
        assertEquals("Bonjour", parsed.items.single().text)
    }

    @Test
    fun `out of range box is rejected`() {
        val payload = JSONObject()
            .put("sessionId", "session")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("text", "bad")
                        .put("role", "source")
                        .put("box", JSONArray().put(-0.1).put(0).put(1).put(1)),
                ),
            )
        assertNull(CameraOverlayContract.parse(payload, requireRequestId = false))
    }
}
