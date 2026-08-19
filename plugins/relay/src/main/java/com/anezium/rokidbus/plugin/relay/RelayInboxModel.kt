package com.anezium.rokidbus.plugin.relay

internal enum class RelayReplyAvailability {
    REPLIABLE,
    READ_ONLY,
}

/** Text-only notification state. Live Android reply objects deliberately stay in ReplyRepository. */
internal data class RelayInboxSnapshot(
    val id: String,
    val sender: String,
    val appLabel: String,
    val renderedText: String,
    val capturedAtMs: Long,
)

internal data class RelayInboxEntry(
    val snapshot: RelayInboxSnapshot,
    val availability: RelayReplyAvailability,
) {
    val id: String
        get() = snapshot.id
}

/** Pure counterpart of NexusCardLine, kept Android-free for local JVM tests. */
internal object RelayInboxCatalog {
    // NexusCard accepts at most 64 rows. This is a data cap, not a viewport calculation.
    const val MAX_ENTRIES = 64
    const val MAX_CARD_LINES = 64
    const val MAX_CARD_LINE_CHARS = 240
    const val MAX_BADGE_CHARS = 24

    /**
     * How wide a list row may read, in monospace columns. Measured on the
     * optics at card body size, where 38 wrapped onto a second line and broke
     * the one-row-per-conversation rhythm the list depends on.
     *
     * Not a layout decision — the renderer still owns type, width and wrapping.
     * This is the width past which a row stops being scannable.
     */
    const val LIST_LINE_CHARS = 26

    /** The sub line is drawn smaller, so it holds more than the title above it. */
    const val PREVIEW_CHARS = 40

    /** Past this, a leading "word:" is prose with a colon in it, not a speaker. */
    const val MAX_SPEAKER_CHARS = 20

    fun entries(
        snapshots: Collection<RelayInboxSnapshot>,
        liveReplyIds: Set<String>,
        limit: Int = MAX_ENTRIES,
    ): List<RelayInboxEntry> {
        val boundedLimit = limit.coerceIn(0, MAX_ENTRIES)
        return snapshots
            .sortedWith(
                compareByDescending<RelayInboxSnapshot> { it.capturedAtMs }
                    .thenBy { it.id },
            )
            .distinctBy(RelayInboxSnapshot::id)
            .take(boundedLimit)
            .map { snapshot ->
                RelayInboxEntry(
                    snapshot = snapshot,
                    availability = if (snapshot.id in liveReplyIds) {
                        RelayReplyAvailability.REPLIABLE
                    } else {
                        RelayReplyAvailability.READ_ONLY
                    },
                )
            }
    }

    /**
     * The row's title: who the conversation is with.
     *
     * The preview lives in [previewFor] and travels as the row's `sub`, which
     * the HUD draws smaller and dimmer on its own line. Before the list rows
     * existed, both had to share one 26-column line and the result ellipsized
     * each into uselessness — "> Relay tes... Mika: Reply from the..." says
     * nothing twice.
     *
     * A trailing dot marks a thread whose live reply objects died with an
     * earlier process: still readable, no longer answerable.
     */
    fun lineFor(
        entry: RelayInboxEntry,
        selected: Boolean,
        width: Int = LIST_LINE_CHARS,
    ): String {
        val snapshot = entry.snapshot
        val sender = compact(snapshot.sender)
            .ifBlank { compact(snapshot.appLabel) }
            .ifBlank { "Unknown" }
        val unreachable = entry.availability != RelayReplyAvailability.REPLIABLE
        val mark = if (unreachable) " ·" else ""
        return fitWithEllipsis(sender, width - mark.length) + mark
    }

    /** One message of a thread: who said it, and what they said. */
    data class RelayThreadMessage(val speaker: String, val text: String)

    /**
     * Splits a rendered thread back into who-said-what.
     *
     * The extractor renders messaging-style threads as `"sender: text"` per
     * line, which is the right thing to send and the wrong thing to read: on a
     * band it becomes a wall where every line restates the name. Recovering the
     * speaker lets the HUD set it beside the message as a label, the way the
     * conversation actually looks.
     *
     * A line with no recognisable speaker keeps its whole text and no label —
     * plenty of apps send prose, and inventing a name for it would be worse than
     * leaving the column empty.
     */
    fun threadMessages(rendered: String): List<RelayThreadMessage> =
        rendered.lineSequence()
            .map(::compact)
            .filter(String::isNotBlank)
            .map { line ->
                val separator = line.indexOf(": ")
                val speaker = if (separator in 1..MAX_SPEAKER_CHARS) line.take(separator) else ""
                if (speaker.isBlank()) {
                    RelayThreadMessage("", line)
                } else {
                    RelayThreadMessage(speaker, line.substring(separator + 2).trim())
                }
            }
            .toList()

    /**
     * The newest thing said, for the row's second line. Empty if there is none.
     * When `hidden`, the row names the sender only — no message text — which is
     * the list's counterpart to the band's hidden state; opening the
     * conversation still reads it in full.
     */
    fun previewFor(entry: RelayInboxEntry, width: Int = PREVIEW_CHARS, hidden: Boolean = false): String {
        if (hidden) return fitWithEllipsis(RelayPrivacy.HIDDEN_BODY, width)
        val newest = entry.snapshot.renderedText
            .lineSequence()
            .map(::compact)
            .filter(String::isNotBlank)
            .lastOrNull()
            .orEmpty()
        return if (newest.isBlank()) "" else fitWithEllipsis(newest, width)
    }

    /**
     * Fits source message boundaries into the card wire field limit. This does not choose visual
     * pages or line wrapping; those remain renderer-owned.
     */
    fun cardLines(value: String): List<String> {
        val source = value
            .lineSequence()
            .map(::compact)
            .filter(String::isNotBlank)
            .toList()
            .ifEmpty { listOf("No message text") }
        return source
            .flatMap(::splitForCardContract)
            .takeLast(MAX_CARD_LINES)
    }

    private fun splitForCardContract(value: String): List<String> = buildList {
        var remaining = value
        while (remaining.length > MAX_CARD_LINE_CHARS) {
            val end = safeUtf16End(remaining, MAX_CARD_LINE_CHARS)
            add(remaining.substring(0, end))
            remaining = remaining.substring(end)
        }
        if (remaining.isNotEmpty()) add(remaining)
    }

    private fun fitWithEllipsis(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 3) return value.substring(0, safeUtf16End(value, maxChars))
        val end = safeUtf16End(value, maxChars - 3)
        return value.substring(0, end) + "..."
    }

    private fun safeUtf16End(value: String, requestedEnd: Int): Int {
        var end = requestedEnd.coerceIn(0, value.length)
        if (
            end in 1 until value.length &&
            Character.isHighSurrogate(value[end - 1]) &&
            Character.isLowSurrogate(value[end])
        ) {
            end -= 1
        }
        return end
    }

    private fun compact(value: String): String = value.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")
}

internal enum class RelayInboxView {
    LIST,
    THREAD,
}

internal enum class RelayInboxRefreshTarget {
    LIST,
    THREAD,
    NONE,
}

internal enum class RelayInboxBackResult {
    SHOW_LIST,
    CLOSE_SURFACE,
}

/** Selection/navigation only; speech and Android reply objects stay outside this pure state. */
internal class RelayInboxSelection {
    private var itemIds: List<String> = emptyList()

    var selectedIndex: Int = 0
        private set

    var openedThreadId: String? = null
        private set

    val view: RelayInboxView
        get() = if (openedThreadId == null) RelayInboxView.LIST else RelayInboxView.THREAD

    val selectedId: String?
        get() = itemIds.getOrNull(selectedIndex)

    fun liveRefreshTarget(
        changedItemId: String,
        canRefreshOpenedThread: Boolean,
    ): RelayInboxRefreshTarget = when {
        view == RelayInboxView.LIST -> RelayInboxRefreshTarget.LIST
        canRefreshOpenedThread && openedThreadId == changedItemId -> RelayInboxRefreshTarget.THREAD
        else -> RelayInboxRefreshTarget.NONE
    }

    fun reset(ids: List<String>) {
        itemIds = ids.distinct()
        selectedIndex = 0
        openedThreadId = null
    }

    fun replaceItems(ids: List<String>) {
        val previousSelection = selectedId
        itemIds = ids.distinct()
        selectedIndex = previousSelection
            ?.let(itemIds::indexOf)
            ?.takeIf { it >= 0 }
            ?: selectedIndex.coerceIn(0, (itemIds.size - 1).coerceAtLeast(0))
        if (openedThreadId != null && openedThreadId !in itemIds) {
            openedThreadId = null
        }
    }

    fun move(delta: Int): Boolean {
        if (view != RelayInboxView.LIST || itemIds.isEmpty() || delta == 0) return false
        selectedIndex = Math.floorMod(selectedIndex + delta, itemIds.size)
        return true
    }

    fun openSelected(): String? {
        if (view != RelayInboxView.LIST) return openedThreadId
        return selectedId?.also { openedThreadId = it }
    }

    fun back(): RelayInboxBackResult {
        if (openedThreadId != null) {
            openedThreadId = null
            return RelayInboxBackResult.SHOW_LIST
        }
        return RelayInboxBackResult.CLOSE_SURFACE
    }
}
