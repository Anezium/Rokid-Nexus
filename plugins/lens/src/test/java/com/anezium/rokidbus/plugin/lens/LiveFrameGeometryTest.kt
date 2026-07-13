package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraFrameGeometry
import com.anezium.rokidbus.shared.CameraFrameSize
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class LiveFrameGeometryTest {
    @Test
    fun `legacy config treats rotation as remaining rotation`() {
        val geometry = LiveFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 1280)
                .put("height", 720)
                .put("rotationDegrees", 270),
        )

        assertEquals(CameraFrameGeometry(1280, 720, remainingRotationDegrees = 270), geometry)
        assertEquals(CameraFrameSize(720, 1280), geometry.orientedSize)
    }

    @Test
    fun `new preferred config is already portrait oriented`() {
        val geometry = LiveFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 720)
                .put("height", 1280)
                .put("appliedRotationDegrees", 90)
                .put("remainingRotationDegrees", 0)
                .put("rotationDegrees", 0),
        )

        assertEquals(CameraFrameSize(720, 1280), geometry.orientedSize)
        assertEquals(0, geometry.remainingRotationDegrees)
    }

    @Test
    fun `fallback landscape config normalizes to portrait`() {
        val geometry = LiveFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 1280)
                .put("height", 720)
                .put("appliedRotationDegrees", 0)
                .put("remainingRotationDegrees", 270)
                .put("rotationDegrees", 270),
        )

        assertEquals(CameraFrameSize(720, 1280), geometry.orientedSize)
        assertEquals(270, geometry.remainingRotationDegrees)
    }

    @Test
    fun `decoder crop dimensions remain authoritative`() {
        val configured = CameraFrameGeometry(
            rasterWidth = 720,
            rasterHeight = 1280,
            appliedRotationDegrees = 90,
            remainingRotationDegrees = 0,
        )

        val decoded = LiveFrameGeometry.withDecoderCrop(configured, width = 704, height = 1264)

        assertEquals(704, decoded.rasterWidth)
        assertEquals(1264, decoded.rasterHeight)
        assertEquals(90, decoded.appliedRotationDegrees)
        assertEquals(0, decoded.remainingRotationDegrees)
        assertEquals(CameraFrameSize(704, 1264), decoded.orientedSize)
    }

    @Test
    fun `invalid config values are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            LiveFrameGeometry.fromVideoConfigMetadata(
                JSONObject().put("width", 1280).put("height", 720).put("rotationDegrees", 45),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveFrameGeometry.fromVideoConfigMetadata(
                JSONObject().put("width", "1280").put("height", 720),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            LiveFrameGeometry.fromVideoConfigMetadata(
                JSONObject()
                    .put("width", 1280)
                    .put("height", 720)
                    .put("remainingRotationDegrees", 270)
                    .put("rotationDegrees", 90),
            )
        }
    }
}
