package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceContract

/** A live activity as the host reports it; [pinned] marks the wearer's head card. */
internal data class ActivityStop(
    val id: String,
    val pageId: String,
    val text: String,
    val pinned: Boolean = false,
)

/** The latest notification, kept as an unarmed preview; it can never answer. */
internal data class NoticePreview(val pageId: String, val text: String)

internal enum class RootStopKind { ACTIVITY, NOTICE_PREVIEW, ACTIVITIES, NOTIFICATIONS, APPLICATIONS }

internal data class RootStop(
    val id: String,
    val kind: RootStopKind,
    val pageId: String,
    val text: String,
    val count: Int? = null,
    val ended: Boolean = false,
)

/**
 * Builds the anchored root once per session. The order is fixed by the plan:
 * pinned activity, second activity, latest notification preview,
 * `Activities · n` when more than two activities are live, `Notifications · n`,
 * Applications. Any number of activities is accepted; the shipped two-activity
 * capacity is negotiated elsewhere.
 */
internal object RootStops {
    const val PREVIEW_STOP_ID = "nexus:notice-preview"
    const val ACTIVITIES_PAGE_ID = "nexus:activities"
    const val NOTIFICATIONS_PAGE_ID = "nexus:notifications"
    const val APPLICATIONS_PAGE_ID = "nexus:applications"

    private const val VISIBLE_ACTIVITY_STOPS = 2

    fun build(
        activities: List<ActivityStop>,
        preview: NoticePreview?,
        notificationCount: Int,
    ): List<RootStop> {
        val ordered = activities.sortedByDescending(ActivityStop::pinned)
        val stops = buildList {
            ordered.take(VISIBLE_ACTIVITY_STOPS).forEach {
                add(RootStop(it.id, RootStopKind.ACTIVITY, it.pageId, it.text))
            }
            preview?.let { add(RootStop(PREVIEW_STOP_ID, RootStopKind.NOTICE_PREVIEW, it.pageId, it.text)) }
            if (ordered.size > VISIBLE_ACTIVITY_STOPS) {
                add(RootStop(ACTIVITIES_PAGE_ID, RootStopKind.ACTIVITIES, ACTIVITIES_PAGE_ID, "Activities", ordered.size))
            }
            add(
                RootStop(
                    NOTIFICATIONS_PAGE_ID, RootStopKind.NOTIFICATIONS, NOTIFICATIONS_PAGE_ID,
                    "Notifications", notificationCount,
                ),
            )
            add(RootStop(APPLICATIONS_PAGE_ID, RootStopKind.APPLICATIONS, APPLICATIONS_PAGE_ID, "Applications"))
        }
        return stops.take(PageSurfaceContract.MAX_ROOT_STOPS)
    }

    /** Arrivals inside a session change text and counters only, never order or membership. */
    fun withNotice(stops: List<RootStop>, preview: NoticePreview, notificationCount: Int): List<RootStop> =
        stops.map {
            when (it.kind) {
                RootStopKind.NOTICE_PREVIEW -> it.copy(pageId = preview.pageId, text = preview.text)
                RootStopKind.NOTIFICATIONS -> it.copy(count = notificationCount)
                else -> it
            }
        }

    fun withEnded(stops: List<RootStop>, activityId: String): List<RootStop> =
        stops.map { if (it.kind == RootStopKind.ACTIVITY && it.id == activityId) it.copy(ended = true) else it }
}
