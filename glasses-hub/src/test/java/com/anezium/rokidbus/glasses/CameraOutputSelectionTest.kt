package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraOutputSelectionTest {
    @Test
    fun `freeze chooses old Lens 2048 edge full sensor aspect`() {
        val selected = selectFullFovJpegSize(
            candidates = listOf(
                CameraOutputSize(2_560, 1_440),
                CameraOutputSize(2_400, 1_800),
                CameraOutputSize(2_048, 1_536),
                CameraOutputSize(1_512, 2_016),
            ),
            sensorWidth = 4_032,
            sensorHeight = 3_024,
        )

        assertEquals(CameraOutputSize(2_048, 1_536), selected)
    }

    @Test
    fun `aspect ratio wins over a closer cropped long edge`() {
        val selected = selectFullFovJpegSize(
            candidates = listOf(CameraOutputSize(1_920, 1_080), CameraOutputSize(1_600, 1_200)),
            sensorWidth = 4_032,
            sensorHeight = 3_024,
        )

        assertEquals(CameraOutputSize(1_600, 1_200), selected)
    }
}
