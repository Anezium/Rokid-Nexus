package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Test

class FrozenZoomPolicyTest {
    @Test
    fun queuesUntilJpegExistsAndHdBaseOcrHasFinished() {
        assertEquals(true, FrozenZoomPolicy.shouldQueueUntilHdBaseReady(false, false))
        assertEquals(true, FrozenZoomPolicy.shouldQueueUntilHdBaseReady(true, true))
        assertEquals(false, FrozenZoomPolicy.shouldQueueUntilHdBaseReady(true, false))
    }

    @Test
    fun directionalStepsClampWithoutWrapping() {
        assertEquals(FrozenZoomLevel.ONE_POINT_SIX, FrozenZoomPolicy.zoomIn(FrozenZoomLevel.ONE))
        assertEquals(FrozenZoomLevel.TWO_POINT_FIVE, FrozenZoomPolicy.zoomIn(FrozenZoomLevel.ONE_POINT_SIX))
        assertEquals(FrozenZoomLevel.TWO_POINT_FIVE, FrozenZoomPolicy.zoomIn(FrozenZoomLevel.TWO_POINT_FIVE))
        assertEquals(FrozenZoomLevel.ONE_POINT_SIX, FrozenZoomPolicy.zoomOut(FrozenZoomLevel.TWO_POINT_FIVE))
        assertEquals(FrozenZoomLevel.ONE, FrozenZoomPolicy.zoomOut(FrozenZoomLevel.ONE_POINT_SIX))
        assertEquals(FrozenZoomLevel.ONE, FrozenZoomPolicy.zoomOut(FrozenZoomLevel.ONE))
    }

    @Test
    fun centerCropAtOnePointSixUsesNativeJpegCoordinates() {
        assertEquals(
            FrozenCropRect(left = 480, top = 360, right = 2_080, bottom = 1_560),
            FrozenZoomPolicy.centerCropInRawCoordinates(
                rawWidth = 2_560,
                rawHeight = 1_920,
                rotationDegrees = 0,
                zoomLevel = FrozenZoomLevel.ONE_POINT_SIX,
            ),
        )
    }

    @Test
    fun centerCropAccountsForExifQuarterTurn() {
        assertEquals(
            FrozenCropRect(left = 480, top = 360, right = 2_080, bottom = 1_560),
            FrozenZoomPolicy.centerCropInRawCoordinates(
                rawWidth = 2_560,
                rawHeight = 1_920,
                rotationDegrees = 270,
                zoomLevel = FrozenZoomLevel.ONE_POINT_SIX,
            ),
        )
    }

    @Test
    fun mapsOffCenterOrientedRectBackThroughEveryExifRotation() {
        val rawWidth = 400
        val rawHeight = 300

        assertEquals(
            FrozenCropRect(10, 20, 110, 220),
            FrozenZoomPolicy.mapOrientedRectToRaw(
                FrozenCropRect(10, 20, 110, 220),
                rawWidth,
                rawHeight,
                0,
            ),
        )
        assertEquals(
            FrozenCropRect(20, 190, 220, 290),
            FrozenZoomPolicy.mapOrientedRectToRaw(
                FrozenCropRect(10, 20, 110, 220),
                rawWidth,
                rawHeight,
                90,
            ),
        )
        assertEquals(
            FrozenCropRect(290, 80, 390, 280),
            FrozenZoomPolicy.mapOrientedRectToRaw(
                FrozenCropRect(10, 20, 110, 220),
                rawWidth,
                rawHeight,
                180,
            ),
        )
        assertEquals(
            FrozenCropRect(180, 10, 380, 110),
            FrozenZoomPolicy.mapOrientedRectToRaw(
                FrozenCropRect(10, 20, 110, 220),
                rawWidth,
                rawHeight,
                270,
            ),
        )
    }
}
