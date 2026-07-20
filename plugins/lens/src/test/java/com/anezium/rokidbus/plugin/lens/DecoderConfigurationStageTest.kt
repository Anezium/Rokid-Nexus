package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraFrameGeometry
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderConfigurationStageTest {
    private val geometry = CameraFrameGeometry(
        rasterWidth = 1_280,
        rasterHeight = 720,
        remainingRotationDegrees = 270,
        sensorToDisplayRotationDegrees = 270,
    )

    @Test
    fun `same payload and geometry dedupe while pending applying and applied`() {
        val stage = DecoderConfigurationStage()
        val payload = byteArrayOf(1, 2, 3)

        assertTrue(stage.offer(payload, geometry, fps = 20))
        assertFalse(stage.offer(payload.copyOf(), geometry, fps = 60))

        val applying = stage.takePending()!!
        assertFalse(stage.offer(payload.copyOf(), geometry, fps = 30))
        stage.markApplied(applying)

        assertFalse(stage.offer(payload.copyOf(), geometry, fps = 10))
        assertNull(stage.takePending())
    }

    @Test
    fun `new config replaces pending config and owns a payload copy`() {
        val stage = DecoderConfigurationStage()
        val first = byteArrayOf(1, 2, 3)
        val latest = byteArrayOf(4, 5, 6)

        assertTrue(stage.offer(first, geometry, fps = 20))
        assertTrue(stage.offer(latest, geometry.copy(rasterWidth = 1_264), fps = 24))
        latest[0] = 99

        val pending = stage.takePending()!!
        assertArrayEquals(byteArrayOf(4, 5, 6), pending.payload)
        assertEquals(1_264, pending.geometry.rasterWidth)
        assertEquals(24, pending.fps)
        assertNull(stage.takePending())
    }

    @Test
    fun `failed config can be retried and close rejects further work`() {
        val stage = DecoderConfigurationStage()
        val payload = byteArrayOf(1, 2, 3)

        assertTrue(stage.offer(payload, geometry, fps = 20))
        stage.markFailed(stage.takePending()!!)
        assertTrue(stage.offer(payload, geometry, fps = 20))

        stage.close()
        assertNull(stage.takePending())
        assertFalse(stage.offer(byteArrayOf(7), geometry, fps = 20))
    }

    @Test
    fun `rejected config leaves applied config deduplicated`() {
        val stage = DecoderConfigurationStage()
        val appliedPayload = byteArrayOf(1, 2, 3)
        assertTrue(stage.offer(appliedPayload, geometry, fps = 20))
        stage.markApplied(stage.takePending()!!)

        assertTrue(stage.offer(byteArrayOf(9), geometry, fps = 20))
        stage.markRejected(stage.takePending()!!)

        assertFalse(stage.offer(appliedPayload.copyOf(), geometry, fps = 60))
        assertNull(stage.takePending())
    }
}
