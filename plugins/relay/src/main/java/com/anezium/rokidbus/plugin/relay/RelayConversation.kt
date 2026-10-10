package com.anezium.rokidbus.plugin.relay

import java.security.MessageDigest

/**
 * Who wrote a captured message, as far as the notification itself says. A messaging-style
 * message from someone else is [RECEIVED]; one from the device's user, or a reply the app lists
 * in its remote-input history, is [OURS]. Plain notification text names no author, so it is
 * [UNKNOWN] rather than guessed onto a side.
 */
internal enum class RelayMessageRole {
    RECEIVED,
    OURS,
    UNKNOWN,
}

/** One captured message. [sender] is the other person's name, only ever on a received message. */
internal data class RelayMessage(
    val role: RelayMessageRole,
    val text: String,
    val sender: String? = null,
)

/** What Relay honestly knows about a reply it handed to the app; never a delivery or read receipt. */
internal enum class RelayHandoffStatus {
    /** `PendingIntent.send` returned: the app has the text. */
    HANDED_OFF,

    /** The send failed in a way that does not say whether the app got the text. */
    UNCONFIRMED,
}

/**
 * A reply Relay handed to the app from this process. [afterMessageId] is the transcript id of the
 * newest captured message when it was handed off, which is where it reads in the thread; null when
 * the thread had none.
 */
internal data class RelayHandoff(
    val serial: Long,
    val text: String,
    val status: RelayHandoffStatus,
    val afterMessageId: String?,
)

/** A captured message or one of our handoffs, in thread order, with an id stable across captures. */
internal data class RelayTranscriptEntry(
    val id: String,
    val role: RelayMessageRole,
    val text: String,
    val sender: String? = null,
    val status: RelayHandoffStatus? = null,
)

/**
 * The thread as it reads: what the notification captured, with Relay's own handoffs placed after
 * the message they answered. No time is invented for anything; order is all there is.
 *
 * Captured message ids depend only on who said what and how many identical messages came before,
 * so they stay the same when an older message falls off the start or the app reposts the thread.
 */
internal object RelayTranscript {
    fun messageIds(messages: List<RelayMessage>): List<String> {
        val seen = mutableMapOf<String, Int>()
        return messages.map { message ->
            val key = digest(message.role.name + "\u0000" + message.sender.orEmpty() + "\u0000" + message.text).take(16)
            val occurrence = seen.merge(key, 1, Int::plus)!!
            "m-$key-$occurrence"
        }
    }

    /**
     * Handoffs go right after the message they followed. One whose message is no longer captured
     * is older than everything still shown, so it reads first.
     */
    fun merge(messages: List<RelayMessage>, handoffs: List<RelayHandoff>): List<RelayTranscriptEntry> {
        val ids = messageIds(messages)
        val anchored = handoffs.sortedBy(RelayHandoff::serial).groupBy { anchorIndex(ids, it) }
        return buildList {
            anchored[-1].orEmpty().forEach { add(entryOf(it)) }
            messages.forEachIndexed { index, message ->
                add(RelayTranscriptEntry(ids[index], message.role, message.text, message.sender))
                anchored[index].orEmpty().forEach { add(entryOf(it)) }
            }
        }
    }

    /**
     * The handoffs whose text a newer capture now shows as ours after the message they followed:
     * the app's own copy of the reply replaces Relay's. Each captured message stands for one
     * handoff at most.
     */
    fun superseded(messages: List<RelayMessage>, handoffs: List<RelayHandoff>): Set<Long> {
        val ids = messageIds(messages)
        val consumed = mutableSetOf<Int>()
        val gone = mutableSetOf<Long>()
        handoffs.sortedBy(RelayHandoff::serial).forEach { handoff ->
            val wanted = normalized(handoff.text)
            val match = (anchorIndex(ids, handoff) + 1 until messages.size).firstOrNull { index ->
                index !in consumed &&
                    messages[index].role == RelayMessageRole.OURS &&
                    normalized(messages[index].text) == wanted
            }
            if (match != null) {
                consumed += match
                gone += handoff.serial
            }
        }
        return gone
    }

    /** The newest handoffs within [maxEntries] and [maxChars]; the newest one always stays. */
    fun bounded(log: List<RelayHandoff>, maxEntries: Int, maxChars: Int): List<RelayHandoff> {
        val kept = ArrayDeque(log.takeLast(maxEntries.coerceAtLeast(1)))
        var chars = kept.sumOf { it.text.length }
        while (kept.size > 1 && chars > maxChars) chars -= kept.removeFirst().text.length
        return kept.toList()
    }

    fun normalized(text: String): String = text.trim().replace(WHITESPACE, " ")

    private fun anchorIndex(ids: List<String>, handoff: RelayHandoff): Int =
        handoff.afterMessageId?.let(ids::indexOf) ?: -1

    private fun entryOf(handoff: RelayHandoff) = RelayTranscriptEntry(
        id = "h-${handoff.serial}",
        role = RelayMessageRole.OURS,
        text = handoff.text,
        status = handoff.status,
    )

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private val WHITESPACE = Regex("\\s+")
}
