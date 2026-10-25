package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusRenderedMap
import com.anezium.rokidbus.client.plugin.NexusRenderedMapView

/** Owner-local covers of publications, never keys supplied by a feedback sender. */
internal class NavRenderedFrames {
    // A still-drawn publication can outlive many updates of another surface.
    private val covers = LinkedHashMap<NexusRenderedMap, List<TileKey>>(64, 0.75f, true)
    private val latest = mutableMapOf<String?, Long>()

    @Synchronized
    fun remember(revision: Long, snapshot: NavRouteSnapshot, pageId: String? = null) {
        latest[pageId] = revision
        for (view in NavMapView.entries) {
            if (pageId == null && view != NavMapView.FOLLOW) continue
            val frame = NexusRenderedMap(
                if (view == NavMapView.FOLLOW) NexusRenderedMapView.FOLLOW else NexusRenderedMapView.OVERVIEW,
                revision, pageId,
            )
            covers[frame] = NavMapProjection.tiles(snapshot, view, NavJourneyService.BASEMAP_SOURCE)
        }
        while (covers.size > MAX_FRAMES) covers.remove(covers.keys.first())
    }

    @Synchronized
    fun keys(acknowledged: Set<NexusRenderedMap>): List<TileKey> = acknowledged.flatMap { covers[it].orEmpty() }.distinct()

    /** Let a drawn surface acknowledge its in-flight replacement before superseding it again. */
    @Synchronized
    fun awaitingAcknowledgment(acknowledged: Set<NexusRenderedMap>, pageId: String? = null): Boolean {
        val drawn = acknowledged.firstOrNull { it.pageId == pageId } ?: return false
        return latest[pageId]?.let { it != drawn.revision } == true
    }

    @Synchronized
    fun clear() {
        covers.clear()
        latest.clear()
    }

    private companion object {
        const val MAX_FRAMES = 48
    }
}
