package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject

internal enum class AppKind {
    /** A plugin that provides pages over a negotiated pair: its row opens `<id>:main`. */
    PAGES,

    /** Any other plugin: its labelled row opens the full app directly on a fresh tap. */
    IMMERSION,
    NATIVE,

    /** The hub's own camera entry, opened locally. */
    CAMERA,
}

/**
 * One Applications row, frozen with a stable key when the page is answered. [icon] is its mark as
 * the launcher catalog gives it; null when the catalog names none, which draws the generic grid.
 */
internal data class AppEntry(
    val key: String,
    val label: String,
    val kind: AppKind,
    val pluginId: String? = null,
    val icon: IconRef? = null,
)

/**
 * The pages the glasses hub authors itself, under `nexus:`. They are answered in-process through
 * the same runner and correlation as provider pages, never over the bus, and need no phone.
 */
internal class LocalPages(
    /** The notification history, its entries as the band showed them. */
    private val history: () -> NotificationHistory,
    private val activities: () -> List<ActivityStop>,
    private val applications: () -> List<AppEntry>,
    private val providerPagesReady: (String) -> Boolean,
    private val formatTime: (Long) -> String,
    private val wallClock: () -> Long = { 0L },
    /** The phone is away: history entries stay readable and lead nowhere until it is back. */
    private val linkLost: () -> Boolean = { false },
) {
    private var revision = 0L

    /** G6: Applications as the wearer entered it, in that order; refreshes and More pages read it. */
    private var enteredApplications: List<AppEntry>? = null

    fun isLocal(pageId: String): Boolean = PageTemplateContract.isLocalPageId(pageId)

    /**
     * The mark of an Applications row, by its row id, as the entry was frozen: its catalog mark,
     * or the generic grid when the catalog names none, never a guessed one. Null for any other
     * page or row ("More applications").
     */
    fun iconOf(pageId: String, rowKey: String): IconRef? {
        if (pageId != RootStops.APPLICATIONS_PAGE_ID) return null
        val entry = enteredApplications?.firstOrNull { rowId(it.key) == rowKey } ?: return null
        return entry.icon ?: IconRef.Place(IconPlace.GRID)
    }

    /** The response payload for [request], always correlated: a page, or an honest error. */
    fun answer(request: PageSurfaceRequest, live: Boolean = false): JSONObject {
        val pageId = request.correlation.pageId
        val page = when {
            pageId == RootStops.NOTIFICATIONS_PAGE_ID -> notifications()
            pageId == RootStops.NOTICE_PAGE_ID -> notice(NotificationHistory.entryOf(request.paramsJson))
            pageId == RootStops.ACTIVITIES_PAGE_ID -> activityList()
            pageId.startsWith(ACTIVITY_PAGE_PREFIX) -> activity(pageId.removePrefix(ACTIVITY_PAGE_PREFIX))
            pageId == RootStops.APPLICATIONS_PAGE_ID -> applicationList(request.paramsJson, request.reason)
            else -> null
        } ?: return PageSurfaceContract.correlationPayload(request.correlation)
            .put("error", PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
        return encode(request.correlation, page, live)
    }

    private class LocalPage(val title: String, val body: PageBody, val actions: List<Pair<String, String>> = emptyList())

    /**
     * Every history entry still displayed, newest first: its source and the text the band showed.
     * Each row opens its entry through a hub action, which also marks it seen; the screens add the
     * source's icon, the entry's state tag and its age.
     */
    private fun notifications(): LocalPage {
        val actions = mutableListOf<Pair<String, String>>()
        val rows = history().visible(wallClock()).take(PageTemplateContract.MAX_ITEMS).mapNotNull { entry ->
            val actionId = (TypedPageItems.NOTICE_PREFIX + entry.id).takeIf(PageSurfaceContract::isValidId) ?: return@mapNotNull null
            val label = clip(entry.notice.sourceLabel.ifBlank { TITLE_NOTIFICATION }, PageTemplateContract.MAX_LABEL_CHARS)
            actions += actionId to label
            PageRow(
                id = rowId(entry.id),
                label = label,
                sub = entry.notice.text.ifBlank { null }?.let { clip(it, PageTemplateContract.MAX_DETAIL_CHARS) },
                target = PageTarget.Action(actionId),
            )
        }
        return LocalPage(TITLE_NOTIFICATIONS, PageBody.SelectableList(rows, emptyText = "No notifications"), actions)
    }

    /**
     * One history entry (the newest when none is named) as the band showed it. A conversation
     * leads to its exact thread and any other destination to its page; a legacy Relay notice, which
     * names no thread, keeps Relay's own list. While the phone is away it leads nowhere.
     */
    private fun notice(entryId: String?): LocalPage {
        val all = history()
        // A named entry that is gone (purged) is never replaced by another one.
        // Past the display bound a named entry is as gone as a purged one.
        val entry = (if (entryId != null) all.visibleEntry(entryId, wallClock()) else all.visible(wallClock()).firstOrNull()) ?: return LocalPage(
            TITLE_NOTIFICATION,
            PageBody.Document(listOf(PageSegment("empty", PageSegmentKind.ASIDE, "No notification to show."))),
        )
        val current = entry.notice
        val source = current.sourceLabel.ifBlank { TITLE_NOTIFICATION }
        val segments = buildList {
            add(PageSegment("source", PageSegmentKind.HEADER, clip("$source · ${formatTime(current.atWallMs)}", PageTemplateContract.MAX_TEXT_CHARS)))
            current.title?.takeIf(String::isNotBlank)?.let { add(PageSegment("title", PageSegmentKind.PROSE, clip(it, PageTemplateContract.MAX_TEXT_CHARS))) }
            val text = current.body?.takeIf(String::isNotBlank) ?: current.lines.joinToString("\n").takeIf(String::isNotBlank)
            text?.let { add(PageSegment("body", PageSegmentKind.PROSE, clip(it, PageTemplateContract.MAX_TEXT_CHARS))) }
        }
        val owner = current.ownerPluginId
        val destination = current.destination
        val reachable = !linkLost() && owner.isNotBlank() && providerPagesReady(owner)
        val row = when {
            !reachable -> null
            destination != null && current.conversation ->
                PageRow(CONVERSATION_ROW, READ_CONVERSATION, target = PageTarget.Page(destination.pageId, destination.paramsJson))
            destination != null ->
                PageRow(DESTINATION_ROW, "Open in $source", target = PageTarget.Page(destination.pageId, destination.paramsJson))
            // D2: a legacy notice names no thread, so Relay's own list is offered, never a guess.
            owner == RELAY_PLUGIN_ID ->
                PageRow(RELAY_THREADS_ROW, "Read Relay threads", target = PageTarget.Page(PageTemplateContract.mainPageId(RELAY_PLUGIN_ID)))
            else -> null
        }
        return LocalPage(clip(source, PageSurfaceContract.MAX_TITLE_CHARS), PageBody.Document(segments, listOfNotNull(row)))
    }

    /**
     * Every collection entry, live and offline, in the root's order. A row does what its card
     * does: it stands for its owner's entry through a hub action, never for a guessed page.
     */
    private fun activityList(): LocalPage {
        val actions = mutableListOf<Pair<String, String>>()
        val rows = activities().sortedWith(RootStops.ORDER).take(PageTemplateContract.MAX_ITEMS).map { activity ->
            val label = clip(activity.ownerLabel ?: activity.ownerPluginId ?: activity.id, PageTemplateContract.MAX_LABEL_CHARS)
            val actionId = activity.ownerPluginId?.let { TypedPageItems.ACTIVITY_PREFIX + it }?.takeIf(PageSurfaceContract::isValidId)
            actionId?.let { actions += it to label }
            PageRow(
                id = rowId(activity.id),
                label = label,
                sub = clip(listOfNotNull(activity.text, activity.detail).joinToString(" · "), PageTemplateContract.MAX_DETAIL_CHARS),
                target = actionId?.let { PageTarget.Action(it) } ?: PageTarget.Page(activity.pageId),
            )
        }
        return LocalPage(TITLE_ACTIVITIES, PageBody.SelectableList(rows, emptyText = "No activity"), actions)
    }

    /** A hub summary of an activity whose owner provides no page; never an automatic launch. */
    private fun activity(ownerPluginId: String): LocalPage? {
        val activity = activities().firstOrNull { it.ownerPluginId == ownerPluginId } ?: return null
        val launchable = applications().any { it.pluginId == ownerPluginId && it.kind != AppKind.NATIVE }
        val actionId = TypedPageItems.LAUNCH_PREFIX + ownerPluginId
        val label = activity.ownerLabel ?: ownerPluginId
        return LocalPage(
            clip(label, PageSurfaceContract.MAX_TITLE_CHARS),
            PageBody.Summary(
                primary = clip(activity.text, PageTemplateContract.MAX_PRIMARY_CHARS),
                secondary = activity.detail?.let { clip(it, PageTemplateContract.MAX_DETAIL_CHARS) },
                facts = listOfNotNull(
                    activity.measure?.let { PageFact("measure", "Then", clip(it, PageTemplateContract.MAX_DETAIL_CHARS)) },
                    activity.updatedAtWallMs?.let { PageFact("updated", "Updated", formatTime(it)) },
                ),
                items = listOfNotNull(
                    PageRow(OPEN_APP_ROW, OPEN_APP, target = PageTarget.Action(actionId))
                        .takeIf { launchable },
                ),
            ),
            actions = if (launchable) listOf(actionId to label) else emptyList(),
        )
    }

    /**
     * G6: entering Applications from elsewhere freezes its entries and their order. Later answers,
     * a refresh or a More page, keep that membership and order: labels and availability follow
     * the catalog by key, a removed entry stays in place as an inert tombstone, and an entry added
     * since waits for the next entry. Launches still check the catalog as it is when they run.
     */
    private fun applicationList(paramsJson: String?, reason: String): LocalPage {
        val requested = paramsJson?.let { runCatching { JSONObject(it).optInt(PARAM_FROM, 0) }.getOrNull() } ?: 0
        val current = applications()
        val entered = enteredApplications?.takeUnless { reason == SessionReducer.REASON_OPEN && requested == 0 }
            ?: current.also { enteredApplications = it }
        val live = current.associateBy(AppEntry::key)
        val all = entered.map { frozen -> live[frozen.key] to frozen }
        val from = requested.coerceIn(0, all.size)
        // One row stays free for "More" when the catalog does not fit a page.
        val room = PageTemplateContract.MAX_ITEMS - 1
        val chunk = all.drop(from).take(room)
        val actions = mutableListOf<Pair<String, String>>()
        val rows = chunk.mapNotNull { (present, frozen) ->
            val id = rowId(frozen.key)
            val entry = present
                ?: return@mapNotNull PageRow(id, clip(frozen.label, PageTemplateContract.MAX_LABEL_CHARS), sub = REMOVED, enabled = false)
            when (entry.kind) {
                AppKind.PAGES -> entry.pluginId?.let {
                    PageRow(id, clip(entry.label, PageTemplateContract.MAX_LABEL_CHARS), target = PageTarget.Page(PageTemplateContract.mainPageId(it)))
                }
                AppKind.IMMERSION -> entry.pluginId?.let { pluginId ->
                    val actionId = TypedPageItems.LAUNCH_PREFIX + pluginId
                    actionId.takeIf(PageSurfaceContract::isValidId)?.let {
                        actions += it to entry.label
                        // The app's name alone: what opening it replaces is said on the Open panel.
                        PageRow(id, clip(entry.label, PageTemplateContract.MAX_LABEL_CHARS), target = PageTarget.Action(it))
                    }
                }
                AppKind.NATIVE, AppKind.CAMERA -> {
                    val actionId = TypedPageItems.LOCAL_PREFIX + entry.key
                    actionId.takeIf(PageSurfaceContract::isValidId)?.let {
                        actions += it to entry.label
                        PageRow(id, clip(entry.label, PageTemplateContract.MAX_LABEL_CHARS), target = PageTarget.Action(it))
                    }
                }
            }
        }
        val more = (from + chunk.size).takeIf { it < all.size }?.let { next ->
            PageRow(
                MORE_ROW,
                "More applications",
                sub = "${all.size - next} more",
                target = PageTarget.Page(RootStops.APPLICATIONS_PAGE_ID, JSONObject().put(PARAM_FROM, next).toString()),
            )
        }
        return LocalPage(TITLE_APPLICATIONS, PageBody.SelectableList(rows + listOfNotNull(more), emptyText = "No applications"), actions)
    }

    private fun encode(correlation: PageCorrelation, page: LocalPage, live: Boolean): JSONObject =
        PageSurfaceContract.correlationPayload(correlation)
            .put("revision", ++revision)
            .put("template", page.body.template)
            .put("title", clip(page.title, PageSurfaceContract.MAX_TITLE_CHARS))
            .put("body", PageTemplateContract.encode(page.body))
            .put(
                "actions",
                JSONArray().apply {
                    page.actions.forEach { (id, label) ->
                        put(
                            JSONObject().put("id", id).put("label", clip(label, PageSurfaceContract.MAX_ACTION_LABEL_CHARS))
                                .put("kind", TypedPageItems.KIND_HUB).put("confirm", false),
                        )
                    }
                },
            )
            .put("live", live)

    /** Row ids follow page-id safety; a key that does not is replaced by a stable digest of it. */
    private fun rowId(key: String): String =
        key.takeIf(PageSurfaceContract::isValidId) ?: "k" + Integer.toHexString(key.hashCode()) + "-" + key.length

    private fun clip(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take(limit - 1).trimEnd() + "…"

    companion object {
        const val ACTIVITY_PAGE_PREFIX = "nexus:activity:"
        const val RELAY_PLUGIN_ID = "relay"
        const val TITLE_NOTIFICATIONS = "Notifications"
        const val TITLE_NOTIFICATION = "Notification"
        const val TITLE_ACTIVITIES = "Activities"
        const val TITLE_APPLICATIONS = "Applications"
        const val OPEN_APP = "Open app"
        const val READ_CONVERSATION = "Read this conversation"
        private const val CONVERSATION_ROW = "conversation"
        private const val DESTINATION_ROW = "destination"
        private const val RELAY_THREADS_ROW = "relay-threads"
        private const val OPEN_APP_ROW = "open-app"
        private const val MORE_ROW = "more"
        private const val PARAM_FROM = "from"
        private const val REMOVED = "No longer available"

        fun activityPageId(ownerPluginId: String): String = ACTIVITY_PAGE_PREFIX + ownerPluginId
    }
}
