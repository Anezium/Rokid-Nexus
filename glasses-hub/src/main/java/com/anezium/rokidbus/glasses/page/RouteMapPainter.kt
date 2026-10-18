package com.anezium.rokidbus.glasses.page

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.view.View
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RenderedMapContext

/** What one map draw is given: the provider's geometry and the hub's own view of it. */
internal data class MapInput(
    val geometry: RouteGeometry,
    val form: MapForm,
    val view: MapView = MapView.FOLLOW,
    /** The smoothed heading, null when absent or older than the hub trusts. */
    val headingDeg: Float? = null,
    val degraded: Boolean = false,
    val insets: MapInsets = MapInsets(),
    /** r17 draws no compass on the small walk-to-stop island; everywhere else it stays. */
    val compass: Boolean = true,
    val renderContext: RenderedMapContext? = null,
)

/** Everything decided for one draw, before a pixel is touched; kept for tests. */
internal data class MapPlan(
    val transform: MapTransform,
    val style: MapStyle,
    val scaleBar: ScaleBar?,
    val labels: List<MapLabel>,
    /** The compass centre, or null without a compass. */
    val compassX: Float?,
    val compassY: Float?,
    /** The credit for the map's street data, bottom right, or null when the geometry carries none. */
    val attribution: MapAttribution? = null,
)

/** The street data's credit: right-aligned at [right], on [baseline], keeping labels out of [box]. */
internal data class MapAttribution(val text: String, val right: Float, val baseline: Float, val box: MapBox)

/**
 * The hub's one vector map, at every size: streets dim (major 2.2, others 1.4), the walked part
 * dim, the route ahead phosphor on a black underlay, the next turn circled, the destination
 * ringed, transit stops boxed with their letter, the wearer an arrow or a dot. A position that
 * cannot be trusted turns the map north-up, dashes the route and drops the arrow. Compass and
 * scale bar sit in the corners; street names, with a black halo, only where they can be read.
 * The provider's credit for its street data, when it gives one, stays in the bottom-right corner
 * at every size. Everything is outline or small mark on black: no area of the map is ever lit.
 */
internal class RouteMapPainter {
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val label = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.mono
        textSize = LABEL_PX
        textAlign = Paint.Align.CENTER
    }
    private val halo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.mono
        textSize = LABEL_PX
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = HALO_PX
        strokeJoin = Paint.Join.ROUND
        color = PageStyle.black
    }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.mono
        textSize = PageStyle.CAPTION_PX
        color = PageStyle.muted
    }
    private val smallHalo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.mono
        textSize = PageStyle.CAPTION_PX
        style = Paint.Style.STROKE
        strokeWidth = HALO_PX
        strokeJoin = Paint.Join.ROUND
        color = PageStyle.black
    }
    private val stopText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = PageStyle.monoBold
        textSize = STOP_TEXT_PX
        textAlign = Paint.Align.CENTER
        color = PageStyle.phosphor
    }
    private val path = Path()
    private val rect = RectF()
    private val routeDash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    private val pathDash = DashPathEffect(floatArrayOf(6f, 6f), 0f)
    private val ringDash = DashPathEffect(floatArrayOf(4f, 4f), 0f)

    fun plan(input: MapInput, width: Float, height: Float): MapPlan {
        val geometry = input.geometry
        val style = RouteMapMath.styleOf(geometry, input.view, input.headingDeg, input.degraded)
        val transform = RouteMapMath.transform(geometry, input.form, input.view, style, input.headingDeg, width, height, input.insets)
        val scaleBar = RouteMapMath.scaleBar(geometry.spanMeters, transform.scale, width * RouteMapMath.SCALE_BAR_FRACTION)
        val compassX = if (input.compass) width - COMPASS_INSET_PX else null
        val compassY = if (input.compass) input.insets.top + COMPASS_INSET_PX else null
        val bottom = height - input.insets.bottom
        val attribution = geometry.attribution?.let { text ->
            val right = width - ATTRIBUTION_INSET_PX
            val baseline = bottom - ATTRIBUTION_INSET_PX
            val textWidth = small.measureText(text)
            MapAttribution(text, right, baseline, MapBox(right - textWidth - 4f, baseline - PageStyle.CAPTION_PX - 2f, width, bottom))
        }
        val reserved = buildList {
            attribution?.let { add(it.box) }
            if (compassX != null && compassY != null) {
                add(MapBox(compassX - COMPASS_RADIUS_PX - 2f, compassY - COMPASS_RADIUS_PX - 2f, compassX + COMPASS_RADIUS_PX + 2f, compassY + COMPASS_LETTER_DROP_PX + 4f))
            }
            scaleBar?.let { bar ->
                add(MapBox(0f, bottom - SCALE_BAR_INSET_PX - 18f, SCALE_BAR_INSET_PX + bar.lengthPx + 6f + small.measureText(bar.label) + 4f, bottom))
            }
        }
        val candidates = geometry.labels.map {
            MapLabel(
                text = it.text,
                priority = it.priority,
                x = transform.x(it.x.toFloat(), it.y.toFloat()),
                y = transform.y(it.x.toFloat(), it.y.toFloat()),
                width = label.measureText(it.text),
                height = LABEL_PX,
            )
        }
        val labels = RouteMapMath.placeLabels(candidates, input.form.maxLabels, MapBox(0f, input.insets.top, width, bottom), reserved)
        return MapPlan(transform, style, scaleBar, labels, compassX, compassY, attribution)
    }

    fun draw(canvas: Canvas, input: MapInput, plan: MapPlan, width: Float, height: Float) {
        val geometry = input.geometry
        val transform = plan.transform
        val saved = canvas.save()
        canvas.clipRect(0f, 0f, width, height)
        val box = RouteMapMath.clipBox(width, height, CLIP_MARGIN_PX)

        fun runs(points: List<RoutePoint>): List<FloatArray> {
            val projected = FloatArray(points.size * 2)
            points.forEachIndexed { index, point ->
                projected[index * 2] = transform.x(point.x.toFloat(), point.y.toFloat())
                projected[index * 2 + 1] = transform.y(point.x.toFloat(), point.y.toFloat())
            }
            return RouteMapMath.clipPolyline(projected, box)
        }

        fun stroke(role: RoutePathRole, color: Int, widthPx: Float, dash: DashPathEffect? = null) {
            line.color = color
            line.strokeWidth = widthPx
            line.pathEffect = dash
            // Round caps would close a dash's gap; dashed lines keep square ends.
            line.strokeCap = if (dash == null) Paint.Cap.ROUND else Paint.Cap.BUTT
            geometry.paths.filter { it.role == role }.forEach { routePath ->
                runs(routePath.points).forEach { run ->
                    path.reset()
                    path.moveTo(run[0], run[1])
                    var index = 2
                    while (index + 1 < run.size) {
                        path.lineTo(run[index], run[index + 1])
                        index += 2
                    }
                    canvas.drawPath(path, line)
                }
            }
        }

        stroke(RoutePathRole.STREET, PageStyle.dim, STREET_PX)
        stroke(RoutePathRole.MAJOR, PageStyle.dim, MAJOR_PX)
        stroke(RoutePathRole.STOP, PageStyle.muted, STOP_PATH_PX, pathDash)
        stroke(RoutePathRole.COMPLETED, PageStyle.black, UNDERLAY_PX)
        stroke(RoutePathRole.COMPLETED, PageStyle.dim, ROUTE_PX)
        stroke(RoutePathRole.ROUTE, PageStyle.black, UNDERLAY_PX)
        if (plan.style.dashedRoute) {
            stroke(RoutePathRole.ROUTE, PageStyle.done, ROUTE_PX, routeDash)
        } else {
            stroke(RoutePathRole.ROUTE, PageStyle.phosphor, ROUTE_PX)
        }
        line.pathEffect = null

        geometry.turn?.let { point -> turn(canvas, transform.x(point.x.toFloat(), point.y.toFloat()), transform.y(point.x.toFloat(), point.y.toFloat())) }
        geometry.destination?.let { point -> destination(canvas, transform.x(point.x.toFloat(), point.y.toFloat()), transform.y(point.x.toFloat(), point.y.toFloat())) }
        geometry.stops.forEach { stop -> stop(canvas, transform.x(stop.x.toFloat(), stop.y.toFloat()), transform.y(stop.x.toFloat(), stop.y.toFloat()), stop.label) }

        plan.labels.forEach { placed ->
            val baseline = placed.y + LABEL_PX * 0.35f
            canvas.drawText(placed.text, placed.x, baseline, halo)
            label.color = if (placed.priority == 0) PageStyle.ink else PageStyle.muted
            canvas.drawText(placed.text, placed.x, baseline, label)
        }

        geometry.position?.point?.let { point ->
            position(canvas, transform.x(point.x.toFloat(), point.y.toFloat()), transform.y(point.x.toFloat(), point.y.toFloat()), plan.style.position, (input.headingDeg ?: 0f) - transform.rotationDeg)
        }

        if (plan.compassX != null && plan.compassY != null) compass(canvas, plan.compassX, plan.compassY, transform.rotationDeg)
        plan.scaleBar?.let { scaleBar(canvas, it, height - input.insets.bottom) }
        plan.attribution?.let { attribution(canvas, it) }
        canvas.restoreToCount(saved)
    }

    private fun turn(canvas: Canvas, x: Float, y: Float) {
        fill.color = PageStyle.black
        canvas.drawCircle(x, y, TURN_RADIUS_PX, fill)
        ring(canvas, x, y, TURN_RADIUS_PX, PageStyle.phosphor, 3f)
        fill.color = PageStyle.phosphor
        canvas.drawCircle(x, y, 4f, fill)
    }

    private fun destination(canvas: Canvas, x: Float, y: Float) {
        ring(canvas, x, y, DESTINATION_OUTER_PX, PageStyle.dim, 2f)
        fill.color = PageStyle.black
        canvas.drawCircle(x, y, DESTINATION_INNER_PX, fill)
        ring(canvas, x, y, DESTINATION_INNER_PX, PageStyle.phosphor, 3f)
        fill.color = PageStyle.phosphor
        canvas.drawCircle(x, y, 4f, fill)
    }

    private fun stop(canvas: Canvas, x: Float, y: Float, text: String) {
        rect.set(x - 17f, y - 13f, x + 17f, y + 13f)
        fill.color = PageStyle.black
        canvas.drawRoundRect(rect, 6f, 6f, fill)
        line.color = PageStyle.phosphor
        line.strokeWidth = 2.5f
        line.pathEffect = null
        canvas.drawRoundRect(rect, 6f, 6f, line)
        canvas.drawText(text, x, y + 6f, stopText)
    }

    private fun position(canvas: Canvas, x: Float, y: Float, mark: PositionMark, arrowDeg: Float) {
        when (mark) {
            PositionMark.NONE -> Unit
            PositionMark.LOST -> {
                line.pathEffect = ringDash
                ring(canvas, x, y, 22f, PageStyle.muted, 1.5f)
                line.pathEffect = null
                dot(canvas, x, y, PageStyle.muted)
            }
            PositionMark.DOT -> {
                ring(canvas, x, y, 19f, PageStyle.phosphor, 1.2f, alpha = 0.6f)
                dot(canvas, x, y, PageStyle.phosphor)
            }
            PositionMark.ARROW -> {
                ring(canvas, x, y, 19f, PageStyle.phosphor, 1.2f, alpha = 0.6f)
                val saved = canvas.save()
                canvas.translate(x, y)
                canvas.rotate(arrowDeg)
                path.reset()
                path.moveTo(0f, -14f)
                path.lineTo(11f, 11f)
                path.lineTo(0f, 5f)
                path.lineTo(-11f, 11f)
                path.close()
                fill.color = PageStyle.phosphor
                canvas.drawPath(path, fill)
                line.color = PageStyle.black
                line.strokeWidth = 2f
                canvas.drawPath(path, line)
                canvas.restoreToCount(saved)
            }
        }
    }

    private fun dot(canvas: Canvas, x: Float, y: Float, color: Int) {
        fill.color = color
        canvas.drawCircle(x, y, 7f, fill)
        line.color = PageStyle.black
        line.strokeWidth = 2f
        canvas.drawCircle(x, y, 7f, line)
    }

    private fun ring(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int, widthPx: Float, alpha: Float = 1f) {
        line.color = color
        line.alpha = (alpha * 255).toInt()
        line.strokeWidth = widthPx
        canvas.drawCircle(x, y, radius, line)
        line.alpha = 255
    }

    private fun compass(canvas: Canvas, x: Float, y: Float, rotationDeg: Float) {
        fill.color = PageStyle.black
        canvas.drawCircle(x, y, COMPASS_RADIUS_PX, fill)
        ring(canvas, x, y, COMPASS_RADIUS_PX, PageStyle.outline, 1.5f)
        val saved = canvas.save()
        canvas.translate(x, y)
        // The needle points to north wherever the map has turned it.
        canvas.rotate(-rotationDeg)
        path.reset()
        path.moveTo(0f, -9f)
        path.lineTo(4f, 3f)
        path.lineTo(0f, 0f)
        path.lineTo(-4f, 3f)
        path.close()
        fill.color = PageStyle.muted
        canvas.drawPath(path, fill)
        canvas.restoreToCount(saved)
        small.textAlign = Paint.Align.CENTER
        canvas.drawText(NORTH, x, y + COMPASS_LETTER_DROP_PX, small)
    }

    private fun attribution(canvas: Canvas, credit: MapAttribution) {
        // The same black halo as the street names keeps it readable over a street drawn beneath.
        smallHalo.textAlign = Paint.Align.RIGHT
        canvas.drawText(credit.text, credit.right, credit.baseline, smallHalo)
        small.textAlign = Paint.Align.RIGHT
        canvas.drawText(credit.text, credit.right, credit.baseline, small)
    }

    private fun scaleBar(canvas: Canvas, bar: ScaleBar, bottom: Float) {
        val x = SCALE_BAR_INSET_PX
        val y = bottom - SCALE_BAR_INSET_PX
        line.color = PageStyle.muted
        line.strokeWidth = 1.5f
        line.strokeCap = Paint.Cap.BUTT
        path.reset()
        path.moveTo(x, y - 6f)
        path.lineTo(x, y)
        path.lineTo(x + bar.lengthPx, y)
        path.lineTo(x + bar.lengthPx, y - 6f)
        canvas.drawPath(path, line)
        small.textAlign = Paint.Align.LEFT
        canvas.drawText(bar.label, x + bar.lengthPx + 6f, y + 1f, small)
    }

    companion object {
        const val STREET_PX = 1.4f
        const val MAJOR_PX = 2.2f
        const val ROUTE_PX = 6f
        const val UNDERLAY_PX = 10f
        const val STOP_PATH_PX = 2f
        const val LABEL_PX = 13f
        const val HALO_PX = 5f
        const val STOP_TEXT_PX = 16f
        const val TURN_RADIUS_PX = 12f
        const val DESTINATION_OUTER_PX = 26f
        const val DESTINATION_INNER_PX = 11f
        const val COMPASS_RADIUS_PX = 13f
        const val COMPASS_INSET_PX = 22f
        const val COMPASS_LETTER_DROP_PX = 26f
        const val SCALE_BAR_INSET_PX = 12f
        const val ATTRIBUTION_INSET_PX = 8f
        const val CLIP_MARGIN_PX = 8f
        const val NORTH = "N"
    }
}

/**
 * One map, measured: [MapForm.naturalHeightPx] tall unless its parent gives it an exact height,
 * and drawn by [RouteMapPainter] at whatever size it is laid out.
 */
internal class RouteMapView(context: Context, leaseFactory: (View) -> RenderedMapLease = { RenderedMapLease(it) }) : View(context) {
    private val renderedLease = leaseFactory(this)
    private val painter = RouteMapPainter()
    private val clip = Path()

    var input: MapInput? = null
        private set

    /** Rounds the map's corners to sit inside an outline of this radius; 0 draws it square. */
    var cornerRadius = 0f
        set(value) {
            field = value
            invalidate()
        }

    /** The plan of the last draw at the current size; recomputed on any change. */
    var plan: MapPlan? = null
        private set

    fun bind(input: MapInput) {
        if (input == this.input) return
        renderedLease.bind(input.renderContext, input.view)
        val resized = input.form != this.input?.form
        this.input = input
        plan = null
        if (resized) requestLayout()
        invalidate()
    }

    /** The plan for the current size, computed now: what the next draw will use. */
    fun planNow(): MapPlan? {
        val current = input ?: return null
        if (width <= 0 || height <= 0) return null
        return plan ?: painter.plan(current, width.toFloat(), height.toFloat()).also { plan = it }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val natural = input?.form?.naturalHeightPx ?: MapForm.COMPACT.naturalHeightPx
        val height = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(natural, MeasureSpec.getSize(heightMeasureSpec))
            else -> natural
        }
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        plan = null
    }

    override fun onDraw(canvas: Canvas) {
        val current = input ?: return
        val plan = planNow() ?: return
        val saved = canvas.save()
        if (cornerRadius > 0f) {
            clip.reset()
            clip.addRoundRect(0f, 0f, width.toFloat(), height.toFloat(), cornerRadius, cornerRadius, Path.Direction.CW)
            canvas.clipPath(clip)
        }
        painter.draw(canvas, current, plan, width.toFloat(), height.toFloat())
        canvas.restoreToCount(saved)
        renderedLease.drawn()
    }

    override fun onDetachedFromWindow() {
        renderedLease.detach()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityAggregated(isVisible: Boolean) {
        super.onVisibilityAggregated(isVisible)
        if (!isVisible) renderedLease.detach() else invalidate()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility != VISIBLE) renderedLease.detach() else invalidate()
    }
}
