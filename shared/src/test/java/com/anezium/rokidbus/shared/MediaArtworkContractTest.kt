package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtworkContractTest {
    @Test
    fun `accepts valid binary media artwork`() {
        val bytes = jpeg()

        val result = MediaArtworkContract.validate(payload(bytes), bytes)

        assertTrue(result is ImageSurfaceValidationResult.Valid)
    }

    @Test
    fun `rejects media artwork over compressed cap`() {
        val bytes = jpeg(size = ImageSurfaceContract.MAX_IMAGE_BYTES + 1)

        val result = MediaArtworkContract.validate(payload(bytes), bytes)

        assertEquals(
            ImageSurfaceContract.ERROR_IMAGE_TOO_LARGE,
            (result as ImageSurfaceValidationResult.Invalid).code,
        )
    }

    @Test
    fun `rejects media artwork sha mismatch`() {
        val bytes = jpeg()

        val result = MediaArtworkContract.validate(
            payload(bytes).apply { getJSONObject("artwork").put("sha256", "0".repeat(64)) },
            bytes,
        )

        assertEquals(
            ImageSurfaceContract.ERROR_INVALID_IMAGE,
            (result as ImageSurfaceValidationResult.Invalid).code,
        )
    }

    @Test
    fun `rejects media artwork beyond 256 pixels`() {
        val bytes = jpeg(width = 257, height = 128)

        val result = MediaArtworkContract.validate(payload(bytes, width = 257, height = 128), bytes)

        assertTrue(result is ImageSurfaceValidationResult.Invalid)
    }

    private fun payload(
        bytes: ByteArray,
        width: Int = MediaArtworkContract.MAX_EDGE_PIXELS,
        height: Int = MediaArtworkContract.MAX_EDGE_PIXELS,
    ): JSONObject = JSONObject()
        .put("kind", MediaArtworkContract.KIND)
        .put("mediaVersion", MediaArtworkContract.MEDIA_VERSION)
        .put("contentKey", "track-1")
        .put(
            "artwork",
            JSONObject()
                .put("encoding", MediaArtworkContract.ENCODING_BINARY)
                .put("mimeType", ImageSurfaceContract.MIME_JPEG)
                .put("pixelWidth", width)
                .put("pixelHeight", height)
                .put("sha256", ImageSurfaceContract.sha256(bytes)),
        )

    private fun jpeg(
        size: Int = 128,
        width: Int = MediaArtworkContract.MAX_EDGE_PIXELS,
        height: Int = MediaArtworkContract.MAX_EDGE_PIXELS,
    ): ByteArray = ByteArray(size).also { bytes ->
        byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
            0x00, 0x11, 0x08,
            (height ushr 8).toByte(), height.toByte(),
            (width ushr 8).toByte(), width.toByte(),
            0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        ).copyInto(bytes)
    }
}
