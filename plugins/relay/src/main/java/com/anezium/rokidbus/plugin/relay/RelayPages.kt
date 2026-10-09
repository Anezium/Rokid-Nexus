package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageTarget
import java.security.MessageDigest

/**
 * Relay's read-only pages: `relay:main` lists the inbox's threads, and each thread is a document
 * of its messages as the inbox reads them. Nothing here replies, speaks, marks read or touches a
 * notification. Thread ids are opaque digests of the inbox id, so two threads never share a key.
 * Times are the capture time the inbox keeps; there is no per-message time to show.
 */
internal object RelayPages {
    const val MAIN = "relay:main"
    const val THREAD_PREFIX = "relay:thread:"

    const val TITLE_CHARS = 48
    private const val LABEL_CHARS = 120
    private const val DETAIL_CHARS = 240
    private const val SEGMENT_CHARS = 4_096
    private const val MAX_SEGMENTS = 240
    private const val MAX_ROWS = 64

    /**
     * The serialized UTF-8 bytes the document's segments may take. The whole answer must fit
     * 65,536 bytes with its correlation, the hub's identity stamps and the title, so this leaves
     * a fixed margin for them rather than measuring after the fact.
     */
    const val SEGMENT_BUDGET_BYTES = 60_000

    const val OMITTED_TEXT = "Earlier messages are not shown."
    const val MISSING_TEXT = "This conversation is no longer on the phone."
    const val EMPTY_LIST_TEXT = "No conversations yet"

    fun threadPageId(entryId: String): String = THREAD_PREFIX + digest(entryId)

    fun page(
        pageId: String,
        revision: Long,
        entries: List<RelayInboxEntry>,
        hidePreviews: Boolean,
        formatTime: (Long) -> String,
    ): NexusPage = when {
        pageId == MAIN -> NexusPage.Content(revision, "Relay", list(entries, hidePreviews, formatTime), live = true)
        pageId.startsWith(THREAD_PREFIX) -> thread(pageId, revision, entries, formatTime)
        else -> NexusPage.Unavailable
    }

    /** The list follows the inbox's hidden-preview setting; a sensitive body is already redacted. */
    private fun list(entries: List<RelayInboxEntry>, hidePreviews: Boolean, formatTime: (Long) -> String): PageBody {
        val rows = entries.take(MAX_ROWS).map { entry ->
            val snapshot = entry.snapshot
            val preview = RelayInboxCatalog.previewFor(entry, width = DETAIL_CHARS - 8, hidden = hidePreviews)
            PageRow(
                id = "t-" + digest(entry.id),
                label = clip(senderOf(snapshot), LABEL_CHARS),
                sub = clip(listOf(formatTime(snapshot.capturedAtMs), preview).filter(String::isNotBlank).joinToString(" · "), DETAIL_CHARS),
                target = PageTarget.Page(threadPageId(entry.id)),
            )
        }
        return PageBody.SelectableList(rows, emptyText = EMPTY_LIST_TEXT)
    }

    /**
     * A thread opened explicitly reads as the inbox reads it. When it does not fit the page, the
     * oldest whole messages go first and the page says so; the newest are always kept.
     */
    private fun thread(pageId: String, revision: Long, entries: List<RelayInboxEntry>, formatTime: (Long) -> String): NexusPage {
        val entry = entries.firstOrNull { threadPageId(it.id) == pageId }
            ?: return NexusPage.Content(
                revision,
                "Relay",
                PageBody.Document(listOf(PageSegment("missing", PageSegmentKind.ASIDE, MISSING_TEXT))),
                live = true,
            )
        val snapshot = entry.snapshot
        val header = PageSegment("captured", PageSegmentKind.HEADER, clip("${snapshot.appLabel.trim().ifBlank { "Relay" }} · captured ${formatTime(snapshot.capturedAtMs)}", SEGMENT_CHARS))
        val title = senderOf(snapshot)
        val messages = RelayInboxCatalog.threadMessages(snapshot.renderedText)
        val solo = messages.all { it.speaker.isBlank() || it.speaker.equals(title, ignoreCase = true) }
        // Each message becomes its segments under ids that only depend on what was said, so they
        // stay the same when older messages fall off the start.
        val seen = mutableMapOf<String, Int>()
        val groups = messages.map { message ->
            val key = digest(message.speaker + "\u0000" + message.text).take(16)
            val occurrence = seen.merge(key, 1, Int::plus)!!
            val id = "m-$key-$occurrence"
            buildList {
                if (!solo && message.speaker.isNotBlank()) add(PageSegment("$id-who", PageSegmentKind.HEADER, clip(message.speaker, SEGMENT_CHARS)))
                chunks(message.text.ifBlank { " " }).forEachIndexed { index, part ->
                    add(PageSegment(if (index == 0) id else "$id-$index", PageSegmentKind.PROSE, part))
                }
            }
        }
        var budget = SEGMENT_BUDGET_BYTES - bytesOf(header) - bytesOf(PageSegment("omitted", PageSegmentKind.ASIDE, OMITTED_TEXT))
        var room = MAX_SEGMENTS - 2
        val kept = ArrayDeque<List<PageSegment>>()
        for (group in groups.asReversed()) {
            val cost = group.sumOf(::bytesOf)
            if (cost > budget || group.size > room) break
            kept.addFirst(group)
            budget -= cost
            room -= group.size
        }
        val omitted = kept.size < groups.size
        val segments = buildList {
            add(header)
            if (omitted) add(PageSegment("omitted", PageSegmentKind.ASIDE, OMITTED_TEXT))
            kept.forEach { addAll(it) }
            if (groups.isEmpty()) add(PageSegment("empty", PageSegmentKind.ASIDE, "No message text"))
        }
        return NexusPage.Content(revision, clip(title, TITLE_CHARS), PageBody.Document(segments), live = true)
    }

    private fun senderOf(snapshot: RelayInboxSnapshot): String =
        snapshot.sender.trim().ifBlank { snapshot.appLabel.trim() }.ifBlank { "Unknown" }

    /** A message longer than one segment is split, never cut, at a safe UTF-16 boundary. */
    private fun chunks(text: String): List<String> {
        val parts = mutableListOf<String>()
        var rest = text
        while (rest.length > SEGMENT_CHARS) {
            var end = SEGMENT_CHARS
            if (Character.isHighSurrogate(rest[end - 1])) end -= 1
            parts += rest.substring(0, end)
            rest = rest.substring(end)
        }
        parts += rest
        return parts
    }

    /** An upper bound on a segment's serialized UTF-8 bytes: its JSON object with every escape. */
    fun bytesOf(segment: PageSegment): Int =
        SEGMENT_OVERHEAD + segment.id.length + segment.kind.wire.length + jsonBytes(segment.text)

    private fun jsonBytes(text: String): Int {
        var bytes = 0
        var index = 0
        while (index < text.length) {
            val char = text[index]
            bytes += when {
                char == '"' || char == '\\' -> 2
                char.code < 0x20 -> 6
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                Character.isHighSurrogate(char) && index + 1 < text.length && Character.isLowSurrogate(text[index + 1]) -> {
                    index += 1
                    4
                }
                // A lone surrogate is escaped by a JSON writer.
                Character.isSurrogate(char) -> 6
                else -> 3
            }
            index += 1
        }
        return bytes
    }

    private fun clip(value: String, limit: Int): String {
        if (value.length <= limit) return value
        var end = limit - 1
        if (Character.isHighSurrogate(value[end - 1])) end -= 1
        return value.substring(0, end).trimEnd() + "…"
    }

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    // {"id":"","kind":"","text":""} plus the separating comma.
    private const val SEGMENT_OVERHEAD = 30
}

/**
 * Publishes Relay's visible pages again when the inbox changes: only to a current visible
 * request, at most two revisions a second, never after a cover, a close or a loss. Revisions
 * only go up, and always above the floor the glasses retain: this provider rebuilds a page without
 * knowing that it matches what they kept, for instance after it was destroyed and bound cold again.
 */
internal class RelayPageProvider<R : Any>(
    private val pageIdOf: (R) -> String,
    private val floorOf: (R) -> Long?,
    private val publish: (R, NexusPage) -> NexusSdkResult,
    private val build: (pageId: String, revision: Long) -> NexusPage,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val cancelScheduled: () -> Unit,
) {
    private var revision = 0L
    private val current = linkedMapOf<String, R>()
    private val visible = mutableSetOf<String>()
    private var lastPublishAt: Long? = null
    private var dirty = false

    fun onRequest(request: R): NexusPage {
        val pageId = pageIdOf(request)
        current[pageId] = request
        return build(pageId, nextRevision(floorOf(request)))
    }

    fun onVisibility(pageId: String, isVisible: Boolean) {
        if (pageId !in current) return
        if (isVisible) visible += pageId else visible -= pageId
        if (visible.isEmpty()) cancelScheduled() else if (dirty) inboxChanged()
    }

    fun onClosed(pageId: String) {
        current.remove(pageId)
        visible -= pageId
        if (visible.isEmpty()) cancelScheduled()
    }

    /** A capture changed the inbox: revise the visible pages now, or once the half-second has passed. */
    fun inboxChanged() {
        if (current.isEmpty()) return
        dirty = true
        if (visible.isEmpty()) return
        val last = lastPublishAt
        val wait = if (last == null) 0L else (last + MIN_INTERVAL_MS - now()).coerceAtLeast(0)
        if (wait > 0) {
            cancelScheduled()
            schedule(wait) { inboxChanged() }
            return
        }
        visible.mapNotNull { pageId -> current[pageId]?.let { pageId to it } }.forEach { (pageId, request) ->
            publish(request, build(pageId, nextRevision(null)))
        }
        dirty = false
        lastPublishAt = now()
    }

    fun forget() {
        cancelScheduled()
        current.clear()
        visible.clear()
        dirty = false
    }

    private fun nextRevision(floor: Long?): Long {
        // An equal revision would keep the glasses' old body; only identical content may reuse it.
        revision = maxOf(revision + 1, (floor ?: -1) + 1)
        return revision
    }

    companion object {
        const val MIN_INTERVAL_MS = 500L
    }
}
