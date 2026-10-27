package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusRenderedMap
import com.anezium.rokidbus.client.plugin.NexusRenderedMapView
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Gate
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Reason
import com.anezium.rokidbus.shared.MapFeedbackDiagnostics.Verdict
import com.anezium.rokidbus.shared.RenderedMapContract

/** Owner-local covers of publications, never keys supplied by a feedback sender. */
internal class NavRenderedFrames(
    private val diagnostics: MapFeedbackDiagnostics? = null,
    private val now: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private data class Cover(val keys: List<TileKey>, val publishedAtMs: Long)
    private data class Identity(val route: NavRoute, val quality: RouteQuality, val step: Int)
    private fun identity(snapshot: NavRouteSnapshot) = Identity(snapshot.route, snapshot.quality, snapshot.stepIndex)
    private data class Publication(val revision: Long, val atMs: Long, val identity: Identity)
    // A still-drawn publication can outlive many updates of another surface.
    private val covers = LinkedHashMap<NexusRenderedMap, Cover>(64, 0.75f, true)
    private val latest = mutableMapOf<String?, Publication>()

    @Synchronized
    fun remember(revision: Long, snapshot: NavRouteSnapshot, pageId: String? = null) {
        latest[pageId] = Publication(revision, now(), identity(snapshot))
        for (view in NavMapView.entries) {
            if (pageId == null && view != NavMapView.FOLLOW) continue
            val frame = NexusRenderedMap(
                if (view == NavMapView.FOLLOW) NexusRenderedMapView.FOLLOW else NexusRenderedMapView.OVERVIEW,
                revision, pageId,
            )
            covers[frame] = Cover(NavMapProjection.tiles(snapshot, view, NavJourneyService.BASEMAP_SOURCE), now())
        }
        while (covers.size > MAX_FRAMES) covers.remove(covers.keys.first())
    }

    @Synchronized
    fun keys(acknowledged: Set<NexusRenderedMap>): List<TileKey> {
        if (acknowledged.isEmpty()) diagnostics?.record(Gate.NAV_CORRELATION, Verdict.CLEAR, Reason.NO_LEASE,
            MapFeedbackDiagnostics.Sample(frameCount = 0, keyCount = 0))
        return acknowledged.flatMap { frame ->
            val cover = covers[frame]
            diagnostics?.record(Gate.NAV_CORRELATION, if (cover == null) Verdict.DROP else Verdict.PASS,
                if (cover == null) Reason.UNKNOWN_PUBLICATION else Reason.REMEMBERED,
                sample(frame).copy(frameCount = acknowledged.size, keyCount = cover?.keys?.size ?: 0))
            cover?.keys.orEmpty()
        }.distinct()
    }

    /** Give the first draw a quiet window too: no lease yet does not mean no report in flight. */
    @Synchronized
    fun acknowledgmentDelayMs(
        acknowledged: Set<NexusRenderedMap>, leaseRemainingMs: Long?, pageId: String? = null,
        snapshot: NavRouteSnapshot? = null,
    ): Long? {
        val drawn = acknowledged.firstOrNull { it.pageId == pageId }
        val publication = latest[pageId]
        val delay = when {
            publication == null || drawn?.revision == publication.revision -> null
            snapshot != null && identity(snapshot) != publication.identity -> null
            drawn != null && leaseRemainingMs != null -> leaseRemainingMs.coerceAtLeast(1)
            // Missing feedback (Text, Commands, loss or expiry) must not freeze publications.
            else -> (publication.atMs + RenderedMapContract.LEASE_MS - now()).takeIf { it > 0 }
        }
        val held = delay != null
        val sample = drawn?.let(::sample) ?: MapFeedbackDiagnostics.Sample(
            kind = if (pageId == null) MapFeedbackDiagnostics.Kind.AMBIENT else MapFeedbackDiagnostics.Kind.PAGE,
            currentRevision = publication?.revision ?: -1,
        )
        diagnostics?.record(Gate.NAV_PUBLICATION, if (held) Verdict.HOLD else Verdict.PASS,
            if (held) Reason.AWAITING_ACK else if (drawn == null) Reason.NO_LEASE else Reason.CURRENT, sample)
        return delay
    }

    private fun sample(frame: NexusRenderedMap) = MapFeedbackDiagnostics.Sample(
        kind = if (frame.pageId == null) MapFeedbackDiagnostics.Kind.AMBIENT else MapFeedbackDiagnostics.Kind.PAGE,
        view = if (frame.view == NexusRenderedMapView.FOLLOW) MapFeedbackDiagnostics.View.FOLLOW else MapFeedbackDiagnostics.View.OVERVIEW,
        revision = frame.revision, currentRevision = latest[frame.pageId]?.revision ?: -1,
        ageMs = covers[frame]?.let { now() - it.publishedAtMs } ?: -1,
    )

    @Synchronized
    fun clear() {
        covers.clear()
        latest.clear()
    }

    private companion object {
        const val MAX_FRAMES = 48
    }
}
