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

/**
 * The one readable notice the hub retains (D1), as the notice band received it: its text is
 * already what the owner's redaction and hide policy let through.
 */
internal data class NoticeSnapshot(
    val ownerPluginId: String,
    val sourceLabel: String,
    val title: String?,
    val body: String?,
    val lines: List<String>,
    val atWallMs: Long,
)

internal enum class AppKind {
    /** A plugin that provides pages over a negotiated pair: its row opens `<id>:main`. */
    PAGES,

    /** Any other plugin: its row offers the full app, labelled, behind a launch step. */
    IMMERSION,
    NATIVE,

    /** The hub's own camera entry, opened locally. */
    CAMERA,
}

/** One Applications row, frozen with a stable key when the page is answered. */
internal data class AppEntry(val key: String, val label: String, val kind: AppKind, val pluginId: String? = null)

/**
 * The pages the glasses hub authors itself, under `nexus:`. They are answered in-process through
 * the same runner and correlation as provider pages, never over the bus, and need no phone.
 */
internal class LocalPages(
    private val notice: () -> NoticeSnapshot?,
    private val activities: () -> List<ActivityStop>,
    private val applications: () -> List<AppEntry>,
    private val providerPagesReady: (String) -> Boolean,
    private val formatTime: (Long) -> String,
) {
    private var revision = 0L

    /** G6: Applications as the wearer entered it, in that order; refreshes and More pages read it. */
    private var enteredApplications: List<AppEntry>? = null

    fun isLocal(pageId: String): Boolean = PageTemplateContract.isLocalPageId(pageId)

    /** The response payload for [request], always correlated: a page, or an honest error. */
    fun answer(request: PageSurfaceRequest, live: Boolean = false): JSONObject {
        val pageId = request.correlation.pageId
        val page = when {
            pageId == RootStops.NOTIFICATIONS_PAGE_ID -> notifications()
            pageId == RootStops.NOTICE_PAGE_ID -> currentNotice()
            pageId == RootStops.ACTIVITIES_PAGE_ID -> activityList()
            pageId.startsWith(ACTIVITY_PAGE_PREFIX) -> activity(pageId.removePrefix(ACTIVITY_PAGE_PREFIX))
            pageId == RootStops.APPLICATIONS_PAGE_ID -> applicationList(request.paramsJson, request.reason)
            else -> null
        } ?: return PageSurfaceContract.correlationPayload(request.correlation)
            .put("error", PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
        return encode(request.correlation, page, live)
    }

    private class LocalPage(val title: String, val body: PageBody, val actions: List<Pair<String, String>> = emptyList())

    private fun notifications(): LocalPage {
        val current = notice()
        val rows = listOfNotNull(
            current?.let {
                PageRow(
                    id = NOTICE_ROW,
                    label = clip(it.sourceLabel, PageTemplateContract.MAX_LABEL_CHARS),
                    sub = clip(listOfNotNull(formatTime(it.atWallMs), preview(it)).joinToString(" · "), PageTemplateContract.MAX_DETAIL_CHARS),
                    target = PageTarget.Page(RootStops.NOTICE_PAGE_ID),
                )
            },
        )
        return LocalPage(TITLE_NOTIFICATIONS, PageBody.SelectableList(rows, emptyText = "No notifications"))
    }

    private fun currentNotice(): LocalPage {
        val current = notice() ?: return LocalPage(
            TITLE_NOTIFICATION,
            PageBody.Document(listOf(PageSegment("empty", PageSegmentKind.ASIDE, "No notification to show."))),
        )
        val segments = buildList {
            add(PageSegment("source", PageSegmentKind.HEADER, clip("${current.sourceLabel} · ${formatTime(current.atWallMs)}", PageTemplateContract.MAX_TEXT_CHARS)))
            current.title?.takeIf(String::isNotBlank)?.let { add(PageSegment("title", PageSegmentKind.PROSE, clip(it, PageTemplateContract.MAX_TEXT_CHARS))) }
            val text = current.body?.takeIf(String::isNotBlank) ?: current.lines.joinToString("\n").takeIf(String::isNotBlank)
            text?.let { add(PageSegment("body", PageSegmentKind.PROSE, clip(it, PageTemplateContract.MAX_TEXT_CHARS))) }
        }
        // D2: the notice wire names no thread, so Relay's own list is offered, never a guess.
        val rows = listOfNotNull(
            PageRow(RELAY_THREADS_ROW, "Read Relay threads", target = PageTarget.Page(PageTemplateContract.mainPageId(RELAY_PLUGIN_ID)))
                .takeIf { current.ownerPluginId == RELAY_PLUGIN_ID && providerPagesReady(RELAY_PLUGIN_ID) },
        )
        return LocalPage(clip(current.sourceLabel, PageSurfaceContract.MAX_TITLE_CHARS), PageBody.Document(segments, rows))
    }

    private fun activityList(): LocalPage {
        val rows = activities().take(PageTemplateContract.MAX_ITEMS).map { activity ->
            PageRow(
                id = rowId(activity.id),
                label = clip(activity.ownerLabel ?: activity.ownerPluginId ?: activity.id, PageTemplateContract.MAX_LABEL_CHARS),
                sub = clip(listOfNotNull(activity.text, activity.detail).joinToString(" · "), PageTemplateContract.MAX_DETAIL_CHARS),
                target = PageTarget.Page(activity.pageId),
            )
        }
        return LocalPage(TITLE_ACTIVITIES, PageBody.SelectableList(rows, emptyText = "No activity"))
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
                    PageRow(OPEN_APP_ROW, OPEN_FULL_APP, sub = EXCLUSIVE_SURFACE, target = PageTarget.Action(actionId))
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
                        PageRow(id, clip(entry.label, PageTemplateContract.MAX_LABEL_CHARS), sub = "$OPEN_FULL_APP · $EXCLUSIVE_SURFACE", target = PageTarget.Action(it))
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

    private fun preview(notice: NoticeSnapshot): String? =
        listOfNotNull(notice.title, notice.body, notice.lines.firstOrNull()).firstOrNull { it.isNotBlank() }

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
        const val OPEN_FULL_APP = "Open the full app"
        const val EXCLUSIVE_SURFACE = "exclusive surface"
        private const val NOTICE_ROW = "notice"
        private const val RELAY_THREADS_ROW = "relay-threads"
        private const val OPEN_APP_ROW = "open-app"
        private const val MORE_ROW = "more"
        private const val PARAM_FROM = "from"
        private const val REMOVED = "No longer available"

        fun activityPageId(ownerPluginId: String): String = ACTIVITY_PAGE_PREFIX + ownerPluginId
    }
}
