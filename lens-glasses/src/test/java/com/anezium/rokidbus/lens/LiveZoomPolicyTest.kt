package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveZoomPolicyTest {
    @Test
    fun centeredCropUsesInverseZoomAndStaysCenteredAtOddDimensions() {
        assertEquals(
            LiveCropRect(left = 17, top = 11, right = 84, bottom = 56),
            LiveZoomPolicy.centerCrop(101, 67, LiveZoomLevel.ONE_POINT_FIVE),
        )
        assertEquals(
            LiveCropRect(left = 25, top = 17, right = 76, bottom = 50),
            LiveZoomPolicy.centerCrop(101, 67, LiveZoomLevel.TWO),
        )
    }

    @Test
    fun orientedCropMapsToRawAndAlignsEveryComponentDownToEven() {
        val oriented = LiveCropRect(left = 11, top = 21, right = 112, bottom = 222)

        assertEquals(
            LiveCropRect(10, 20, 110, 220),
            LiveZoomPolicy.mapOrientedCropToEvenRaw(oriented, 400, 300, 0),
        )
        assertEquals(
            LiveCropRect(20, 188, 220, 288),
            LiveZoomPolicy.mapOrientedCropToEvenRaw(oriented, 400, 300, 90),
        )
        assertEquals(
            LiveCropRect(288, 78, 388, 278),
            LiveZoomPolicy.mapOrientedCropToEvenRaw(oriented, 400, 300, 180),
        )
        assertEquals(
            LiveCropRect(178, 10, 378, 110),
            LiveZoomPolicy.mapOrientedCropToEvenRaw(oriented, 400, 300, 270),
        )
    }

    @Test
    fun nv21CropReportsAlignedRawAndMatchingOrientedCoordinates() {
        assertEquals(
            LiveNv21Crop(
                raw = LiveCropRect(2, 2, 10, 8),
                oriented = LiveCropRect(2, 2, 8, 10),
            ),
            LiveZoomPolicy.nv21Crop(
                rawWidth = 14,
                rawHeight = 10,
                rotationDegrees = 90,
                level = LiveZoomLevel.ONE_POINT_FIVE,
            ),
        )
    }

    @Test
    fun centeredZoomTransformMapsCropCenterAndCornersToView() {
        val crop = LiveZoomPolicy.centerCrop(100, 80, LiveZoomLevel.TWO)
        val frameToView = floatArrayOf(
            5f, 0f, 0f,
            0f, 5f, 0f,
            0f, 0f, 1f,
        )
        val cropToView = LiveZoomPolicy.composeCropToView(frameToView, crop)
        val composed = LiveZoomPolicy.postScaleAboutViewCenter(
            transform = cropToView,
            scale = LiveZoomLevel.TWO.scale,
            viewWidth = 500f,
            viewHeight = 400f,
        )

        val center = LiveZoomPolicy.mapPoint(composed, crop.width / 2f, crop.height / 2f)
        assertEquals(250f, center.first, 0.001f)
        assertEquals(200f, center.second, 0.001f)

        val topLeft = LiveZoomPolicy.mapPoint(composed, 0f, 0f)
        assertEquals(0f, topLeft.first, 0.001f)
        assertEquals(0f, topLeft.second, 0.001f)

        val bottomRight = LiveZoomPolicy.mapPoint(composed, crop.width.toFloat(), crop.height.toFloat())
        assertEquals(500f, bottomRight.first, 0.001f)
        assertEquals(400f, bottomRight.second, 0.001f)
    }

    @Test
    fun liveStepsClampWithoutWrapping() {
        assertEquals(LiveZoomLevel.ONE_POINT_FIVE, LiveZoomPolicy.zoomIn(LiveZoomLevel.ONE))
        assertEquals(LiveZoomLevel.TWO, LiveZoomPolicy.zoomIn(LiveZoomLevel.ONE_POINT_FIVE))
        assertEquals(LiveZoomLevel.TWO, LiveZoomPolicy.zoomIn(LiveZoomLevel.TWO))
        assertEquals(LiveZoomLevel.ONE, LiveZoomPolicy.zoomOut(LiveZoomLevel.ONE))
    }
}
