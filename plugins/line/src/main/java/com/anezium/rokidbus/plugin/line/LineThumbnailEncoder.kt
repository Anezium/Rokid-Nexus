package com.anezium.rokidbus.plugin.line

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import java.io.ByteArrayOutputStream
import java.util.Base64

/** The same bounded JPEG thumbnails as Meta Messages, including a static frame for stickers. */
internal object LineThumbnailEncoder {
    fun encode(bytes: ByteArray): LauncherArtwork? {
        if (bytes.isEmpty() || bytes.size > 4 * 1024 * 1024) return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32_768 || bounds.outHeight !in 1..32_768) return null
        val maxEdge = PageTemplateContract.MAX_MESSAGE_IMAGE_EDGE
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val edge = maxOf(bitmap.width, bitmap.height)
            if (edge > maxEdge) {
                val scale = maxEdge.toFloat() / edge
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
            }
            for (quality in 70 downTo 25 step 15) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                val jpeg = output.toByteArray()
                if (jpeg.size <= PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES) {
                    return LauncherArtwork(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(jpeg), LauncherArtwork.JPEG)
                }
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }
}
