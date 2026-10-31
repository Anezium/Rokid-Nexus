package com.anezium.rokidbus.media

import android.graphics.Bitmap
import android.graphics.Color
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageArtworkEncoderTest {
    @Test
    fun `scales longest edge to 256 and emits capped jpeg`() {
        val source = gradientBitmap(width = 1_024, height = 300)

        val encoded = try {
            ImageArtworkEncoder.encode(source)
        } finally {
            source.recycle()
        }

        assertNotNull(encoded)
        encoded!!
        assertEquals(256, encoded.width)
        assertEquals(75, encoded.height)
        assertEquals(ImageSurfaceContract.MIME_JPEG, encoded.mimeType)
        assertTrue(encoded.bytes.size <= ImageSurfaceContract.MAX_IMAGE_BYTES)
        assertEquals(ImageSurfaceContract.sha256(encoded.bytes), encoded.sha256)
        assertTrue(maxOf(encoded.width, encoded.height) <= MediaArtworkContract.MAX_EDGE_PIXELS)
    }

    @Test
    fun `image capability off selects the unchanged mono fallback`() {
        assertEquals(MediaArtworkMode.MONO, artworkModeFor(supportsImage = false))
        assertEquals(MediaArtworkMode.IMAGE, artworkModeFor(supportsImage = true))
    }

    private fun gradientBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bitmap ->
            for (y in 0 until height) {
                val green = y * 255 / height
                for (x in 0 until width) {
                    bitmap.setPixel(x, y, Color.rgb(x * 255 / width, green, (x + y) % 256))
                }
            }
        }
    @Test fun `launcher covers reuse JPEG with continuous tones within the page budget`() {
        val source = gradientBitmap(512, 512)
        val encoded = try { ImageArtworkEncoder.encode(source, com.anezium.rokidbus.shared.LauncherArtwork.MAX_IMAGE_BYTES) }
            finally { source.recycle() }
        assertNotNull(encoded)
        encoded!!
        assertEquals(256, encoded.width)
        assertTrue(encoded.bytes.size <= 32768)
        val cover = com.anezium.rokidbus.shared.LauncherArtwork(encoded.width, encoded.height,
            java.util.Base64.getEncoder().encodeToString(encoded.bytes), "jpeg")
        val body = com.anezium.rokidbus.shared.PageTemplateContract.encode(
            com.anezium.rokidbus.shared.PageBody.Media("Track", com.anezium.rokidbus.shared.MediaPlaybackState.PAUSED, artwork = cover))
        assertTrue(body.toString().toByteArray().size < com.anezium.rokidbus.shared.PageSurfaceContract.MAX_PAGE_BYTES - 4096)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(encoded.bytes, 0, encoded.bytes.size)
        assertNotNull(decoded)
        val pixels = IntArray(decoded.width * decoded.height)
        decoded.getPixels(pixels, 0, decoded.width, 0, 0, decoded.width, decoded.height)
        assertTrue("the cover retains continuous tones instead of two-colour dithering", pixels.toSet().size > 256)
        decoded.recycle()
    }

}
