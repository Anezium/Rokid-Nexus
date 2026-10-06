package com.anezium.rokidbus.plugin.patcher

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import com.anezium.rokidbus.client.ui.NexusUi

/**
 * Thin phosphor progress bar. A known fraction fills from the left. An unknown one
 * sweeps a segment so the screen visibly lives, and draws a static dashed fill
 * instead when the user has turned animations off.
 */
class PhosphorBar(context: Context) : View(context) {
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NexusUi.LINE2 }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NexusUi.GREEN }
    private val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = NexusUi.alpha(NexusUi.GREEN, 0x70) }
    private val rect = RectF()
    private var fraction: Float? = null
    private var sweep = 0f
    private var sweeping: ValueAnimator? = null

    val isSweeping: Boolean get() = sweeping != null

    fun show(fraction: Double?) {
        this.fraction = fraction?.toFloat()
        if (fraction == null) startSweep() else stopSweep()
        invalidate()
    }

    private fun startSweep() {
        if (sweeping != null || !isAttachedToWindow || !ValueAnimator.areAnimatorsEnabled()) return
        sweeping = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener { sweep = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    private fun stopSweep() {
        sweeping?.cancel()
        sweeping = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (fraction == null) startSweep()
    }

    override fun onDetachedFromWindow() {
        stopSweep()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val r = h / 2
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, track)
        val known = fraction
        when {
            known != null -> if (known > 0f) {
                rect.set(0f, 0f, (w * known).coerceAtLeast(h), h)
                canvas.drawRoundRect(rect, r, r, fill)
            }
            sweeping != null -> {
                val segment = w * 0.28f
                val x = (w + segment) * sweep - segment
                rect.set(x.coerceAtLeast(0f), 0f, (x + segment).coerceAtMost(w), h)
                if (rect.width() > 0f) canvas.drawRoundRect(rect, r, r, fill)
            }
            else -> {
                val dash = h * 3
                var x = 0f
                while (x < w) {
                    rect.set(x, 0f, minOf(x + dash, w), h)
                    canvas.drawRoundRect(rect, r, r, dim)
                    x += dash * 2
                }
            }
        }
    }
}
