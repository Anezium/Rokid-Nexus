package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.MediaPlaybackState
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteRibbonBetween
import com.anezium.rokidbus.shared.RouteRibbonStop

/**
 * A canonical icon the renderer resolves: a plugin's own mark, a hub place, a native app's
 * launcher icon, or a named glyph. The screen model names it; the renderer owns pixels.
 */
internal sealed interface IconRef {
    /** A plugin's icon; [iconKey] is its descriptor icon key, null for its default mark. */
    data class Plugin(val pluginId: String, val iconKey: String? = null) : IconRef

    /** A hub place: the notifications bell or the applications grid. */
    data class Place(val place: IconPlace) : IconRef

    /** A native Android app on the glasses, by package. */
    data class Native(val packageName: String) : IconRef

    /** A glyph from the shared glyph set (or a plugin glyph by its qualified name). */
    data class Glyph(val name: String) : IconRef
}

internal enum class IconPlace { BELL, GRID }

/** The fixed chrome above the viewport: a title, a short trail on its right, and an optional icon. */
internal data class ScreenHeader(val title: String, val trail: String? = null, val icon: IconRef? = null)

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

    /**
     * A root card: fixed lines, fixed size, whatever its values become. [tertiary] is a third,
     * quieter line (a capture time, a handoff state); [offline] draws the last-known treatment;
     * [dashed] outlines it dashed; [commands] is the inline command strip of an activity in
     * command focus.
     */
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
        val icon: IconRef? = null,
        val tertiary: String? = null,
        val offline: Boolean = false,
        val dashed: Boolean = false,
        val commands: CommandStrip? = null,
    ) : ScreenBlock

    /**
     * [checked] draws a checkbox mark (null: none); [tag] is a short state tag on the right
     * ("SEEN IN NEXUS", "HANDED OFF"); [pending] marks an invocation awaiting its result.
     */
    data class Row(
        override val key: String,
        val label: String,
        val sub: String? = null,
        val trail: String? = null,
        val enabled: Boolean = true,
        val selected: Boolean = false,
        val tombstone: Boolean = false,
        val centered: Boolean = false,
        val icon: IconRef? = null,
        val checked: Boolean? = null,
        val tag: String? = null,
        val pending: Boolean = false,
    ) : ScreenBlock

    /** [dimmed] is retained content of an unavailable page: readable, drawn as inert. */
    data class Text(
        override val key: String,
        val text: String,
        val role: TextRole,
        val centered: Boolean = false,
        val dimmed: Boolean = false,
    ) : ScreenBlock

    data class Fact(override val key: String, val label: String, val value: String, val dimmed: Boolean = false) : ScreenBlock

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

    /**
     * A conversation page's bubbles, oldest first; [caption] is its one capture time. A live one
     * is read page by page like a document: the renderer reports where its pages start (anchors
     * naming bubble keys) through [SessionEvent.DocumentPaged] for [frame], [pageId] and
     * [revision], and keeps [anchor] on screen. A retained (dimmed) one names no frame.
     */
    data class Conversation(
        override val key: String,
        val caption: String?,
        val bubbles: List<Bubble>,
        val dimmed: Boolean = false,
        val frame: FrameIdentity? = null,
        val pageId: String? = null,
        val revision: Long? = null,
        val anchor: DocumentAnchor? = null,
    ) : ScreenBlock

    /** Now playing: what the media page says, with no controls (they are the command strip). */
    data class Media(
        override val key: String,
        val title: String,
        val artist: String? = null,
        val album: String? = null,
        val source: String? = null,
        val state: MediaPlaybackState,
        val status: String? = null,
    ) : ScreenBlock

    /** The next manoeuvre; [approximate] prefixes the distance with "~" for a degraded position. */
    data class Maneuver(
        override val key: String,
        val glyph: String,
        val distance: String,
        val street: String? = null,
        val eta: String? = null,
        val approximate: Boolean = false,
    ) : ScreenBlock

    /**
     * Hub-rendered route geometry. [headingDeg] is the smoothed heading, null when absent or
     * stale; [degraded] draws north-up with a dashed route and a dot without an arrow. A compact
     * or panel map is a card: [label] and [trail] are its caption row ("MAP · FOLLOW", "1/2").
     */
    data class Map(
        override val key: String,
        val geometry: RouteGeometry,
        val headingDeg: Float? = null,
        val size: MapSize = MapSize.COMPACT,
        val view: MapView = MapView.FOLLOW,
        val degraded: Boolean = false,
        val selected: Boolean = false,
        val label: String? = null,
        val trail: String? = null,
        val renderContext: RenderedMapContext? = null,
    ) : ScreenBlock

    /** A transit ride's stops, with the vehicle [between] two of them. */
    data class Ribbon(
        override val key: String,
        val line: String?,
        val stops: List<RouteRibbonStop>,
        val between: RouteRibbonBetween? = null,
        val note: String? = null,
    ) : ScreenBlock
}

/** One plugin or hub command in an activity card's inline strip. [hub] marks the hub's own Open app. */
internal data class CommandSlot(
    val key: String,
    val label: String,
    val glyph: String,
    val enabled: Boolean = true,
    val hub: Boolean = false,
)

/** The commands of a card in command focus; [focusedIndex] is the outlined one. */
internal data class CommandStrip(val items: List<CommandSlot>, val focusedIndex: Int)

internal enum class BubbleSide { LEFT, RIGHT, NONE }

/** SOLID is a captured message, DASHED a draft or unconfirmed one, LIVE text being captured. */
internal enum class BubbleStyle { SOLID, DASHED, LIVE }

internal data class Bubble(
    val key: String,
    val side: BubbleSide,
    val text: String,
    val latest: Boolean = false,
    val style: BubbleStyle = BubbleStyle.SOLID,
    /** A short line under it: "handed to the app", "draft · not sent yet". */
    val meta: String? = null,
    /** Who sent a received message, as its provider names it in a group thread; never guessed. */
    val sender: String? = null,
)

internal enum class MapSize { COMPACT, PANEL, FULL }

internal enum class MapView { FOLLOW, OVERVIEW }

/** One inline button of a footer: Dictate, Type, Send, a map view. */
internal data class FooterButton(
    val key: String,
    val label: String,
    val glyph: String? = null,
    val selected: Boolean = false,
    val enabled: Boolean = true,
)

internal enum class StatusLineStyle {
    /** A capture is running: the glasses mic or the phone keyboard. */
    CAPTURE,

    /** Something is unavailable: the mic, or replying from here. */
    OFF,
}

/** The inline footer under the viewport, above the hint: buttons, or one status line. */
internal sealed interface InlineFooter {
    data class Buttons(val buttons: List<FooterButton>) : InlineFooter

    data class StatusLine(
        val text: String,
        val sub: String? = null,
        val glyph: String? = null,
        val style: StatusLineStyle = StatusLineStyle.CAPTURE,
    ) : InlineFooter
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
    /** Present while the renderer must tell whether this content fits the viewport. */
    val fit: FitCheck? = null,
    /** The composer's buttons or capture line, drawn between the viewport and the hint. */
    val footer: InlineFooter? = null,
    /** The full-map excursion: no header, no hint, the viewport is the whole HUD. */
    val chromeless: Boolean = false,
)

/**
 * Everything from the top of the viewport through [untilKey] must be in view at once, or the
 * renderer reports [SessionEvent.ContentOverflowed] for this frame and revision, and the content
 * is then read page by page.
 */
internal data class FitCheck(val frame: FrameIdentity, val pageId: String, val revision: Long, val untilKey: String)

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
    /** Wall-clock now, for the age of a notification. */
    private val wallClock: () -> Long = { 0L },
    /** An owner's live geometry and smoothed heading, for its route page's map. */
    private val liveRoute: (String) -> LiveRoute = { LiveRoute() },
    /** A plugin's mark as the launcher catalog names it, for its pages' headers. */
    private val pluginIcon: (String) -> IconRef = { IconRef.Plugin(it) },
    /** The mark of a row of a hub page, by page and row key: an Applications entry's. */
    private val localIcon: (String, String) -> IconRef? = { _, _ -> null },
) {
    /**
     * [generation] is the open session's, which names its frames for the renderer's reports;
     * [facts] carries the activity collection and the commands' pending and answered states.
     */
    fun screen(
        state: SessionState,
        status: SessionStatus?,
        generation: Long = 0,
        facts: CommandFacts = CommandFacts(),
    ): SessionScreen {
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
            is SessionState.Root -> root(state, statusText, facts)
            is SessionState.InPage -> page(state, statusText, generation, facts)
            is SessionState.Launching -> screen(state.previous, status, generation, facts).copy(
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

    private fun root(state: SessionState.Root, statusText: String?, facts: CommandFacts): SessionScreen {
        state.panel?.let { step ->
            val owner = state.stops.firstOrNull { it.id == state.focus?.stopId }?.label ?: NEXUS
            return panel(step, owner, owner, statusText)
        }
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
                    icon = stop.icon,
                    tertiary = stop.activity?.takeUnless { stop.ended }?.let { commandLine(it, facts) },
                    offline = stop.activity?.lastKnown == true,
                    commands = stop.activity?.takeUnless { stop.ended }
                        ?.let { activity -> state.focus?.takeIf { it.stopId == stop.id }?.let { strip(activity, it) } },
                )
                RootStopKind.NOTICE_PREVIEW -> noticeCard(stop, selected)
                RootStopKind.ACTIVITIES, RootStopKind.NOTIFICATIONS ->
                    ScreenBlock.Row(stop.id, stop.text, trail = (stop.count ?: 0).toString(), selected = selected, icon = stop.icon)
                RootStopKind.APPLICATIONS -> ScreenBlock.Row(stop.id, stop.text, selected = selected, icon = stop.icon)
            }
        }
        val selectedStop = state.stops.getOrNull(state.selected)
        val hint = when {
            state.focus != null -> ScreenHint(FOCUS_HINT, FOCUS_BACK)
            selectedStop?.ended == true -> ScreenHint("swipe next card", "back close")
            selectedStop?.kind == RootStopKind.NOTIFICATIONS -> ScreenHint(NOTIFICATIONS_HINT, "back close")
            else -> ScreenHint("swipe next · tap open", "back close")
        }
        return SessionScreen(ScreenHeader(NEXUS, "back: close"), blocks, hint, statusText, focusKey = selectedStop?.id)
    }

    /**
     * The root's notification stop (plan 028a §6.4, §9). A conversation card says what is known of
     * its one entry and nothing more: unanswered, handed off with delivery unconfirmed, or removed
     * from the phone with nothing proven. An aggregate counts its owner's threads; anything else is
     * the generic preview of the newest entry.
     */
    private fun noticeCard(stop: RootStop, selected: Boolean): ScreenBlock.Card {
        val card = stop.notice
        val removed = card?.state == HistoryState.WITHDRAWN
        val generic = ScreenBlock.Card(
            key = stop.id,
            label = listOfNotNull(NOTIFICATION, stop.label?.uppercase()).joinToString(" · "),
            time = stop.atWallMs?.let(formatTime),
            primary = stop.text,
            secondary = null,
            measure = null,
            figure = false,
            ended = false,
            selected = selected,
            icon = stop.icon,
            tertiary = REMOVED_NOTHING_PROVEN.takeIf { removed },
            dashed = removed,
        )
        if (card == null) return generic
        val source = (stop.label ?: card.ownerPluginId?.let(pluginLabel) ?: NOTIFICATION).uppercase()
        return when (card.kind) {
            NoticeCardKind.GENERIC -> generic
            NoticeCardKind.CONVERSATION -> ScreenBlock.Card(
                key = stop.id,
                label = "$source · " + when (card.state) {
                    HistoryState.HANDED_OFF -> REPLY_HANDED_OFF
                    HistoryState.WITHDRAWN -> NOTIFICATION_REMOVED
                    HistoryState.NEW, HistoryState.SEEN -> UNANSWERED
                },
                // The arrival time is known in every state, so it is said in every state.
                time = stop.atWallMs?.let(formatTime),
                primary = stop.text,
                secondary = card.secondary,
                measure = null,
                figure = false,
                ended = false,
                selected = selected,
                icon = stop.icon ?: card.ownerPluginId?.let { IconRef.Plugin(it) },
                tertiary = when (card.state) {
                    HistoryState.HANDED_OFF -> HANDED_OFF_UNCONFIRMED
                    HistoryState.WITHDRAWN -> REMOVED_NOTHING_PROVEN
                    HistoryState.NEW, HistoryState.SEEN -> stop.atWallMs?.let { "captured ${formatTime(it)}" }
                },
                dashed = removed,
            )
            NoticeCardKind.AGGREGATE -> ScreenBlock.Card(
                key = stop.id,
                label = source,
                time = stop.atWallMs?.let(formatTime),
                primary = stop.text,
                secondary = card.secondary,
                measure = null,
                figure = false,
                ended = false,
                selected = selected,
                icon = stop.icon ?: card.ownerPluginId?.let { IconRef.Plugin(it) },
            )
        }
    }

    /** An activity's commands as its card draws them in command focus. */
    private fun strip(activity: ActivityStop, focus: CommandFocus): CommandStrip = CommandStrip(
        ActivityCommands.slots(activity).map { CommandSlot(it.key, it.label, it.glyph, enabled = it.enabled, hub = it.hub) },
        focus.index,
    )

    /**
     * A card's third line: an offline entry's last-known time first, else its owner's command
     * pending or last answered. Nothing is invented: a lost answer says it is unconfirmed.
     */
    private fun commandLine(activity: ActivityStop, facts: CommandFacts): String? {
        if (activity.lastKnown) {
            val knownAt = activity.knownAtWallMs ?: activity.updatedAtWallMs.takeIf { activity.stale }
            return knownAt?.let { "$OFFLINE_HINT ${formatTime(it)}" } ?: OFFLINE
        }
        val owner = activity.ownerPluginId ?: return null
        facts.pending[owner]?.let { pending ->
            val label = activity.commands.firstOrNull { it.id == pending.actionId }?.label ?: pending.actionId
            return "$label · $PENDING"
        }
        return facts.outcomes[owner]?.let { outcomeText(ActionOutcome(it.actionId, it.status, it.message)) }
    }

    private fun page(state: SessionState.InPage, statusText: String?, generation: Long, facts: CommandFacts): SessionScreen {
        val frame = state.frames.last()
        val frameIndex = state.frames.size
        val parent = state.frames.getOrNull(state.frames.lastIndex - 1)?.let { titleOfFrame(it) } ?: "root"
        val title = titleOfFrame(frame)
        val back = "back $parent"
        frame.panel?.let { return panel(it, title, titleOf(frame.pageId), statusText) }
        val outcomeText = frame.outcome?.let(::outcomeText)
        return when (val status = frame.status) {
            is FrameStatus.Loading -> {
                val blocks = buildList {
                    status.previous?.let { addAll(summaryOf(it, frame, readable = true)) }
                    add(ScreenBlock.Text("loading", if (status.stillLoading) STILL_LOADING else LOADING, TextRole.CAPTION, centered = true))
                    add(ScreenBlock.Progress("progress", if (status.stillLoading) 0.6f else 0.25f))
                }
                SessionScreen(ScreenHeader(title.uppercase(), "back: $parent", pageIcon(frame.pageId)), blocks, ScreenHint(null, back), statusText, "loading")
            }
            is FrameStatus.Unavailable ->
                unavailable(frame, status, FrameIdentity(generation, frameIndex), title, parent, back, statusText)
            is FrameStatus.Shown -> when (val body = items.body(status.snapshot.page)) {
                // A reply's outcome is its bubble or the composer's line; only a refusal or a stale page is said in words.
                is PageBody.Conversation -> conversation(
                    frame, status, FrameIdentity(generation, frameIndex), body, title, back,
                    statusText ?: frame.outcome?.takeIf { it.status in SPOKEN_OUTCOMES }?.let(::outcomeText), facts.composer,
                )
                is PageBody.Route -> route(frame, status, FrameIdentity(generation, frameIndex), body, title, parent, back, statusText ?: outcomeText, facts)
                else -> shown(frame, status, FrameIdentity(generation, frameIndex), title, parent, back, statusText ?: outcomeText, facts)
            }
        }
    }

    /**
     * G4: the failure, dated, over the retained content, dimmed and inert. Content too tall for
     * the viewport is read page by page; SELECT reaches only Retry and Back.
     */
    private fun unavailable(
        frame: Frame,
        status: FrameStatus.Unavailable,
        identity: FrameIdentity,
        title: String,
        parent: String,
        back: String,
        statusText: String?,
    ): SessionScreen {
        val snapshot = status.lastSnapshot
        val body = snapshot?.let(items::retainedBody)
        val readsFirst = snapshot != null && (frame.overflowing || body is PageBody.Document)
        val failure = listOfNotNull(
            ScreenBlock.Text("unavailable", UNAVAILABLE_TITLE, TextRole.TITLE, centered = true),
            ScreenBlock.Text("reason", reasonText(status.reason), TextRole.CAPTION, centered = true),
            snapshot?.let {
                val dated = frame.shownAtMs?.let { "Last known · ${formatTime(wallTimeOf(it))}" } ?: "Last known"
                ScreenBlock.Text("last-dated", dated, TextRole.CAPTION, centered = true)
            },
        )
        if (snapshot != null && readsFirst && frame.mode == FrameMode.READING) {
            // A conversation is paged as the conversation it was: sides, senders, its capture
            // caption and each message's own handoff state, dimmed, with no composer.
            val reading = if (body is PageBody.Conversation) {
                ScreenBlock.Conversation(
                    CONVERSATION_BLOCK, body.caption, bubbles(body.messages), dimmed = true,
                    frame = identity, pageId = frame.pageId, revision = snapshot.page.revision, anchor = frame.anchor,
                )
            } else {
                ScreenBlock.Document(
                    "document", identity, frame.pageId, snapshot.page.revision,
                    readingSegments(body), frame.anchor, dimmed = true, footer = (body as? PageBody.Document)?.footer,
                )
            }
            val pages = frame.pageStarts.size
            return SessionScreen(
                ScreenHeader(title.uppercase(), if (pages > 0) "${frame.page + 1}/$pages" else "back: $parent", pageIcon(frame.pageId)),
                failure + reading,
                // The tap only reaches Retry and Back; a second one runs the selected row.
                ScreenHint(listOfNotNull("swipe page".takeIf { pages > 1 }, TAP_RETRY_BACK).joinToString(" · "), back),
                statusText,
                reading.key,
            )
        }
        val retained = if (snapshot != null && !readsFirst) retainedBlocks(snapshot) else emptyList()
        val blocks = failure + retained + listOf(
            ScreenBlock.Row("retry", RETRY, selected = frame.selected == 0, centered = true),
            ScreenBlock.Row("back", BACK, selected = frame.selected == 1, centered = true),
        )
        val focus = if (frame.selected == 0) "retry" else "back"
        return SessionScreen(
            ScreenHeader(title.uppercase(), "back: $parent", pageIcon(frame.pageId)),
            blocks,
            ScreenHint("tap $focus", if (readsFirst) BACK_TO_TEXT else back),
            statusText,
            focus,
            fit = snapshot?.takeIf { retained.isNotEmpty() }?.let { FitCheck(identity, frame.pageId, it.page.revision, "back") },
        )
    }

    /** Retained content that fits beside the failure: what the page showed, dimmed, its actions left out. */
    private fun retainedBlocks(snapshot: Snapshot): List<ScreenBlock> = when (val body = items.retainedBody(snapshot)) {
        is PageBody.Summary -> listOfNotNull(
            body.primary?.let { ScreenBlock.Text("last:primary", it, TextRole.FIGURE, dimmed = true) },
            body.secondary?.let { ScreenBlock.Text("last:secondary", it, TextRole.BODY, dimmed = true) },
            body.text?.let { ScreenBlock.Text("last:text", it, TextRole.BODY, dimmed = true) },
        ) + body.facts.map { ScreenBlock.Fact("last:fact:${it.id}", it.label, it.value, dimmed = true) }
        is PageBody.Commands -> listOfNotNull(body.text?.let { ScreenBlock.Text("last:text", it, TextRole.BODY, dimmed = true) })
        is PageBody.SelectableList -> body.items.map { ScreenBlock.Row("last:row:${it.id}", it.label, it.sub, enabled = false) }
            .ifEmpty { listOf(ScreenBlock.Text("last:empty", body.emptyText ?: "Nothing here", TextRole.CAPTION, centered = true, dimmed = true)) }
        // What was playing, never its controls: a retained page acts on nothing.
        is PageBody.Media -> listOfNotNull(
            ScreenBlock.Text("last:title", body.title, TextRole.BODY, dimmed = true),
            listOfNotNull(body.artist, body.album).joinToString(" · ").ifBlank { null }
                ?.let { ScreenBlock.Text("last:artist", it, TextRole.CAPTION, dimmed = true) },
        )
        // The messages as they were, dimmed; never the composer: a retained page acts on nothing.
        is PageBody.Conversation -> listOf(ScreenBlock.Conversation("last:conversation", body.caption, bubbles(body.messages), dimmed = true))
        // The guidance as it was, dimmed; never its map: a retained position is no position.
        is PageBody.Route -> listOfNotNull(
            body.maneuver?.let { ScreenBlock.Text("last:distance", "~${it.distance.removePrefix("~")}", TextRole.FIGURE, dimmed = true) },
            body.maneuver?.street?.let { ScreenBlock.Text("last:street", it, TextRole.BODY, dimmed = true) },
        ) + body.facts.map { ScreenBlock.Fact("last:fact:${it.id}", it.label, it.value, dimmed = true) }
        is PageBody.Document, null -> emptyList()
    }

    /** What is read page by page: a document's own segments, or a body's text laid out as prose. */
    private fun readingSegments(body: PageBody?): List<PageSegment> = when (body) {
        is PageBody.Document -> body.segments
        is PageBody.Summary -> listOfNotNull(
            body.primary?.let { PageSegment("primary", PageSegmentKind.PROSE, it) },
            body.secondary?.let { PageSegment("secondary", PageSegmentKind.PROSE, it) },
            body.text?.let { PageSegment("text", PageSegmentKind.PROSE, it) },
        ) + body.facts.map { PageSegment("fact:${it.id}", PageSegmentKind.PROSE, "${it.label} · ${it.value}") }
        is PageBody.Commands -> listOfNotNull(body.text?.let { PageSegment("text", PageSegmentKind.PROSE, it) })
        is PageBody.SelectableList -> body.items.flatMap { row ->
            listOfNotNull(PageSegment("row:${row.id}", PageSegmentKind.PROSE, row.label), row.sub?.let { PageSegment("sub:${row.id}", PageSegmentKind.ASIDE, it) })
        }
        is PageBody.Media -> listOfNotNull(
            PageSegment("title", PageSegmentKind.PROSE, body.title),
            body.artist?.let { PageSegment("artist", PageSegmentKind.ASIDE, it) },
            body.album?.let { PageSegment("album", PageSegmentKind.ASIDE, it) },
            body.status?.let { PageSegment("status", PageSegmentKind.ASIDE, it) },
        )
        is PageBody.Conversation -> body.messages.map { PageSegment("m:${it.id}", PageSegmentKind.PROSE, it.text) }
        is PageBody.Route -> listOfNotNull(
            body.maneuver?.let { PageSegment("maneuver", PageSegmentKind.PROSE, listOfNotNull(it.distance, it.street).joinToString(" · ")) },
        ) + body.facts.map { PageSegment("fact:${it.id}", PageSegmentKind.PROSE, "${it.label} · ${it.value}") } +
            listOfNotNull(body.ribbon?.note?.let { PageSegment("note", PageSegmentKind.ASIDE, it) })
        null -> emptyList()
    }

    private fun shown(
        frame: Frame,
        status: FrameStatus.Shown,
        identity: FrameIdentity,
        title: String,
        parent: String,
        back: String,
        statusText: String?,
        facts: CommandFacts,
    ): SessionScreen {
        val page = status.snapshot.page
        val body = items.body(page)
        val header = title.uppercase()
        // Text measured taller than the viewport is read like a document, before its rows.
        val readsFirst = body is PageBody.Document ||
            (frame.overflowing && (body is PageBody.Summary || body is PageBody.Commands))
        if (readsFirst && frame.mode == FrameMode.READING) {
            val document = ScreenBlock.Document(
                "document", identity, frame.pageId, status.revision,
                readingSegments(body), frame.anchor, dimmed = false, footer = (body as? PageBody.Document)?.footer,
            )
            val pages = frame.pageStarts.size
            val trail = if (pages > 0) "${frame.page + 1}/$pages" else null
            val hasRows = items.rows(page).isNotEmpty()
            return SessionScreen(
                ScreenHeader(header, trail, pageIcon(frame.pageId)),
                listOf(document),
                ScreenHint(listOfNotNull("swipe page".takeIf { pages > 1 }, "tap commands".takeIf { hasRows }).joinToString(" · ").ifBlank { null }, back),
                statusText,
                "document",
            )
        }
        val blocks = mutableListOf<ScreenBlock>()
        when {
            readsFirst -> blocks += ScreenBlock.Text("section", COMMANDS, TextRole.SECTION)
            body is PageBody.Summary -> {
                body.primary?.let { blocks += ScreenBlock.Text("primary", it, TextRole.FIGURE) }
                body.secondary?.let { blocks += ScreenBlock.Text("secondary", it, TextRole.BODY) }
                body.text?.let { blocks += ScreenBlock.Text("text", it, TextRole.BODY) }
                body.facts.forEach { blocks += ScreenBlock.Fact("fact:${it.id}", it.label, it.value) }
            }
            body is PageBody.Commands -> body.text?.let { blocks += ScreenBlock.Text("text", it, TextRole.BODY) }
            body is PageBody.Media ->
                blocks += ScreenBlock.Media("media", body.title, body.artist, body.album, body.source, body.state, body.status)
        }
        val rows = ActivityCommands.withOpenAppRows(items.rows(page), frame.openApp)
        val controls = items.controls(page)
        val commandFocus = frame.commandFocus
        // A control strip shows a selection only while it holds the focus.
        val showsSelection = controls == null || commandFocus != null
        val pendingAction = frame.invocation?.actionId
        val rowBlocks: MutableList<ScreenBlock> = rows.mapIndexed { index, row ->
            val selected = showsSelection && frame.tombstone == null && index == frame.selected
            val activity = (row.item as? PageItem.Activity)?.let { facts.activity(it.ownerPluginId) }
            val entry = (row.item as? PageItem.Notification)?.let { facts.history.entry(it.entryId) }
            when {
                activity != null ->
                    activityCard("row:${row.key}", activity, selected, commandFocus?.takeIf { it.stopId == activity.id }, facts)
                // A Notifications row: its source's icon, its state tag and its age.
                entry != null -> ScreenBlock.Row(
                    "row:${row.key}", row.label, row.sub,
                    trail = ageText(entry.notice.atWallMs),
                    selected = selected,
                    icon = entry.notice.icon ?: entry.notice.ownerPluginId.ifBlank { null }?.let { IconRef.Plugin(it) },
                    tag = when (entry.state) {
                        HistoryState.NEW -> null
                        HistoryState.SEEN -> TAG_SEEN
                        HistoryState.HANDED_OFF -> TAG_HANDED_OFF
                        HistoryState.WITHDRAWN -> TAG_REMOVED
                    },
                )
                else -> ScreenBlock.Row(
                    "row:${row.key}", row.label, row.sub,
                    enabled = row.enabled && row.item != PageItem.Inert,
                    selected = selected,
                    icon = localIcon(frame.pageId, row.key),
                    checked = row.checked,
                    pending = pendingAction != null && row.actionId == pendingAction,
                )
            }
        }.toMutableList()
        frame.tombstone?.let { label ->
            rowBlocks.add(frame.selected.coerceIn(0, rowBlocks.size), ScreenBlock.Row("tombstone", label, "removed", enabled = false, selected = true, tombstone = true))
        }
        blocks += rowBlocks
        if (rows.isEmpty() && body is PageBody.SelectableList) {
            blocks += ScreenBlock.Text("empty", body.emptyText ?: "Nothing here", TextRole.CAPTION, centered = true)
        }
        // The collection says what its tags and bounds mean (r17 4.14).
        if (frame.pageId == RootStops.NOTIFICATIONS_PAGE_ID) blocks += ScreenBlock.Text("footnote", NOTIFICATIONS_FOOTNOTE, TextRole.CAPTION)
        val focus = rowBlocks.firstOrNull { it.isSelected() }?.key ?: blocks.firstOrNull()?.key
        val selectedRow = if (frame.tombstone == null && showsSelection) rows.getOrNull(frame.selected) else null
        val action = when {
            commandFocus != null -> FOCUS_HINT
            controls != null -> TAP_CONTROLS
            else -> when (selectedRow?.item) {
                null, PageItem.Inert -> if (rows.size > 1) "swipe choose" else null
                is PageItem.OpenPage, is PageItem.Activity, is PageItem.Notification -> "tap open"
                is PageItem.Confirm, is PageItem.Invoke -> "tap choose"
                is PageItem.Quick -> "tap run"
                is PageItem.LaunchPrompt, is PageItem.Launch, is PageItem.LocalLaunch -> "tap open"
                else -> null
            }
        }
        val backHint = when {
            commandFocus?.stopId == CommandFocus.MEDIA -> BACK_PAGE
            commandFocus != null -> FOCUS_BACK
            readsFirst -> BACK_TO_TEXT
            else -> back
        }
        val trail = if (rows.size > 1) "${(frame.selected + 1).coerceAtMost(rows.size)}/${rows.size}" else "back: $parent"
        // The text fits when it is whole on screen with the first row: the scroll that row asks for.
        val fit = if (!readsFirst && (body is PageBody.Summary || body is PageBody.Commands)) {
            (rowBlocks.firstOrNull() ?: blocks.lastOrNull())?.let { FitCheck(identity, frame.pageId, status.revision, it.key) }
        } else {
            null
        }
        return SessionScreen(ScreenHeader(header, trail, pageIcon(frame.pageId)), blocks, ScreenHint(action, backHint), statusText, focus, fit = fit)
    }

    /**
     * A route page (plan 028a §2.1, §5.5, r17 3.4-3.8): a glance page of the manoeuvre, the compact
     * map card in the chosen view, the transit ribbon and one Commands row, the facts under them.
     * The page selects the map and Commands only. Commands opens the command list (the views,
     * Full screen, Display, then the provider's actions, then the full app); the full map is the
     * map alone with its header and hint floating over it, and Enter there opens the same list.
     * See [RoutePresentation] for the geometry and degraded rules.
     */
    private fun route(
        frame: Frame,
        status: FrameStatus.Shown,
        identity: FrameIdentity,
        body: PageBody.Route,
        title: String,
        parent: String,
        back: String,
        statusText: String?,
        facts: CommandFacts,
    ): SessionScreen {
        val owner = PageTemplateContract.namespaceOf(frame.pageId).orEmpty()
        val live = liveRoute(owner)
        val view = facts.mapViews[owner] ?: MapView.FOLLOW
        val geometry = RoutePresentation.geometry(body, view, live.geometry)
        val renderContext = status.leaseUntilMs?.let {
            RenderedMapContext(
                RenderedMapContract.PAGE, frame.pageId, status.revision,
                sessionGeneration = identity.sessionGeneration, frameIndex = identity.frameIndex, requestId = status.requestId,
            )
        }
        val degraded = RoutePresentation.degraded(body, geometry)
        val heading = RoutePresentation.heading(view, degraded, live.headingDeg)
        val maneuver = body.maneuver?.let {
            ScreenBlock.Maneuver(
                "maneuver", RoutePresentation.glyph(it.glyph, degraded), it.distance.removePrefix("~"), it.street, it.eta,
                approximate = degraded,
            )
        }
        val rows = ActivityCommands.withOpenAppRows(items.rows(status.snapshot.page), frame.openApp)
        val mapIndex = rows.indexOfFirst { it.item == PageItem.RouteMap }
        val commandsIndex = rows.indexOfFirst { it.item == PageItem.RouteCommands }
        val mode = when (val wanted = frame.fullMap) {
            null -> null
            FullMapMode.MAP -> wanted.takeIf { geometry != null && mapIndex >= 0 }
            FullMapMode.COMMANDS, FullMapMode.PAGE_COMMANDS -> wanted.takeIf { commandsIndex >= 0 }
        }
        val name = title.uppercase()
        if (mode == FullMapMode.MAP && geometry != null) {
            val map = ScreenBlock.Map(
                "map", geometry, heading, MapSize.FULL, view, degraded,
                label = RoutePresentation.mapLabel(view), trail = RoutePresentation.mapTrail(view),
                renderContext = renderContext,
            )
            // The header and hint float over the map; no app mark sits on it (r17 3.5, 3.6).
            return SessionScreen(
                ScreenHeader("$name · ${RoutePresentation.viewName(view)}", "view · ${RoutePresentation.mapTrail(view)}"),
                listOfNotNull(map, maneuver),
                ScreenHint(RoutePresentation.fullMapHint(view), BACK_PAGE),
                statusText,
                "map",
                chromeless = true,
            )
        }
        val pendingAction = frame.invocation?.actionId
        fun rowBlock(index: Int, row: TypedRow, sub: String? = row.sub): ScreenBlock = ScreenBlock.Row(
            "row:${row.key}", row.label, sub,
            enabled = row.enabled && row.item != PageItem.Inert,
            selected = frame.tombstone == null && index == frame.selected,
            checked = (row.item as? PageItem.RouteView)?.let { it.view == view } ?: row.checked,
            pending = pendingAction != null && row.actionId == pendingAction,
        )
        val tombstone = frame.tombstone?.let { ScreenBlock.Row("tombstone", it, "removed", enabled = false, selected = true, tombstone = true) }
        val selectedRow = if (frame.tombstone == null) rows.getOrNull(frame.selected) else null
        if (mode == FullMapMode.COMMANDS || mode == FullMapMode.PAGE_COMMANDS) {
            val commands = rows.withIndex().filter { it.index > commandsIndex }
            val blocks = mutableListOf<ScreenBlock>()
            var section: String? = null
            commands.forEach { (index, row) ->
                if (tombstone != null && index == frame.selected) blocks += tombstone
                // Hub commands first, unlabelled; then the plugin's actions; the full app last.
                val next = when (row.item) {
                    is PageItem.RouteView, PageItem.RouteFullScreen, is PageItem.RouteDisplay -> null
                    is PageItem.LaunchPrompt -> RoutePresentation.IMMERSION
                    else -> RoutePresentation.PLUGIN_ACTIONS
                }
                if (next != null && next != section) blocks += ScreenBlock.Text("section:$next", next, TextRole.SECTION)
                section = next
                val sub = row.sub ?: when (row.item) {
                    is PageItem.Confirm -> RoutePresentation.CONFIRM_SUB
                    is PageItem.LaunchPrompt -> RoutePresentation.IMMERSION_SUB
                    else -> null
                }
                blocks += rowBlock(index, row, sub)
            }
            if (tombstone != null && commands.none { it.index == frame.selected }) blocks += tombstone
            val action = when (selectedRow?.item) {
                null, PageItem.Inert -> if (commands.size > 1) "swipe choose" else null
                PageItem.RouteFullScreen, is PageItem.RouteDisplay, is PageItem.LaunchPrompt, is PageItem.Launch -> "swipe choose · tap open"
                is PageItem.RouteView, is PageItem.Confirm, is PageItem.Invoke -> "swipe choose · tap choose"
                is PageItem.Quick -> "swipe choose · tap run"
                else -> null
            }
            val position = commands.indexOfFirst { it.index == frame.selected } + 1
            return SessionScreen(
                ScreenHeader(
                    "$name · ${RoutePresentation.COMMANDS.uppercase()}",
                    if (commands.size > 1 && position > 0) "$position/${commands.size}" else null,
                    pageIcon(frame.pageId),
                ),
                blocks,
                ScreenHint(action, if (mode == FullMapMode.COMMANDS) BACK_MAP else BACK_PAGE),
                statusText,
                blocks.firstOrNull { it.isSelected() }?.key,
            )
        }
        val blocks = mutableListOf<ScreenBlock>()
        maneuver?.let(blocks::add)
        RoutePresentation.gpsLost(body, geometry)?.let { blocks += ScreenBlock.Text("gps", it, TextRole.CAPTION) }
        if (mapIndex >= 0 && geometry != null) {
            blocks += ScreenBlock.Map(
                "row:${rows[mapIndex].key}", geometry, heading, MapSize.COMPACT, view, degraded,
                selected = frame.tombstone == null && mapIndex == frame.selected,
                label = RoutePresentation.mapLabel(view), trail = RoutePresentation.mapTrail(view),
                renderContext = renderContext,
            )
        }
        body.ribbon?.let { blocks += ScreenBlock.Ribbon("ribbon", it.line, it.stops, it.between, it.note) }
        tombstone?.let(blocks::add)
        // Commands, a command, carries no mark (r17 3.4).
        if (commandsIndex >= 0) blocks += rowBlock(commandsIndex, rows[commandsIndex])
        // The facts stay readable under the page's two targets: the route data's attribution is one.
        body.facts.forEach { blocks += ScreenBlock.Fact("fact:${it.id}", it.label, it.value) }
        val action = when (selectedRow?.item) {
            PageItem.RouteMap -> RoutePresentation.MAP_HINT
            PageItem.RouteCommands -> if (mapIndex >= 0) RoutePresentation.COMMANDS_HINT else "tap open"
            else -> null
        }
        val focus = blocks.firstOrNull { it.isSelected() }?.key ?: blocks.firstOrNull()?.key
        return SessionScreen(ScreenHeader(name, "back: $parent", pageIcon(frame.pageId)), blocks, ScreenHint(action, back), statusText, focus)
    }

    /** An entry of the Activities page, drawn as its root card would be, commands included. */
    private fun activityCard(
        key: String,
        activity: ActivityStop,
        selected: Boolean,
        focus: CommandFocus?,
        facts: CommandFacts,
    ) = ScreenBlock.Card(
        key = key,
        label = (activity.ownerLabel ?: activity.ownerPluginId ?: ACTIVITY).uppercase(),
        time = activity.updatedAtWallMs?.let(formatTime),
        primary = activity.text,
        secondary = activity.detail,
        measure = activity.measure,
        figure = true,
        ended = false,
        selected = selected,
        icon = activity.icon,
        tertiary = commandLine(activity, facts),
        offline = activity.lastKnown,
        commands = focus?.let { strip(activity, it) },
    )

    /**
     * A conversation page (plan 028a §7, §9): the thread's bubbles, then this thread's draft, the
     * reply just handed off or the capture's live text; under them the composer's buttons or its
     * one line. Reading pages through the renderer's measured layout, from the newest page.
     */
    private fun conversation(
        frame: Frame,
        status: FrameStatus.Shown,
        identity: FrameIdentity,
        body: PageBody.Conversation,
        title: String,
        back: String,
        statusText: String?,
        facts: ComposerFacts,
    ): SessionScreen {
        val owner = PageTemplateContract.namespaceOf(frame.pageId)
        val composer = body.composer
        val header = if (composer != null) {
            "${(owner?.let(pluginLabel) ?: title).uppercase()} · ${composer.recipient.uppercase()}"
        } else {
            title.uppercase()
        }
        val capture = facts.capture
            ?.takeIf { it.generation == identity.sessionGeneration && it.frameIndex == identity.frameIndex && it.pageId == frame.pageId }
        val view = ComposerRules.view(owner, body, facts.draft, facts.micUnavailable, facts.pendingSend)
        val draft = view?.threadDraft
        val sending = facts.pendingSend?.takeIf { it.ownerPluginId == owner && it.threadKey == body.threadKey } != null
        val drawn = bubbles(body.messages).toMutableList()
        facts.handoff?.takeIf { it.ownerPluginId == owner && it.threadKey == body.threadKey && it.revision == status.revision }?.let {
            drawn += Bubble(HANDOFF_BUBBLE, BubbleSide.RIGHT, it.text, meta = HANDED_TO_APP)
        }
        if (draft != null && capture == null) {
            val meta = when {
                sending -> PENDING
                draft.state == DraftState.READY -> DRAFT_NOT_SENT
                draft.state == DraftState.UNCONFIRMED -> UNCONFIRMED_CHECK_PHONE
                else -> NOT_SENT_HERE
            }
            drawn += Bubble(DRAFT_BUBBLE, BubbleSide.RIGHT, draft.text, style = BubbleStyle.DASHED, meta = meta)
        }
        if (capture != null) {
            // Completing this capture replaces another recipient's draft: say so before it does.
            val replaced = facts.draft?.takeUnless { it.isFor(capture.ownerPluginId, capture.threadKey) }
            drawn += Bubble(
                LIVE_BUBBLE, BubbleSide.RIGHT, capture.liveText, style = BubbleStyle.LIVE,
                meta = replaced?.let { "$REPLACES_DRAFT ${it.recipient}" },
            )
        }
        // Another recipient's draft is replaced by a capture started here: the focused Dictate or
        // Type says so before it starts (r17 4.12).
        val replacing = facts.draft?.takeUnless { it.isFor(owner, body.threadKey) }
        val focus = frame.composerFocus
        val unavailable = view?.unavailable
        val footer: InlineFooter? = when {
            capture != null -> when (capture.kind) {
                CaptureKind.DICTATE -> InlineFooter.StatusLine(GLASSES_MIC, LISTENING, glyph = GLYPH_MIC)
                CaptureKind.TYPE -> InlineFooter.StatusLine(PHONE_KEYBOARD, TYPING, glyph = GLYPH_KEYBOARD)
            }
            view == null -> null
            unavailable != null -> InlineFooter.StatusLine(unavailable.text, unavailable.sub, style = StatusLineStyle.OFF)
            focus == null && facts.micUnavailable -> InlineFooter.StatusLine(MIC_UNAVAILABLE, USE_TYPE, glyph = GLYPH_MIC, style = StatusLineStyle.OFF)
            else -> InlineFooter.Buttons(
                view.buttons.map { key ->
                    FooterButton(key, buttonLabel(key), buttonGlyph(key), selected = focus?.key == key, enabled = !sending)
                },
            )
        }
        val pages = frame.pageStarts.size
        val backText = if (draft != null) "$back · $DRAFT_KEPT" else back
        val hint = when {
            capture?.kind == CaptureKind.DICTATE -> ScreenHint(TAP_FINISH, BACK_CANCEL_DICTATION)
            capture?.kind == CaptureKind.TYPE -> ScreenHint(TAP_FINISH, BACK_CANCEL_TYPING)
            focus != null && focus.key == ComposerRules.SEND && composer != null ->
                ScreenHint("swipe choose · tap send to ${composer.recipient}", BACK_READING)
            focus != null && focus.key != ComposerRules.SEND && replacing != null ->
                ScreenHint("tap start · $REPLACES_DRAFT ${replacing.recipient}", BACK_READING)
            focus != null -> ScreenHint(FOCUS_START, BACK_READING)
            view?.available == true && !sending -> ScreenHint(if (pages > 1) "swipe page · tap reply" else "tap reply", backText)
            else -> ScreenHint(if (pages > 1) "swipe page" else null, backText)
        }
        val block = ScreenBlock.Conversation(
            CONVERSATION_BLOCK, body.caption, drawn, dimmed = false,
            frame = identity, pageId = frame.pageId, revision = status.revision, anchor = frame.anchor,
        )
        // In focus the footer holds the buttons, so the microphone's state is said on the status line.
        val micLine = "$MIC_UNAVAILABLE · $USE_TYPE".takeIf { facts.micUnavailable && focus != null && capture == null }
        return SessionScreen(
            ScreenHeader(header, if (pages > 1) "${frame.page + 1}/$pages" else null, pageIcon(frame.pageId)),
            listOf(block),
            hint,
            statusText ?: micLine,
            CONVERSATION_BLOCK,
            footer = footer,
        )
    }

    /** Sides come from the role alone: received left, ours right, an unknown author on neither. */
    private fun bubbles(messages: List<ConversationMessage>): List<Bubble> = messages.mapIndexed { index, message ->
        Bubble(
            key = "m:${message.id}",
            side = when (message.role) {
                ConversationRole.RECEIVED -> BubbleSide.LEFT
                ConversationRole.OURS -> BubbleSide.RIGHT
                ConversationRole.UNKNOWN -> BubbleSide.NONE
            },
            text = message.text,
            sender = message.sender?.takeIf { message.role == ConversationRole.RECEIVED && it.isNotBlank() },
            latest = index == messages.lastIndex,
            style = if (message.status == ConversationMessageStatus.UNCONFIRMED) BubbleStyle.DASHED else BubbleStyle.SOLID,
            meta = when (message.status) {
                ConversationMessageStatus.HANDED_OFF -> HANDED_TO_APP
                ConversationMessageStatus.UNCONFIRMED -> UNCONFIRMED_CHECK_PHONE
                null -> null
            },
        )
    }

    private fun buttonLabel(key: String): String = when (key) {
        ComposerRules.SEND -> SEND
        ComposerRules.TYPE -> TYPE
        else -> DICTATE
    }

    private fun buttonGlyph(key: String): String = when (key) {
        ComposerRules.SEND -> GLYPH_SEND
        ComposerRules.TYPE -> GLYPH_KEYBOARD
        else -> GLYPH_MIC
    }

    /** A notification's age on the Notifications page: minutes within the hour, then hours. */
    private fun ageText(atWallMs: Long): String {
        val minutes = ((wallClock() - atWallMs) / 60_000L).coerceAtLeast(1)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h"
    }

    private fun ScreenBlock.isSelected(): Boolean = when (this) {
        is ScreenBlock.Row -> selected
        is ScreenBlock.Card -> selected
        is ScreenBlock.Map -> selected
        else -> false
    }

    /** [owner] is who will act: the page's plugin for a page action, the activity's for a command. */
    private fun panel(panel: FramePanel, title: String, owner: String, statusText: String?): SessionScreen = when (panel) {
        is FramePanel.Confirm -> confirmScreen(title, panel.label, owner, panel.selected, statusText)
        is FramePanel.ConfirmCommand -> {
            val name = pluginLabel(panel.ownerPluginId) ?: owner
            confirmScreen(name, panel.label, name, panel.selected, statusText)
        }
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
        // The hub's display page: the owner's island size, previewed on swipe, saved on tap (r17 3.8).
        is FramePanel.Display -> {
            val name = pluginLabel(panel.ownerPluginId) ?: owner
            SessionScreen(
                ScreenHeader("${name.uppercase()} · ${RoutePresentation.DISPLAY.uppercase()}", "back: cancel", pluginIcon(panel.ownerPluginId)),
                listOf<ScreenBlock>(ScreenBlock.Text("section", RoutePresentation.AMBIENT_SECTION, TextRole.SECTION)) +
                    RoutePresentation.SIZES.mapIndexed { index, size ->
                        ScreenBlock.Row(
                            "size:${size.wireValue}", RoutePresentation.sizeLabel(size), RoutePresentation.sizeSub(size),
                            selected = index == panel.selected, checked = size == panel.current,
                        )
                    } +
                    ScreenBlock.Text("note", RoutePresentation.DISPLAY_NOTE, TextRole.CAPTION),
                ScreenHint("swipe choose · tap save", "back cancel"),
                statusText,
                "size:${panel.size.wireValue}",
            )
        }
    }

    private fun confirmScreen(title: String, label: String, owner: String, selected: Int, statusText: String?) = SessionScreen(
        ScreenHeader("${title.uppercase()} · CONFIRM", "back: cancel"),
        listOf(
            ScreenBlock.Text("question", "$label?", TextRole.TITLE, centered = true),
            ScreenBlock.Text("explain", "$owner will do this.", TextRole.CAPTION, centered = true),
            ScreenBlock.Row("cancel", CANCEL, selected = selected == FramePanel.CANCEL, centered = true),
            ScreenBlock.Row("confirm", CONFIRM, selected = selected == FramePanel.CONFIRM, centered = true),
        ),
        ScreenHint("swipe choose · tap confirm", "back cancel"),
        statusText,
        if (selected == FramePanel.CANCEL) "cancel" else "confirm",
    )

    /** A retained snapshot drawn while it is being revalidated: readable, never selectable. */
    private fun summaryOf(snapshot: Snapshot, frame: Frame, readable: Boolean): List<ScreenBlock> {
        val body = items.retainedBody(snapshot) ?: return listOf(ScreenBlock.Text("retained", snapshot.page.title, TextRole.BODY))
        val first = when (body) {
            is PageBody.Summary -> body.primary ?: body.secondary ?: body.text
            is PageBody.Document -> body.segments.firstOrNull()?.text
            is PageBody.Commands -> body.text ?: body.items.firstOrNull()?.label
            is PageBody.SelectableList -> body.items.firstOrNull()?.label
            is PageBody.Conversation -> body.messages.lastOrNull()?.text
            is PageBody.Media -> body.title
            is PageBody.Route -> body.maneuver?.distance
        }
        return listOfNotNull(
            ScreenBlock.Text("retained", snapshot.page.title, TextRole.BODY),
            first?.takeIf { readable }?.let { ScreenBlock.Text("retained-line", it, TextRole.CAPTION) },
            frame.shownAtMs?.let { ScreenBlock.Text("retained-time", "Known · ${formatTime(wallTimeOf(it))}", TextRole.CAPTION) },
        )
    }

    /** A provider page's header carries its owner's mark; the hub's own places carry none. */
    private fun pageIcon(pageId: String): IconRef? =
        if (PageTemplateContract.isLocalPageId(pageId)) null else PageTemplateContract.namespaceOf(pageId)?.let(pluginIcon)

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
        SessionReducer.UNAVAILABLE_RECONNECTING -> "The phone is reconnecting. Nothing is asked until you retry."
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

        /** Retained text being read: a tap only reaches Retry and Back, it never retries by itself. */
        const val TAP_RETRY_BACK = "tap for Retry / Back"
        const val BACK = "Back"
        const val CANCEL = "Cancel"
        const val CONFIRM = "Confirm"
        const val OPEN = "Open"
        const val COMMANDS = "COMMANDS"

        /** BACK from the rows of read text returns to the text before it leaves the page. */
        const val BACK_TO_TEXT = "back text"
        const val LAUNCH_EXPLANATION =
            "Opens the full app as an exclusive surface. It replaces what the glasses show now; BACK leaves it."

        /** Command focus on a card (plan 028a §9). */
        const val FOCUS_HINT = "swipe choose · tap run"
        const val FOCUS_BACK = "back cards"
        const val OFFLINE = "offline"
        const val OFFLINE_HINT = "offline · last known"
        const val PENDING = "waiting for the app"
        const val TAP_CONTROLS = "tap controls"
        const val BACK_PAGE = "back page"

        /** BACK from the full map's commands returns to the map. */
        const val BACK_MAP = "back map"
        private const val ACTIVITY = "Activity"
        private const val ENDED = "ENDED"
        private const val NOTIFICATION = "NOTIFICATION"
        private const val PAGE = "Page"

        // Notifications (plan 028a §9).
        const val UNANSWERED = "UNANSWERED"
        const val REPLY_HANDED_OFF = "REPLY HANDED OFF"
        const val NOTIFICATION_REMOVED = "NOTIFICATION REMOVED"
        const val HANDED_OFF_UNCONFIRMED = "handed to the app · delivery not confirmed"
        const val REMOVED_NOTHING_PROVEN = "removed from the phone · nothing is proven"
        const val TAG_SEEN = "SEEN IN NEXUS"
        const val TAG_HANDED_OFF = "HANDED OFF"
        const val TAG_REMOVED = "REMOVED"
        const val NOTIFICATIONS_HINT = "swipe next · tap open the collection"
        val NOTIFICATIONS_FOOTNOTE =
            "“Seen in Nexus” is local to Nexus: the phone is not told. The history is bounded: " +
                "${NoticeSurfaceContract.MAX_HISTORY_ENTRIES} entries, ${NoticeSurfaceContract.MAX_HISTORY_AGE_MS / 3_600_000} h."

        // The composer (plan 028a §9).
        const val DICTATE = "Dictate"
        const val TYPE = "Type"
        const val SEND = "Send"
        const val HANDED_TO_APP = "handed to the app"
        const val DRAFT_NOT_SENT = "draft · not sent yet"
        const val DRAFT_KEPT = "draft kept"
        const val UNCONFIRMED_CHECK_PHONE = "unconfirmed · check on the phone"
        const val NOT_SENT_HERE = "not sent · can't be sent from here"
        const val GLASSES_MIC = "Glasses mic"
        const val LISTENING = "listening"
        const val PHONE_KEYBOARD = "Phone keyboard"
        const val TYPING = "typing"
        const val MIC_UNAVAILABLE = "Glasses mic unavailable"
        const val USE_TYPE = "use Type"
        const val TAP_FINISH = "tap finish"
        const val BACK_CANCEL_DICTATION = "back cancel dictation"
        const val BACK_CANCEL_TYPING = "back cancel typing"
        const val FOCUS_START = "swipe choose · tap start"
        const val REPLACES_DRAFT = "replaces the draft for"
        const val BACK_READING = "back reading"
        const val GLYPH_MIC = "mic"
        const val GLYPH_KEYBOARD = "keyboard"
        const val GLYPH_SEND = "send"
        const val CONVERSATION_BLOCK = "conversation"
        const val DRAFT_BUBBLE = "draft"
        const val LIVE_BUBBLE = "live"
        const val HANDOFF_BUBBLE = "handed"

        /** Outcomes of a reply said in words; the others are its bubble or the composer's line. */
        private val SPOKEN_OUTCOMES = setOf("rejected", "stale")
    }
}
