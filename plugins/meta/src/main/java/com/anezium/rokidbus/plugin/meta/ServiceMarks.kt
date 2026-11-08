package com.anezium.rokidbus.plugin.meta

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import java.util.Base64

/** A `mono1` [LauncherArtwork] packed from pixels: row-major, most significant bit first, 1 draws. */
internal object MonoMark {
    fun pack(width: Int, height: Int, lit: (x: Int, y: Int) -> Boolean): LauncherArtwork {
        val bytes = ByteArray((width * height + 7) / 8)
        for (y in 0 until height) for (x in 0 until width) {
            if (!lit(x, y)) continue
            val index = y * width + x
            bytes[index / 8] = (bytes[index / 8].toInt() or (0x80 ushr (index % 8))).toByte()
        }
        return LauncherArtwork(width, height, Base64.getEncoder().encodeToString(bytes))
    }
}

/**
 * The service marks of the phone screens as row icons for the glasses (launcher bundle v7): each
 * brand vector drawn once at the row icon's size and thresholded to a `mono1` mark, which the
 * glasses tint in the row's tone. Cached for the process; the vectors are tiny.
 */
internal object ServiceMarks {
    /** The glasses draw row icons at 24 px; a mark of that size needs no scaling there. */
    const val EDGE = 24

    private val cache = HashMap<MessagingService, LauncherArtwork?>()

    fun mark(context: Context, service: MessagingService): LauncherArtwork? = cache.getOrPut(service) {
        runCatching { rasterise(context, MetaUi.serviceMark(service.title) ?: return@getOrPut null) }.getOrNull()
    }

    private fun rasterise(context: Context, resId: Int): LauncherArtwork? {
        val edge = EDGE.coerceAtMost(PageTemplateContract.MAX_ROW_ICON_EDGE)
        val drawable = context.getDrawable(resId)?.mutate() ?: return null
        drawable.setTint(Color.WHITE)
        val bitmap = Bitmap.createBitmap(edge, edge, Bitmap.Config.ARGB_8888)
        try {
            drawable.setBounds(0, 0, edge, edge)
            drawable.draw(Canvas(bitmap))
            val pixels = IntArray(edge * edge)
            bitmap.getPixels(pixels, 0, edge, 0, 0, edge, edge)
            return MonoMark.pack(edge, edge) { x, y -> Color.alpha(pixels[y * edge + x]) >= 128 }
        } finally {
            bitmap.recycle()
        }
    }
}
