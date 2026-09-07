package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSurfaceContractTest {
    private fun line(timeMs: Long, text: String): JSONObject =
        JSONObject().put("timeMs", timeMs).put("text", text)

    @Test
    fun `validates a full show with timed lines and anchor`() {
        val payload = JSONObject()
            .put("kind", "widget")
            .put("contentKey", "track-42")
            .put("lines", JSONArray().put(line(0, "line one")).put(line(1_000, "line two")))
            .put("anchor", JSONObject().put("positionMs", 500).put("playing", true).put("sentAtElapsedRealtime", 1000))

        val result = WidgetSurfaceContract.validateShow(payload)
        assertTrue(result is WidgetSurfaceValidationResult.Valid)
        val content = (result as WidgetSurfaceValidationResult.Valid).content
        assertEquals("track-42", content.contentKey)
        assertEquals(listOf(WidgetTimedLine(0, "line one"), WidgetTimedLine(1_000, "line two")), content.lines)
        assertEquals(WidgetAnchor(500, true, 1000), content.anchor)
    }

    @Test
    fun `anchor update rejects missing lines and keeps no line content`() {
        val payload = JSONObject()
            .put("kind", "widget")
            .put("contentKey", "track-42")
            .put("anchor", JSONObject().put("positionMs", 0).put("playing", false).put("sentAtElapsedRealtime", 0))

        val result = WidgetSurfaceContract.validateAnchorUpdate(payload)
        assertTrue(result is WidgetSurfaceValidationResult.Valid)
        val content = (result as WidgetSurfaceValidationResult.Valid).content
        assertTrue(content.lines.isEmpty())
    }

    @Test
    fun `karaoke mode is carried as holdDisplay and defaults to false`() {
        val off = WidgetSurfaceContract.validateShow(
            JSONObject().put("kind", "widget").put("contentKey", "t")
                .put("lines", JSONArray().put(line(0, "a")))
                .put("anchor", anchorConfig()),
        )
        assertFalse((off as WidgetSurfaceValidationResult.Valid).content.holdDisplay)

        val on = WidgetSurfaceContract.validateShow(
            JSONObject().put("kind", "widget").put("contentKey", "t").put("holdDisplay", true)
                .put("lines", JSONArray().put(line(0, "a")))
                .put("anchor", anchorConfig()),
        )
        assertTrue((on as WidgetSurfaceValidationResult.Valid).content.holdDisplay)
    }

    private fun anchorConfig(): JSONObject =
        JSONObject().put("positionMs", 0).put("playing", true).put("sentAtElapsedRealtime", 0)

    @Test
    fun `cycles through to payload`() {
        val content = WidgetSurfaceContent(
            contentKey = "track-42",
            lines = listOf(WidgetTimedLine(0, "a"), WidgetTimedLine(900, "b")),
            anchor = WidgetAnchor(100, true, 2000),
        )
        val payload = WidgetSurfaceContract.toPayload(WidgetSurfaceContract.LOCAL_SURFACE_ID, content)
        assertFalse(payload.has("size"))
        val roundTrip = WidgetSurfaceContract.validateShow(payload)
        assertTrue(roundTrip is WidgetSurfaceValidationResult.Valid)
        assertEquals(content, (roundTrip as WidgetSurfaceValidationResult.Valid).content)
    }

    @Test
    fun `rejects malformed payloads`() {
        val invalid = listOf(
            JSONObject().put("kind", "pin").put("contentKey", "x").put("anchor", anchor()),
            JSONObject().put("kind", "widget").put("contentKey", "").put("anchor", anchor()),
            JSONObject().put("kind", "widget").put("contentKey", "x").put("lines", JSONArray()).put("anchor", anchor()),
            JSONObject().put("kind", "widget").put("contentKey", "x")
                .put("lines", JSONArray().put(line(0, "a"))).put("anchor", JSONObject().put("positionMs", -1)),
            JSONObject().put("kind", "widget").put("contentKey", "x")
                .put("lines", JSONArray().put(line(0, "a"))).put("anchor", JSONObject().put("playing", true)),
        )
        invalid.forEach { assertTrue(it.toString(), WidgetSurfaceContract.validateShow(it) is WidgetSurfaceValidationResult.Invalid) }
    }

    private fun anchor(): JSONObject =
        JSONObject().put("positionMs", 0).put("playing", true).put("sentAtElapsedRealtime", 0)
}