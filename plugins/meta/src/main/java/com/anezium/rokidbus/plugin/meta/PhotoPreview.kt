package com.anezium.rokidbus.plugin.meta

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anezium.rokidbus.shared.LauncherArtwork
import java.io.ByteArrayOutputStream
import java.util.Base64

/**
 * Shrinks the camera's JPEG to what an image page accepts (launcher bundle v6): both edges at
 * most [LauncherArtwork.MAX_PAGE_EDGE] and the encoding at most [LauncherArtwork.MAX_PAGE_BYTES],
 * lowering the JPEG quality until it fits. The photo sent to the recipient is never this
 * reduced copy.
 */
internal object PhotoPreviewEncoder {
    private const val FIRST_QUALITY = 75
    private const val LAST_QUALITY = 30
    private const val QUALITY_STEP = 15

    fun encode(jpeg: ByteArray): LauncherArtwork? {
        if (jpeg.isEmpty()) return null
        val maxEdge = LauncherArtwork.MAX_PAGE_EDGE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        val longEdge = maxOf(bounds.outWidth, bounds.outHeight)
        while (longEdge / (sampleSize * 2) >= maxEdge) sampleSize *= 2
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sampleSize })
            ?: return null
        try {
            val decodedLongEdge = maxOf(bitmap.width, bitmap.height)
            if (decodedLongEdge > maxEdge) {
                val scale = maxEdge.toFloat() / decodedLongEdge
                val scaled = Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                )
                if (scaled !== bitmap) {
                    bitmap.recycle()
                    bitmap = scaled
                }
            }
            var quality = FIRST_QUALITY
            while (true) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= LauncherArtwork.MAX_PAGE_BYTES) {
                    return runCatching {
                        LauncherArtwork(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(bytes), LauncherArtwork.JPEG)
                    }.getOrNull()
                }
                if (quality <= LAST_QUALITY) return null
                quality -= QUALITY_STEP
            }
        } finally {
            bitmap.recycle()
        }
    }
}
