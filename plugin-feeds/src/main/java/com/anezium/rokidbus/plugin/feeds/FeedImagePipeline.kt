package com.anezium.rokidbus.plugin.feeds

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.ImageSurfaceValidationResult
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import kotlin.math.roundToInt

internal fun interface FeedImageLoader {
    suspend fun load(media: FeedMedia): FeedImageFrame?
}

internal data class FeedImageFrame(
    val contentKey: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val mimeType: String,
    val sha256: String,
    val bytes: ByteArray,
    val jpegQuality: Int,
    val contrastFactor: Float,
) {
    fun payload(): JSONObject = JSONObject()
        .put("kind", ImageSurfaceContract.KIND)
        .put("imageVersion", ImageSurfaceContract.VERSION)
        .put("contentKey", contentKey)
        .put("mimeType", mimeType)
        .put("pixelWidth", pixelWidth)
        .put("pixelHeight", pixelHeight)
        .put("sha256", sha256)
}

internal fun interface FeedImageFetcher {
    fun fetch(url: String, maxBytes: Int): ByteArray
}

internal class HttpFeedImageFetcher : FeedImageFetcher {
    override fun fetch(url: String, maxBytes: Int): ByteArray {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
            instanceFollowRedirects = true
            setRequestProperty("Accept", "image/*")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            if (status !in 200..299) throw IOException("Image HTTP $status")
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maxBytes) {
                throw FeedImageTooLargeException("Image body exceeds $maxBytes bytes")
            }
            connection.inputStream.buffered().use { input ->
                val output = ByteArrayOutputStream(
                    declaredLength.takeIf { it in 1..maxBytes.toLong() }?.toInt() ?: DEFAULT_BUFFER_BYTES,
                )
                val buffer = ByteArray(DEFAULT_BUFFER_BYTES)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > maxBytes) {
                        throw FeedImageTooLargeException("Image body exceeds $maxBytes bytes")
                    }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val TIMEOUT_MS = 8_000
        const val DEFAULT_BUFFER_BYTES = 16 * 1024
        const val USER_AGENT = "RokidNexus/0.1 (+https://github.com/Anezium)"
    }
}

internal class FeedImageTooLargeException(message: String) : IOException(message)

internal fun interface FeedImageContrast {
    fun apply(bitmap: Bitmap, factor: Float): Bitmap
}

internal object ColorMatrixFeedImageContrast : FeedImageContrast {
    override fun apply(bitmap: Bitmap, factor: Float): Bitmap {
        val adjustedFactor = factor.coerceIn(MIN_CONTRAST_FACTOR, MAX_CONTRAST_FACTOR)
        val contrastOffset = 128f * (1f - adjustedFactor)
        val brightnessLift = (adjustedFactor - 1f).coerceAtLeast(0f) * 24f
        val offset = contrastOffset + brightnessLift
        val matrix = ColorMatrix(
            floatArrayOf(
                adjustedFactor, 0f, 0f, 0f, offset,
                0f, adjustedFactor, 0f, 0f, offset,
                0f, 0f, adjustedFactor, 0f, offset,
                0f, 0f, 0f, 1f, 0f,
            ),
        )
        val output = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(output).drawBitmap(
            bitmap,
            0f,
            0f,
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                colorFilter = ColorMatrixColorFilter(matrix)
            },
        )
        return output
    }

    private const val MIN_CONTRAST_FACTOR = 1f
    private const val MAX_CONTRAST_FACTOR = 1.35f
}

internal fun interface FeedJpegEncoder {
    fun encode(bitmap: Bitmap, quality: Int): ByteArray
}

internal object AndroidFeedJpegEncoder : FeedJpegEncoder {
    override fun encode(bitmap: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) { "JPEG encoding failed" }
            output.toByteArray()
        }
}

internal class FeedImagePipeline(
    private val fetcher: FeedImageFetcher = HttpFeedImageFetcher(),
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val contrastFactor: Float = DEFAULT_CONTRAST_FACTOR,
    private val contrast: FeedImageContrast = ColorMatrixFeedImageContrast,
    private val encoder: FeedJpegEncoder = AndroidFeedJpegEncoder,
    cacheEntries: Int = DEFAULT_CACHE_ENTRIES,
) : FeedImageLoader {
    private val cacheSize = cacheEntries.coerceAtLeast(1)
    private val cache = object : LinkedHashMap<String, FeedImageFrame>(cacheSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FeedImageFrame>?): Boolean =
            size > cacheSize
    }

    override suspend fun load(media: FeedMedia): FeedImageFrame? {
        val requestUrl = media.previewUrl.trim().ifBlank { media.url.trim() }
        if (requestUrl.isBlank()) return null
        val cacheKey = media.url.trim().ifBlank { requestUrl }
        synchronized(cache) { cache[cacheKey] }?.let { return it }
        return withContext(dispatcher) {
            synchronized(cache) { cache[cacheKey] }?.let { return@withContext it }
            val downloaded = fetcher.fetch(requestUrl, MAX_DOWNLOAD_BYTES)
            if (downloaded.size > MAX_DOWNLOAD_BYTES) {
                throw FeedImageTooLargeException("Image body exceeds $MAX_DOWNLOAD_BYTES bytes")
            }
            process(cacheKey, downloaded)?.also { frame ->
                synchronized(cache) { cache[cacheKey] = frame }
            }
        }
    }

    private fun process(cacheKey: String, downloaded: ByteArray): FeedImageFrame? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(downloaded, 0, downloaded.size, bounds)
        val sourceWidth = bounds.outWidth
        val sourceHeight = bounds.outHeight
        if (sourceWidth <= 0 || sourceHeight <= 0) return null

        val longestEdge = maxOf(sourceWidth, sourceHeight)
        val scale = minOf(1.0, ImageSurfaceContract.MAX_EDGE_PIXELS.toDouble() / longestEdge)
        val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
        var sampleSize = 1
        while (
            sourceWidth / (sampleSize * 2) >= targetWidth &&
            sourceHeight / (sampleSize * 2) >= targetHeight
        ) {
            sampleSize *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            downloaded,
            0,
            downloaded.size,
            BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            },
        ) ?: return null
        val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also { decoded.recycle() }
        }
        val adjusted = try {
            contrast.apply(scaled, contrastFactor)
        } catch (failure: Throwable) {
            scaled.recycle()
            throw failure
        }
        if (adjusted !== scaled) scaled.recycle()
        try {
            JPEG_QUALITIES.forEach { quality ->
                val bytes = encoder.encode(adjusted, quality)
                if (bytes.size > ImageSurfaceContract.MAX_IMAGE_BYTES) return@forEach
                val frame = FeedImageFrame(
                    contentKey = contentKey(cacheKey),
                    pixelWidth = adjusted.width,
                    pixelHeight = adjusted.height,
                    mimeType = ImageSurfaceContract.MIME_JPEG,
                    sha256 = ImageSurfaceContract.sha256(bytes),
                    bytes = bytes,
                    jpegQuality = quality,
                    contrastFactor = contrastFactor,
                )
                if (ImageSurfaceContract.validate(frame.payload(), bytes) is ImageSurfaceValidationResult.Valid) {
                    return frame
                }
            }
            return null
        } finally {
            adjusted.recycle()
        }
    }

    private fun contentKey(cacheKey: String): String =
        "feed-${ImageSurfaceContract.sha256(cacheKey.toByteArray(StandardCharsets.UTF_8)).take(32)}"

    companion object {
        const val MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024
        const val DEFAULT_CONTRAST_FACTOR = 1.15f
        const val DEFAULT_CACHE_ENTRIES = 8
        internal val JPEG_QUALITIES = listOf(85, 72, 60, 48)
    }
}
