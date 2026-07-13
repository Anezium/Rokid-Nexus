package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayItem
import com.anezium.rokidbus.shared.CameraOverlayLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraPreviewGeometryTest {
    @Test
    fun `hardware rotated RG preview uses inverse local quarter turn`() {
        val corners = CameraPreviewGeometry.textureDestinationCorners(
            CameraPreviewConsumerGeometry(720, 1_280, 90),
            viewWidth = 500,
            viewHeight = 700,
        )!!

        assertTrue(corners[0] > 500f)
        assertEquals(0f, corners[1], 0.0001f)
        assertTrue(corners[2] > 500f)
        assertEquals(700f, corners[3], 0.0001f)
        assertTrue(corners[4] < 0f)
        assertEquals(700f, corners[5], 0.0001f)
        assertTrue(corners[6] < 0f)
        assertEquals(0f, corners[7], 0.0001f)
    }

    @Test
    fun `software fallback preview uses producer-oriented portrait aspect without another turn`() {
        val plan = CameraStreamPlan(
            rasterSize = CameraPixelSize(1_280, 720),
            requestedHardwareRotationDegrees = 0,
            remainingRotationDegrees = 270,
        )
        val geometry = CameraPreviewGeometry.previewConsumerGeometry(plan)
        assertEquals(CameraPreviewConsumerGeometry(720, 1_280, 0), geometry)

        val corners = CameraPreviewGeometry.textureDestinationCorners(
            geometry,
            viewWidth = 500,
            viewHeight = 700,
        )!!

        assertEquals(0f, corners[0], 0.0001f)
        assertTrue(corners[1] < 0f)
        assertEquals(500f, corners[2], 0.0001f)
        assertTrue(corners[3] < 0f)
        assertEquals(500f, corners[4], 0.0001f)
        assertTrue(corners[5] > 700f)
        assertEquals(0f, corners[6], 0.0001f)
        assertTrue(corners[7] > 700f)
    }

    @Test
    fun `frozen viewport matches live stream and screen crops`() {
        val viewport = CameraPreviewGeometry.matchingFrozenSourceViewport(
            frozenWidth = 1_536,
            frozenHeight = 2_048,
            liveWidth = 720,
            liveHeight = 1_280,
            viewWidth = 480,
            viewHeight = 640,
        )!!

        assertEquals(192f, viewport.left, 0.001f)
        assertEquals(256f, viewport.top, 0.001f)
        assertEquals(1_344f, viewport.right, 0.001f)
        assertEquals(1_792f, viewport.bottom, 0.001f)
        assertEquals(1_152f, viewport.width, 0.001f)
    }

    @Test
    fun `portrait OCR box maps without becoming vertical`() {
        val viewport = CameraPreviewGeometry.fillCenterViewport(720, 1_280, 480, 640)!!
        val mapped = CameraPreviewGeometry.mapFillCenter(
            box = CameraOverlayBounds(0.2f, 0.40f, 0.8f, 0.50f),
            sourceWidth = 720,
            sourceHeight = 1_280,
            viewWidth = 480,
            viewHeight = 640,
        )!!

        assertEquals(853.3334f, viewport.displayedHeight, 0.001f)
        assertEquals(480f, viewport.displayedWidth, 0.0001f)
        assertEquals(0.5f, (mapped.left + mapped.right) / 2f, 0.0001f)
        assertTrue(mapped.right - mapped.left > mapped.bottom - mapped.top)
    }

    @Test
    fun `adaptive paragraph box and vertical metrics share nontrivial fill center scale`() {
        val mapped = CameraPreviewGeometry.mapFillCenter(
            item = CameraOverlayItem(
                text = "translated paragraph",
                box = CameraOverlayBounds(0.2f, 0.40f, 0.8f, 0.50f),
                role = "translation",
                layout = CameraOverlayLayout(
                    kind = "paragraph",
                    version = 1,
                    medianLineHeight = 0.05f,
                    growDown = 0.15f,
                ),
            ),
            sourceWidth = 720,
            sourceHeight = 1_280,
            viewWidth = 480,
            viewHeight = 640,
        )!!

        assertEquals(0.2f, mapped.box.left, 0.0001f)
        assertEquals(0.8f, mapped.box.right, 0.0001f)
        assertEquals(0.36666667f, mapped.box.top, 0.0001f)
        assertEquals(0.5f, mapped.box.bottom, 0.0001f)
        assertEquals(0.06666667f, mapped.layout!!.medianLineHeight, 0.0001f)
        assertEquals(0.2f, mapped.layout!!.growDown, 0.0001f)
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
