package com.anezium.rokidbus.plugin.feeds

import android.graphics.Bitmap
import android.graphics.Color
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class FeedImagePipelineTest {
    @Test
    fun downscalesAndReturnsContractValidHashedJpeg() = runBlocking {
        val input = jpeg(1_024, 300)
        val frame = pipeline(input).load(media())

        assertNotNull(frame)
        frame!!
        assertEquals(512, frame.pixelWidth)
        assertEquals(150, frame.pixelHeight)
        assertTrue(frame.bytes.size <= ImageSurfaceContract.MAX_IMAGE_BYTES)
        assertEquals(ImageSurfaceContract.sha256(frame.bytes), frame.sha256)
        assertTrue(
            ImageSurfaceContract.validate(frame.payload(), frame.bytes) is ImageSurfaceValidationResult.Valid,
        )
    }

    @Test
    fun largeSyntheticBitmapRecompressesUnderWireCap() = runBlocking {
        val frame = pipeline(jpeg(1_800, 1_400)).load(media())

        assertNotNull(frame)
        assertTrue(frame!!.bytes.size <= ImageSurfaceContract.MAX_IMAGE_BYTES)
        assertTrue(maxOf(frame.pixelWidth, frame.pixelHeight) <= ImageSurfaceContract.MAX_EDGE_PIXELS)
    }

    @Test
    fun stepsQualityUntilEncodedBodyFits() = runBlocking {
        val qualities = mutableListOf<Int>()
        val encoder = FeedJpegEncoder { bitmap, quality ->
            qualities += quality
            val valid = encode(bitmap, 80)
            if (quality > 60) valid + ByteArray(ImageSurfaceContract.MAX_IMAGE_BYTES) else valid
        }
        val frame = pipeline(jpeg(640, 480), encoder = encoder).load(media())

        assertNotNull(frame)
        assertEquals(60, frame!!.jpegQuality)
        assertEquals(listOf(85, 72, 60), qualities)
    }

    @Test
    fun appliesConfiguredContrastPassBeforeEncoding() = runBlocking {
        var calls = 0
        var factor = 0f
        val contrast = FeedImageContrast { bitmap, requestedFactor ->
            calls += 1
            factor = requestedFactor
            ColorMatrixFeedImageContrast.apply(bitmap, requestedFactor)
        }

        val frame = pipeline(jpeg(320, 240), contrast = contrast).load(media())

        assertNotNull(frame)
        assertEquals(1, calls)
        assertEquals(FeedImagePipeline.DEFAULT_CONTRAST_FACTOR, factor)
        assertEquals(factor, frame!!.contrastFactor)
    }

    @Test
    fun rejectsFetcherBodyOverDownloadCap() {
        val pipeline = FeedImagePipeline(
            fetcher = FeedImageFetcher { _, _ -> ByteArray(FeedImagePipeline.MAX_DOWNLOAD_BYTES + 1) },
            dispatcher = Dispatchers.Unconfined,
        )

        assertThrows(FeedImageTooLargeException::class.java) {
            runBlocking { pipeline.load(media()) }
        }
    }

    @Test
    fun cachedMediaDoesNotRefetch() = runBlocking {
        var fetches = 0
        val input = jpeg(320, 240)
        val pipeline = FeedImagePipeline(
            fetcher = FeedImageFetcher { _, _ -> fetches += 1; input },
            dispatcher = Dispatchers.Unconfined,
        )

        val first = pipeline.load(media())
        val second = pipeline.load(media())

        assertEquals(1, fetches)
        assertArrayEquals(first!!.bytes, second!!.bytes)
        assertEquals(first.contentKey, second.contentKey)
    }

    @Test
    fun httpFetcherRejectsUnexpectedAndPrivateDestinationsBeforeConnecting() {
        val publicResolver = FeedImageAddressResolver { arrayOf(InetAddress.getByName("8.8.8.8")) }
        assertThrows(FeedImageUnsafeUrlException::class.java) {
            HttpFeedImageFetcher(publicResolver).fetch(
                "https://localhost/image.jpg",
                FeedImagePipeline.MAX_DOWNLOAD_BYTES,
            )
        }

        val privateResolver = FeedImageAddressResolver { arrayOf(InetAddress.getByName("127.0.0.1")) }
        assertThrows(FeedImageUnsafeUrlException::class.java) {
            HttpFeedImageFetcher(privateResolver).fetch(
                "https://pbs.twimg.com/media/image.jpg",
                FeedImagePipeline.MAX_DOWNLOAD_BYTES,
            )
        }
    }

    private fun pipeline(
        bytes: ByteArray,
        contrast: FeedImageContrast = ColorMatrixFeedImageContrast,
        encoder: FeedJpegEncoder = AndroidFeedJpegEncoder,
    ) = FeedImagePipeline(
        fetcher = FeedImageFetcher { _, _ -> bytes },
        dispatcher = Dispatchers.Unconfined,
        contrast = contrast,
        encoder = encoder,
    )

    private fun media() = FeedMedia(
        type = FeedMediaType.PHOTO,
        url = "https://cdn.example/media/full.jpg",
        previewUrl = "https://cdn.example/media/preview.jpg",
        altText = "",
        durationMs = null,
    )

    private fun jpeg(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        for (y in 0 until height) {
            val green = y * 255 / height.coerceAtLeast(1)
            for (x in 0 until width) {
                bitmap.setPixel(x, y, Color.rgb(x * 255 / width.coerceAtLeast(1), green, (x + y) % 256))
            }
        }
        return try {
            encode(bitmap, 94)
        } finally {
            bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output))
            output.toByteArray()
        }
}
