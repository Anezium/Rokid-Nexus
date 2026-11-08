package com.anezium.rokidbus.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import com.anezium.rokidbus.client.ui.NexusUi

/** Side profile connects the measured angle to the head movement. */
internal class HeadTiltPreviewView(context: Context) : View(context) {
    var upDegrees = 0f
        set(value) { field = value; invalidate() }
    var threshold = 20
        set(value) { field = value; invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), NexusUi.dp(context, 190))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val scale = height / 190f
        canvas.save()
        canvas.translate(width / 2f, height * .56f)
        canvas.scale(scale, scale)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = NexusUi.LINE
        canvas.drawLine(-115f, 0f, 115f, 0f, paint)
        canvas.drawArc(RectF(-94f, -94f, 94f, 94f), -threshold.toFloat(), threshold.toFloat(), false, paint)
        val radians = Math.toRadians(-threshold.toDouble())
        canvas.drawLine(0f, 0f, (115 * kotlin.math.cos(radians)).toFloat(), (115 * kotlin.math.sin(radians)).toFloat(), paint)
        paint.color = NexusUi.INK3
        canvas.drawLine(-18f, 27f, -18f, 62f, paint)
        canvas.drawLine(-18f, 62f, 40f, 62f, paint)
        canvas.save()
        canvas.rotate(-upDegrees.coerceIn(-30f, 60f), -18f, 27f)
        paint.color = if (upDegrees >= threshold) NexusUi.ACCENT else NexusUi.INK
        paint.strokeWidth = 3f
        canvas.drawOval(RectF(-42f, -51f, 25f, 29f), paint)
        canvas.drawLine(25f, -19f, 37f, -7f, paint)
        canvas.drawLine(37f, -7f, 25f, -3f, paint)
        canvas.drawCircle(12f, -24f, 2f, paint)
        canvas.restore()
        canvas.restore()
    }
}
