package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract

/** The fixed chrome above the viewport: a title and a short trail on its right. */
internal data class ScreenHeader(val title: String, val trail: String? = null)

/** The fixed chrome under the viewport: what tap does, and where BACK goes. */
internal data class ScreenHint(val action: String?, val back: String?)

internal enum class TextRole {
    /** A figure, 30 px or more: the distance, the time left. */
    FIGURE,

    /** A name or body line, 17 px or more. */
    BODY,

    /** A secondary or tertiary line, 12 px or more. */
    CAPTION,

    /** A section label in letter-spaced capitals. */
    SECTION,

    /** A centred message title. */
    TITLE,
}

/** One block of the viewport, top to bottom. [key] names it across renders. */
internal sealed interface ScreenBlock {
    val key: String

    /** A root card: fixed lines, fixed size, whatever its values become. */
    data class Card(
        override val key: String,
        val label: String,
        val time: String?,
        val primary: String,
        val secondary: String?,
        val measure: String?,
        val figure: Boolean,
        val ended: Boolean,
        val selected: Boolean,
    ) : ScreenBlock

    data class Row(
        override val key: String,
        val label: String,
        val sub: String? = null,
        val trail: String? = null,
        val enabled: Boolean = true,
        val selected: Boolean = false,
        val tombstone: Boolean = false,
        val centered: Boolean = false,
    ) : ScreenBlock

    data class Text(override val key: String, val text: String, val role: TextRole, val centered: Boolean = false) : ScreenBlock

    data class Fact(override val key: String, val label: String, val value: String) : ScreenBlock

    data class Progress(override val key: String, val fraction: Float) : ScreenBlock

    /**
     * A document read page by page. The renderer measures it at the viewport and reports where
     * its pages start; [anchor] is the reading position it must keep on screen.
     */
    data class Document(
        override val key: String,
        val frame: FrameIdentity,
        val pageId: String,
        val revision: Long,
        val segments: List<PageSegment>,
        val anchor: DocumentAnchor?,
        val dimmed: Boolean,
        /** The document's own footer, drawn after its text. */
        val footer: String? = null,
    ) : ScreenBlock
}

internal data class SessionScreen(
    val header: ScreenHeader?,
    val blocks: List<ScreenBlock>,
    val hint: ScreenHint?,
    val status: String? = null,
    /** The block the viewport keeps fully visible: the selection, or the reading position. */
    val focusKey: String? = null,
    /** Present while the opening gate runs, for its progress line. */
    val gate: Boolean = false,
)

/**
 * Builds what the session window draws from the reducer's state, purely. It reads the same typed
 * rows the reducer acts on, so the outline, the scroll and SELECT always agree.
 */
internal class SessionScreens(
    private val items: TypedPageItems,
    /** The authenticated catalog name of a plugin, or null when it is not in the catalog. */
    private val pluginLabel: (String) -> String?,
    private val formatTime: (Long) -> String,
    /** Wall-clock time of a session-clock instant, for dating a retained snapshot. */
    private val wallTimeOf: (Long) -> Long,
) {
    /** [generation] is the open session's, which names its frames for the renderer's reports. */
    fun screen(state: SessionState, status: SessionStatus?, generation: Long = 0): SessionScreen {
        val statusText = status?.let(::statusText)
        return when (state) {
            SessionState.Closed -> SessionScreen(null, emptyList(), null, statusText)
            is SessionState.Opening -> SessionScreen(
                ScreenHeader(NEXUS, "opening…"),
                listOf(ScreenBlock.Text("gate", GATE_TEXT, TextRole.CAPTION, centered = true)),
                ScreenHint("gestures are absorbed", null),
                statusText,
                gate = true,
            )
            is SessionState.Root -> root(state, statusText)
            is SessionState.InPage -> page(state, statusText, generation)
            is SessionState.Launching -> screen(state.previous, status, generation).copy(
                status = statusText ?: "Opening ${pluginLabel(state.pluginId) ?: state.pluginId}…",
            )
        }
    }

    fun titleOf(pageId: String): String = when {
        pageId == RootStops.NOTIFICATIONS_PAGE_ID -> LocalPages.TITLE_NOTIFICATIONS
        pageId == RootStops.NOTICE_PAGE_ID -> LocalPages.TITLE_NOTIFICATION
        pageId == RootStops.ACTIVITIES_PAGE_ID -> LocalPages.TITLE_ACTIVITIES
        pageId == RootStops.APPLICATIONS_PAGE_ID -> LocalPages.TITLE_APPLICATIONS
        pageId.startsWith(LocalPages.ACTIVITY_PAGE_PREFIX) ->
            pluginLabel(pageId.removePrefix(LocalPages.ACTIVITY_PAGE_PREFIX)) ?: LocalPages.TITLE_ACTIVITIES
        else -> PageTemplateContract.namespaceOf(pageId)?.let(pluginLabel) ?: PAGE
    }

    private fun root(state: SessionState.Root, statusText: String?): SessionScreen {
        val blocks = state.stops.mapIndexed { index, stop ->
            val selected = index == state.selected
            when (stop.kind) {
                RootStopKind.ACTIVITY -> ScreenBlock.Card(
                    key = stop.id,
                    label = listOfNotNull((stop.label ?: ACTIVITY).uppercase(), ENDED.takeIf { stop.ended }).joinToString(" · "),
                    time = stop.atWallMs?.let(formatTime),
                    primary = stop.text,
                    secondary = stop.detail,
                    measure = stop.measure,
                    figure = true,
                    ended = stop.ended,
                    selected = selected,
                )
                RootStopKind.NOTICE_PREVIEW -> ScreenBlock.Card(
                    key = stop.id,
                    label = listOfNotNull(NOTIFICATION, stop.label?.uppercase()).joinToString(" · "),
                    time = stop.atWallMs?.let(formatTime),
                    primary = stop.text,
                    secondary = null,
                    measure = null,
                    figure = false,
                    ended = false,
                    selected = selected,
                )
                RootStopKind.ACTIVITIES, RootStopKind.NOTIFICATIONS ->
                    ScreenBlock.Row(stop.id, stop.text, trail = (stop.count ?: 0).toString(), selected = selected)
                RootStopKind.APPLICATIONS -> ScreenBlock.Row(stop.id, stop.text, selected = selected)
            }
        }
        val selectedStop = state.stops.getOrNull(state.selected)
        return SessionScreen(
            ScreenHeader(NEXUS, "back: close"),
            blocks,
            ScreenHint(if (selectedStop?.ended == true) "swipe next card" else "swipe next · tap open", "back close"),
            statusText,
            focusKey = selectedStop?.id,
        )
    }

    private fun page(state: SessionState.InPage, statusText: String?, generation: Long): SessionScreen {
        val frame = state.frames.last()
        val frameIndex = state.frames.size
        val parent = state.frames.getOrNull(state.frames.lastIndex - 1)?.let { titleOfFrame(it) } ?: "root"
        val title = titleOfFrame(frame)
        val back = "back $parent"
        frame.panel?.let { return panel(frame, it, title, statusText) }
        val outcomeText = frame.outcome?.let(::outcomeText)
        return when (val status = frame.status) {
            is FrameStatus.Loading -> {
                val blocks = buildList {
                    status.previous?.let { addAll(summaryOf(it, frame, readable = true)) }
                    add(ScreenBlock.Text("loading", if (status.stillLoading) STILL_LOADING else LOADING, TextRole.CAPTION, centered = true))
                    add(ScreenBlock.Progress("progress", if (status.stillLoading) 0.6f else 0.25f))
                }
                SessionScreen(ScreenHeader(title.uppercase(), "back: $parent"), blocks, ScreenHint(null, back), statusText, "loading")
            }
            is FrameStatus.Unavailable -> {
                val blocks = buildList {
                    status.lastSnapshot?.let { snapshot ->
                        val dated = frame.shownAtMs?.let { "Last known · ${formatTime(wallTimeOf(it))}" } ?: "Last known"
                        add(ScreenBlock.Text("last-title", snapshot.page.title, TextRole.BODY))
                        add(ScreenBlock.Text("last-dated", dated, TextRole.CAPTION))
                    }
                    add(ScreenBlock.Text("unavailable", UNAVAILABLE_TITLE, TextRole.TITLE, centered = true))
                    add(ScreenBlock.Text("reason", reasonText(status.reason), TextRole.CAPTION, centered = true))
                    add(ScreenBlock.Row("retry", RETRY, selected = frame.selected == 0, centered = true))
                    add(ScreenBlock.Row("back", BACK, selected = frame.selected == 1, centered = true))
                }
                val focus = if (frame.selected == 0) "retry" else "back"
                SessionScreen(ScreenHeader(title.uppercase(), "back: $parent"), blocks, ScreenHint("tap ${if (frame.selected == 0) "retry" else "back"}", back), statusText, focus)
            }
            is FrameStatus.Shown ->
                shown(frame, status, FrameIdentity(generation, frameIndex), title, parent, back, statusText ?: outcomeText)
        }
    }

    private fun shown(
        frame: Frame,
        status: FrameStatus.Shown,
        identity: FrameIdentity,
        title: String,
        parent: String,
        back: String,
        statusText: String?,
    ): SessionScreen {
        val page = status.snapshot.page
        val body = items.body(page)
        val header = title.uppercase()
        if (body is PageBody.Document && frame.mode == FrameMode.READING) {
            val document = ScreenBlock.Document(
                "document", identity, frame.pageId, status.revision,
                body.segments, frame.anchor, dimmed = false, footer = body.footer,
            )
            val pages = frame.pageStarts.size
            val trail = if (pages > 0) "${frame.page + 1}/$pages" else null
            val hasRows = items.rows(page).isNotEmpty()
            return SessionScreen(
                ScreenHeader(header, trail),
                listOf(document),
                ScreenHint(listOfNotNull("swipe page".takeIf { pages > 1 }, "tap commands".takeIf { hasRows }).joinToString(" · ").ifBlank { null }, back),
                statusText,
                "document",
            )
        }
        val blocks = mutableListOf<ScreenBlock>()
        when (body) {
            is PageBody.Summary -> {
                body.primary?.let { blocks += ScreenBlock.Text("primary", it, TextRole.FIGURE) }
                body.secondary?.let { blocks += ScreenBlock.Text("secondary", it, TextRole.BODY) }
                body.text?.let { blocks += ScreenBlock.Text("text", it, TextRole.BODY) }
                body.facts.forEach { blocks += ScreenBlock.Fact("fact:${it.id}", it.label, it.value) }
            }
            is PageBody.Commands -> body.text?.let { blocks += ScreenBlock.Text("text", it, TextRole.BODY) }
            is PageBody.Document -> blocks += ScreenBlock.Text("section", COMMANDS, TextRole.SECTION)
            is PageBody.SelectableList, null -> Unit
        }
        val rows = items.rows(page)
        val rowBlocks = rows.mapIndexed { index, row ->
            ScreenBlock.Row(
                "row:${row.key}", row.label, row.sub,
                enabled = row.enabled && row.item != PageItem.Inert,
                selected = frame.tombstone == null && index == frame.selected,
            )
        }.toMutableList()
        frame.tombstone?.let { label ->
            rowBlocks.add(frame.selected.coerceIn(0, rowBlocks.size), ScreenBlock.Row("tombstone", label, "removed", enabled = false, selected = true, tombstone = true))
        }
        blocks += rowBlocks
        if (rows.isEmpty() && body is PageBody.SelectableList) {
            blocks += ScreenBlock.Text("empty", body.emptyText ?: "Nothing here", TextRole.CAPTION, centered = true)
        }
        val focus = rowBlocks.firstOrNull { it.selected }?.key ?: blocks.firstOrNull()?.key
        val selectedRow = if (frame.tombstone == null) rows.getOrNull(frame.selected) else null
        val action = when (selectedRow?.item) {
            null, PageItem.Inert -> if (rows.size > 1) "swipe choose" else null
            is PageItem.OpenPage -> "tap open"
            is PageItem.Confirm, is PageItem.Invoke -> "tap choose"
            is PageItem.LaunchPrompt, is PageItem.Launch, is PageItem.LocalLaunch -> "tap open"
            else -> null
        }
        val trail = if (rows.size > 1) "${(frame.selected + 1).coerceAtMost(rows.size)}/${rows.size}" else "back: $parent"
        return SessionScreen(ScreenHeader(header, trail), blocks, ScreenHint(action, back), statusText, focus)
    }

    private fun panel(frame: Frame, panel: FramePanel, title: String, statusText: String?): SessionScreen = when (panel) {
        is FramePanel.Confirm -> SessionScreen(
            ScreenHeader("${title.uppercase()} · CONFIRM", "back: cancel"),
            listOf(
                ScreenBlock.Text("question", "${panel.label}?", TextRole.TITLE, centered = true),
                ScreenBlock.Text("explain", "${titleOf(frame.pageId)} will do this.", TextRole.CAPTION, centered = true),
                ScreenBlock.Row("cancel", CANCEL, selected = panel.selected == FramePanel.CANCEL, centered = true),
                ScreenBlock.Row("confirm", CONFIRM, selected = panel.selected == FramePanel.CONFIRM, centered = true),
            ),
            ScreenHint("swipe choose · tap confirm", "back cancel"),
            statusText,
            if (panel.selected == FramePanel.CANCEL) "cancel" else "confirm",
        )
        is FramePanel.Launch -> {
            val name = pluginLabel(panel.pluginId) ?: panel.label
            SessionScreen(
                ScreenHeader(name.uppercase(), "back: cancel"),
                listOf(
                    ScreenBlock.Text("name", name, TextRole.TITLE, centered = true),
                    ScreenBlock.Text("explain", LAUNCH_EXPLANATION, TextRole.CAPTION, centered = true),
                    ScreenBlock.Row("open", OPEN, selected = panel.selected == FramePanel.OPEN, centered = true),
                    ScreenBlock.Row("dismiss", CANCEL, selected = panel.selected == FramePanel.DISMISS, centered = true),
                ),
                ScreenHint("swipe choose · tap open", "back cancel"),
                statusText,
                if (panel.selected == FramePanel.OPEN) "open" else "dismiss",
            )
        }
    }

    /** A retained snapshot drawn while it is being revalidated: readable, never selectable. */
    private fun summaryOf(snapshot: Snapshot, frame: Frame, readable: Boolean): List<ScreenBlock> {
        val body = items.body(snapshot.page) ?: return listOf(ScreenBlock.Text("retained", snapshot.page.title, TextRole.BODY))
        val first = when (body) {
            is PageBody.Summary -> body.primary ?: body.secondary ?: body.text
            is PageBody.Document -> body.segments.firstOrNull()?.text
            is PageBody.Commands -> body.text ?: body.items.firstOrNull()?.label
            is PageBody.SelectableList -> body.items.firstOrNull()?.label
        }
        return listOfNotNull(
            ScreenBlock.Text("retained", snapshot.page.title, TextRole.BODY),
            first?.takeIf { readable }?.let { ScreenBlock.Text("retained-line", it, TextRole.CAPTION) },
            frame.shownAtMs?.let { ScreenBlock.Text("retained-time", "Known · ${formatTime(wallTimeOf(it))}", TextRole.CAPTION) },
        )
    }

    private fun titleOfFrame(frame: Frame): String {
        val retained = when (val status = frame.status) {
            is FrameStatus.Loading -> status.previous
            is FrameStatus.Shown -> status.snapshot
            is FrameStatus.Unavailable -> status.lastSnapshot
        }
        return retained?.page?.title?.takeIf(String::isNotBlank) ?: titleOf(frame.pageId)
    }

    private fun reasonText(reason: String): String = when (reason) {
        PageSurfaceContract.ERROR_PAGE_TIMEOUT -> "The plugin did not respond."
        SessionReducer.UNAVAILABLE_LINK_LOST -> "Phone disconnected. The page stays readable; nothing is replayed."
        SessionReducer.UNAVAILABLE_DATA_PLANE -> "The phone link is too narrow for this page now. It stays readable; nothing is replayed."
        PageSurfaceContract.ERROR_PAGE_UNAVAILABLE -> "The plugin cannot show this page now."
        SessionReducer.UNAVAILABLE_EVICTED -> "This page was set aside to save memory."
        SessionReducer.UNAVAILABLE_UNSUPPORTED -> "This page needs a newer Nexus."
        else -> "This page could not be shown."
    }

    private fun outcomeText(outcome: ActionOutcome): String = when (outcome.status) {
        SessionReducer.RESULT_DONE -> outcome.message ?: "Done"
        "rejected" -> outcome.message ?: "Not done"
        "stale" -> outcome.message ?: "Out of date; nothing was done"
        PageSurfaceContract.ERROR_UNCONFIRMED_ACTION -> "Unconfirmed: the plugin did not answer"
        else -> outcome.status
    }

    private fun statusText(status: SessionStatus): String = when (status) {
        is SessionStatus.OpenFailed -> "Could not open ${pluginLabel(status.pluginId) ?: status.pluginId} (${status.reason.name.lowercase()})"
        is SessionStatus.LaunchFailed -> "Could not open ${status.label}"
        SessionStatus.FrameLimit -> "Too many pages open"
        SessionStatus.NotAvailable -> "Not available here"
    }

    companion object {
        const val NEXUS = "NEXUS"
        const val GATE_TEXT = "Opening…"
        const val LOADING = "Loading…"
        const val STILL_LOADING = "Still loading…"
        const val UNAVAILABLE_TITLE = "Detail unavailable"
        const val RETRY = "Retry"
        const val BACK = "Back"
        const val CANCEL = "Cancel"
        const val CONFIRM = "Confirm"
        const val OPEN = "Open"
        const val COMMANDS = "COMMANDS"
        const val LAUNCH_EXPLANATION =
            "Opens the full app as an exclusive surface. It replaces what the glasses show now; BACK leaves it."
        private const val ACTIVITY = "Activity"
        private const val ENDED = "ENDED"
        private const val NOTIFICATION = "NOTIFICATION"
        private const val PAGE = "Page"
    }
}
