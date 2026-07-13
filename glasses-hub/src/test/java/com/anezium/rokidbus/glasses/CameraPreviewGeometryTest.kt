package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test
    fun `RG raw buffer corners rotate clockwise into the fill center viewport`() {
        val corners = CameraPreviewGeometry.textureDestinationCorners(
            CameraLiveFrameGeometry(720, 1_280, 270),
            viewWidth = 480,
            viewHeight = 640,
        )!!

        assertEquals(640f, corners[1], 0.0001f)
        assertEquals(0f, corners[3], 0.0001f)
        assertEquals(0f, corners[5], 0.0001f)
        assertEquals(640f, corners[7], 0.0001f)
        assertTrue(corners[0] < 0f)
        assertTrue(corners[4] > 480f)
    }

    @Test
    fun `rotated landscape OCR box maps through portrait center crop without becoming vertical`() {
        val viewport = CameraPreviewGeometry.fillCenterViewport(1_280, 720, 480, 640)!!
        val mapped = CameraPreviewGeometry.mapFillCenter(
            box = CameraOverlayBounds(0.35f, 0.40f, 0.65f, 0.50f),
            sourceWidth = 1_280,
            sourceHeight = 720,
            viewWidth = 480,
            viewHeight = 640,
        )!!

        assertEquals(640f, viewport.displayedHeight, 0.0001f)
        assertTrue(viewport.displayedWidth > 480f)
        assertEquals(0.5f, (mapped.left + mapped.right) / 2f, 0.0001f)
        assertTrue(mapped.right - mapped.left > mapped.bottom - mapped.top)
    }

    @Test
    fun `boxes outside the displayed center crop are omitted`() {
        assertNull(
            CameraPreviewGeometry.mapFillCenter(
                CameraOverlayBounds(0f, 0.2f, 0.1f, 0.3f),
                sourceWidth = 1_280,
                sourceHeight = 720,
                viewWidth = 480,
                viewHeight = 640,
            ),
        )
    }
}
