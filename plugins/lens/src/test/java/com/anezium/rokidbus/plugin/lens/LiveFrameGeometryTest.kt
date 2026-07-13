package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveFrameGeometryTest {
    @Test
    fun `ML Kit rotation uses the landscape display coordinate frame`() {
        assertEquals(
            OrientedLiveFrameSize(1280, 720),
            LiveFrameGeometry.orientedSize(720, 1280, 270),
        )
    }

    @Test
    fun `zero rotation preserves decoder dimensions`() {
        assertEquals(OrientedLiveFrameSize(720, 1280), LiveFrameGeometry.orientedSize(720, 1280, 0))
    }
}
