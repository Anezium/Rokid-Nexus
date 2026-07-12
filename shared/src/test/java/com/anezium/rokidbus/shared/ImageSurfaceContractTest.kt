package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSurfaceContractTest {
    private fun jpeg(size: Int = 128, width: Int = 512, height: Int = 512): ByteArray =
        ByteArray(size).also { bytes ->
            val header = byteArrayOf(
                0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(),
                0x00, 0x11, 0x08,
                (height ushr 8).toByte(), height.toByte(),
                (width ushr 8).toByte(), width.toByte(),
                0x03, 0x01, 0x11, 0x00, 0x02, 0x11, 0x00, 0x03, 0x11, 0x00,
                0xff.toByte(), 0xd9.toByte(),
            )
            header.copyInto(bytes)
        }

    private fun payload(bytes: ByteArray = jpeg()): JSONObject = JSONObject()
        .put("kind", ImageSurfaceContract.KIND)
        .put("imageVersion", ImageSurfaceContract.VERSION)
        .put("contentKey", "photo-1")
        .put("mimeType", ImageSurfaceContract.MIME_JPEG)
        .put("pixelWidth", 512)
        .put("pixelHeight", 512)
        .put("sha256", ImageSurfaceContract.sha256(bytes))
        .put("title", "Title")
        .put("caption", "Caption")
        .put("footer", "Footer")
        .put("handlesBack", true)

    @Test
    fun `accepts exact compressed and decoded boundaries`() {
        val bytes = jpeg(ImageSurfaceContract.MAX_IMAGE_BYTES)
        val result = ImageSurfaceContract.validate(payload(bytes), bytes)
        assertTrue(result is ImageSurfaceValidationResult.Valid)
    }

    @Test
    fun `rejects compressed body over cap`() {
        val bytes = jpeg(ImageSurfaceContract.MAX_IMAGE_BYTES + 1)
        val result = ImageSurfaceContract.validate(payload(bytes), bytes)
        assertEquals(ImageSurfaceContract.ERROR_IMAGE_TOO_LARGE, (result as ImageSurfaceValidationResult.Invalid).code)
    }

    @Test
    fun `rejects missing and empty binary bodies`() {
        assertInvalid(payload(), null)
        assertInvalid(payload(ByteArray(0)), ByteArray(0))
    }

    @Test
    fun `rejects unsupported and mismatched MIME`() {
        val bytes = jpeg()
        assertInvalid(payload(bytes).put("mimeType", "image/webp"), bytes)
        assertInvalid(payload(bytes).put("mimeType", ImageSurfaceContract.MIME_PNG), bytes)
    }

    @Test
    fun `rejects invalid dimensions and accepts maximum pixel count`() {
        val bytes = jpeg()
        assertTrue(ImageSurfaceContract.validate(payload(bytes), bytes) is ImageSurfaceValidationResult.Valid)
        assertInvalid(payload(bytes).put("pixelWidth", 513), bytes)
        assertInvalid(payload(bytes).put("pixelHeight", 0), bytes)
        assertInvalid(payload(bytes).put("pixelWidth", 1.5), bytes)
        assertInvalid(payload(bytes).put("pixelWidth", 511), bytes)
    }

    @Test
    fun `rejects hash mismatch and noncanonical hash`() {
        val bytes = jpeg()
        assertInvalid(payload(bytes).put("sha256", "0".repeat(64)), bytes)
        assertInvalid(payload(bytes).put("sha256", ImageSurfaceContract.sha256(bytes).uppercase()), bytes)
    }

    @Test
    fun `rejects metadata text boundaries and wrong version`() {
        val bytes = jpeg()
        assertInvalid(payload(bytes).put("imageVersion", 2), bytes)
        assertInvalid(payload(bytes).put("contentKey", "x".repeat(129)), bytes)
        assertInvalid(payload(bytes).put("title", "x".repeat(121)), bytes)
        assertInvalid(payload(bytes).put("caption", "x".repeat(241)), bytes)
        assertInvalid(payload(bytes).put("handlesBack", "true"), bytes)
    }

    private fun assertInvalid(payload: JSONObject, bytes: ByteArray?) {
        assertTrue(ImageSurfaceContract.validate(payload, bytes) is ImageSurfaceValidationResult.Invalid)
    }
}
