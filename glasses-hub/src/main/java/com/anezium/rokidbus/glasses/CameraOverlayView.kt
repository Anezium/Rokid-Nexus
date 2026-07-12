package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import com.anezium.rokidbus.shared.CameraOverlayItem
import kotlin.math.max
import kotlin.math.min

/** AR-safe port of Lens' block renderer for generic structured camera overlays. */
internal class CameraOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {
    private val density = resources.displayMetrics.density
    private val scaledDensity = resources.displayMetrics.scaledDensity
    private val panelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val outlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = GREEN
        textSize = 12f * scaledDensity
        typeface = Typeface.MONOSPACE
    }
    private val hudBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        alpha = 220
        style = Paint.Style.FILL
    }
    private val bitmapMatrix = Matrix()
    private val stabilizer = CameraOverlayStabilizer()

    private var items: List<CameraOverlayItem> = emptyList()
    private var status: String = "STARTING"
    private var zoomLabel: String = "1.0x"
    private var frozenBackground: Bitmap? = null
    private var frozenDisplayScale = 1f

    fun updateOverlay(next: List<CameraOverlayItem>) {
        items = stabilizer.update(next)
        invalidate()
    }

    fun updateStatus(next: String, zoom: String = zoomLabel) {
        status = next
        zoomLabel = zoom
        invalidate()
    }

    fun setFrozenBackground(bitmap: Bitmap?, recyclePrevious: Boolean = true) {
        val previous = frozenBackground
        frozenBackground = bitmap
        frozenDisplayScale = 1f
        if (recyclePrevious && previous !== bitmap && previous?.isRecycled == false) previous.recycle()
        invalidate()
    }

    fun setFrozenDisplayScale(scale: Float) {
        frozenDisplayScale = scale.coerceAtLeast(1f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val frozen = frozenBackground
        if (frozen != null && !frozen.isRecycled) {
            drawFrozenBackground(canvas, frozen)
        }
        val scaled = frozen != null && frozenDisplayScale > 1f
        if (scaled) {
            canvas.save()
            canvas.scale(frozenDisplayScale, frozenDisplayScale, width / 2f, height / 2f)
        }
        items.forEach { drawItem(canvas, it) }
        if (scaled) canvas.restore()
        drawHud(canvas)
    }

    private fun drawFrozenBackground(canvas: Canvas, bitmap: Bitmap) {
        val sourceRatio = bitmap.width.toFloat() / bitmap.height.toFloat()
        val viewRatio = width.toFloat() / height.toFloat()
        val scale = if (sourceRatio > viewRatio) height.toFloat() / bitmap.height else width.toFloat() / bitmap.width
        val dx = (width - bitmap.width * scale) / 2f
        val dy = (height - bitmap.height * scale) / 2f
        bitmapMatrix.reset()
        bitmapMatrix.postScale(scale, scale)
        bitmapMatrix.postTranslate(dx, dy)
        canvas.save()
        if (frozenDisplayScale > 1f) {
            canvas.scale(frozenDisplayScale, frozenDisplayScale, width / 2f, height / 2f)
        }
        canvas.drawBitmap(bitmap, bitmapMatrix, null)
        canvas.restore()
    }

    private fun drawItem(canvas: Canvas, item: CameraOverlayItem) {
        val rect = RectF(
            item.box.left * width,
            item.box.top * height,
            item.box.right * width,
            item.box.bottom * height,
        )
        if (rect.width() < 2f || rect.height() < 2f) return
        val color = roleColor(item.role)
        outlinePaint.color = color
        textPaint.color = color
        canvas.drawRect(rect, panelPaint)
        canvas.drawRect(rect, outlinePaint)
        val padding = 3f * density
        val textWidth = max(1, (rect.width() - padding * 2f).toInt())
        val textHeight = max(1f, rect.height() - padding * 2f)
        val layout = fitText(item.text, textWidth, textHeight)
        canvas.save()
        canvas.clipRect(rect)
        canvas.translate(rect.left + padding, rect.top + padding)
        layout.draw(canvas)
        canvas.restore()
    }

    private fun fitText(text: String, availableWidth: Int, availableHeight: Float): StaticLayout {
        var size = min(28f * scaledDensity, availableHeight * 0.6f).coerceAtLeast(MIN_TEXT_SP * scaledDensity)
        var layout: StaticLayout
        while (true) {
            textPaint.textSize = size
            layout = StaticLayout.Builder.obtain(text, 0, text.length, textPaint, availableWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setLineSpacing(0f, 1f)
                .build()
            if (layout.height <= availableHeight || size <= MIN_TEXT_SP * scaledDensity) return layout
            size = max(MIN_TEXT_SP * scaledDensity, size - scaledDensity)
        }
    }

    private fun drawHud(canvas: Canvas) {
        val lines = listOf("CAMERA", status.take(MAX_STATUS_CHARS), zoomLabel)
        val padding = 6f * density
        val lineHeight = hudPaint.textSize + 3f * density
        val textWidth = lines.maxOf(hudPaint::measureText)
        val right = width - 4f * density
        val rect = RectF(
            right - textWidth - padding * 2f,
            4f * density,
            right,
            4f * density + padding * 2f + lineHeight * lines.size,
        )
        canvas.drawRoundRect(rect, 5f * density, 5f * density, hudBackgroundPaint)
        var baseline = rect.top + padding + hudPaint.textSize
        lines.forEach { line ->
            canvas.drawText(line, rect.left + padding, baseline, hudPaint)
            baseline += lineHeight
        }
    }

    private fun roleColor(role: String): Int = when (role.lowercase()) {
        "source" -> CYAN
        "label", "status" -> AMBER
        else -> GREEN
    }

    companion object {
        private val GREEN = Color.rgb(0, 255, 102)
        private val CYAN = Color.rgb(80, 220, 255)
        private val AMBER = Color.rgb(255, 179, 0)
        private const val MIN_TEXT_SP = 11f
        private const val MAX_STATUS_CHARS = 48
    }
}
