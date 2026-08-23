package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WidgetStateMachineTest {
    @Before
    fun reset() {
        WidgetStateMachine.resetSequencesForTest()
    }
    private fun show(seq: Long, contentKey: String = "track", hold: Boolean = false): BusEnvelope =
        BusEnvelope(
            path = BusPaths.WIDGET_SHOW,
            payload = JSONObject()
                .put("surfaceId", "widget")
                .put("seq", seq)
                .put("kind", "widget")
                .put("contentKey", contentKey)
                .put("lines", JSONArray()
                    .put(JSONObject().put("timeMs", 0).put("text", "a"))
                    .put(JSONObject().put("timeMs", 1_000).put("text", "b")))
                .put("anchor", JSONObject()
                    .put("positionMs", 10)
                    .put("playing", true)
                    .put("sentAtElapsedRealtime", 1000))
                .apply { if (hold) put("holdDisplay", true) },
        )

    private fun anchorUpdate(seq: Long, contentKey: String = "track"): BusEnvelope =
        BusEnvelope(
            path = BusPaths.WIDGET_UPDATE,
            payload = JSONObject()
                .put("surfaceId", "widget")
                .put("seq", seq)
                .put("kind", "widget")
                .put("contentKey", contentKey)
                .put("anchor", JSONObject()
                    .put("positionMs", 500)
                    .put("playing", false)
                    .put("sentAtElapsedRealtime", 2000)),
        )

    private fun hide(seq: Long): BusEnvelope =
        BusEnvelope(
            path = BusPaths.WIDGET_HIDE,
            payload = JSONObject().put("surfaceId", "widget").put("seq", seq).put("kind", "widget"),
        )

    @Test
    fun `show installs a clock and carries karaoke mode`() {
        WidgetStateMachine.handleWidgetEnvelope(show(seq = 1, hold = true))
        assertNotNull(WidgetStateMachine.clock)
        assertTrue(WidgetStateMachine.holdDisplay)
        WidgetStateMachine.clear()
        assertNull(WidgetStateMachine.clock)
        assertFalse(WidgetStateMachine.holdDisplay)
    }

    @Test
    fun `anchor update advances the clock and keeps karaoke mode`() {
        WidgetStateMachine.handleWidgetEnvelope(show(seq = 1, hold = true))
        WidgetStateMachine.handleWidgetEnvelope(anchorUpdate(seq = 2, contentKey = "track"))
        val clock = WidgetStateMachine.clock!!
        // Anchor is paused at 500 ms -> still line index 0, frozen.
        assertEquals(0, clock.currentIndexAt(2_000))
        assertTrue(WidgetStateMachine.holdDisplay)
        WidgetStateMachine.clear()
    }

    @Test
    fun `stale sequences are dropped`() {
        WidgetStateMachine.handleWidgetEnvelope(show(seq = 5))
        assertNotNull(WidgetStateMachine.clock)
        WidgetStateMachine.handleWidgetEnvelope(show(seq = 4))
        assertNotNull(WidgetStateMachine.clock) // unchanged
        WidgetStateMachine.handleWidgetEnvelope(anchorUpdate(seq = 4))
        assertNotNull(WidgetStateMachine.clock)
        WidgetStateMachine.clear()
    }

    @Test
    fun `hide clears the clock`() {
        WidgetStateMachine.handleWidgetEnvelope(show(seq = 1))
        WidgetStateMachine.handleWidgetEnvelope(hide(seq = 2))
        assertNull(WidgetStateMachine.clock)
        assertFalse(WidgetStateMachine.holdDisplay)
        assertNull(WidgetStateMachine.currentContentKey())
    }

    @Test
    fun `only widget paths are handled`() {
        assertFalse(WidgetStateMachine.handleWidgetEnvelope(BusEnvelope(path = "/pin/show", payload = JSONObject())))
        assertTrue(WidgetStateMachine.handleWidgetEnvelope(show(0)))
        WidgetStateMachine.clear()
    }

}
