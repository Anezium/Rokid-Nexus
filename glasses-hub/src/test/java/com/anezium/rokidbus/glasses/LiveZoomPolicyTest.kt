package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZoomPolicyTest {
    @Test
    fun `zoom steps clamp and crop centrally`() {
        assertEquals(LiveZoomLevel.TWO, LiveZoomPolicy.zoomIn(LiveZoomLevel.TWO))
        assertEquals(LiveZoomLevel.ONE, LiveZoomPolicy.zoomOut(LiveZoomLevel.ONE))
        val crop = LiveZoomPolicy.centerCrop(720, 1280, LiveZoomLevel.TWO)
        assertEquals(360, crop.width)
        assertEquals(640, crop.height)
        assertEquals(180, crop.left)
        assertEquals(320, crop.top)
    }
}
