package com.anezium.rokidbus.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.anezium.rokidbus.shared.ImageSurfaceContract
import com.anezium.rokidbus.shared.MediaArtworkContract
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import kotlin.math.roundToInt

internal sealed interface EncodedMediaArtwork

internal data class EncodedMonoArtwork(
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val hash: String,
) : EncodedMediaArtwork {
    fun toJson(): JSONObject = JSONObject()
        .put("encoding", "mono1")
        .put("width", width)
        .put("height", height)
        .put("hash", hash)
        .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
}

internal data class EncodedImageArtwork(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val bytes: ByteArray,
    val sha256: String,
    val jpegQuality: Int,
) : EncodedMediaArtwork

internal enum class MediaArtworkMode { IMAGE, MONO }

internal fun artworkModeFor(supportsImage: Boolean): MediaArtworkMode =
    if (supportsImage) MediaArtworkMode.IMAGE else MediaArtworkMode.MONO

internal object MonoArtworkEncoder {
    const val SIZE = 96

    fun encode(context: Context, source: Bitmap?, sourceUri: String): EncodedMonoArtwork? {
        encode(source)?.let { return it }
        if (sourceUri.isBlank()) return null
        val decoded = runCatching { decodeLocalArtwork(context, sourceUri) }.getOrNull() ?: return null
        return try {
            encode(decoded)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    fun encode(source: Bitmap?): EncodedMonoArtwork? {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return runCatching { encodeChecked(source) }.getOrNull()
    }

    private fun encodeChecked(source: Bitmap): EncodedMonoArtwork {
        val owned = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap {
            if (bitmap !== source && owned.none { it === bitmap }) owned += bitmap
            return bitmap
        }

        try {
            val software = if (source.config?.toString() == "HARDWARE") {
                own(requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)))
            } else {
                source
            }
            val side = minOf(software.width, software.height)
            val left = (software.width - side) / 2
            val top = (software.height - side) / 2
            val cropped = own(Bitmap.createBitmap(software, left, top, side, side))
            val scaled = own(Bitmap.createScaledBitmap(cropped, SIZE, SIZE, true))
            val pixels = IntArray(SIZE * SIZE)
            scaled.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            val luminance = FloatArray(pixels.size)
            val histogram = IntArray(256)
            pixels.forEachIndexed { index, color ->
                val alpha = color ushr 24 and 0xff
                val red = color ushr 16 and 0xff
                val green = color ushr 8 and 0xff
                val blue = color and 0xff
                val value = ((red * 54 + green * 183 + blue * 19) / 256f) * (alpha / 255f)
                luminance[index] = value
                histogram[value.roundToInt().coerceIn(0, 255)]++
            }
            normalizeContrast(luminance, histogram)
            val packed = dither(luminance, SIZE, SIZE)
            return EncodedMonoArtwork(
                width = SIZE,
                height = SIZE,
                bytes = packed,
                hash = shortHash(packed),
            )
        } finally {
            owned.asReversed().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun decodeLocalArtwork(context: Context, value: String): Bitmap? {
        val uri = Uri.parse(value)
        if (uri.scheme !in LOCAL_URI_SCHEMES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private fun normalizeContrast(values: FloatArray, histogram: IntArray) {
        val low = percentile(histogram, values.size, 0.04f)
        val high = percentile(histogram, values.size, 0.96f)
        if (high - low < 24) return
        val scale = 255f / (high - low)
        values.indices.forEach { index ->
            values[index] = ((values[index] - low) * scale).coerceIn(0f, 255f)
        }
    }

    private fun percentile(histogram: IntArray, total: Int, fraction: Float): Int {
        val target = (total * fraction).roundToInt()
        var seen = 0
        histogram.forEachIndexed { value, count ->
            seen += count
            if (seen >= target) return value
        }
        return 255
    }

    private fun dither(values: FloatArray, width: Int, height: Int): ByteArray {
        val output = ByteArray((width * height + 7) / 8)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                val old = values[index].coerceIn(0f, 255f)
                val bright = old >= 128f
                val next = if (bright) 255f else 0f
                if (bright) {
                    output[index / 8] = (output[index / 8].toInt() or (1 shl (7 - index % 8))).toByte()
                }
                val error = old - next
                if (x + 1 < width) values[index + 1] += error * 7f / 16f
                if (y + 1 < height) {
                    if (x > 0) values[index + width - 1] += error * 3f / 16f
                    values[index + width] += error * 5f / 16f
                    if (x + 1 < width) values[index + width + 1] += error / 16f
                }
            }
        }
        return output
    }

    private fun shortHash(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val hex = "0123456789abcdef"
        return buildString(16) {
            for (index in 0 until 8) {
                val value = digest[index].toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private const val MAX_DECODE_DIMENSION = 512
    private val LOCAL_URI_SCHEMES = setOf("content", "android.resource", "file")
}

internal object ImageArtworkEncoder {
    private val JPEG_QUALITIES = listOf(88, 80, 72, 60, 48, 36)

    fun encode(context: Context, source: Bitmap?, sourceUri: String): EncodedImageArtwork? {
        encode(source)?.let { return it }
        if (sourceUri.isBlank()) return null
        val decoded = runCatching { decodeLocalArtwork(context, sourceUri) }.getOrNull() ?: return null
        return try {
            encode(decoded)
        } finally {
            if (!decoded.isRecycled) decoded.recycle()
        }
    }

    fun encode(source: Bitmap?): EncodedImageArtwork? {
        if (source == null || source.isRecycled || source.width <= 0 || source.height <= 0) return null
        return runCatching { encodeChecked(source) }.getOrNull()
    }

    private fun encodeChecked(source: Bitmap): EncodedImageArtwork? {
        val owned = mutableListOf<Bitmap>()
        fun own(bitmap: Bitmap): Bitmap {
            if (bitmap !== source && owned.none { it === bitmap }) owned += bitmap
            return bitmap
        }

        try {
            val software = if (source.config?.toString() == "HARDWARE") {
                own(requireNotNull(source.copy(Bitmap.Config.ARGB_8888, false)))
            } else {
                source
            }
            val longestEdge = maxOf(software.width, software.height)
            val scale = minOf(1.0, MediaArtworkContract.MAX_EDGE_PIXELS.toDouble() / longestEdge)
            val targetWidth = (software.width * scale).roundToInt().coerceAtLeast(1)
            val targetHeight = (software.height * scale).roundToInt().coerceAtLeast(1)
            val scaled = if (software.width == targetWidth && software.height == targetHeight) {
                software
            } else {
                own(Bitmap.createScaledBitmap(software, targetWidth, targetHeight, true))
            }

            JPEG_QUALITIES.forEach { quality ->
                val bytes = ByteArrayOutputStream().use { output ->
                    if (!scaled.compress(Bitmap.CompressFormat.JPEG, quality, output)) return@forEach
                    output.toByteArray()
                }
                if (bytes.isEmpty() || bytes.size > ImageSurfaceContract.MAX_IMAGE_BYTES) return@forEach
                val dimensions = ImageSurfaceContract.dimensions(ImageSurfaceContract.MIME_JPEG, bytes)
                    ?: return@forEach
                if (dimensions.width != targetWidth || dimensions.height != targetHeight) return@forEach
                return EncodedImageArtwork(
                    mimeType = ImageSurfaceContract.MIME_JPEG,
                    width = targetWidth,
                    height = targetHeight,
                    bytes = bytes,
                    sha256 = ImageSurfaceContract.sha256(bytes),
                    jpegQuality = quality,
                )
            }
            return null
        } finally {
            owned.asReversed().forEach { bitmap ->
                if (!bitmap.isRecycled) bitmap.recycle()
            }
        }
    }

    private fun decodeLocalArtwork(context: Context, value: String): Bitmap? {
        val uri = Uri.parse(value)
        if (uri.scheme !in LOCAL_URI_SCHEMES) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, bounds)
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_DECODE_DIMENSION) {
            sampleSize *= 2
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BitmapFactory.decodeStream(input, null, options)
        }
    }

    private const val MAX_DECODE_DIMENSION = 512
    private val LOCAL_URI_SCHEMES = setOf("content", "android.resource", "file")
}
