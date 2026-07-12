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
}
