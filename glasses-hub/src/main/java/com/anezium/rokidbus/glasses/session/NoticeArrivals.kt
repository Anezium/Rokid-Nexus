package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.NexusNoticeSurface

/**
 * Turns the notice controller's changes into session arrivals, one per notice instance. A
 * plugin shows every notice under the same surface id, so only the instance tells a new notice
 * from a redraw of the one already up, or from the band being hidden and shown again around a
 * session. The preview opens the hub's notifications page, which lands with the notification
 * centre.
 */
internal class NoticeArrivals {
    private var lastInstance: String? = null

    /** [notice] is the controller's active notice, null when none is up. */
    fun onNotice(notice: NexusNoticeSurface?): SessionEvent.NoticeArrived? {
        val instance = notice?.let { it.interactionIdentity?.instanceId ?: it.surfaceId }
        if (instance == lastInstance) return null
        lastInstance = instance
        if (notice == null) return null
        val content = notice.content
        val text = listOfNotNull(content.title, content.body, content.lines.firstOrNull())
            .firstOrNull { it.isNotBlank() }
            .orEmpty()
        return SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTIFICATIONS_PAGE_ID, text))
    }
}
