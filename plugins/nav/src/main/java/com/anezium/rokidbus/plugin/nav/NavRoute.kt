package com.anezium.rokidbus.plugin.nav

import org.json.JSONObject
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow

internal data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lon.isFinite() && lat in -85.0..85.0 && lon in -180.0..180.0)
    }
}

internal data class NavDestination(val name: String, val point: GeoPoint)

/** A maneuver [atMeters] along the route: turn [direction] onto [street]. */
internal data class RouteStep(val atMeters: Double, val direction: String, val street: String) {
    val glyph: String
        get() = when (direction) {
            "LEFT" -> "turn-left"
            "RIGHT" -> "turn-right"
            "SLIGHTLY_LEFT" -> "turn-slight-left"
            "SLIGHTLY_RIGHT" -> "turn-slight-right"
            "HARD_LEFT" -> "turn-sharp-left"
            "HARD_RIGHT" -> "turn-sharp-right"
            "CIRCLE_CLOCKWISE", "CIRCLE_COUNTERCLOCKWISE" -> "roundabout"
            "UTURN_LEFT", "UTURN_RIGHT" -> "u-turn"
            "ARRIVE" -> "arrive"
            "CONTINUE", "DEPART" -> "straight"
            else -> NavText.ROUTE_GLYPH
        }

    val instruction: String
        get() = when (glyph) {
            "turn-left" -> "Turn left"
            "turn-right" -> "Turn right"
            "turn-slight-left" -> "Bear left"
            "turn-slight-right" -> "Bear right"
            "turn-sharp-left" -> "Sharp left"
            "turn-sharp-right" -> "Sharp right"
            "roundabout" -> "At the roundabout"
            "u-turn" -> "Turn around"
            "arrive" -> "Destination"
            "straight" -> "Continue"
            else -> "Follow the route"
        } + street.takeIf { it.isNotBlank() && direction != "ARRIVE" }?.let { " onto $it" }.orEmpty()
}

/** The street the route follows from [atMeters] on, as the planner named it. */
internal data class RouteStreet(val atMeters: Double, val name: String)

internal class NavRoute(
    val destination: NavDestination,
    val points: List<GeoPoint>,
    val durationSeconds: Int,
    val steps: List<RouteStep>,
    val streets: List<RouteStreet> = emptyList(),
) {
    val line = RouteLine(points)
    val lengthMeters: Double get() = line.lengthMeters

    init {
        require(durationSeconds in 1..43_200)
        require(steps.isNotEmpty() && steps.size <= MAX_STEPS)
        require(steps.zipWithNext().all { (a, b) -> a.atMeters <= b.atMeters })
        require(steps.all { it.atMeters in 0.0..line.lengthMeters })
        require(streets.size <= MAX_STEPS && streets.zipWithNext().all { (a, b) -> a.atMeters <= b.atMeters })
    }

    /** The street named for the stretch at [meters], or null where the planner named none. */
    fun streetAt(meters: Double): String? = streets.lastOrNull { it.atMeters <= meters }?.name?.takeIf(String::isNotBlank)

    companion object {
        const val MAX_STEPS = 512
    }
}

/** Reads the walking-only direct response; transit legs never become walking guidance. */
internal object TransitousRouteParser {
    fun parse(payload: JSONObject, destination: NavDestination): NavRoute {
        val direct = payload.optJSONArray("direct") ?: error("No walking route returned")
        val itinerary = (0 until direct.length()).mapNotNull { direct.optJSONObject(it) }.firstOrNull {
            val legs = it.optJSONArray("legs")
            legs != null && legs.length() == 1 && legs.optJSONObject(0)?.optString("mode") == "WALK"
        } ?: error("No walking route returned")
        val leg = itinerary.getJSONArray("legs").getJSONObject(0)
        val points = decodeGeometry(leg.getJSONObject("legGeometry"))
        val line = RouteLine(points)
        require(line.lengthMeters in 1.0..100_000.0) { "Walking route is outside the supported range" }
        require(line.distance(points.last(), destination.point) <= 150.0) { "Route does not reach the selected place" }
        val rawSteps = leg.optJSONArray("steps")
        require(rawSteps == null || rawSteps.length() <= NavRoute.MAX_STEPS) { "Too many route instructions" }
        var previousAt = 0.0
        val streets = mutableListOf<RouteStreet>()
        val steps = buildList {
            for (index in 0 until (rawSteps?.length() ?: 0)) {
                val step = rawSteps!!.getJSONObject(index)
                val polyline = step.optJSONObject("polyline") ?: continue
                val start = RoutePolyline.decode(polyline.getString("points"), polyline.getInt("precision"), minimumPoints = 1).first()
                val at = line.project(start, minimum = previousAt).alongMeters.coerceAtLeast(previousAt)
                previousAt = at
                val street = step.optString("streetName").trim().take(MAX_STREET_CHARS)
                // Every step names the street it walks, a turn or not; a new name is a new street.
                if (streets.lastOrNull()?.name != street) streets += RouteStreet(at, street)
                val direction = step.optString("relativeDirection", "CONTINUE")
                // Continue segments describe the street mesh, not a new maneuver.
                if (direction != "CONTINUE" && at > 5.0) add(RouteStep(at, direction, street))
            }
            add(RouteStep(line.lengthMeters, "ARRIVE", destination.name.take(MAX_STREET_CHARS)))
        }
        return NavRoute(destination, points, leg.getInt("duration"), steps, streets)
    }

    private fun decodeGeometry(json: JSONObject): List<GeoPoint> =
        RoutePolyline.decode(json.getString("points"), json.getInt("precision"))

    private const val MAX_STREET_CHARS = 120
}

/** Google's encoded polyline, at the precision the response states; bounded before it is trusted. */
internal object RoutePolyline {
    const val MAX_POINTS = 20_000
    private const val MAX_ENCODED_CHARS = 250_000

    fun decode(encoded: String, precision: Int, minimumPoints: Int = 2): List<GeoPoint> {
        require(precision in 5..7 && encoded.length <= MAX_ENCODED_CHARS) { "Unsupported route geometry" }
        var index = 0
        var latitude = 0L
        var longitude = 0L
        val scale = 10.0.pow(precision)
        fun delta(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                require(index < encoded.length && shift <= 35) { "Truncated route geometry" }
                val next = encoded[index++].code - 63
                require(next in 0..63) { "Invalid route geometry" }
                result = result or ((next and 31).toLong() shl shift)
                if (next < 32) break
                shift += 5
            }
            return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
        }
        return buildList {
            while (index < encoded.length) {
                require(size < MAX_POINTS) { "Route geometry is too large" }
                latitude += delta()
                longitude += delta()
                add(GeoPoint(latitude / scale, longitude / scale))
            }
            require(size >= minimumPoints) { "Route geometry is empty" }
        }
    }
}

internal data class RouteProjection(val point: GeoPoint, val alongMeters: Double, val distanceMeters: Double)

/** Metres east ([x]) and south ([y]) of a line's origin: screen axes, north up. */
internal data class MapMeters(val x: Double, val y: Double)

/**
 * A polyline in a local metric projection around its first point, with longitude wrapping, shared
 * by guidance and the map. Accurate at walking and city scale, which is all it is used for.
 */
internal class RouteLine(val points: List<GeoPoint>) {
    private val origin = points.first()
    private val lonScale = cos(Math.toRadians(origin.lat)) * METERS_PER_DEGREE
    val meters: List<MapMeters> = points.map(::toMeters)
    val cumulative = DoubleArray(points.size)
    val lengthMeters: Double get() = cumulative.last()

    init {
        require(points.size in 2..RoutePolyline.MAX_POINTS)
        for (index in 1 until meters.size) cumulative[index] = cumulative[index - 1] + distance(meters[index - 1], meters[index])
    }

    fun toMeters(point: GeoPoint): MapMeters = MapMeters(
        (((point.lon - origin.lon + 540.0) % 360.0) - 180.0) * lonScale,
        -(point.lat - origin.lat) * METERS_PER_DEGREE,
    )

    private fun fromMeters(point: MapMeters) = GeoPoint(
        origin.lat - point.y / METERS_PER_DEGREE,
        ((origin.lon + point.x / lonScale + 540.0) % 360.0) - 180.0,
    )

    fun distance(a: GeoPoint, b: GeoPoint): Double = distance(toMeters(a), toMeters(b))

    fun metersAt(alongMeters: Double): MapMeters {
        val along = alongMeters.coerceIn(0.0, lengthMeters)
        for (index in 1 until points.size) {
            if (cumulative[index] < along || cumulative[index] == cumulative[index - 1]) continue
            val fraction = (along - cumulative[index - 1]) / (cumulative[index] - cumulative[index - 1])
            val a = meters[index - 1]
            val b = meters[index]
            return MapMeters(a.x + fraction * (b.x - a.x), a.y + fraction * (b.y - a.y))
        }
        return meters.last()
    }

    fun pointAt(alongMeters: Double): GeoPoint =
        if (alongMeters >= lengthMeters) points.last() else fromMeters(metersAt(alongMeters))

    /** The closest point of the line to [point] between [minimum] and [maximum] metres along it. */
    fun project(point: GeoPoint, minimum: Double = 0.0, maximum: Double = lengthMeters): RouteProjection {
        val p = toMeters(point)
        var bestDistance = Double.POSITIVE_INFINITY
        var bestAlong = minimum.coerceIn(0.0, lengthMeters)
        var bestPoint = meters.first()
        for (index in 1 until meters.size) {
            val length = cumulative[index] - cumulative[index - 1]
            if (length <= 0.0 || cumulative[index] < minimum || cumulative[index - 1] > maximum) continue
            val a = meters[index - 1]
            val b = meters[index]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val lower = ((minimum - cumulative[index - 1]) / length).coerceIn(0.0, 1.0)
            val upper = ((maximum - cumulative[index - 1]) / length).coerceIn(lower, 1.0)
            val t = (((p.x - a.x) * dx + (p.y - a.y) * dy) / (length * length)).coerceIn(lower, upper)
            val candidate = MapMeters(a.x + dx * t, a.y + dy * t)
            val gap = distance(p, candidate)
            if (gap < bestDistance) {
                bestDistance = gap
                bestAlong = cumulative[index - 1] + length * t
                bestPoint = candidate
            }
        }
        return RouteProjection(fromMeters(bestPoint), bestAlong, bestDistance)
    }

    private fun distance(a: MapMeters, b: MapMeters) = hypot(a.x - b.x, a.y - b.y)

    private companion object {
        const val METERS_PER_DEGREE = 111_195.0
    }
}
