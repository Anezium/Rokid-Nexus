package com.anezium.rokidbus.glasses

import android.graphics.Bitmap
import android.graphics.Color

internal fun MonoArtwork.toPhosphorBitmap(phosphor: Int): Bitmap {
    val pixels = IntArray(width * height)
    for (index in pixels.indices) {
        val value = bytes[index / 8].toInt() and 0xff
        val bright = value and (1 shl (7 - index % 8)) != 0
        pixels[index] = if (bright) phosphor else Color.TRANSPARENT
    }
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}
