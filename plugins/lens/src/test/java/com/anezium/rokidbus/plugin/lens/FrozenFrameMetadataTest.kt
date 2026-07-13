package com.anezium.rokidbus.plugin.lens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrozenFrameMetadataTest {
    @Test
    fun `producer remaining rotation takes precedence`() {
        assertEquals(
            270,
            frozenRemainingRotation(
                JSONObject()
                    .put("remainingRotationDegrees", 270)
                    .put("rotationDegrees", 270)
                    .put("rotation", 270),
            ),
        )
    }

    @Test
    fun `legacy frozen rotation metadata remains compatible`() {
        assertEquals(90, frozenRemainingRotation(JSONObject().put("rotationDegrees", 90)))
        assertEquals(180, frozenRemainingRotation(JSONObject().put("rotation", 180)))
    }

    @Test
    fun `missing or invalid producer rotation is rejected instead of guessing from exif`() {
        assertNull(frozenRemainingRotation(JSONObject()))
        assertNull(frozenRemainingRotation(JSONObject().put("rotationDegrees", 45)))
        assertNull(frozenRemainingRotation(JSONObject().put("rotationDegrees", "90")))
    }
}
