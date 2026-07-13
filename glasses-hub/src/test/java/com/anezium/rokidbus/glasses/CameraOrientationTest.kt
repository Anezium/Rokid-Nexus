package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun `RG back sensor maps to the old Lens 270 degree portrait rotation`() {
        assertEquals(
            270,
            CameraOrientation.sensorToDisplayRotationDegrees(
                sensorOrientation = 270,
                displayRotationDegrees = 0,
                frontFacing = false,
            ),
        )
        assertEquals(CameraPixelSize(1_536, 2_048), CameraOrientation.orientedSize(2_048, 1_536, 270))
    }

    @Test
    fun `display rotation is subtracted for a back camera`() {
        assertEquals(
            180,
            CameraOrientation.sensorToDisplayRotationDegrees(270, 90, frontFacing = false),
        )
    }
}
