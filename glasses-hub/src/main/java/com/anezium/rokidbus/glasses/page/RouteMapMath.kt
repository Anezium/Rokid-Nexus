package com.anezium.rokidbus.glasses.page

import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePositionQuality
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * The four places one painter draws a route: the page's compact card, the two island sizes and
 * the full screen. [anchorY] is where the wearer sits in a follow view, as a fraction of the
 * height: the lower third, so more of the way ahead is on screen. Street names are drawn only
 * where there is room for them to be read ([maxLabels]).
 */
internal enum class MapForm(val anchorY: Float, val maxLabels: Int, val naturalHeightPx: Int) {
    COMPACT(0.66f, 0, 262),
    MINIMAP(0.66f, 0, 190),
    PANEL(0.68f, 2, 300),
    FULL(0.64f, 4, 640),
}

/** Screen bands the map runs under but must not put its frame or its furniture in. */
internal data class MapInsets(val top: Float = 0f, val bottom: Float = 0f)

/** How the wearer is marked: a heading arrow, a plain dot, the muted lost mark, or nothing. */
internal enum class PositionMark { ARROW, DOT, LOST, NONE }

/**
 * What the painter decides from how far the position can be trusted, before anything is drawn.
 * A degraded position is said, never hidden: north-up, a dashed route, a dot without an arrow.
 */
internal data class MapStyle(
    val northUp: Boolean,
    val dashedRoute: Boolean,
    val position: PositionMark,
)

/**
 * Frame units to view pixels: the frame point ([centerX], [centerY]) lands on ([anchorX],
 * [anchorY]), the map turned so the [rotationDeg] heading points up, at [scale] pixels a unit.
 */
internal data class MapTransform(
    val anchorX: Float,
    val anchorY: Float,
    val centerX: Float,
    val centerY: Float,
    val rotationDeg: Float,
    val scale: Float,
) {
    private val cosine = cos(Math.toRadians(-rotationDeg.toDouble())).toFloat()
    private val sine = sin(Math.toRadians(-rotationDeg.toDouble())).toFloat()

    fun x(ux: Float, uy: Float): Float = anchorX + (cosine * (ux - centerX) - sine * (uy - centerY)) * scale

    fun y(ux: Float, uy: Float): Float = anchorY + (sine * (ux - centerX) + cosine * (uy - centerY)) * scale

    fun metersPerPixel(spanMeters: Double): Double = spanMeters / RouteGeometryContract.EXTENT / scale
}

internal data class ScaleBar(val meters: Int, val lengthPx: Float, val label: String)

/** A box in view pixels. */
internal data class MapBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    fun intersects(other: MapBox): Boolean = left < other.right && other.left < right && top < other.bottom && other.top < bottom

    fun contains(other: MapBox): Boolean = other.left >= left && other.right <= right && other.top >= top && other.bottom <= bottom
}

/** A street name projected into the view, measured, and its [priority] from the provider (0 first). */
internal data class MapLabel(val text: String, val priority: Int, val x: Float, val y: Float, val width: Float, val height: Float) {
    fun box(pad: Float): MapBox = MapBox(x - width / 2f - pad, y - height / 2f - pad, x + width / 2f + pad, y + height / 2f + pad)
}

/**
 * The pure geometry of the hub's route map: projection, rotation, clipping, scale bar and label
 * choice. No Android type is used here, so every decision the painter takes is tested on its own.
 */
internal object RouteMapMath {
    /** The frame's half extent: a follow view's frame reaches this far round the wearer. */
    private const val HALF_EXTENT = RouteGeometryContract.EXTENT / 2f

    /** A degraded follow view keeps the last fix a little higher than a live one, as r17 draws it. */
    const val DEGRADED_ANCHOR_Y = 0.6f
    const val FIT_MARGIN_PX = 30f

    /** The smallest extent a whole-route view is fitted to, so a short route is not blown up. */
    const val MIN_FIT_UNITS = 100f
    const val SCALE_BAR_FRACTION = 0.18f

    private val NICE_METERS = intArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500, 1_000, 2_000, 5_000, 10_000, 20_000, 50_000)

    /**
     * A follow view turns heading-up only with a live position and a heading; everything else is
     * north-up. Stale or missing positions, or a hub-side [degraded] flag, dash the route and
     * mark the last fix with a dot and no arrow.
     */
    fun styleOf(geometry: RouteGeometry, view: MapView, headingDeg: Float?, degraded: Boolean): MapStyle {
        val position = geometry.position
        val point = position?.point
        val lost = degraded || position?.quality == RoutePositionQuality.STALE || position?.quality == RoutePositionQuality.NONE
        val mark = when {
            point == null -> PositionMark.NONE
            lost -> PositionMark.LOST
            headingDeg == null -> PositionMark.DOT
            else -> PositionMark.ARROW
        }
        val northUp = view == MapView.OVERVIEW || lost || point == null || headingDeg == null
        return MapStyle(northUp = northUp, dashedRoute = lost, position = mark)
    }

    /**
     * Follow: the wearer at the form's anchor and the frame turned by [headingDeg], at a scale where
     * the frame's half extent reaches the farthest corner of the view, so a provider's frame that
     * covers the rotated viewport fills it at any heading. Whole route: north-up, fitted to the
     * route, its marks and the wearer, inside [insets].
     */
    fun transform(
        geometry: RouteGeometry,
        form: MapForm,
        view: MapView,
        style: MapStyle,
        headingDeg: Float?,
        width: Float,
        height: Float,
        insets: MapInsets = MapInsets(),
    ): MapTransform {
        val areaTop = insets.top.coerceIn(0f, height)
        val areaBottom = (height - insets.bottom).coerceIn(areaTop, height)
        val areaHeight = areaBottom - areaTop
        if (view == MapView.OVERVIEW) return fit(geometry, width, areaTop, areaHeight)
        val point = geometry.position?.point
        val anchorFraction = when {
            point == null -> 0.5f
            style.position == PositionMark.LOST -> DEGRADED_ANCHOR_Y
            else -> form.anchorY
        }
        val anchorX = width / 2f
        val anchorY = areaTop + areaHeight * anchorFraction
        val reach = maxOf(
            hypot(anchorX, anchorY),
            hypot(width - anchorX, anchorY),
            hypot(anchorX, height - anchorY),
            hypot(width - anchorX, height - anchorY),
        )
        val scale = (reach / HALF_EXTENT).coerceAtLeast(MIN_SCALE)
        val rotation = if (style.northUp) 0f else normalized(headingDeg ?: 0f)
        return MapTransform(
            anchorX = anchorX,
            anchorY = anchorY,
            centerX = point?.x?.toFloat() ?: HALF_EXTENT,
            centerY = point?.y?.toFloat() ?: HALF_EXTENT,
            rotationDeg = rotation,
            scale = scale,
        )
    }

    private fun fit(geometry: RouteGeometry, width: Float, areaTop: Float, areaHeight: Float): MapTransform {
        val focus = buildList {
            geometry.paths.filter { it.role != RoutePathRole.STREET && it.role != RoutePathRole.MAJOR }.forEach { addAll(it.points) }
            geometry.turn?.let(::add)
            geometry.destination?.let(::add)
            geometry.stops.forEach { add(RoutePoint(it.x, it.y)) }
            geometry.position?.point?.let(::add)
        }.ifEmpty { geometry.paths.flatMap { it.points } }
        var minX = focus.minOfOrNull { it.x }?.toFloat() ?: 0f
        var maxX = focus.maxOfOrNull { it.x }?.toFloat() ?: RouteGeometryContract.EXTENT.toFloat()
        var minY = focus.minOfOrNull { it.y }?.toFloat() ?: 0f
        var maxY = focus.maxOfOrNull { it.y }?.toFloat() ?: RouteGeometryContract.EXTENT.toFloat()
        if (maxX - minX < MIN_FIT_UNITS) {
            val mid = (minX + maxX) / 2f
            minX = mid - MIN_FIT_UNITS / 2f
            maxX = mid + MIN_FIT_UNITS / 2f
        }
        if (maxY - minY < MIN_FIT_UNITS) {
            val mid = (minY + maxY) / 2f
            minY = mid - MIN_FIT_UNITS / 2f
            maxY = mid + MIN_FIT_UNITS / 2f
        }
        val roomX = (width - 2 * FIT_MARGIN_PX).coerceAtLeast(1f)
        val roomY = (areaHeight - 2 * FIT_MARGIN_PX).coerceAtLeast(1f)
        val scale = min(roomX / (maxX - minX), roomY / (maxY - minY)).coerceAtLeast(MIN_SCALE)
        return MapTransform(
            anchorX = width / 2f,
            anchorY = areaTop + areaHeight / 2f,
            centerX = (minX + maxX) / 2f,
            centerY = (minY + maxY) / 2f,
            rotationDeg = 0f,
            scale = scale,
        )
    }

    /** The largest round distance whose bar fits in [maxPx]; null when even a metre does not. */
    fun scaleBar(spanMeters: Double, scale: Float, maxPx: Float): ScaleBar? {
        if (!(spanMeters > 0.0) || !(scale > 0f) || !(maxPx > 0f)) return null
        val metersPerPixel = spanMeters / RouteGeometryContract.EXTENT / scale
        if (!metersPerPixel.isFinite() || metersPerPixel <= 0.0) return null
        val meters = NICE_METERS.lastOrNull { it / metersPerPixel <= maxPx } ?: return null
        val label = if (meters < 1_000) "$meters m" else "${meters / 1_000} km"
        return ScaleBar(meters, (meters / metersPerPixel).toFloat(), label)
    }

    /**
     * Liang-Barsky: the part of the segment inside the box, or null when none of it is. The ends
     * that are inside stay exactly where they were, so a clipped line keeps its true direction.
     */
    fun clipSegment(x0: Float, y0: Float, x1: Float, y1: Float, box: MapBox): FloatArray? {
        var t0 = 0f
        var t1 = 1f
        val dx = x1 - x0
        val dy = y1 - y0
        val p = floatArrayOf(-dx, dx, -dy, dy)
        val q = floatArrayOf(x0 - box.left, box.right - x0, y0 - box.top, box.bottom - y0)
        for (i in 0 until 4) {
            if (p[i] == 0f) {
                if (q[i] < 0f) return null
            } else {
                val r = q[i] / p[i]
                if (p[i] < 0f) {
                    if (r > t1) return null
                    if (r > t0) t0 = r
                } else {
                    if (r < t0) return null
                    if (r < t1) t1 = r
                }
            }
        }
        return floatArrayOf(x0 + t0 * dx, y0 + t0 * dy, x0 + t1 * dx, y0 + t1 * dy)
    }

    /** The runs of a polyline (flat x, y pairs) that lie inside the box, each as flat pairs. */
    fun clipPolyline(points: FloatArray, box: MapBox): List<FloatArray> {
        val runs = mutableListOf<FloatArray>()
        var run = mutableListOf<Float>()
        fun close() {
            if (run.size >= 4) runs += run.toFloatArray()
            run = mutableListOf()
        }
        var index = 0
        while (index + 3 < points.size) {
            val x0 = points[index]
            val y0 = points[index + 1]
            val x1 = points[index + 2]
            val y1 = points[index + 3]
            index += 2
            val clipped = clipSegment(x0, y0, x1, y1, box)
            if (clipped == null) {
                close()
                continue
            }
            val continues = run.size >= 2 && run[run.size - 2] == clipped[0] && run[run.size - 1] == clipped[1]
            if (!continues) {
                close()
                run += clipped[0]
                run += clipped[1]
            }
            run += clipped[2]
            run += clipped[3]
            // The line leaves the box here; whatever comes back in starts a run of its own.
            if (clipped[2] != x1 || clipped[3] != y1) close()
        }
        close()
        return runs
    }

    /**
     * Street names in provider priority, at most [maxCount], each whole inside [bounds] and clear
     * of the compass, the scale bar and every name already placed. A name that cannot be read
     * whole is left out rather than cut.
     */
    fun placeLabels(candidates: List<MapLabel>, maxCount: Int, bounds: MapBox, reserved: List<MapBox>, pad: Float = 3f): List<MapLabel> {
        if (maxCount <= 0) return emptyList()
        val placed = mutableListOf<MapLabel>()
        candidates.withIndex().sortedWith(compareBy<IndexedValue<MapLabel>>({ it.value.priority }, { it.index })).forEach { (_, label) ->
            if (placed.size >= maxCount) return placed
            val box = label.box(pad)
            if (!bounds.contains(box)) return@forEach
            if (reserved.any { it.intersects(box) }) return@forEach
            if (placed.any { it.box(pad).intersects(box) }) return@forEach
            placed += label
        }
        return placed
    }

    /** Heading in degrees 0 until 360. */
    fun normalized(degrees: Float): Float {
        val value = degrees % 360f
        return if (value < 0f) value + 360f else value
    }

    /** The box the painter clips lines to: the view, grown by [margin] so line caps are never cut at its edge. */
    fun clipBox(width: Float, height: Float, margin: Float): MapBox = MapBox(-margin, -margin, width + margin, height + margin)

    private const val MIN_SCALE = 1e-4f
}
