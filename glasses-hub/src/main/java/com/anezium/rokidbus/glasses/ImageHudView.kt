package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.View
import com.anezium.rokidbus.client.ui.BusTheme

/** Draws decoded pixels as-is with FIT_CENTER on the AR-safe black surface. */
class ImageHudView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var bitmap: Bitmap? = null

    init {
        setBackgroundColor(BusTheme.glassesBg)
    }

    fun render(surface: NexusSurface?) {
        bitmap = surface?.takeIf { it.isImage }?.imageBitmap
        contentDescription = surface?.imageMetadata?.caption
            ?.takeIf(String::isNotBlank)
            ?: surface?.title.orEmpty()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val image = bitmap?.takeUnless(Bitmap::isRecycled) ?: return
        source.set(0, 0, image.width, image.height)
        val availableWidth = (width - paddingLeft - paddingRight).coerceAtLeast(0).toFloat()
        val availableHeight = (height - paddingTop - paddingBottom).coerceAtLeast(0).toFloat()
        if (availableWidth <= 0f || availableHeight <= 0f) return
        val scale = minOf(availableWidth / image.width, availableHeight / image.height)
        val drawWidth = image.width * scale
        val drawHeight = image.height * scale
        val left = paddingLeft + (availableWidth - drawWidth) / 2f
        val top = paddingTop + (availableHeight - drawHeight) / 2f
        destination.set(left, top, left + drawWidth, top + drawHeight)
        canvas.drawBitmap(image, source, destination, paint)
    }

    override fun onDetachedFromWindow() {
        bitmap = null
        super.onDetachedFromWindow()
    }

    companion object {
        fun decodeRgb565(bytes: ByteArray, metadata: SurfaceImageMetadata): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth != metadata.pixelWidth || bounds.outHeight != metadata.pixelHeight ||
                bounds.outWidth !in 1..512 || bounds.outHeight !in 1..512 ||
                bounds.outWidth.toLong() * bounds.outHeight.toLong() > 512L * 512L ||
                bounds.outMimeType != metadata.mimeType
            ) return null

            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inDither = false
            }
            val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
            if (decoded.width != metadata.pixelWidth || decoded.height != metadata.pixelHeight) {
                decoded.recycle()
                return null
            }
            if (decoded.config == Bitmap.Config.RGB_565) return decoded
            val rgb565 = decoded.copy(Bitmap.Config.RGB_565, false)
            decoded.recycle()
            return rgb565
        }
    }
}
