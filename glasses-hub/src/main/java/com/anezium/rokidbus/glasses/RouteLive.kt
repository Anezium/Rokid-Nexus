package com.anezium.rokidbus.glasses

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.ActivityGeometryUpdate
import com.anezium.rokidbus.shared.ActivityHeading
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import com.anezium.rokidbus.shared.RouteGeometry
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs
import kotlin.math.exp

/**
 * A low-pass over the shortest arc, so 350° then 10° turns through north and never sweeps back
 * across south. [TAU_MS] trades lag for jitter at the phone's five samples a second at most.
 *
 * Age is measured from arrival on the glasses' monotonic clock: the sample's own `atWallMs` comes
 * from the phone, whose wall clock may differ from this one by more than the whole stale window,
 * so it only orders samples. The data plane never queues a heading, so arrival age is its age.
 */
internal class HeadingFilter {
    private var value: Double? = null
    private var arrivedAtMs = 0L
    private var lastWallMs = Long.MIN_VALUE

    /** The smoothed heading after [heading], or null when it is older than the last one taken. */
    fun sample(heading: ActivityHeading, nowMs: Long): Double? {
        if (heading.atWallMs < lastWallMs) return null
        val previous = value?.takeIf { nowMs - arrivedAtMs <= STALE_MS }
        val next = if (previous == null) {
            normalize(heading.deg)
        } else {
            val dt = (nowMs - arrivedAtMs).coerceAtLeast(0L).toDouble()
            val alpha = 1.0 - exp(-dt / TAU_MS)
            normalize(previous + alpha * shortestArc(previous, heading.deg))
        }
        value = next
        arrivedAtMs = nowMs
        lastWallMs = heading.atWallMs
        return next
    }

    /** The smoothed heading, or null when no sample arrived within [STALE_MS]. */
    fun current(nowMs: Long): Double? = value?.takeIf { nowMs - arrivedAtMs <= STALE_MS }

    /** When the current heading turns stale, or null without one. */
    fun staleAtMs(): Long? = value?.let { arrivedAtMs + STALE_MS + 1 }

    companion object {
        const val STALE_MS = ActivitySurfaceContract.HEADING_STALE_MS
        const val TAU_MS = 350.0

        /** The signed turn from [from] to [to] in (-180, 180]. */
        fun shortestArc(from: Double, to: Double): Double {
            var delta = (to - from) % 360.0
            if (delta > 180.0) delta -= 360.0
            if (delta <= -180.0) delta += 360.0
            return delta
        }

        fun normalize(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
    }
}

/**
 * The route geometry and heading each owner sent for its live activity (plan 028a §1.7). Pure:
 * time arrives as arguments. Geometry is latest-wins by revision; both are forgotten when the
 * owner's activity ends or goes offline, so the next activity starts from nothing.
 */
internal class RouteLiveState {
    private data class LiveGeometry(val revision: Long, val geometry: RouteGeometry)

    private class LiveHeading {
        val filter = HeadingFilter()
        var drawnDeg: Double? = null
    }

    private val geometries = mutableMapOf<String, LiveGeometry>()
    private val headings = mutableMapOf<String, LiveHeading>()
    private var liveOwners = emptySet<String>()

    /** True when [update] replaced what is drawn for [owner]; an older or equal revision is dropped. */
    fun onGeometry(owner: String, update: ActivityGeometryUpdate): Boolean {
        val current = geometries[owner]
        if (current != null && update.revision <= current.revision) return false
        geometries[owner] = LiveGeometry(update.revision, update.geometry)
        return current?.geometry != update.geometry
    }

    /**
     * True when the heading as drawn changed: it moved by at least [REDRAW_DEG] since the last
     * redraw, or it came back after being absent. Smaller moves are kept, not redrawn.
     */
    fun onHeading(owner: String, heading: ActivityHeading, nowMs: Long): Boolean {
        val live = headings.getOrPut(owner, ::LiveHeading)
        val wasAbsent = live.filter.current(nowMs) == null
        val smoothed = live.filter.sample(heading, nowMs) ?: return false
        val drawn = live.drawnDeg
        if (!wasAbsent && drawn != null && abs(HeadingFilter.shortestArc(drawn, smoothed)) < REDRAW_DEG) return false
        live.drawnDeg = smoothed
        return true
    }

    fun geometry(owner: String): RouteGeometry? = geometries[owner]?.geometry

    fun geometryRevision(owner: String): Long? = geometries[owner]?.revision

    /** The smoothed heading, or null when absent or older than the stale window. */
    fun heading(owner: String, nowMs: Long): Float? = headings[owner]?.filter?.current(nowMs)?.toFloat()

    /** When [owner]'s heading turns stale, for the redraw that drops it. */
    fun headingStaleAtMs(owner: String): Long? = headings[owner]?.filter?.staleAtMs()

    /**
     * Forgets the owners whose activity went offline, or that were live before and are gone now.
     * An owner never seen live keeps what it sent: its start may still be on its way.
     */
    fun retain(live: Set<String>, offline: Set<String>): Set<String> {
        val known = geometries.keys + headings.keys
        val cleared = known.filterTo(mutableSetOf()) { it in offline || (it in liveOwners && it !in live) }
        cleared.forEach(::forget)
        liveOwners = live
        return cleared
    }

    /** Everything is forgotten, as when the link goes away. */
    fun clear(): Set<String> {
        val cleared = (geometries.keys + headings.keys).toSet()
        geometries.clear()
        headings.clear()
        liveOwners = emptySet()
        return cleared
    }

    private fun forget(owner: String) {
        geometries.remove(owner)
        headings.remove(owner)
    }

    companion object {
        /** Below this the map is not turned again: a redraw per jitter would cost more than it shows. */
        const val REDRAW_DEG = 2.0
    }
}

/**
 * The hub's copy of every owner's live route, fed by `/activity/geometry` and `/activity/heading`
 * as the phone forwards them over the data plane, with the owner it authenticated. Main thread
 * only; only over a pair that negotiated the launcher bundle.
 */
internal object RouteLive {
    private val main = Handler(Looper.getMainLooper())
    private val state = RouteLiveState()
    private val listeners = CopyOnWriteArrayList<(String) -> Unit>()
    private val staleChecks = mutableMapOf<String, Runnable>()

    fun observe(listener: (String) -> Unit): () -> Unit {
        listeners += listener
        return { listeners.remove(listener) }
    }

    fun geometry(owner: String): RouteGeometry? = state.geometry(owner)

    fun heading(owner: String): Float? = state.heading(owner, SystemClock.uptimeMillis())

    fun onGeometry(payload: JSONObject) {
        runOnMain {
            val owner = ownerOf(payload) ?: return@runOnMain log("activity geometry dropped reason=OWNER")
            if (!GlassesHub.launcherInteractiveActive()) return@runOnMain log("activity geometry dropped reason=NOT_NEGOTIATED")
            val update = ActivitySurfaceContract.parseGeometry(payload)
                ?: return@runOnMain log("activity geometry dropped owner=$owner reason=MALFORMED")
            if (state.onGeometry(owner, update)) notify(owner)
        }
    }

    fun onHeading(payload: JSONObject) {
        runOnMain {
            val owner = ownerOf(payload) ?: return@runOnMain
            if (!GlassesHub.launcherInteractiveActive()) return@runOnMain
            val heading = ActivitySurfaceContract.parseHeading(payload) ?: return@runOnMain
            val now = SystemClock.uptimeMillis()
            val changed = state.onHeading(owner, heading, now)
            scheduleStaleCheck(owner, now)
            if (changed) notify(owner)
        }
    }

    /** The activity collection changed: what ended or went offline loses its route. */
    fun retain(live: Set<String>, offline: Set<String>) {
        runOnMain { state.retain(live, offline).forEach(::cleared) }
    }

    fun clear() {
        runOnMain { state.clear().forEach(::cleared) }
    }

    private fun cleared(owner: String) {
        staleChecks.remove(owner)?.let(main::removeCallbacks)
        notify(owner)
    }

    /** A heading that stops arriving is dropped from the map once stale, not at the next sample. */
    private fun scheduleStaleCheck(owner: String, nowMs: Long) {
        staleChecks.remove(owner)?.let(main::removeCallbacks)
        val at = state.headingStaleAtMs(owner) ?: return
        val check = Runnable {
            staleChecks.remove(owner)
            if (state.heading(owner, SystemClock.uptimeMillis()) == null) notify(owner)
        }
        staleChecks[owner] = check
        main.postDelayed(check, (at - nowMs).coerceAtLeast(0L))
    }

    private fun notify(owner: String) {
        listeners.forEach { listener -> runCatching { listener(owner) } }
    }

    private fun ownerOf(payload: JSONObject): String? {
        val owner = payload.optString("ownerPluginId").takeIf(PluginDescriptor::isValidId) ?: return null
        val surfaceId = payload.optString("surfaceId")
        return owner.takeIf { surfaceId == "$owner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}" }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }
}
