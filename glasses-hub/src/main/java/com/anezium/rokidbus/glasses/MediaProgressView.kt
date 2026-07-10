package com.anezium.rokidbus.glasses

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import com.anezium.rokidbus.client.ui.BusTheme
import kotlin.math.abs

internal class MediaProgressView(context: Context) : View(context) {
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BusTheme.hairline
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(2f)
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = BusTheme.phosphor
        strokeCap = Paint.Cap.ROUND
        strokeWidth = dp(3f)
    }
    private var progress = 0f

    fun setProgress(value: Float) {
        val next = value.coerceIn(0f, 1f)
        if (abs(progress - next) < 0.001f) return
        progress = next
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(
            MeasureSpec.getSize(widthMeasureSpec),
            resolveSize(dp(14f).toInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val inset = dp(5f)
        val start = inset
        val end = (width - inset).coerceAtLeast(start)
        val y = height / 2f
        canvas.drawLine(start, y, end, y, trackPaint)
        if (progress > 0f) {
            val progressX = start + (end - start) * progress
            canvas.drawLine(start, y, progressX, y, progressPaint)
            canvas.drawCircle(progressX, y, dp(2.5f), progressPaint)
        }
    }

    private fun dp(value: Float): Float = value * resources.displayMetrics.density
}
