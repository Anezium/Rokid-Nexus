package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
                        .put("id", "track-7")
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
        assertEquals("track-7", parsed.items.single().id)
    }

    @Test
    fun `paragraph layout metadata parses without changing payload version`() {
        val layout = CameraOverlayContract.parse(
            payload(item = validItem().put(
                "layout",
                JSONObject()
                    .put("kind", "paragraph")
                    .put("version", 1)
                    .put("medianLineHeight", 0.032)
                    .put("growDown", 0.075)
                    .put("column", 2),
            )),
            requireRequestId = false,
        )!!.items.single().layout

        assertNotNull(layout)
        assertEquals("paragraph", layout!!.kind)
        assertEquals(1, layout.version)
        assertEquals(0.032f, layout.medianLineHeight, 0f)
        assertEquals(0.075f, layout.growDown, 0f)
        assertEquals(2, layout.column)
        assertEquals(1, CameraOverlayContract.VERSION)
    }

    @Test
    fun `unknown or malformed optional layout falls back to legacy item`() {
        val invalidLayouts = listOf(
            JSONObject().put("kind", "line").put("version", 1)
                .put("medianLineHeight", 0.03).put("growDown", 0.1),
            JSONObject().put("kind", "paragraph").put("version", 2)
                .put("medianLineHeight", 0.03).put("growDown", 0.1),
            JSONObject().put("kind", "paragraph").put("version", 1.5)
                .put("medianLineHeight", 0.03).put("growDown", 0.1),
            JSONObject().put("kind", "paragraph").put("version", 1)
                .put("medianLineHeight", "0.03").put("growDown", 0.1),
            JSONObject().put("kind", "paragraph").put("version", 1)
                .put("medianLineHeight", 0).put("growDown", 0.1),
            JSONObject().put("kind", "paragraph").put("version", 1)
                .put("medianLineHeight", 0.03).put("growDown", -0.1),
            JSONObject().put("kind", "paragraph").put("version", 1)
                .put("medianLineHeight", 0.03).put("growDown", 0.1)
                .put("column", CameraOverlayContract.MAX_LAYOUT_COLUMN + 1),
        )

        invalidLayouts.forEach { invalid ->
            val parsed = CameraOverlayContract.parse(
                payload(item = validItem().put("layout", invalid)),
                requireRequestId = false,
            )
            assertNotNull(parsed)
            assertNull(parsed!!.items.single().layout)
            assertEquals("legacy", parsed.items.single().text)
        }
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

    @Test
    fun `id stays optional for version one items`() {
        val payload = JSONObject()
            .put("version", 1)
            .put("sessionId", "session")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("text", "legacy")
                        .put("role", "source")
                        .put("box", JSONArray().put(0).put(0).put(1).put(1)),
                ),
            )
        assertNull(CameraOverlayContract.parse(payload, requireRequestId = false)!!.items.single().id)
    }

    @Test
    fun `id longer than contract cap is rejected`() {
        val payload = JSONObject()
            .put("sessionId", "session")
            .put(
                "items",
                JSONArray().put(
                    JSONObject()
                        .put("id", "x".repeat(CameraOverlayContract.MAX_ID_CHARS + 1))
                        .put("text", "bad")
                        .put("role", "source")
                        .put("box", JSONArray().put(0).put(0).put(1).put(1)),
                ),
            )
        assertNull(CameraOverlayContract.parse(payload, requireRequestId = false))
    }

    private fun payload(item: JSONObject): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("sessionId", "session")
            .put("items", JSONArray().put(item))

    private fun validItem(): JSONObject =
        JSONObject()
            .put("text", "legacy")
            .put("role", "source")
            .put("box", JSONArray().put(0).put(0).put(1).put(1))
}
