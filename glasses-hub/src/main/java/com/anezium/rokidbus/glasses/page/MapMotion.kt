package com.anezium.rokidbus.glasses.page

import android.os.SystemClock
import com.anezium.rokidbus.glasses.HudMotion
import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/** Where one draw puts the wearer, in the frame's units, and the heading it turns the map by. */
internal data class MapPose(val x: Float, val y: Float, val headingDeg: Float?)

/**
 * Glides a map between the geometries a provider publishes a few seconds apart, so the wearer
 * moves across it instead of jumping. A new geometry starts a glide from wherever the wearer is
 * drawn now to where it puts them, over the time the previous one took to come: one glide ends
 * as the next begins, and walking draws as steady motion one publication behind. The heading
 * eases toward each sample the same way. Only frames sharing an anchor's frame glide; anything
 * else (no anchor, a new route, a new zoom, a jump past a third of the frame, a long silence,
 * motion turned off) is drawn where it says at once.
 *
 * Views are rebuilt with every island or page update, so the state lives here, one per form and
 * view, and a rebuilt map continues the glide its predecessor was drawing.
 */
internal class MapMotion(private val clock: () -> Long = SystemClock::uptimeMillis) {
    private var geometry: RouteGeometry? = null
    private var frame: Int? = null
    private var spanMeters = 0.0
    /** The current geometry's centre, in metres on the ground of [frame]. */
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var fromX = 0.0
    private var fromY = 0.0
    private var toX = 0.0
    private var toY = 0.0
    private var startMs = 0L
    private var durationMs = 0L
    private var arrivedMs: Long? = null
    private var hasPosition = false

    private var headingTarget: Float? = null
    private var heading: Float? = null
    private var headingAtMs = 0L

    /** A geometry the owner bound; the same one bound again, as a rebuilt view does, is not news. */
    fun arrive(next: RouteGeometry) {
        if (next == geometry) return
        val now = clock()
        val point = next.position?.point
        val anchor = next.anchor
        val previousArrival = arrivedMs
        geometry = next
        arrivedMs = now
        if (point == null || anchor == null) {
            frame = null
            hasPosition = false
            return
        }
        val span = next.spanMeters
        val nextAnchorX = anchor.x / 10.0
        val nextAnchorY = anchor.y / 10.0
        val targetX = nextAnchorX + (point.x - HALF) * span / RouteGeometryContract.EXTENT
        val targetY = nextAnchorY + (point.y - HALF) * span / RouteGeometryContract.EXTENT
        val (shownX, shownY) = shownMeters(now)
        val glides = HudMotion.enabled && hasPosition && frame == anchor.frame && spanMeters == span &&
            previousArrival != null && now - previousArrival in 1..MAX_GAP_MS &&
            hypot(targetX - shownX, targetY - shownY) <= span / 3.0
        frame = anchor.frame
        spanMeters = span
        anchorX = nextAnchorX
        anchorY = nextAnchorY
        toX = targetX
        toY = targetY
        hasPosition = true
        if (glides) {
            fromX = shownX
            fromY = shownY
            startMs = now
            durationMs = (now - previousArrival!!).coerceIn(MIN_GLIDE_MS, MAX_GLIDE_MS)
        } else {
            fromX = targetX
            fromY = targetY
            startMs = now
            durationMs = 0L
        }
    }

    /** The heading the owner was given; the drawn one eases toward it. */
    fun aim(headingDeg: Float?) {
        val now = clock()
        if (headingDeg == null || heading == null || !HudMotion.enabled) {
            heading = headingDeg
        } else {
            heading = easedHeading(now)
        }
        headingTarget = headingDeg
        headingAtMs = now
    }

    /** Where to draw the wearer of [current] now, or null to draw it as it stands. */
    fun pose(current: RouteGeometry): MapPose? {
        val now = clock()
        val drawnHeading = easedHeading(now).also {
            heading = it
            headingAtMs = now
        }
        if (current != geometry || !hasPosition) return null
        val (x, y) = shownMeters(now)
        return MapPose(
            (HALF + (x - anchorX) * RouteGeometryContract.EXTENT / spanMeters).toFloat(),
            (HALF + (y - anchorY) * RouteGeometryContract.EXTENT / spanMeters).toFloat(),
            drawnHeading,
        )
    }

    /** Whether the next frames still move: a glide in progress or a heading not yet reached. */
    fun moving(): Boolean {
        val now = clock()
        val gliding = hasPosition && durationMs > 0L && now - startMs < durationMs
        val target = headingTarget
        val turning = target != null && heading?.let { abs(shortest(it, target)) > HEADING_SETTLED_DEG } == true
        return gliding || turning
    }

    private fun shownMeters(now: Long): Pair<Double, Double> {
        if (!hasPosition) return toX to toY
        val t = if (durationMs <= 0L) 1.0 else ((now - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        return (fromX + (toX - fromX) * t) to (fromY + (toY - fromY) * t)
    }

    private fun easedHeading(now: Long): Float? {
        val target = headingTarget ?: return null
        val shown = heading ?: return target
        val elapsed = (now - headingAtMs).coerceAtLeast(0L)
        val left = exp(-elapsed / HEADING_EASE_MS.toDouble()).toFloat()
        return normalized(target - shortest(shown, target) * left)
    }

    internal companion object {
        private const val HALF = RouteGeometryContract.EXTENT / 2.0
        const val MIN_GLIDE_MS = 300L
        const val MAX_GLIDE_MS = 8_000L

        /** Longer without a geometry than this, the wearer is drawn where the next one says. */
        const val MAX_GAP_MS = 12_000L
        const val HEADING_EASE_MS = 220L
        private const val HEADING_SETTLED_DEG = 0.5f

        private val states = HashMap<Pair<MapForm, MapView>, MapMotion>()

        /** The one motion of a form and view; a hidden form's state simply waits for its next map. */
        fun of(form: MapForm, view: MapView): MapMotion = synchronized(states) { states.getOrPut(form to view) { MapMotion() } }

        /** For tests: every form starts afresh. */
        fun reset() = synchronized(states) { states.clear() }

        /** How far [from] must turn to reach [to], -180 until 180. */
        fun shortest(from: Float, to: Float): Float = ((to - from) % 360f + 540f) % 360f - 180f

        private fun normalized(degrees: Float): Float = ((degrees % 360f) + 360f) % 360f
    }
}
