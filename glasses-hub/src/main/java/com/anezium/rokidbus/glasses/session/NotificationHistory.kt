package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject

/**
 * [said] without the `<title>: ` that starts every one of its lines in a one-to-one thread, the
 * name already shown above them; null when a line names someone else, as a group's do, or none
 * names anyone. The card and the band both read a conversation this way.
 */
internal fun withoutRepeatedSender(title: String?, said: List<String>): List<String>? {
    val name = title?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val prefix = "$name: "
    val lines = said.map(String::trim).filter(String::isNotEmpty)
    if (lines.isEmpty() || !lines.all { it.startsWith(prefix) && it.length > prefix.length }) return null
    return lines.map { it.removePrefix(prefix).trim() }
}

/** The local state of a history entry (plan 028a §6.3); none of them is a delivery or read receipt. */
internal enum class HistoryState { NEW, SEEN, HANDED_OFF, WITHDRAWN }

/**
 * One notice as the band received it: its text is already what the owner's redaction and hide
 * choices let through, and nothing here rewrites it. The launcher-bundle fields ([destination],
 * [eventKey], [conversation]) are set only over a pair that negotiated the bundle.
 */
internal data class NoticeEntry(
    val ownerPluginId: String,
    val sourceLabel: String,
    val title: String?,
    val body: String?,
    val lines: List<String> = emptyList(),
    val atWallMs: Long,
    val destination: PageDestination? = null,
    val eventKey: String? = null,
    val conversation: Boolean = false,
    val icon: IconRef? = null,
    /** The page the generic preview opens: the hub's notice page, unless a caller named another. */
    val pageId: String = RootStops.NOTICE_PAGE_ID,
) {
    /** The one line a preview shows: the first of title, body and lines that says something. */
    val text: String
        get() = listOfNotNull(title, body, lines.firstOrNull()).firstOrNull { it.isNotBlank() }.orEmpty()

    /**
     * The message of a conversation event, under its sender's name. A one-to-one thread's lines
     * all start `<title>: `, the name the card already shows above them, and lose that prefix; a
     * group's lines name other senders than its title and keep theirs.
     */
    val message: String?
        get() {
            if (title == null) return null
            val text = withoutRepeatedSender(title, body?.lines() ?: lines)
                ?.joinToString(if (body != null) "\n" else " ")
                ?: body
                ?: lines.joinToString(" ")
            return text.takeIf(String::isNotBlank)
        }

    /** The exact thread a conversation event belongs to: its event key, by the provider's contract. */
    val threadKey: String? get() = eventKey.takeIf { conversation && destination != null }

    companion object {
        /** An arrival described only by its preview, as older callers report one. */
        fun of(preview: NoticePreview, atWallMs: Long): NoticeEntry = NoticeEntry(
            ownerPluginId = "",
            sourceLabel = preview.sourceLabel.orEmpty(),
            title = null,
            body = preview.text,
            atWallMs = preview.atWallMs ?: atWallMs,
            pageId = preview.pageId,
        )
    }
}

internal data class HistoryEntry(val id: String, val notice: NoticeEntry, val state: HistoryState = HistoryState.NEW) {
    /** Not withdrawn and not answered from here: what the root still offers to reply to. */
    val live: Boolean get() = state == HistoryState.NEW || state == HistoryState.SEEN
}

/** How the root's one notification stop presents its entry or entries. */
internal enum class NoticeCardKind { GENERIC, CONVERSATION, AGGREGATE }

/**
 * What the root's notification stop stands for. A conversation card names one entry for the whole
 * session and only its [state] follows it; an aggregate names an owner and [threads] of it.
 */
internal data class NoticeCard(
    val kind: NoticeCardKind,
    val entryId: String?,
    val ownerPluginId: String?,
    val state: HistoryState = HistoryState.NEW,
    val secondary: String? = null,
    val threads: Int = 0,
)

/**
 * The glasses' notification history (plan 028a §6.3): in memory only, never persisted, newest
 * first, at most [NoticeSurfaceContract.MAX_HISTORY_ENTRIES] entries, each displayed for at most
 * [NoticeSurfaceContract.MAX_HISTORY_AGE_MS]. That age is a display bound, never a reply lease.
 */
internal data class NotificationHistory(
    val entries: List<HistoryEntry> = emptyList(),
    val nextSeq: Long = 1,
) {
    fun add(notice: NoticeEntry): NotificationHistory = copy(
        entries = (listOf(HistoryEntry("n$nextSeq", notice)) + entries).take(NoticeSurfaceContract.MAX_HISTORY_ENTRIES),
        nextSeq = nextSeq + 1,
    )

    /** What may be shown now: younger than the display bound (an entry from a later clock stays). */
    fun visible(nowWallMs: Long): List<HistoryEntry> = entries.filter { isVisible(it, nowWallMs) }

    private fun isVisible(entry: HistoryEntry, nowWallMs: Long): Boolean =
        nowWallMs - entry.notice.atWallMs < NoticeSurfaceContract.MAX_HISTORY_AGE_MS

    fun entry(id: String): HistoryEntry? = entries.firstOrNull { it.id == id }

    /** [entry], but only while it is still within the display bound. */
    fun visibleEntry(id: String, nowWallMs: Long): HistoryEntry? = entry(id)?.takeIf { isVisible(it, nowWallMs) }

    /** When the next entry shown now leaves the display bound, in wall time; null when none shows. */
    fun nextExpiryWallMs(nowWallMs: Long): Long? =
        visible(nowWallMs).minOfOrNull { it.notice.atWallMs }?.plus(NoticeSurfaceContract.MAX_HISTORY_AGE_MS)

    /** The event is no longer on the phone. Not proof of read or delivery, so nothing else changes. */
    fun withdraw(ownerPluginId: String, eventKey: String): NotificationHistory = map {
        if (it.notice.ownerPluginId == ownerPluginId && it.notice.eventKey == eventKey) it.copy(state = HistoryState.WITHDRAWN) else it
    }

    /** Revoke, disable, uninstall or a new signer: every entry of the owner goes. */
    fun purge(ownerPluginId: String): NotificationHistory =
        copy(entries = entries.filterNot { it.notice.ownerPluginId == ownerPluginId })

    /** The newest event of this exact thread left the phone, and no newer one came since. */
    fun threadWithdrawn(ownerPluginId: String, threadKey: String): Boolean =
        entries.firstOrNull { it.notice.ownerPluginId == ownerPluginId && it.notice.threadKey == threadKey }?.state == HistoryState.WITHDRAWN

    /** Opened in Nexus. A later state (handed off, withdrawn) is never rolled back to seen. */
    fun markSeen(id: String): NotificationHistory = map { if (it.id == id && it.state == HistoryState.NEW) it.copy(state = HistoryState.SEEN) else it }

    /** A reply to this exact thread was handed to its app. */
    fun markHandedOff(ownerPluginId: String, threadKey: String): NotificationHistory = map {
        if (it.notice.ownerPluginId == ownerPluginId && it.notice.threadKey == threadKey && it.live) {
            it.copy(state = HistoryState.HANDED_OFF)
        } else {
            it
        }
    }

    /**
     * The root's notification stop (plan 028a §6.4): an aggregate when the newest live
     * conversation entries span several threads of one owner, else a card for the newest live
     * conversation entry, else the generic preview of the newest non-withdrawn entry.
     */
    fun rootPreview(nowWallMs: Long): NoticePreview? {
        val visible = visible(nowWallMs).filter { it.state != HistoryState.WITHDRAWN }
        val newest = visible.firstOrNull() ?: return null
        val conversation = visible.firstOrNull { it.live && it.notice.threadKey != null } ?: return generic(newest)
        val owner = conversation.notice.ownerPluginId
        val threads = liveConversations(visible).takeWhile { it.notice.ownerPluginId == owner }.distinctBy { it.notice.threadKey }
        return if (threads.size > 1) aggregate(owner, threads) else card(conversation)
    }

    /** The aggregate for [ownerPluginId] as it is now, or null when none of its threads is live. */
    fun aggregateFor(ownerPluginId: String?, nowWallMs: Long): NoticePreview? {
        val threads = liveConversations(visible(nowWallMs))
            .filter { it.notice.ownerPluginId == ownerPluginId }
            .distinctBy { it.notice.threadKey }
        return if (threads.isEmpty() || ownerPluginId == null) null else aggregate(ownerPluginId, threads)
    }

    /**
     * The aggregate for [ownerPluginId] once none of its threads is live: only the threads still
     * within the display bound and not withdrawn; null when none is.
     */
    fun resolvedAggregateFor(ownerPluginId: String?, nowWallMs: Long): NoticePreview? {
        val threads = visible(nowWallMs)
            .filter { it.state != HistoryState.WITHDRAWN && it.notice.ownerPluginId == ownerPluginId && it.notice.threadKey != null }
            .distinctBy { it.notice.threadKey }
        return if (threads.isEmpty() || ownerPluginId == null) null else aggregate(ownerPluginId, threads)
    }

    /** The generic preview of one entry: its text, opening the hub's notice page on it. */
    fun generic(entry: HistoryEntry): NoticePreview = NoticePreview(
        pageId = entry.notice.pageId,
        text = entry.notice.text,
        sourceLabel = entry.notice.sourceLabel.ifBlank { null },
        atWallMs = entry.notice.atWallMs,
        paramsJson = entryParams(entry.id).takeIf { entry.notice.pageId == RootStops.NOTICE_PAGE_ID },
        card = NoticeCard(NoticeCardKind.GENERIC, entry.id, entry.notice.ownerPluginId.ifBlank { null }, entry.state),
        icon = entry.notice.icon,
    )

    private fun card(entry: HistoryEntry): NoticePreview {
        val destination = entry.notice.destination ?: return generic(entry)
        return NoticePreview(
            pageId = destination.pageId,
            text = entry.notice.title?.takeIf(String::isNotBlank) ?: entry.notice.text,
            sourceLabel = entry.notice.sourceLabel.ifBlank { null },
            atWallMs = entry.notice.atWallMs,
            paramsJson = destination.paramsJson,
            card = NoticeCard(
                NoticeCardKind.CONVERSATION, entry.id, entry.notice.ownerPluginId, entry.state, secondary = entry.notice.message,
            ),
            icon = entry.notice.icon,
        )
    }

    private fun aggregate(owner: String, threads: List<HistoryEntry>): NoticePreview {
        val newest = threads.first()
        return NoticePreview(
            pageId = PageTemplateContract.mainPageId(owner),
            text = if (threads.size == 1) "1 conversation" else "${threads.size} conversations",
            sourceLabel = newest.notice.sourceLabel.ifBlank { null },
            atWallMs = newest.notice.atWallMs,
            card = NoticeCard(
                NoticeCardKind.AGGREGATE, null, owner,
                secondary = threads.map { it.notice.title?.takeIf(String::isNotBlank) ?: it.notice.text }.joinToString(", "),
                threads = threads.size,
            ),
            icon = newest.notice.icon,
        )
    }

    private fun liveConversations(visible: List<HistoryEntry>) = visible.filter { it.live && it.notice.threadKey != null }

    private inline fun map(transform: (HistoryEntry) -> HistoryEntry): NotificationHistory {
        val next = entries.map(transform)
        return if (next == entries) this else copy(entries = next)
    }

    companion object {
        /** The hub notice page's parameter naming the entry it shows. */
        const val PARAM_ENTRY = "entry"

        fun entryParams(id: String): String = JSONObject().put(PARAM_ENTRY, id).toString()

        fun entryOf(paramsJson: String?): String? =
            paramsJson?.let { runCatching { JSONObject(it).optString(PARAM_ENTRY).ifBlank { null } }.getOrNull() }
    }
}
