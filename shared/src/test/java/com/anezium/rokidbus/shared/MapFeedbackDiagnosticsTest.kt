package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapFeedbackDiagnosticsTest {
    @Test
    fun `repeated verdicts aggregate counts without hiding a different rejection`() {
        var now = 0L
        val lines = mutableListOf<String>()
        val diagnostics = MapFeedbackDiagnostics({ now }, lines::add)
        repeat(200) { diagnostics.record(Gate.PHONE_AMBIENT, Verdict.PASS, Reason.CURRENT) }
        diagnostics.record(Gate.PHONE_AMBIENT, Verdict.DROP, Reason.SEQUENCE)
        repeat(200) { diagnostics.record(Gate.PHONE_AMBIENT, Verdict.DROP, Reason.SEQUENCE) }
        assertEquals(2, lines.size)
        now = 5_000L
        diagnostics.record(Gate.PHONE_AMBIENT, Verdict.PASS, Reason.CURRENT)
        diagnostics.record(Gate.PHONE_AMBIENT, Verdict.DROP, Reason.SEQUENCE)
        assertEquals(4, lines.size)
        assertTrue(lines[2].contains("count=201 suppressed=199"))
        assertTrue(lines[3].contains("count=202 suppressed=200"))
    }

    @Test
    fun `wire data can contribute only numeric fields and fixed kind and view enums`() {
        val privateValue = "private-coordinate-tile-street-token\nInjected"
        val payload = JSONObject().put("kind", "page").put("view", "follow").put("revision", 7)
            .put("seq", 3).put("visible", true)
        for (key in listOf("id", "pageId", "requestId", "pluginId", "token", "geometry")) payload.put(key, privateValue)
        val lines = mutableListOf<String>()
        val diagnostics = MapFeedbackDiagnostics({ 0 }, lines::add)
        diagnostics.record(Gate.SDK_DELIVERY, Verdict.PASS, Reason.CURRENT, MapFeedbackDiagnostics.Sample.from(payload))
        payload.put("kind", privateValue).put("view", privateValue).put("revision", privateValue)
        diagnostics.record(Gate.SDK_DELIVERY, Verdict.DROP, Reason.MALFORMED, MapFeedbackDiagnostics.Sample.from(payload))
        assertEquals(2, lines.size)
        assertTrue(lines.first().contains("kind=PAGE view=FOLLOW revision=7"))
        assertTrue(lines.last().contains("kind=UNKNOWN view=UNKNOWN revision=-1"))
        assertTrue(lines.all { it.startsWith("NexusMapDiag gate=") })
        assertFalse(lines.joinToString().contains(privateValue))
        assertFalse(lines.any { '\n' in it })
    }
}
