package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusRenderedMap
import com.anezium.rokidbus.client.plugin.NexusRenderedMapView

/** Owner-local covers of publications, never keys supplied by a feedback sender. */
internal class NavRenderedFrames {
    private val covers = LinkedHashMap<NexusRenderedMap, List<TileKey>>()

    @Synchronized
    fun remember(revision: Long, snapshot: NavRouteSnapshot, pageId: String? = null) {
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

    @Synchronized
    fun clear() = covers.clear()

    private companion object {
        const val MAX_FRAMES = 48
    }
}
