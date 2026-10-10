package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import com.anezium.rokidbus.shared.RouteRibbonBetween
import com.anezium.rokidbus.shared.RouteRibbonStop
import com.anezium.rokidbus.shared.RouteStopState

/**
 * A transit ride's stops on one vertical rail: stops passed dim, stops ahead in ink, the stop to
 * get off at bright and bold in a double ring. The rail is dim up to the vehicle and bright after
 * it. The vehicle is drawn only when the provider says where it is between two stops; without
 * that the rail turns bright at the last stop passed, and nothing is placed on a guess.
 */
internal class RibbonView(context: Context) : View(context) {
    private var stops: List<RouteRibbonStop> = emptyList()
    private var between: RouteRibbonBetween? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val name = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = PageStyle.sans }
    private val time = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.mono
        textSize = TIME_PX
        textAlign = Paint.Align.RIGHT
    }
    private val rect = RectF()

    /** Where the rail changes from passed to ahead, as last drawn. */
    var cutY: Float = 0f
        private set

    /** The vehicle's centre, or null when no position between stops was given. */
    var vehicleY: Float? = null
        private set

    fun bind(stops: List<RouteRibbonStop>, between: RouteRibbonBetween?) {
        val resized = stops.size != this.stops.size
        this.stops = stops
        this.between = between
        if (resized) requestLayout()
        invalidate()
    }

    fun yOf(index: Int): Float = TOP_PX + index * PITCH_PX

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val height = if (stops.isEmpty()) 0 else (TOP_PX * 2 + (stops.size - 1) * PITCH_PX).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        if (stops.isEmpty()) return
        val last = stops.lastIndex
        val placed = between?.let { at ->
            val after = at.after.coerceIn(0, last)
            val next = (after + 1).coerceAtMost(last)
            yOf(after) + (yOf(next) - yOf(after)) * at.fraction.toFloat().coerceIn(0f, 1f)
        }
        val lastPassed = stops.indexOfLast { it.state == RouteStopState.PASSED }
        cutY = placed ?: if (lastPassed >= 0) yOf(lastPassed) else yOf(0)
        vehicleY = placed

        line.strokeWidth = RAIL_PX
        if (cutY > yOf(0)) {
            line.color = PageStyle.dim
            canvas.drawLine(RAIL_X, yOf(0), RAIL_X, cutY, line)
        }
        if (cutY < yOf(last)) {
            line.color = PageStyle.phosphor
            canvas.drawLine(RAIL_X, cutY, RAIL_X, yOf(last), line)
        }

        stops.forEachIndexed { index, stop ->
            val y = yOf(index)
            val alight = stop.state == RouteStopState.ALIGHT
            val passed = stop.state == RouteStopState.PASSED
            if (alight) {
                ring(canvas, y, 15f, PageStyle.dim, 2f)
                fill.color = PageStyle.black
                canvas.drawCircle(RAIL_X, y, 9f, fill)
                ring(canvas, y, 9f, PageStyle.phosphor, 3f)
                fill.color = PageStyle.phosphor
                canvas.drawCircle(RAIL_X, y, 3.5f, fill)
            } else {
                fill.color = PageStyle.black
                canvas.drawCircle(RAIL_X, y, 7f, fill)
                ring(canvas, y, 7f, if (passed) PageStyle.dim else PageStyle.phosphor, 3f)
            }
            val right = width - SIDE_PX
            var nameRight = right
            stop.time?.let { value ->
                time.color = if (alight) PageStyle.ink else PageStyle.muted
                canvas.drawText(value, right, y + 5f, time)
                nameRight = right - time.measureText(value) - TIME_GAP_PX
            }
            name.textSize = if (alight) ALIGHT_PX else NAME_PX
            name.typeface = if (alight) PageStyle.sansBold else PageStyle.sans
            name.color = when {
                alight -> PageStyle.phosphor
                passed -> PageStyle.dim
                else -> PageStyle.ink
            }
            val room = (nameRight - NAME_X).coerceAtLeast(0f)
            val shown = TextUtils.ellipsize(stop.name, name, room, TextUtils.TruncateAt.END)
            canvas.drawText(shown, 0, shown.length, NAME_X, y + 6f, name)
        }

        placed?.let { y ->
            rect.set(RAIL_X - 13f, y - 10f, RAIL_X + 13f, y + 10f)
            fill.color = PageStyle.black
            canvas.drawRoundRect(rect, 5f, 5f, fill)
            line.color = PageStyle.phosphor
            line.strokeWidth = 3f
            canvas.drawRoundRect(rect, 5f, 5f, line)
            fill.color = PageStyle.phosphor
            canvas.drawRect(RAIL_X - 7f, y - 5f, RAIL_X + 7f, y, fill)
        }
    }

    private fun ring(canvas: Canvas, y: Float, radius: Float, color: Int, widthPx: Float) {
        line.color = color
        line.strokeWidth = widthPx
        canvas.drawCircle(RAIL_X, y, radius, line)
    }

    companion object {
        const val TOP_PX = 28f
        const val PITCH_PX = 44f
        const val RAIL_X = 36f
        const val RAIL_PX = 6f
        const val NAME_X = 66f
        const val NAME_PX = 17f
        const val ALIGHT_PX = 19f
        const val TIME_PX = 14f
        const val TIME_GAP_PX = 12f
        const val SIDE_PX = 18f
    }
}
