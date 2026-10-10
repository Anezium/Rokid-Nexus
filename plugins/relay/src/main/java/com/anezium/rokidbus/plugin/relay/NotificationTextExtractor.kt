package com.anezium.rokidbus.plugin.relay

import android.app.Notification
import android.app.Person
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import java.util.Locale

internal data class ExtractedMessage(
    val text: String?,
    val sender: String?,
    val timestamp: Long,
    /** Written by the device's user: no sender person, or the messaging user's own. */
    val fromSelf: Boolean = false,
)

internal data class NotificationTextInput(
    val messages: List<ExtractedMessage> = emptyList(),
    val expandedLines: List<String?> = emptyList(),
    val bigText: String? = null,
    val text: String? = null,
    /** `EXTRA_REMOTE_INPUT_HISTORY`: replies already sent from this notification, newest first. */
    val remoteInputHistory: List<String?> = emptyList(),
)

/**
 * A capture's text both ways: [rendered] is the flattened string the band, the inbox and the
 * document pages have always read, and [messages] the same thread with who wrote each message.
 */
internal data class ExtractedThread(
    val rendered: String,
    val messages: List<RelayMessage>,
)

internal object NotificationTextExtractor {
    fun extract(input: NotificationTextInput, messageLimit: Int): String =
        extractThread(input, messageLimit).rendered

    /**
     * Both forms come from the same source the rendered string always chose, so the messages never
     * show something the band did not. Only messaging-style messages carry an author; lines and
     * plain text are [RelayMessageRole.UNKNOWN]. The app's remote-input history adds our recent
     * replies it does not already list, after the rest, where Android itself draws them.
     */
    fun extractThread(input: NotificationTextInput, messageLimit: Int): ExtractedThread {
        val limit = messageLimit.coerceIn(
            RelaySettings.MIN_MESSAGES_PER_THREAD,
            RelaySettings.MAX_MESSAGES_PER_THREAD,
        )
        val kept = orderedMessages(input.messages).takeLast(limit)
        val messages = messagingStyleText(kept)
        val lines = expandedTextLines(input.expandedLines, limit)
        val (selected, structured) = when {
            messages.isNotBlank() && shouldPreferExpandedLines(messages, lines) -> lines to unknownLines(lines)
            messages.isNotBlank() -> messages to authoredMessages(kept)
            lines.isNotBlank() -> lines to unknownLines(lines)
            !input.bigText.isNullOrBlank() -> input.bigText to unknownLines(input.bigText, splitLines = false)
            else -> input.text.orEmpty() to unknownLines(input.text.orEmpty(), splitLines = false)
        }
        val thread = withRemoteInputHistory(structured, input.remoteInputHistory, limit)
        return ExtractedThread(
            rendered = trimFromTop(selected, NoticeSurfaceContract.MAX_BODY_CHARS),
            messages = boundedMessages(thread, limit, NoticeSurfaceContract.MAX_BODY_CHARS),
        )
    }

    fun shouldPreferExpandedLines(primary: String, lines: String): Boolean {
        if (lines.isBlank()) return false
        val cleanPrimary = primary.normalizedForComparison()
        val cleanLines = lines.normalizedForComparison()
        if (cleanLines.isBlank() || cleanLines == cleanPrimary) return false
        if (primary.looksLikeCollapsedMessageSummary()) return true
        return lines.count { it == '\n' } >= primary.count { it == '\n' } &&
            cleanLines.length > cleanPrimary.length + EXPANDED_LINES_DETAIL_MARGIN
    }

    /** Drops complete oldest lines first, then the oldest characters of one oversized message. */
    fun trimFromTop(value: String, maxChars: Int): String {
        var remaining = value.trim()
        if (maxChars <= 0) return ""
        while (remaining.length > maxChars) {
            val firstBreak = remaining.indexOf('\n')
            if (firstBreak < 0) return remaining.takeLast(maxChars)
            remaining = remaining.substring(firstBreak + 1).trimStart()
        }
        return remaining
    }

    fun fromExtras(extras: Bundle): NotificationTextInput {
        val user = messagingUser(extras)
        val selfName = extras.getCharSequence(Notification.EXTRA_SELF_DISPLAY_NAME)?.toString()
        return NotificationTextInput(
            messages = messagingStyleMessages(extras).map { message ->
                val sender = message.senderPerson
                ExtractedMessage(
                    text = message.text?.toString(),
                    sender = sender?.name?.toString(),
                    timestamp = message.timestamp,
                    // Android's own rule: a message without a sender is the user's.
                    fromSelf = sender == null || isMessagingUser(sender, user, selfName),
                )
            },
            expandedLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
                .orEmpty()
                .map { it?.toString() },
            bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            remoteInputHistory = extras.getCharSequenceArray(Notification.EXTRA_REMOTE_INPUT_HISTORY)
                .orEmpty()
                .map { it?.toString() },
        )
    }

    /**
     * Whether [sender] is the conversation's own user. Keys decide when both sides have one;
     * otherwise the name must match the user's, which a contact sharing the user's exact display
     * name would also match.
     */
    private fun isMessagingUser(sender: Person, user: Person?, selfName: String?): Boolean {
        val senderName = sender.name?.toString()?.trim()
        if (user != null) {
            val senderKey = sender.key
            val userKey = user.key
            if (!senderKey.isNullOrBlank() && !userKey.isNullOrBlank()) return senderKey == userKey
            val userName = user.name?.toString()?.trim()
            if (!userName.isNullOrEmpty()) return senderName == userName
        }
        val self = selfName?.trim()
        return !self.isNullOrEmpty() && senderName == self
    }

    private fun messagingUser(extras: Bundle): Person? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON, Person::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelable(Notification.EXTRA_MESSAGING_PERSON)
        }

    fun messagingStyleMessages(extras: Bundle): List<Notification.MessagingStyle.Message> {
        val bundles = messageBundles(extras) ?: return emptyList()
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        if (messages.none { it.timestamp > 0L }) return messages
        return messages
            .mapIndexed { index, message -> index to message }
            .sortedWith(
                compareBy<Pair<Int, Notification.MessagingStyle.Message>> {
                    it.second.timestamp.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.first },
            )
            .map { it.second }
    }

    private fun orderedMessages(messages: List<ExtractedMessage>): List<ExtractedMessage> =
        if (messages.none { it.timestamp > 0L }) {
            messages
        } else {
            messages.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<ExtractedMessage>> {
                        it.value.timestamp.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE
                    }.thenBy { it.index },
                )
                .map { it.value }
        }

    private fun authoredMessages(kept: List<ExtractedMessage>): List<RelayMessage> =
        kept.mapNotNull { message ->
            val text = message.text?.trim().orEmpty()
            when {
                text.isBlank() -> null
                message.fromSelf -> RelayMessage(RelayMessageRole.OURS, text)
                else -> RelayMessage(RelayMessageRole.RECEIVED, text, message.sender?.trim()?.takeIf(String::isNotBlank))
            }
        }

    private fun unknownLines(value: String, splitLines: Boolean = true): List<RelayMessage> {
        val parts = if (splitLines) value.lines() else listOf(value)
        return parts.map(String::trim).filter(String::isNotBlank).map { RelayMessage(RelayMessageRole.UNKNOWN, it) }
    }

    /**
     * Appends the app's recorded replies that the thread does not already show, oldest first.
     * Each message already shown as ours, or without an author, accounts for one identical reply.
     */
    private fun withRemoteInputHistory(
        thread: List<RelayMessage>,
        history: List<String?>,
        limit: Int,
    ): List<RelayMessage> {
        val replies = history.mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }.take(limit)
        if (replies.isEmpty()) return thread
        val shown = thread
            .filter { it.role != RelayMessageRole.RECEIVED }
            .mapTo(mutableListOf<String>()) { RelayTranscript.normalized(it.text) }
        val added = replies.asReversed()
            .filterNot { shown.remove(RelayTranscript.normalized(it)) }
            .map { RelayMessage(RelayMessageRole.OURS, it) }
        return thread + added
    }

    /** The newest [limit] messages within [maxChars]; one oversized message keeps its newest end. */
    private fun boundedMessages(messages: List<RelayMessage>, limit: Int, maxChars: Int): List<RelayMessage> {
        val kept = ArrayDeque<RelayMessage>()
        var budget = maxChars
        for (message in messages.takeLast(limit).asReversed()) {
            if (message.text.length > budget) {
                if (kept.isEmpty()) kept.addFirst(message.copy(text = trimFromTop(message.text, budget)))
                break
            }
            kept.addFirst(message)
            budget -= message.text.length
        }
        return kept.toList()
    }

    private fun messagingStyleText(kept: List<ExtractedMessage>): String {
        return kept
            .mapNotNull { message ->
                val text = message.text?.trim().orEmpty()
                if (text.isBlank()) {
                    null
                } else {
                    val sender = message.sender?.trim().orEmpty()
                    if (sender.isBlank()) text else "$sender: $text"
                }
            }
            .joinToString("\n")
    }

    private fun expandedTextLines(lines: List<String?>, messageLimit: Int): String =
        lines
            .map { it?.trim().orEmpty() }
            .filter(String::isNotBlank)
            .takeLast(messageLimit)
            .joinToString("\n")

    private fun String.normalizedForComparison(): String =
        trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun String.looksLikeCollapsedMessageSummary(): Boolean {
        val normalized = normalizedForComparison()
        val tail = normalized.substringAfterLast(": ").trim()
        return COLLAPSED_MESSAGE_SUMMARY_PATTERNS.any { pattern ->
            pattern.matches(normalized) || pattern.matches(tail)
        }
    }

    private fun messageBundles(extras: Bundle): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    private const val EXPANDED_LINES_DETAIL_MARGIN = 24
    private val COLLAPSED_MESSAGE_SUMMARY_PATTERNS = listOf(
        Regex("""\d+\s+new\s+messages?"""),
        Regex("""\d+\s+messages?\s+from\s+\d+\s+(chats?|contacts?|conversations?)"""),
        Regex("""\d+\s+(chats?|contacts?|conversations?)\s+from\s+\d+\s+messages?"""),
    )
}
