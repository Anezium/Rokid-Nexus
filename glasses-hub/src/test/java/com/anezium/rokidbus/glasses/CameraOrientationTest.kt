package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraOrientationTest {
    @Test
    fun `RG back sensor requires 270 degrees in portrait`() {
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
    fun `future HAL uses an advertised hardware rotation`() {
        val plan = CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = 270,
            availableHardwareRotationDegrees = setOf(0, 270),
            availableOutputSizes = setOf(CameraPixelSize(720, 1_280), CameraPixelSize(1_280, 720)),
        )

        assertEquals(
            CameraStreamPlan(
                rasterSize = CameraPixelSize(720, 1_280),
                requestedHardwareRotationDegrees = 270,
                remainingRotationDegrees = 0,
            ),
            plan,
        )
        assertEquals(CameraPixelSize(720, 1_280), plan?.orientedSize)
    }

    @Test
    fun `RG encoded and preview consumers keep separate rotation domains`() {
        val plan = CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = 270,
            availableHardwareRotationDegrees = setOf(0, 270),
            availableOutputSizes = setOf(CameraPixelSize(1_280, 720)),
        )!!

        assertEquals(270, plan.remainingRotationDegrees)
        assertEquals(
            CameraPreviewConsumerGeometry(720, 1_280, 0),
            CameraPreviewGeometry.previewConsumerGeometry(plan),
        )
    }

    @Test
    fun `RG contract also works when only rotate and crop none is available`() {
        val plan = CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = 270,
            availableHardwareRotationDegrees = setOf(0),
            availableOutputSizes = setOf(CameraPixelSize(720, 1_280), CameraPixelSize(1_280, 720)),
        )

        assertEquals(
            CameraStreamPlan(
                rasterSize = CameraPixelSize(1_280, 720),
                requestedHardwareRotationDegrees = 0,
                remainingRotationDegrees = 270,
            ),
            plan,
        )
        assertEquals(CameraPixelSize(720, 1_280), plan?.orientedSize)
    }

    @Test
    fun `software fallback moves failed hardware rotation into remaining rotation`() {
        val outputs = setOf(CameraPixelSize(720, 1_280), CameraPixelSize(1_280, 720))
        val hardwarePlan = CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = 270,
            availableHardwareRotationDegrees = setOf(0, 270),
            availableOutputSizes = outputs,
        )
        val fallbackPlan = CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = 270,
            availableHardwareRotationDegrees = setOf(0),
            availableOutputSizes = outputs,
        )

        assertEquals(
            CameraStreamPlan(
                rasterSize = CameraPixelSize(720, 1_280),
                requestedHardwareRotationDegrees = 270,
                remainingRotationDegrees = 0,
            ),
            hardwarePlan,
        )
        assertEquals(
            CameraStreamPlan(
                rasterSize = CameraPixelSize(1_280, 720),
                requestedHardwareRotationDegrees = 0,
                remainingRotationDegrees = 270,
            ),
            fallbackPlan,
        )
        assertEquals(hardwarePlan?.orientedSize, fallbackPlan?.orientedSize)
    }

    @Test
    fun `unsafe portrait raster plus software quarter turn is rejected`() {
        assertNull(
            CameraOrientation.selectStreamPlan(
                sensorToDisplayRotationDegrees = 270,
                availableHardwareRotationDegrees = setOf(0),
                availableOutputSizes = setOf(CameraPixelSize(720, 1_280)),
            ),
        )
    }

    @Test
    fun `display rotation is subtracted for a back camera`() {
        assertEquals(
            180,
            CameraOrientation.sensorToDisplayRotationDegrees(270, 90, frontFacing = false),
        )
    }
}
