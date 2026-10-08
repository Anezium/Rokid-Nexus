package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceContract

/**
 * A live activity as the host reports it; [pinned] marks the wearer's head card. The card fields
 * are the activity's own content and its owner's catalog name, as last received.
 */
internal data class ActivityStop(
    val id: String,
    val pageId: String,
    val text: String,
    val pinned: Boolean = false,
    val ownerPluginId: String? = null,
    val ownerLabel: String? = null,
    val detail: String? = null,
    val measure: String? = null,
    /** Wall-clock time of the activity's latest content, for the card's label row. */
    val updatedAtWallMs: Long? = null,
)

/** The latest notification, kept as an unarmed preview; it can never answer. */
internal data class NoticePreview(
    val pageId: String,
    val text: String,
    val sourceLabel: String? = null,
    val atWallMs: Long? = null,
)

internal enum class RootStopKind { ACTIVITY, NOTICE_PREVIEW, ACTIVITIES, NOTIFICATIONS, APPLICATIONS }

internal data class RootStop(
    val id: String,
    val kind: RootStopKind,
    val pageId: String,
    val text: String,
    val count: Int? = null,
    val ended: Boolean = false,
    /** The card's label: the owner's name for an activity, the source for the notice preview. */
    val label: String? = null,
    val detail: String? = null,
    val measure: String? = null,
    val atWallMs: Long? = null,
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

    /** The local page of the one retained notice, opened from its preview card. */
    const val NOTICE_PAGE_ID = "nexus:notice"

    private const val VISIBLE_ACTIVITY_STOPS = 2

    fun build(
        activities: List<ActivityStop>,
        preview: NoticePreview?,
        notificationCount: Int,
    ): List<RootStop> {
        val ordered = activities.sortedByDescending(ActivityStop::pinned)
        val stops = buildList {
            ordered.take(VISIBLE_ACTIVITY_STOPS).forEach { add(activityStop(it)) }
            preview?.let {
                add(
                    RootStop(
                        PREVIEW_STOP_ID, RootStopKind.NOTICE_PREVIEW, it.pageId, it.text,
                        label = it.sourceLabel, atWallMs = it.atWallMs,
                    ),
                )
            }
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

    /** How many readable notices the hub retains: the current one, or the last one, or none. */
    fun retainedNotices(preview: NoticePreview?): Int = if (preview == null) 0 else 1

    /** Arrivals inside a session change text and counters only, never order or membership. */
    fun withNotice(stops: List<RootStop>, preview: NoticePreview, notificationCount: Int): List<RootStop> =
        stops.map {
            when (it.kind) {
                RootStopKind.NOTICE_PREVIEW -> it.copy(
                    pageId = preview.pageId, text = preview.text, label = preview.sourceLabel, atWallMs = preview.atWallMs,
                )
                RootStopKind.NOTIFICATIONS -> it.copy(count = notificationCount)
                else -> it
            }
        }

    fun withEnded(stops: List<RootStop>, activityId: String): List<RootStop> =
        stops.map { if (it.kind == RootStopKind.ACTIVITY && it.id == activityId) it.copy(ended = true) else it }

    /** A live value changes inside its card; an ended card keeps what it last showed. */
    fun withActivity(stops: List<RootStop>, activity: ActivityStop): List<RootStop> =
        stops.map {
            if (it.kind == RootStopKind.ACTIVITY && it.id == activity.id && !it.ended) {
                activityStop(activity).copy(pageId = it.pageId)
            } else {
                it
            }
        }

    private fun activityStop(activity: ActivityStop) = RootStop(
        activity.id, RootStopKind.ACTIVITY, activity.pageId, activity.text,
        label = activity.ownerLabel, detail = activity.detail, measure = activity.measure,
        atWallMs = activity.updatedAtWallMs,
    )
}
