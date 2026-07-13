package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class CameraFrameGeometryTest {
    @Test
    fun `remaining quarter turns swap raster dimensions`() {
        assertEquals(
            CameraFrameSize(1280, 720),
            CameraFrameGeometry(1280, 720, remainingRotationDegrees = 0).orientedSize,
        )
        assertEquals(
            CameraFrameSize(720, 1280),
            CameraFrameGeometry(1280, 720, remainingRotationDegrees = 90).orientedSize,
        )
        assertEquals(
            CameraFrameSize(1280, 720),
            CameraFrameGeometry(1280, 720, remainingRotationDegrees = 180).orientedSize,
        )
        assertEquals(
            CameraFrameSize(720, 1280),
            CameraFrameGeometry(1280, 720, remainingRotationDegrees = 270).orientedSize,
        )
    }

    @Test
    fun `applied hardware rotation does not rotate the encoded raster again`() {
        val geometry = CameraFrameGeometry(
            rasterWidth = 720,
            rasterHeight = 1280,
            appliedRotationDegrees = 90,
            remainingRotationDegrees = 0,
        )

        assertEquals(CameraFrameSize(720, 1280), geometry.orientedSize)
    }

    @Test
    fun `legacy video config rotation is remaining rotation`() {
        val geometry = CameraFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 1280)
                .put("height", 720)
                .put("rotationDegrees", 90),
        )

        assertEquals(1280, geometry.rasterWidth)
        assertEquals(720, geometry.rasterHeight)
        assertEquals(0, geometry.appliedRotationDegrees)
        assertEquals(90, geometry.remainingRotationDegrees)
        assertEquals(90, geometry.sensorToDisplayRotationDegrees)
        assertEquals(CameraFrameSize(720, 1280), geometry.orientedSize)
    }

    @Test
    fun `missing legacy rotation defaults to no remaining rotation`() {
        val geometry = CameraFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 1280)
                .put("height", 720),
        )

        assertEquals(0, geometry.appliedRotationDegrees)
        assertEquals(0, geometry.remainingRotationDegrees)
    }

    @Test
    fun `additive video config fields preserve applied and remaining rotations`() {
        val geometry = CameraFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 720)
                .put("height", 1280)
                .put("appliedRotationDegrees", 90)
                .put("remainingRotationDegrees", 180)
                .put("rotationDegrees", 180)
                .put("futureField", true),
        )

        assertEquals(
            CameraFrameGeometry(
                rasterWidth = 720,
                rasterHeight = 1280,
                appliedRotationDegrees = 90,
                remainingRotationDegrees = 180,
            ),
            geometry,
        )
    }

    @Test
    fun `new remaining rotation does not require the legacy alias`() {
        val geometry = CameraFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 720)
                .put("height", 1280)
                .put("appliedRotationDegrees", 90)
                .put("remainingRotationDegrees", 270),
        )

        assertEquals(90, geometry.appliedRotationDegrees)
        assertEquals(270, geometry.remainingRotationDegrees)
    }

    @Test
    fun `metadata serialization includes the legacy rotation alias`() {
        val metadata = CameraFrameGeometry(
            rasterWidth = 720,
            rasterHeight = 1280,
            appliedRotationDegrees = 90,
            remainingRotationDegrees = 270,
        ).toVideoConfigMetadata()

        assertEquals(720, metadata.getInt("width"))
        assertEquals(1280, metadata.getInt("height"))
        assertEquals(0, metadata.getInt("sensorToDisplayRotationDegrees"))
        assertEquals(90, metadata.getInt("appliedRotationDegrees"))
        assertEquals(270, metadata.getInt("remainingRotationDegrees"))
        assertEquals(270, metadata.getInt("rotationDegrees"))
    }

    @Test
    fun `metadata round trip preserves geometry`() {
        val geometry = CameraFrameGeometry(
            rasterWidth = 1920,
            rasterHeight = 1080,
            appliedRotationDegrees = 180,
            remainingRotationDegrees = 90,
        )

        assertEquals(
            geometry,
            CameraFrameGeometry.fromVideoConfigMetadata(geometry.toVideoConfigMetadata()),
        )
    }

    @Test
    fun `metadata parser normalizes equivalent right angles`() {
        val geometry = CameraFrameGeometry.fromVideoConfigMetadata(
            JSONObject()
                .put("width", 640)
                .put("height", 480)
                .put("appliedRotationDegrees", -270)
                .put("remainingRotationDegrees", 450)
                .put("rotationDegrees", 90),
        )

        assertEquals(90, geometry.appliedRotationDegrees)
        assertEquals(90, geometry.remainingRotationDegrees)
    }

    @Test
    fun `dimensions must be positive`() {
        assertInvalid { CameraFrameGeometry(0, 720) }
        assertInvalid { CameraFrameGeometry(1280, -1) }
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject().put("width", 0).put("height", 720),
            )
        }
    }

    @Test
    fun `rotations must be right angles`() {
        assertInvalid { CameraFrameGeometry(1280, 720, appliedRotationDegrees = 45) }
        assertInvalid { CameraFrameGeometry(1280, 720, remainingRotationDegrees = 135) }
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject()
                    .put("width", 1280)
                    .put("height", 720)
                    .put("rotationDegrees", 12),
            )
        }
    }

    @Test
    fun `metadata dimensions and rotations must be integers`() {
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject().put("width", 1280.5).put("height", 720),
            )
        }
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject().put("width", "1280").put("height", 720),
            )
        }
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject()
                    .put("width", 1280)
                    .put("height", 720)
                    .put("remainingRotationDegrees", "90"),
            )
        }
    }

    @Test
    fun `legacy alias must agree with additive remaining rotation`() {
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject()
                    .put("width", 1280)
                    .put("height", 720)
                    .put("remainingRotationDegrees", 90)
                    .put("rotationDegrees", 270),
            )
        }
    }

    @Test
    fun `sensor rotation must equal applied plus remaining rotation`() {
        assertInvalid {
            CameraFrameGeometry.fromVideoConfigMetadata(
                JSONObject()
                    .put("width", 720)
                    .put("height", 1280)
                    .put("sensorToDisplayRotationDegrees", 270)
                    .put("appliedRotationDegrees", 0)
                    .put("remainingRotationDegrees", 0),
            )
        }
    }

    @Test
    fun `geometry metadata round trips through version one camera framing`() {
        val geometry = CameraFrameGeometry(
            rasterWidth = 720,
            rasterHeight = 1280,
            appliedRotationDegrees = 270,
            remainingRotationDegrees = 0,
        )
        val output = ByteArrayOutputStream()
        CameraLinkProtocol.write(
            output,
            CameraLinkPacket(
                type = CameraLinkPacketType.VIDEO_CONFIG,
                meta = geometry.toVideoConfigMetadata().toString(),
            ),
        )

        val encoded = output.toByteArray()
        assertEquals(1, encoded[4].toInt())
        val packet = CameraLinkProtocol.read(ByteArrayInputStream(encoded))!!
        assertEquals(CameraLinkPacketType.VIDEO_CONFIG, packet.type)
        assertEquals(
            geometry,
            CameraFrameGeometry.fromVideoConfigMetadata(JSONObject(packet.meta)),
        )
    }

    private fun assertInvalid(block: () -> Unit) {
        assertThrows(IllegalArgumentException::class.java, block)
    }
}
