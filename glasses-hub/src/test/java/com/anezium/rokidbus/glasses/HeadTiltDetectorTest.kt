package com.anezium.rokidbus.glasses

import org.junit.Assert.*
import org.junit.Test

class HeadTiltDetectorTest {
    private val detector = HeadTiltDetector(20f, 500)
    private fun neutral(start: Long = 0) { for (at in start..start + 600 step 50) assertFalse(detector.sample(0f, 0f, at, true)) }
    private fun raise(start: Long, end: Long, up: Float = 22f, side: Float = 0f, allowed: Boolean = true): List<Boolean> =
        (start..end step 50).map { detector.sample(up, side, it, allowed) }

    @Test fun `neutral then sustained upward movement fires once and requires a fresh neutral`() {
        neutral()
        assertEquals(1, raise(650, 2_000).count { it })
        assertFalse(raise(2_050, 5_000).any { it })
        neutral(5_050)
        assertEquals(1, raise(5_700, 6_500).count { it })
    }

    @Test fun `starting raised or looking down does not fire`() {
        assertFalse(raise(0, 1_000).any { it })
        neutral(1_050)
        assertFalse(raise(1_700, 3_000, up = -30f).any { it })
    }

    @Test fun `brief tilt and subthreshold movement do not fire`() {
        neutral()
        assertFalse(raise(650, 1_000).any { it })
        assertFalse(raise(1_050, 2_000, up = 19f).any { it })
        assertFalse(raise(2_050, 2_450).any { it })
    }

    @Test fun `gap or blocked interaction cancels hold and requires neutral`() {
        neutral()
        raise(650, 950)
        assertFalse(raise(1_500, 2_500).any { it })
        neutral(2_550)
        raise(3_200, 3_500)
        assertFalse(detector.sample(22f, 0f, 3_550, false))
        assertFalse(raise(3_600, 4_500).any { it })
    }

    @Test fun `sideways or invalid readings cancel the gesture`() {
        for (side in listOf(26f, Float.NaN)) {
            detector.reset()
            neutral()
            assertFalse(raise(650, 1_500, side = side).any { it })
            assertFalse(raise(1_550, 2_500).any { it })
        }
    }

    @Test fun `cooldown survives rearming and reset requires neutral`() {
        neutral()
        assertEquals(1, raise(650, 1_200).count { it })
        neutral(1_250)
        assertFalse(raise(1_900, 2_600).any { it })
        detector.reset()
        assertFalse(raise(3_000, 5_000).any { it })
    }

    @Test fun `angles wrap across the rotation vector boundary`() {
        assertEquals(2f, headTiltDelta(179f, -179f), .001f)
        assertEquals(-2f, headTiltDelta(-179f, 179f), .001f)
    }

    @Test fun `calibration requires one second of continuous stable samples`() {
        val calibration = HeadTiltCalibration()
        for (at in 0L..900L step 100) assertNull(calibration.sample(80f, 0f, at))
        assertNull(calibration.sample(85f, 0f, 1_000))
        for (at in 1_100L..1_900L step 100) assertNull(calibration.sample(85.5f, 0f, at))
        assertEquals(85f to 0f, calibration.sample(85.5f, 0f, 2_000))
    }

    @Test fun `calibration never bridges missing samples`() {
        val calibration = HeadTiltCalibration()
        calibration.sample(80f, 0f, 0)
        assertNull(calibration.sample(80f, 0f, 2_000))
    }
}
