package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ActivityAction
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract

/**
 * A collection entry as the host reports it; [pinned] marks the primary activity, the one with
 * the expanded island, and [userPinned] the wearer's own pin from the phone. The card fields are
 * the activity's own content and its owner's catalog name, as last received.
 *
 * The launcher-bundle fields are empty unless the pair negotiated it: [activation] is already the
 * effective one, [commands] the plugin's (at most three), and [seq] the revision a command binds
 * to. An [offline] entry is an inert snapshot whose commands never act.
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
    val userPinned: Boolean = false,
    val offline: Boolean = false,
    /** When an offline or [stale] entry was last confirmed live, for its "last known" hint. */
    val knownAtWallMs: Long? = null,
    /** A live entry held while the phone link is down: drawn as last known, its commands inert. */
    val stale: Boolean = false,
    val seq: Long = 0,
    val destination: PageDestination? = null,
    val activation: ActivityActivation? = null,
    val commands: List<ActivityAction> = emptyList(),
    /** The command focused first; null means the first command. */
    val defaultActionId: String? = null,
    /** The owner's full app is in the Applications catalog, so the hub offers Open app. */
    val launchable: Boolean = false,
    val icon: IconRef? = null,
    /** The activity tier's start order and latest significant update, for the deterministic order. */
    val startedOrder: Long = 0,
    val significantOrder: Long? = null,
    /**
     * Its destination and activation were validated over the launcher bundle before the link went,
     * and the phone has not announced since: it still navigates there, but what it opens asks the
     * phone nothing until the bundle is negotiated again.
     */
    val held: Boolean = false,
) {
    /**
     * Drawn as last known, its commands inert: offline on the phone, or [stale] while the link is
     * down. Only the phone's own offline state orders the root; a link loss reorders nothing.
     */
    val lastKnown: Boolean get() = offline || stale
}

/**
 * The root's notification stop, unarmed: it can never answer. [card] says what it stands for (a
 * history entry, or an owner's threads) and [paramsJson] goes with [pageId] when it opens.
 */
internal data class NoticePreview(
    val pageId: String,
    val text: String,
    val sourceLabel: String? = null,
    val atWallMs: Long? = null,
    val paramsJson: String? = null,
    val card: NoticeCard? = null,
    val icon: IconRef? = null,
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
    /** The whole entry behind an activity card, as last received; null for every other kind. */
    val activity: ActivityStop? = null,
    val icon: IconRef? = null,
    /** Opened with [pageId]: a conversation's exact destination params, or a history entry. */
    val paramsJson: String? = null,
    /** What the notification stop stands for; its identity is kept for the whole session. */
    val notice: NoticeCard? = null,
)

/**
 * Builds the anchored root once per session. The order is fixed by the plan: the first two
 * collection entries in [ORDER], latest notification preview, `Activities · n` when the
 * collection holds more than the two promoted cards, `Notifications · n`, Applications. Any
 * number of activities is accepted; the collection's capacity is negotiated elsewhere.
 */
internal object RootStops {
    const val PREVIEW_STOP_ID = "nexus:notice-preview"
    const val ACTIVITIES_PAGE_ID = "nexus:activities"
    const val NOTIFICATIONS_PAGE_ID = "nexus:notifications"

    /** What a purged owner's notification stop says: that one was removed, and nothing of it. */
    const val PURGED_TEXT = "Notification removed"

    /** What a notification stop says once everything it showed is older than the display bound. */
    const val EXPIRED_TEXT = "No recent notification"
    const val APPLICATIONS_PAGE_ID = "nexus:applications"

    /** The local page of one history entry (its params name it), opened from a preview or a row. */
    const val NOTICE_PAGE_ID = "nexus:notice"

    private const val VISIBLE_ACTIVITY_STOPS = 2

    /**
     * The collection's deterministic order (plan 028a §1.3): the wearer's pins first, then live
     * before offline, then the primary, then the latest significant update, then the earliest
     * start, then the owner id.
     */
    val ORDER: Comparator<ActivityStop> = compareByDescending<ActivityStop> { it.userPinned }
        .thenBy { it.offline }
        .thenByDescending { it.pinned }
        .thenByDescending { it.significantOrder ?: Long.MIN_VALUE }
        .thenBy { it.startedOrder }
        .thenBy { it.ownerPluginId ?: it.id }

    fun build(
        activities: List<ActivityStop>,
        preview: NoticePreview?,
        notificationCount: Int,
    ): List<RootStop> {
        val ordered = activities.sortedWith(ORDER)
        val stops = buildList {
            ordered.take(VISIBLE_ACTIVITY_STOPS).forEach { add(activityStop(it)) }
            preview?.let { add(applyPreview(RootStop(PREVIEW_STOP_ID, RootStopKind.NOTICE_PREVIEW, it.pageId, it.text), it)) }
            if (ordered.size > VISIBLE_ACTIVITY_STOPS) {
                add(RootStop(ACTIVITIES_PAGE_ID, RootStopKind.ACTIVITIES, ACTIVITIES_PAGE_ID, "Activities", ordered.size))
            }
            add(
                RootStop(
                    NOTIFICATIONS_PAGE_ID, RootStopKind.NOTIFICATIONS, NOTIFICATIONS_PAGE_ID,
                    "Notifications", notificationCount, icon = IconRef.Place(IconPlace.BELL),
                ),
            )
            add(
                RootStop(
                    APPLICATIONS_PAGE_ID, RootStopKind.APPLICATIONS, APPLICATIONS_PAGE_ID, "Applications",
                    icon = IconRef.Place(IconPlace.GRID),
                ),
            )
        }
        return stops.take(PageSurfaceContract.MAX_ROOT_STOPS)
    }

    /** A single-preview count, for callers that build a root from one preview and no history. */
    fun retainedNotices(preview: NoticePreview?): Int = if (preview == null) 0 else 1

    /**
     * Notification changes inside a session change text, states and counters only, never order or
     * membership (plan 028a §6.4). A conversation card keeps its entry and only resolves in place
     * (handed off, removed); an aggregate keeps its owner; a generic preview follows the newest entry.
     */
    fun withHistory(stops: List<RootStop>, history: NotificationHistory, nowWallMs: Long): List<RootStop> {
        val count = history.visible(nowWallMs).size
        return stops.map {
            when (it.kind) {
                RootStopKind.NOTICE_PREVIEW -> refreshNotice(it, history, nowWallMs)
                RootStopKind.NOTIFICATIONS -> it.copy(count = count)
                else -> it
            }
        }
    }

    /**
     * [ownerPluginId] was purged (revoked, disabled, uninstalled, re-signed): after the usual
     * refresh, a notification stop still showing anything of it keeps its place as an inert,
     * content-free placeholder, so nothing of the owner survives on a root kept open or beneath a
     * page. A stop whose owner is not known is treated as the purged owner's. A withdrawal is
     * not a purge: it keeps its readable card through [withHistory].
     */
    fun withPurged(stops: List<RootStop>, ownerPluginId: String, history: NotificationHistory, nowWallMs: Long): List<RootStop> =
        withHistory(stops, history, nowWallMs).map {
            val owner = it.notice?.ownerPluginId
            if (it.kind == RootStopKind.NOTICE_PREVIEW && (owner == null || owner == ownerPluginId)) {
                placeholderNotice(it, PURGED_TEXT)
            } else {
                it
            }
        }

    /**
     * Some entries were forgotten at their owner's request: after the usual refresh, a card that
     * named one of them keeps its place as the same content-free placeholder as a purge.
     */
    fun withForgotten(stops: List<RootStop>, forgotten: Set<String>, history: NotificationHistory, nowWallMs: Long): List<RootStop> =
        withHistory(stops, history, nowWallMs).map {
            if (it.kind == RootStopKind.NOTICE_PREVIEW && it.notice?.entryId?.let(forgotten::contains) == true) placeholderNotice(it, PURGED_TEXT) else it
        }

    /** The stop keeps its place and selection; nothing it copied from the history stays. */
    fun placeholderNotice(stop: RootStop, text: String): RootStop = stop.copy(
        pageId = NOTIFICATIONS_PAGE_ID,
        text = text,
        count = null,
        label = null,
        detail = null,
        measure = null,
        atWallMs = null,
        icon = null,
        paramsJson = null,
        notice = NoticeCard(NoticeCardKind.GENERIC, entryId = null, ownerPluginId = null, state = HistoryState.WITHDRAWN),
    )

    /**
     * Copied text never outlives the display bound: a card whose entries all passed it becomes a
     * content-free placeholder in place, even on a frozen root or beneath a page.
     */
    private fun refreshNotice(stop: RootStop, history: NotificationHistory, nowWallMs: Long): RootStop {
        val card = stop.notice
        if (card != null && card.kind == NoticeCardKind.CONVERSATION) {
            val entry = card.entryId?.let(history::entry)
            if (entry != null && history.visibleEntry(entry.id, nowWallMs) == null) return placeholderNotice(stop, EXPIRED_TEXT)
            // A purged entry is gone from the phone as surely as a withdrawn one.
            return stop.copy(notice = card.copy(state = entry?.state ?: HistoryState.WITHDRAWN))
        }
        if (card != null && card.kind == NoticeCardKind.AGGREGATE) {
            history.aggregateFor(card.ownerPluginId, nowWallMs)?.let { return applyPreview(stop, it) }
            // Resolved threads keep their names only while each is still within the bound.
            return history.resolvedAggregateFor(card.ownerPluginId, nowWallMs)?.let { applyPreview(stop, it) }
                ?: placeholderNotice(stop, EXPIRED_TEXT)
        }
        return history.visible(nowWallMs).firstOrNull()?.let { applyPreview(stop, history.generic(it)) }
            // Nothing left to show: an undated stop (a placeholder already) stays as it is.
            ?: if (stop.atWallMs == null && card?.entryId == null) stop else placeholderNotice(stop, EXPIRED_TEXT)
    }

    private fun applyPreview(stop: RootStop, preview: NoticePreview): RootStop = stop.copy(
        pageId = preview.pageId,
        text = preview.text,
        label = preview.sourceLabel,
        atWallMs = preview.atWallMs,
        paramsJson = preview.paramsJson,
        notice = preview.card,
        icon = preview.icon,
    )

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
        atWallMs = activity.updatedAtWallMs, activity = activity, icon = activity.icon,
    )
}
