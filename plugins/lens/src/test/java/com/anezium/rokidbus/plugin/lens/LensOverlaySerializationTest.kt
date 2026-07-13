package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class LensOverlaySerializationTest {
    @Test
    fun `live paragraph layout serializes compact metadata without line arrays`() {
        val json = paragraphLayoutJson(
            LiveOverlayParagraphLayout(
                medianLineHeight = 0.032f,
                growDown = 0.075f,
                column = 2,
            ),
        )

        assertEquals("paragraph", json.getString("kind"))
        assertEquals(1, json.getInt("version"))
        assertEquals(0.032, json.getDouble("medianLineHeight"), 0.000001)
        assertEquals(0.075, json.getDouble("growDown"), 0.000001)
        assertEquals(2, json.getInt("column"))
        assertFalse(json.has("lines"))
    }

    @Test
    fun `frozen paragraph layout normalizes portrait metrics and omits invalid column`() {
        val json = normalizedParagraphLayoutJson(
            medianLineHeight = 64f,
            growDown = 150f,
            frameHeight = 2_000,
            column = -1,
        )

        assertEquals(0.032, json.getDouble("medianLineHeight"), 0.000001)
        assertEquals(0.075, json.getDouble("growDown"), 0.000001)
        assertFalse(json.has("column"))
        assertFalse(json.has("lines"))
    }
}
