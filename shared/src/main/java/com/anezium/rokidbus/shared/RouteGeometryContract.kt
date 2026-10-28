package com.anezium.rokidbus.shared

import org.json.JSONArray
import org.json.JSONObject

/** A point of the route frame: integer units, 0..[RouteGeometryContract.EXTENT] on both axes. */
data class RoutePoint(val x: Int, val y: Int)

enum class RoutePathRole(val wireValue: String) {
    STREET("street"),
    MAJOR("major"),
    ROUTE("route"),
    COMPLETED("completed"),
    STOP("stop"),
    ;

    companion object {
        fun fromWireValue(value: String): RoutePathRole? = entries.firstOrNull { it.wireValue == value }
    }
}

data class RoutePath(val role: RoutePathRole, val points: List<RoutePoint>)

/** [priority] 0 is the most important label; the hub drops the others first when space is short. */
data class RouteLabel(val text: String, val x: Int, val y: Int, val priority: Int)

/**
 * How much the wearer's position can be trusted. [STALE] keeps the last fix with its age; [NONE]
 * has no position at all. Either one makes the hub draw north-up with a dashed route.
 */
enum class RoutePositionQuality(val wireValue: String) {
    LIVE("live"),
    STALE("stale"),
    NONE("none"),
    ;

    companion object {
        fun fromWireValue(value: String): RoutePositionQuality? = entries.firstOrNull { it.wireValue == value }
    }
}

/** [point] is null exactly when [quality] is [RoutePositionQuality.NONE]. */
data class RoutePosition(
    val quality: RoutePositionQuality,
    val point: RoutePoint?,
    val fixAgeS: Int? = null,
)

data class RouteStopMark(val x: Int, val y: Int, val label: String)

/**
 * Where a frame's centre lies on the ground: [x] and [y] decimetres along the frame's own axes from
 * a point that stays put for every frame sharing [frame]. Two frames of one [frame] differ only by
 * where they are centred, so the hub can glide the map from one to the next instead of jumping.
 * A new route, or anything that moves that point, takes a new [frame].
 */
data class RouteAnchor(val frame: Int, val x: Int, val y: Int)

/**
 * Bounded, hub-rendered route geometry. The frame is north-up in a local metric projection where
 * [RouteGeometryContract.EXTENT] units span [spanMeters]; the hub rotates it for heading-up views.
 * Providers clip with Liang-Barsky to a window covering the rotated viewport, never by clamping
 * vertices, so a clipped segment keeps its true direction.
 *
 * [attribution] credits the source of the `street` and `major` paths (for OpenStreetMap data,
 * "© OpenStreetMap"); the hub draws it in a corner of the map. Hubs that predate it ignore it.
 * [anchor] places the frame on the ground for that glide; hubs that predate it ignore it too.
 */
data class RouteGeometry(
    val spanMeters: Double,
    val paths: List<RoutePath>,
    val labels: List<RouteLabel> = emptyList(),
    val position: RoutePosition? = null,
    val turn: RoutePoint? = null,
    val destination: RoutePoint? = null,
    val stops: List<RouteStopMark> = emptyList(),
    val attribution: String? = null,
    val anchor: RouteAnchor? = null,
)

sealed interface RouteGeometryResult {
    data class Valid(val geometry: RouteGeometry) : RouteGeometryResult
    data class Invalid(val reason: String) : RouteGeometryResult
}

/** Strict validation and the canonical codec of [RouteGeometry]; pure, no Android dependency. */
object RouteGeometryContract {
    const val EXTENT = 1000
    const val MAX_SPAN_METERS = 50_000.0
    const val MAX_PATHS = 24
    const val MAX_POINTS = 1024
    const val MIN_PATH_POINTS = 2
    const val MAX_BYTES = 16 * 1024
    const val MAX_LABELS = 4
    const val MAX_LABEL_CHARS = 24
    const val MAX_LABEL_PRIORITY = 3
    const val MAX_STOPS = 4
    const val MAX_STOP_LABEL_CHARS = 4
    const val MAX_ATTRIBUTION_CHARS = 40

    /** An anchor's reach, in decimetres either way: a quarter of the way round the Earth. */
    const val MAX_ANCHOR_DM = 100_000_000

    /** Rejects anything outside the schema, including a serialized size above [MAX_BYTES]. */
    fun validate(value: Any?): RouteGeometryResult = try {
        val geometry = value as? JSONObject ?: fail("geometry must be an object")
        val serialized = try {
            geometry.toString()
        } catch (_: Exception) {
            fail("geometry cannot be serialized")
        }
        check(serialized.length <= MAX_BYTES && serialized.toByteArray(Charsets.UTF_8).size <= MAX_BYTES, "geometry exceeds $MAX_BYTES bytes")
        RouteGeometryResult.Valid(read(geometry))
    } catch (invalid: InvalidGeometry) {
        RouteGeometryResult.Invalid(invalid.reason)
    } catch (_: Exception) {
        RouteGeometryResult.Invalid("geometry could not be read")
    }

    fun parseOrNull(value: Any?): RouteGeometry? = (validate(value) as? RouteGeometryResult.Valid)?.geometry

    /** Whether [geometry] would pass [validate] once encoded. */
    fun isValid(geometry: RouteGeometry): Boolean = validate(encode(geometry)) is RouteGeometryResult.Valid

    /** The wire form; [validate] of it gives [geometry] back. */
    fun encode(geometry: RouteGeometry): JSONObject = JSONObject()
        .put("spanMeters", geometry.spanMeters)
        .put(
            "paths",
            JSONArray().apply {
                geometry.paths.forEach { path ->
                    put(
                        JSONObject().put("role", path.role.wireValue).put(
                            "points",
                            JSONArray().apply { path.points.forEach { put(it.x).put(it.y) } },
                        ),
                    )
                }
            },
        )
        .apply {
            if (geometry.labels.isNotEmpty()) {
                put(
                    "labels",
                    JSONArray().apply {
                        geometry.labels.forEach {
                            put(JSONObject().put("text", it.text).put("x", it.x).put("y", it.y).put("priority", it.priority))
                        }
                    },
                )
            }
            geometry.position?.let { position ->
                put(
                    "position",
                    JSONObject().put("quality", position.quality.wireValue).apply {
                        position.point?.let { put("x", it.x).put("y", it.y) }
                        position.fixAgeS?.let { put("fixAgeS", it) }
                    },
                )
            }
            geometry.turn?.let { put("turn", pointJson(it)) }
            geometry.destination?.let { put("destination", pointJson(it)) }
            if (geometry.stops.isNotEmpty()) {
                put(
                    "stops",
                    JSONArray().apply {
                        geometry.stops.forEach { put(JSONObject().put("x", it.x).put("y", it.y).put("label", it.label)) }
                    },
                )
            }
            geometry.attribution?.let { put("attribution", it) }
            geometry.anchor?.let { put("anchor", JSONObject().put("frame", it.frame).put("x", it.x).put("y", it.y)) }
        }

    private fun read(geometry: JSONObject): RouteGeometry {
        val span = (geometry.opt("spanMeters") as? Number)?.toDouble() ?: fail("spanMeters must be a number")
        check(span.isFinite() && span > 0.0 && span <= MAX_SPAN_METERS, "spanMeters must be above 0 and at most $MAX_SPAN_METERS")

        val pathArray = geometry.opt("paths") as? JSONArray ?: fail("paths must be an array")
        check(pathArray.length() <= MAX_PATHS, "more than $MAX_PATHS paths")
        var total = 0
        val paths = (0 until pathArray.length()).map { index ->
            val entry = pathArray.opt(index) as? JSONObject ?: fail("paths[$index] must be an object")
            val role = (entry.opt("role") as? String)?.let(RoutePathRole::fromWireValue) ?: fail("paths[$index] role is unknown")
            val flat = entry.opt("points") as? JSONArray ?: fail("paths[$index] points must be an array")
            check(flat.length() % 2 == 0, "paths[$index] points must be x,y pairs")
            val count = flat.length() / 2
            check(count >= MIN_PATH_POINTS, "paths[$index] needs at least $MIN_PATH_POINTS points")
            total += count
            check(total <= MAX_POINTS, "more than $MAX_POINTS points in total")
            RoutePath(role, (0 until count).map { RoutePoint(coordinate(flat.opt(it * 2)), coordinate(flat.opt(it * 2 + 1))) })
        }

        val labels = optionalArray(geometry, "labels")?.let { array ->
            check(array.length() <= MAX_LABELS, "more than $MAX_LABELS labels")
            (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("labels[$index] must be an object")
                val text = entry.opt("text") as? String ?: fail("labels[$index] text must be a string")
                check(text.isNotBlank() && text.length <= MAX_LABEL_CHARS, "labels[$index] text must be 1..$MAX_LABEL_CHARS characters")
                val priority = integer(entry.opt("priority")) ?: fail("labels[$index] priority must be an integer")
                check(priority in 0..MAX_LABEL_PRIORITY, "labels[$index] priority must be 0..$MAX_LABEL_PRIORITY")
                RouteLabel(text, coordinate(entry.opt("x")), coordinate(entry.opt("y")), priority)
            }
        }.orEmpty()

        val position = optionalObject(geometry, "position")?.let { entry ->
            val quality = (entry.opt("quality") as? String)?.let(RoutePositionQuality::fromWireValue)
                ?: fail("position quality is unknown")
            val fixAge = if (entry.has("fixAgeS")) {
                integer(entry.opt("fixAgeS"))?.takeIf { it >= 0 } ?: fail("position fixAgeS must be a nonnegative integer")
            } else {
                null
            }
            // A position of quality none has no point, whatever the provider left in it.
            val point = if (quality == RoutePositionQuality.NONE) null else RoutePoint(coordinate(entry.opt("x")), coordinate(entry.opt("y")))
            RoutePosition(quality, point, fixAge)
        }

        val stops = optionalArray(geometry, "stops")?.let { array ->
            check(array.length() <= MAX_STOPS, "more than $MAX_STOPS stops")
            (0 until array.length()).map { index ->
                val entry = array.opt(index) as? JSONObject ?: fail("stops[$index] must be an object")
                val label = entry.opt("label") as? String ?: fail("stops[$index] label must be a string")
                check(label.isNotBlank() && label.length <= MAX_STOP_LABEL_CHARS, "stops[$index] label must be 1..$MAX_STOP_LABEL_CHARS characters")
                RouteStopMark(coordinate(entry.opt("x")), coordinate(entry.opt("y")), label)
            }
        }.orEmpty()

        val attribution = if (!geometry.has("attribution") || geometry.opt("attribution") == JSONObject.NULL) {
            null
        } else {
            val text = geometry.opt("attribution") as? String ?: fail("attribution must be a string")
            check(
                text.isNotBlank() && text.length <= MAX_ATTRIBUTION_CHARS && text.none(Char::isISOControl),
                "attribution must be 1..$MAX_ATTRIBUTION_CHARS printable characters",
            )
            text
        }

        val anchor = optionalObject(geometry, "anchor")?.let { entry ->
            fun decimetres(key: String): Int {
                val value = integer(entry.opt(key)) ?: fail("anchor $key must be an integer")
                check(value in -MAX_ANCHOR_DM..MAX_ANCHOR_DM, "anchor $key must be within $MAX_ANCHOR_DM decimetres")
                return value
            }
            RouteAnchor(integer(entry.opt("frame")) ?: fail("anchor frame must be an integer"), decimetres("x"), decimetres("y"))
        }

        return RouteGeometry(
            spanMeters = span,
            paths = paths,
            labels = labels,
            position = position,
            turn = optionalObject(geometry, "turn")?.let(::point),
            destination = optionalObject(geometry, "destination")?.let(::point),
            stops = stops,
            attribution = attribution,
            anchor = anchor,
        )
    }

    private fun point(entry: JSONObject): RoutePoint = RoutePoint(coordinate(entry.opt("x")), coordinate(entry.opt("y")))

    private fun pointJson(point: RoutePoint): JSONObject = JSONObject().put("x", point.x).put("y", point.y)

    private fun coordinate(value: Any?): Int {
        val coordinate = integer(value) ?: fail("coordinates must be integers")
        check(coordinate in 0..EXTENT, "coordinates must be 0..$EXTENT")
        return coordinate
    }

    private fun integer(value: Any?): Int? {
        val number = value as? Number ?: return null
        val double = number.toDouble()
        if (!double.isFinite() || double != Math.rint(double) || double < Int.MIN_VALUE || double > Int.MAX_VALUE) return null
        return double.toInt()
    }

    private fun optionalArray(payload: JSONObject, key: String): JSONArray? {
        if (!payload.has(key) || payload.opt(key) == JSONObject.NULL) return null
        return payload.opt(key) as? JSONArray ?: fail("$key must be an array")
    }

    private fun optionalObject(payload: JSONObject, key: String): JSONObject? {
        if (!payload.has(key) || payload.opt(key) == JSONObject.NULL) return null
        return payload.opt(key) as? JSONObject ?: fail("$key must be an object")
    }

    private class InvalidGeometry(val reason: String) : RuntimeException()

    private fun fail(reason: String): Nothing = throw InvalidGeometry(reason)

    private fun check(condition: Boolean, reason: String) {
        if (!condition) fail(reason)
    }
}
