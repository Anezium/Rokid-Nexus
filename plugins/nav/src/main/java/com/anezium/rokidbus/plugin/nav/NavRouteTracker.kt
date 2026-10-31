package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RoutePositionQuality
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * One location fix. [bearing] is the course over ground, present only when the fix carried a
 * bearing and a walking speed; a phone standing still reports a meaningless one.
 */
internal data class NavFix(
    val point: GeoPoint,
    val accuracyMeters: Double,
    val elapsedMs: Long,
    val bearing: Int? = null,
    val bearingAccuracyDeg: Double? = null,
)

internal enum class RouteQuality(val message: String) {
    WAITING("Waiting for GPS"),
    STALE("GPS signal lost"),
    INACCURATE("GPS accuracy too low"),
    OFF_ROUTE("Off route"),
    TRACKING("On route"),
    ARRIVED("Arrived"),
}

/**
 * What the tracker knows at [atMs] (elapsed realtime). [position] is where the wearer was last
 * seen: the current fix while it is trusted, else the last trusted one, kept with its time
 * [positionAtMs] so its age can be told; it is null only before any trusted fix. [bearing] belongs
 * to the current fix alone and is never carried over to a later one.
 */
internal data class NavRouteSnapshot(
    val route: NavRoute,
    val progressMeters: Double = 0.0,
    val position: GeoPoint? = null,
    val positionAtMs: Long? = null,
    val bearing: Int? = null,
    val bearingAccuracyDeg: Double? = null,
    val quality: RouteQuality = RouteQuality.WAITING,
    val atMs: Long = 0L,
    /** The street tiles loaded for this journey's views; none until the map asks for them. */
    val basemap: NavBasemapTiles = NavBasemapTiles.NONE,
) {
    val remainingMeters: Double get() = (route.lengthMeters - progressMeters).coerceAtLeast(0.0)

    val stepIndex: Int
        get() = route.steps.indexOfFirst { it.atMeters > progressMeters - 12.0 }.takeIf { it >= 0 } ?: route.steps.lastIndex

    val nextStep: RouteStep get() = route.steps[stepIndex]

    val stepDistanceMeters: Double get() = (nextStep.atMeters - progressMeters).coerceAtLeast(0.0)

    /** Live while the current fix is trusted, stale with only an older one, none before any. */
    val positionQuality: RoutePositionQuality
        get() = when {
            position == null -> RoutePositionQuality.NONE
            quality == RouteQuality.TRACKING || quality == RouteQuality.ARRIVED || quality == RouteQuality.OFF_ROUTE ->
                RoutePositionQuality.LIVE
            else -> RoutePositionQuality.STALE
        }

    /** Whole seconds since the fix [position] comes from; null without a position. */
    val fixAgeS: Int?
        get() = positionAtMs?.takeIf { position != null }?.let { ((atMs - it).coerceAtLeast(0L) / 1000L).toInt() }

    /** The heading to draw: only a fresh fix's own bearing, never with a degraded position. */
    val liveBearing: Int? get() = bearing?.takeIf { positionQuality == RoutePositionQuality.LIVE }

    /** Minutes left at the planner's walking pace, never below one. */
    val remainingMinutes: Int
        get() = ceil(route.durationSeconds * remainingMeters / route.lengthMeters / 60.0).toInt().coerceAtLeast(1)

    fun guidance(): NavGuidance {
        val moving = quality == RouteQuality.TRACKING
        val arrived = quality == RouteQuality.ARRIVED
        val instruction = if (nextStep.direction == "ARRIVE") "Follow the route to ${route.destination.name}" else nextStep.instruction
        val stepDistance = distanceLabel(stepDistanceMeters)
        val secondary = if (moving) nextStep.street.ifBlank { nextStep.instruction } else route.destination.name.substringBefore(", ")
        val detail = if (moving) listOf(instruction).filter { it != secondary } else listOf(quality.message)
        return NavGuidance(
            source = NavSource.NEXUS,
            glyph = if (arrived) "arrive" else if (moving && nextStep.direction != "ARRIVE") nextStep.glyph else NavText.ROUTE_GLYPH,
            primary = if (arrived) {
                "Arrived"
            } else if (moving) {
                stepDistance
            } else {
                when (quality) {
                    RouteQuality.STALE -> "GPS lost"
                    RouteQuality.INACCURATE -> "Low accuracy"
                    RouteQuality.OFF_ROUTE -> "Off route"
                    else -> "Waiting GPS"
                }
            },
            secondary = NavText.fit(secondary, 28),
            street = secondary,
            eta = if (moving) "~$remainingMinutes min".take(8) else null,
            // Compare before clipping: the two text slots have different limits.
            detail = detail.map { NavText.fit(it, 32) },
            wholeDetail = detail,
            measure = distanceLabel(remainingMeters).takeUnless { moving && it == stepDistance }?.take(8),
            progressPercent = (progressMeters * 100.0 / route.lengthMeters).roundToInt().coerceIn(0, 100),
            stepKey = "nexus|${quality.name}|$stepIndex",
            imminent = moving && nextStep.direction != "ARRIVE" && stepDistanceMeters <= 25.0,
            arrived = arrived,
            instruction = if (moving) instruction else quality.message,
        )
    }
}

/**
 * Progress only comes from recent, accurate fixes near the expected walking segment, and only
 * moves forward. A fix that is too old or too vague changes the quality, never the progress or
 * the last trusted position.
 */
internal class NavRouteTracker(private val route: NavRoute) {
    var snapshot = NavRouteSnapshot(route)
        private set
    private var acceptedAtMs: Long? = null
    private var arrivalFixes = 0
    private var inaccurate = false
    private var offRoute = false

    fun update(fix: NavFix?, nowMs: Long): NavRouteSnapshot {
        if (snapshot.quality == RouteQuality.ARRIVED) {
            // Arrival is final; only a later fix's bearing could still change, and it is not needed.
            snapshot = snapshot.copy(bearing = null, bearingAccuracyDeg = null, atMs = nowMs)
            return snapshot
        }
        val degraded = when {
            fix == null -> RouteQuality.WAITING
            nowMs - fix.elapsedMs !in 0..STALE_AFTER_MS -> RouteQuality.STALE
            !acceptAccuracy(fix.accuracyMeters) -> RouteQuality.INACCURATE
            else -> null
        }
        if (degraded != null) {
            arrivalFixes = 0
            // The last trusted position stays, with its own time; its bearing does not.
            snapshot = snapshot.copy(
                quality = if (degraded == RouteQuality.WAITING && snapshot.position != null) RouteQuality.STALE else degraded,
                bearing = null,
                bearingAccuracyDeg = null,
                atMs = nowMs,
            )
            return snapshot
        }
        fix!!
        val maximum = acceptedAtMs?.let { last ->
            snapshot.progressMeters + 50.0 + ((fix.elapsedMs - last).coerceAtLeast(0L) / 1000.0 * 3.0).coerceAtMost(250.0)
        } ?: 300.0
        val projected = route.line.project(fix.point, (snapshot.progressMeters - 25.0).coerceAtLeast(0.0), maximum)
        val progress = projected.alongMeters.coerceAtLeast(snapshot.progressMeters)
        val atDestination = route.lengthMeters - progress <= 35.0 &&
            route.line.distance(fix.point, route.destination.point) <= ARRIVAL_RADIUS_METERS &&
            fix.accuracyMeters <= ARRIVAL_RADIUS_METERS
        offRoute = !atDestination && projected.distanceMeters > if (offRoute) ON_ROUTE_METERS else OFF_ROUTE_METERS
        if (offRoute) {
            arrivalFixes = 0
            // Where the wearer actually is, not a point snapped onto a route they left.
            snapshot = snapshot.copy(
                quality = RouteQuality.OFF_ROUTE,
                position = fix.point,
                positionAtMs = fix.elapsedMs,
                bearing = fix.bearing,
                bearingAccuracyDeg = fix.bearingAccuracyDeg,
                atMs = nowMs,
            )
            return snapshot
        }
        if (fix.elapsedMs != acceptedAtMs) arrivalFixes = if (atDestination) arrivalFixes + 1 else 0
        acceptedAtMs = fix.elapsedMs
        snapshot = snapshot.copy(
            progressMeters = progress,
            position = if (atDestination) fix.point else projected.point,
            positionAtMs = fix.elapsedMs,
            bearing = fix.bearing,
            bearingAccuracyDeg = fix.bearingAccuracyDeg,
            quality = if (arrivalFixes >= 2) RouteQuality.ARRIVED else RouteQuality.TRACKING,
            atMs = nowMs,
        )
        return snapshot
    }

    private fun acceptAccuracy(meters: Double): Boolean {
        val limit = if (inaccurate) RECOVER_ACCURACY_METERS else MAX_ACCURACY_METERS
        inaccurate = !meters.isFinite() || meters !in 0.0..limit
        return !inaccurate
    }

    companion object {
        const val STALE_AFTER_MS = 30_000L
        const val MAX_ACCURACY_METERS = 35.0
        private const val RECOVER_ACCURACY_METERS = 30.0
        const val OFF_ROUTE_METERS = 50.0
        private const val ON_ROUTE_METERS = 40.0
        const val ARRIVAL_RADIUS_METERS = 20.0
    }
}

internal fun distanceLabel(meters: Double): String = if (meters >= 1000.0) {
    "%.1f km".format(Locale.ROOT, meters / 1000.0)
} else {
    "${(meters / 5.0).roundToInt() * 5} m"
}
