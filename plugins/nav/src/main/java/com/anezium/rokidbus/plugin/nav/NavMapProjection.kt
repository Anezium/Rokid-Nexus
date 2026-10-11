package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RouteLabel
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Follow is centred on the wearer for the hub to turn heading-up; overview is the whole route, north-up. */
internal enum class NavMapView { FOLLOW, OVERVIEW }

/** The ground one geometry describes: its centre and the metres its 1000 units span. */
internal data class NavMapWindow(val center: MapMeters, val spanMeters: Double) {
    fun normalized(point: MapMeters): MapMeters = MapMeters(
        HALF + (point.x - center.x) / spanMeters * EXTENT,
        HALF + (point.y - center.y) / spanMeters * EXTENT,
    )

    private companion object {
        const val EXTENT = RouteGeometryContract.EXTENT.toDouble()
        const val HALF = EXTENT / 2.0
    }
}

/** What one geometry may carry; the defaults are the wire contract's own limits. */
internal data class NavMapBudget(
    val maxPaths: Int = RouteGeometryContract.MAX_PATHS,
    val maxPoints: Int = RouteGeometryContract.MAX_POINTS,
    val maxBytes: Int = RouteGeometryContract.MAX_BYTES,
)

/**
 * Projects a walking journey into the bounded route geometry the hub draws (north-up, 1000 units
 * across). Only measured data goes in: the planned route, split at the confirmed progress into
 * the walked `completed` part and the `route` ahead, the next turn, the destination, the street
 * names the planner returned, and the wearer's last trusted position with its age. There is no
 * street map behind it, so no `street` or `major` path is ever drawn from guesses.
 *
 * Segments are clipped with Liang-Barsky to the frame, never by moving a vertex onto its border,
 * so a clipped stretch keeps its true direction. A follow frame is centred on the wearer: it holds
 * the whole disc of 500 units around them, so any viewport inside that disc stays covered however
 * the hub rotates it. Over budget, paths are simplified (Douglas-Peucker) with the smallest
 * tolerance that fits, the route ahead more finely than the walked part, rather than keeping every
 * n-th point.
 */
internal object NavMapProjection {
    const val FOLLOW_SPAN_METERS = 400.0
    const val MIN_OVERVIEW_SPAN_METERS = 100.0
    private const val OVERVIEW_MARGIN = 1.25
    private const val EXTENT = RouteGeometryContract.EXTENT.toDouble()
    private const val LABEL_CHARS = RouteGeometryContract.MAX_LABEL_CHARS

    fun window(snapshot: NavRouteSnapshot, view: NavMapView): NavMapWindow = when (view) {
        NavMapView.FOLLOW -> NavMapWindow(followCenter(snapshot), FOLLOW_SPAN_METERS)
        NavMapView.OVERVIEW -> overviewWindow(snapshot)
    }

    fun geometry(snapshot: NavRouteSnapshot, view: NavMapView, budget: NavMapBudget = NavMapBudget()): RouteGeometry {
        if (view == NavMapView.OVERVIEW) return build(snapshot, overviewWindow(snapshot), budget)
        // Off the route, the follow frame widens around the wearer until the route is back in it,
        // instead of drawing a connector nobody measured.
        val center = followCenter(snapshot)
        var span = FOLLOW_SPAN_METERS
        while (true) {
            val geometry = build(snapshot, NavMapWindow(center, span), budget)
            if (geometry.paths.isNotEmpty() || span * 2 > RouteGeometryContract.MAX_SPAN_METERS) return geometry
            span *= 2
        }
    }

    private fun followCenter(snapshot: NavRouteSnapshot): MapMeters {
        val line = snapshot.route.line
        return snapshot.position?.let(line::toMeters) ?: line.metersAt(snapshot.progressMeters)
    }

    private fun overviewWindow(snapshot: NavRouteSnapshot): NavMapWindow {
        val line = snapshot.route.line
        val extent = line.meters + line.toMeters(snapshot.route.destination.point) +
            listOfNotNull(snapshot.position?.let(line::toMeters))
        val left = extent.minOf { it.x }
        val right = extent.maxOf { it.x }
        val top = extent.minOf { it.y }
        val bottom = extent.maxOf { it.y }
        val span = (max(right - left, bottom - top) * OVERVIEW_MARGIN)
            .coerceIn(MIN_OVERVIEW_SPAN_METERS, RouteGeometryContract.MAX_SPAN_METERS)
        return NavMapWindow(MapMeters((left + right) / 2.0, (top + bottom) / 2.0), span)
    }

    private fun build(snapshot: NavRouteSnapshot, window: NavMapWindow, budget: NavMapBudget): RouteGeometry {
        val route = snapshot.route
        val line = route.line
        fun mark(point: MapMeters): RoutePoint? = inside(window.normalized(point))

        val position = snapshot.position?.let { mark(line.toMeters(it)) }
        val quality = snapshot.positionQuality
        val routePosition = if (quality == RoutePositionQuality.NONE || position == null) {
            RoutePosition(RoutePositionQuality.NONE, null)
        } else {
            // The age matters only once the fix is no longer current; a live one would churn every second.
            RoutePosition(quality, position, snapshot.fixAgeS.takeIf { quality == RoutePositionQuality.STALE })
        }
        val step = snapshot.nextStep
        val turnMeters = if (step.direction == "ARRIVE" || snapshot.quality == RouteQuality.ARRIVED) null else line.metersAt(step.atMeters)
        val turn = turnMeters?.let(::mark)
        val destination = mark(line.toMeters(route.destination.point))
        val labels = labels(snapshot, window, turn, destination)
        val fragments = fragments(line, snapshot.progressMeters, window)
            .sortedWith(compareBy<Fragment>({ if (it.role == RoutePathRole.ROUTE) 0 else 1 }, { it.priority }))
            .take(budget.maxPaths)

        fun assemble(paths: List<RoutePath>) = RouteGeometry(
            spanMeters = window.spanMeters,
            paths = paths,
            labels = labels,
            position = routePosition,
            turn = turn,
            destination = destination,
        )

        return fit(fragments, budget, ::assemble)
    }

    /** Street names the planner returned, where they are on the frame: next turn first. */
    private fun labels(snapshot: NavRouteSnapshot, window: NavMapWindow, turn: RoutePoint?, destination: RoutePoint?): List<RouteLabel> {
        val route = snapshot.route
        val line = route.line
        val step = snapshot.nextStep
        val labels = mutableListOf<RouteLabel>()
        val next = step.street.takeIf { turn != null && it.isNotBlank() }
        if (next != null && turn != null) labels += RouteLabel(NavText.fit(next, LABEL_CHARS), turn.x, turn.y, 0)
        val current = route.streetAt(snapshot.progressMeters)
        if (current != null && current != next) {
            // Placed a little ahead on the stretch being walked, where the name applies.
            val ahead = (snapshot.progressMeters + window.spanMeters * 0.15).coerceAtMost(min(step.atMeters, route.lengthMeters))
            inside(window.normalized(line.metersAt(ahead)))?.let { labels += RouteLabel(NavText.fit(current, LABEL_CHARS), it.x, it.y, 1) }
        }
        val name = route.destination.name.substringBefore(", ").takeIf(String::isNotBlank)
        if (name != null && destination != null) labels += RouteLabel(NavText.fit(name, LABEL_CHARS), destination.x, destination.y, 2)
        return labels.take(RouteGeometryContract.MAX_LABELS)
    }

    /** A clipped run of one role; [priority] orders runs of the same role, nearest the wearer first. */
    private class Fragment(val role: RoutePathRole, val priority: Double, val points: MutableList<MapMeters>)

    private fun fragments(line: RouteLine, progressMeters: Double, window: NavMapWindow): List<Fragment> {
        val result = mutableListOf<Fragment>()
        var current: Fragment? = null
        fun add(role: RoutePathRole, a: MapMeters, b: MapMeters, alongStart: Double) {
            val clipped = clip(window.normalized(a), window.normalized(b))
            if (clipped == null) {
                current = null
                return
            }
            val (start, end) = clipped
            val open = current
            if (open == null || open.role != role || open.points.last() != start) {
                val priority = if (role == RoutePathRole.ROUTE) alongStart - progressMeters else progressMeters - alongStart
                current = Fragment(role, priority, mutableListOf(start)).also(result::add)
            }
            val run = current!!
            if (run.points.last() != end) run.points += end
            // A segment that left the frame ends its run; the next visible one starts another.
            if (end != window.normalized(b)) current = null
        }
        val meters = line.meters
        val cumulative = line.cumulative
        for (index in 1 until meters.size) {
            val a = meters[index - 1]
            val b = meters[index]
            val start = cumulative[index - 1]
            val end = cumulative[index]
            when {
                progressMeters <= start -> add(RoutePathRole.ROUTE, a, b, start)
                progressMeters >= end -> add(RoutePathRole.COMPLETED, a, b, start)
                else -> {
                    val fraction = (progressMeters - start) / (end - start)
                    val split = MapMeters(a.x + (b.x - a.x) * fraction, a.y + (b.y - a.y) * fraction)
                    add(RoutePathRole.COMPLETED, a, split, start)
                    add(RoutePathRole.ROUTE, split, b, progressMeters)
                }
            }
        }
        return result.filter { it.points.size >= 2 }
    }

    /**
     * The smallest simplification tolerance (frame units) whose result fits [budget]: found by
     * doubling, then narrowed by bisection. At a tolerance wider than the frame every path is its
     * two endpoints, which always fits, so this always ends.
     */
    private fun fit(fragments: List<Fragment>, budget: NavMapBudget, assemble: (List<RoutePath>) -> RouteGeometry): RouteGeometry {
        fun attempt(tolerance: Double): RouteGeometry? {
            val paths = fragments.mapNotNull { fragment ->
                // The walked part is context; the route ahead keeps the finer shape.
                val own = if (fragment.role == RoutePathRole.COMPLETED) tolerance * 2 else tolerance
                rounded(simplify(fragment.points, own))?.let { RoutePath(fragment.role, it) }
            }
            if (paths.sumOf { it.points.size } > budget.maxPoints) return null
            val geometry = assemble(paths)
            val bytes = RouteGeometryContract.encode(geometry).toString().toByteArray(Charsets.UTF_8).size
            return geometry.takeIf { bytes <= budget.maxBytes }
        }
        attempt(0.0)?.let { return it }
        var low = 0.0
        var high = 0.25
        var best: RouteGeometry? = null
        while (best == null) {
            best = attempt(high)
            if (best == null) {
                low = high
                high *= 2
                if (high > EXTENT * 4) return assemble(emptyList())
            }
        }
        repeat(BISECTION_STEPS) {
            val middle = (low + high) / 2
            val candidate = attempt(middle)
            if (candidate != null) {
                best = candidate
                high = middle
            } else {
                low = middle
            }
        }
        return best!!
    }

    /** Integer frame points without repeats, or null when nothing drawable is left. */
    private fun rounded(points: List<MapMeters>): List<RoutePoint>? {
        val result = mutableListOf<RoutePoint>()
        points.forEach { point ->
            // Clipped points are on or inside the frame already; this only absorbs float error.
            val next = RoutePoint(
                point.x.roundToInt().coerceIn(0, RouteGeometryContract.EXTENT),
                point.y.roundToInt().coerceIn(0, RouteGeometryContract.EXTENT),
            )
            if (result.lastOrNull() != next) result += next
        }
        return result.takeIf { it.size >= RouteGeometryContract.MIN_PATH_POINTS }
    }

    /** Iterative Douglas-Peucker: keeps both ends and every point farther than [tolerance] from the simplified line. */
    internal fun simplify(points: List<MapMeters>, tolerance: Double): List<MapMeters> {
        if (points.size <= 2) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.addLast(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (first, last) = stack.removeLast()
            var farthest = -1
            var distance = -1.0
            for (index in first + 1 until last) {
                val gap = segmentDistance(points[index], points[first], points[last])
                if (gap > distance) {
                    distance = gap
                    farthest = index
                }
            }
            if (farthest >= 0 && distance > tolerance) {
                keep[farthest] = true
                stack.addLast(first to farthest)
                stack.addLast(farthest to last)
            }
        }
        return points.filterIndexed { index, _ -> keep[index] }
    }

    private fun segmentDistance(point: MapMeters, a: MapMeters, b: MapMeters): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0.0) return hypot(point.x - a.x, point.y - a.y)
        val t = (((point.x - a.x) * dx + (point.y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(point.x - (a.x + t * dx), point.y - (a.y + t * dy))
    }

    /** Liang-Barsky against the 0..1000 frame: the visible part of a→b, or null when none is. */
    internal fun clip(a: MapMeters, b: MapMeters): Pair<MapMeters, MapMeters>? {
        val dx = b.x - a.x
        val dy = b.y - a.y
        var enter = 0.0
        var leave = 1.0
        for ((p, q) in listOf(-dx to a.x, dx to (EXTENT - a.x), -dy to a.y, dy to (EXTENT - a.y))) {
            if (p == 0.0) {
                if (q < 0.0) return null
            } else {
                val ratio = q / p
                if (p < 0.0) enter = max(enter, ratio) else leave = min(leave, ratio)
                if (enter > leave) return null
            }
        }
        val start = if (enter == 0.0) a else MapMeters(a.x + enter * dx, a.y + enter * dy)
        val end = if (leave == 1.0) b else MapMeters(a.x + leave * dx, a.y + leave * dy)
        return start to end
    }

    private fun inside(point: MapMeters): RoutePoint? {
        if (point.x !in 0.0..EXTENT || point.y !in 0.0..EXTENT) return null
        return RoutePoint(point.x.roundToInt(), point.y.roundToInt())
    }

    private const val BISECTION_STEPS = 6
}
