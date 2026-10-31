package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import com.anezium.rokidbus.shared.LauncherArtwork

internal fun LauncherArtwork.drawable(context: Context, phosphor: Int): BitmapDrawable? {
    val key = ArtworkBitmapKey(this, if (encoding == LauncherArtwork.JPEG) 0 else phosphor)
    val bitmap = artworkBitmaps.get(key) ?: run {
        val bytes = bytes()
        val decoded = if (encoding == LauncherArtwork.JPEG) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth != width || bounds.outHeight != height) return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            })?.takeIf { it.width == width && it.height == height }
        } else {
            val pixels = IntArray(width * height) { index ->
                if ((bytes[index / 8].toInt() and (1 shl (7 - index % 8))) != 0) phosphor else Color.TRANSPARENT
            }
            Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
        } ?: return null
        artworkBitmaps.put(key, decoded)
        decoded
    }
    return BitmapDrawable(context.resources, bitmap).apply {
        paint.isAntiAlias = encoding == LauncherArtwork.JPEG
        isFilterBitmap = encoding == LauncherArtwork.JPEG
        paint.isDither = false
    }
}

private data class ArtworkBitmapKey(val artwork: LauncherArtwork, val phosphor: Int)

// Bound entries as well as pixels: even a tiny JPEG can retain a full-size encoded key.
// Visible cards may still use an evicted bitmap, so eviction never recycles it explicitly.
private val artworkBitmaps = android.util.LruCache<ArtworkBitmapKey, Bitmap>(8)
