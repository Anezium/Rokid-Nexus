package com.anezium.rokidbus.glasses

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TimedLinesLayoutTest {
    @Test
    fun `absent or unknown layout reads as centered normal`() {
        val plain = NexusSurface.fromPayload(timedPayload())
        assertEquals(TimedPlacement.CENTER, plain.timedPlacement)
        assertEquals(TimedTextSize.NORMAL, plain.timedTextSize)

        val future = NexusSurface.fromPayload(
            timedPayload().put("placement", "future").put("textSize", "huge"),
        )
        assertEquals(TimedPlacement.CENTER, future.timedPlacement)
        assertEquals(TimedTextSize.NORMAL, future.timedTextSize)
    }

    @Test
    fun `full payload states its layout`() {
        val surface = NexusSurface.fromPayload(
            timedPayload().put("placement", "top").put("textSize", "small"),
        )
        assertEquals(TimedPlacement.TOP, surface.timedPlacement)
        assertEquals(TimedTextSize.SMALL, surface.timedTextSize)
    }

    @Test
    fun `anchor-only update inherits layout but a full payload resets it`() {
        val previous = NexusSurface.fromPayload(
            timedPayload().put("placement", "top").put("textSize", "large"),
        )
        val anchorOnly = JSONObject()
            .put("surfaceId", "lyrics")
            .put("kind", "timed-lines")
            .put("contentKey", "track-1")
            .put("anchor", JSONObject().put("positionMs", 500).put("playing", true))
        val merged = NexusSurface.fromPayload(anchorOnly, previous)
        assertEquals(TimedPlacement.TOP, merged.timedPlacement)
        assertEquals(TimedTextSize.LARGE, merged.timedTextSize)

        val reset = NexusSurface.fromPayload(timedPayload(), previous)
        assertEquals(TimedPlacement.CENTER, reset.timedPlacement)
        assertEquals(TimedTextSize.NORMAL, reset.timedTextSize)
    }

    @Test
    fun `other kinds never carry a timed layout`() {
        val card = NexusSurface.fromPayload(
            JSONObject()
                .put("surfaceId", "lyrics")
                .put("kind", "card")
                .put("title", "Lyrics")
                .put("lines", JSONArray().put("No timed lyrics found."))
                .put("placement", "top")
                .put("textSize", "large"),
        )
        assertEquals(TimedPlacement.CENTER, card.timedPlacement)
        assertEquals(TimedTextSize.NORMAL, card.timedTextSize)
    }

    @Test
    fun `normal size keeps the historical lyric sizes`() {
        assertEquals(TimedTextSizes(25, 14, 15f, 17f), TimedTextSize.NORMAL.sizes())
    }

    @Test
    fun `fit shrinks until the line budget holds and stops at the minimum`() {
        // A line that wraps to 8 lines at 25 sp and loses one line per 2 sp.
        val lines = { sp: Int -> 8 - (25 - sp) / 2 }
        assertEquals(19, fitTimedLineSp(25, 14, 5, lines))
        assertEquals(25, fitTimedLineSp(25, 14, 5) { 1 })
        assertEquals(14, fitTimedLineSp(25, 14, 5) { 99 })
    }

    private fun timedPayload(): JSONObject = JSONObject()
        .put("surfaceId", "lyrics")
        .put("kind", "timed-lines")
        .put("title", "Track")
        .put("contentKey", "track-1")
        .put(
            "lines",
            JSONArray()
                .put(JSONObject().put("timeMs", 0).put("text", "First"))
                .put(JSONObject().put("timeMs", 1000).put("text", "Second")),
        )
        .put("anchor", JSONObject().put("positionMs", 0).put("playing", false))
}
