package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.RouteGeometry

/**
 * Sends a walking journey's follow geometry and the wearer's heading beside its activity, only
 * between hubs that negotiated the launcher bundle and only while that activity is live on the
 * hub. The activity's text never waits for any of it: a refused or dropped geometry changes
 * nothing about guidance, and nothing here is queued. A geometry the link could not carry is
 * simply built again from the current journey at the next fix or tick.
 *
 * Geometry goes out at most once a second and only when it changed; revisions only rise, across
 * activity restarts and service recreation alike. A heading goes out only for a fresh fix's own
 * bearing (moving, live position, at most [MAX_HEADING_FIX_AGE_MS] old), once per fix and never
 * above the hub's five a second.
 */
internal class NavMapPublisher(
    private val supported: () -> Boolean,
    private val sendGeometry: (revision: Long, geometry: RouteGeometry) -> NexusSdkResult,
    private val sendHeading: (deg: Double, accuracyDeg: Double?) -> NexusSdkResult,
    private val now: () -> Long,
    private val geometryOf: (NavRouteSnapshot) -> RouteGeometry = { NavMapProjection.geometry(it, NavMapView.FOLLOW) },
) {
    private var snapshot: NavRouteSnapshot? = null
    private var activityLive = false
    private var sentGeometry: RouteGeometry? = null
    private var sentGeometryAtMs: Long? = null
    private var headingFixMs: Long? = null
    private var headingAtMs: Long? = null

    /**
     * The journey moved on: remember it and, unless [send] is false because its text goes first,
     * send what changed if the activity is there for it.
     */
    fun update(next: NavRouteSnapshot, send: Boolean = true) {
        snapshot = next
        if (send) flush()
    }

    /** The hub accepted the activity's start: its geometry can follow now. */
    fun activityStarted() {
        activityLive = true
        sentGeometry = null
        sentGeometryAtMs = null
        flush()
    }

    /** The activity ended, failed or went with its registration; the hub cleared its geometry. */
    fun activityEnded() {
        activityLive = false
        sentGeometry = null
        sentGeometryAtMs = null
        headingFixMs = null
    }

    /** The link changed: what the data plane missed is the current journey, sent again whole. */
    fun linkChanged() {
        sentGeometry = null
        flush()
    }

    fun clear() {
        snapshot = null
        activityEnded()
    }

    /** Sends what the remembered journey changed, within the rates above. */
    fun flush() {
        val current = snapshot ?: return
        if (!activityLive || !supported()) return
        val at = now()
        val geometry = geometryOf(current)
        val last = sentGeometryAtMs
        if (geometry != sentGeometry && (last == null || at - last >= MIN_GEOMETRY_INTERVAL_MS)) {
            if (sendGeometry(nextRevision(), geometry) == NexusSdkResult.SENT) {
                sentGeometry = geometry
                sentGeometryAtMs = at
            }
        }
        val bearing = current.liveBearing ?: return
        val fixAt = current.positionAtMs ?: return
        if (fixAt == headingFixMs || current.atMs - fixAt > MAX_HEADING_FIX_AGE_MS) return
        val lastHeading = headingAtMs
        if (lastHeading != null && at - lastHeading < MIN_HEADING_INTERVAL_MS) return
        val accuracy = current.bearingAccuracyDeg?.takeIf { it.isFinite() && it in 0.0..ActivitySurfaceContract.MAX_HEADING_ACCURACY_DEG }
        if (sendHeading(((bearing % 360) + 360) % 360.0, accuracy) == NexusSdkResult.SENT) {
            headingFixMs = fixAt
            headingAtMs = at
        }
    }

    private fun nextRevision(): Long {
        // Wall time seeds a new process above anything an earlier one could have sent.
        revision = maxOf(revision + 1, System.currentTimeMillis())
        return revision
    }

    companion object {
        const val MIN_GEOMETRY_INTERVAL_MS = 1_000L
        const val MIN_HEADING_INTERVAL_MS = 1_000L / ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND
        const val MAX_HEADING_FIX_AGE_MS = 2_000L

        @Volatile
        private var revision = 0L
    }
}
