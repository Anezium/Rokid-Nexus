package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination

internal enum class CaptureKind { DICTATE, TYPE }

/**
 * A composer capture in progress (plan 028a §7.3), bound to the conversation frame it was started
 * on: the session [generation], the wire [frameIndex], the page and its thread. [liveText] is the
 * phone's running text. [finishing] means the wearer tapped and the final text is awaited until
 * [deadlineMs]; nothing is ever sent to a conversation from a capture.
 */
internal data class Capture(
    val captureId: String,
    val kind: CaptureKind,
    val generation: Long,
    val frameIndex: Int,
    val pageId: String,
    val ownerPluginId: String,
    val threadKey: String,
    val destination: PageDestination,
    val recipient: String,
    val maxChars: Int,
    val liveText: String = "",
    val finishing: Boolean = false,
    val deadlineMs: Long? = null,
)

/** The composer button focused on a conversation frame; [key] is one of [ComposerRules]' keys. */
internal data class ComposerFocus(val key: String, val index: Int)

/** The hub's own line instead of the composer buttons. */
internal data class ComposerLine(val text: String, val sub: String?)

/**
 * What the composer offers on one conversation page: its buttons in order, the one a tap focuses
 * first, or the line saying why replying is unavailable here.
 */
internal data class ComposerView(
    val buttons: List<String>,
    val initialIndex: Int,
    val unavailable: ComposerLine?,
    /** The draft for this exact thread, whatever its state. */
    val threadDraft: Draft?,
) {
    val available: Boolean get() = unavailable == null
}

/** What the screens read about the composer beside the session state. */
internal data class ComposerFacts(
    val draft: Draft? = null,
    val capture: Capture? = null,
    val micUnavailable: Boolean = false,
    val pendingSend: PendingSend? = null,
    val handoffs: List<Handoff> = emptyList(),
)

/**
 * The composer's rules, shared by the reducer that acts and the screens that draw (plan 028a §7.2):
 * Send exists, first and focused first, only for a ready draft of this exact thread with no send
 * in flight; Type is focused first after an unconfirmed send, a lost draft reopened by a new
 * notice, or an unavailable microphone; Dictate otherwise.
 */
internal object ComposerRules {
    /** Spaces keep the hub's keys out of every wire id. */
    const val DICTATE = "compose dictate"
    const val TYPE = "compose type"
    const val SEND = "compose send"

    /** How long the final text of a finished capture is awaited before it is given up. */
    const val FINISH_TIMEOUT_MS = 8_000L

    const val REPLY_UNAVAILABLE = "Reply unavailable here"
    const val REPLY_FROM_PHONE = "reply from the phone"

    /** Null when the page offers no composer at all. */
    fun view(
        ownerPluginId: String?,
        body: PageBody.Conversation,
        draft: Draft?,
        micUnavailable: Boolean,
        pendingSend: PendingSend?,
    ): ComposerView? {
        val composer = body.composer ?: return null
        val threadDraft = draft?.takeIf { it.isFor(ownerPluginId, body.threadKey) }
        val unavailable = when {
            !composer.replyable -> ComposerLine(composer.unavailableText ?: REPLY_UNAVAILABLE, composer.unavailableSub ?: REPLY_FROM_PHONE)
            threadDraft?.state == DraftState.AUTHORITY_LOST && !threadDraft.reopened -> ComposerLine(REPLY_UNAVAILABLE, REPLY_FROM_PHONE)
            else -> null
        }
        val sendable = threadDraft?.state == DraftState.READY && pendingSend == null && unavailable == null
        val buttons = listOfNotNull(SEND.takeIf { sendable }, DICTATE, TYPE)
        val typeFirst = threadDraft?.state == DraftState.UNCONFIRMED ||
            (threadDraft?.state == DraftState.AUTHORITY_LOST && threadDraft.reopened) || micUnavailable
        val initial = when {
            sendable -> buttons.indexOf(SEND)
            typeFirst -> buttons.indexOf(TYPE)
            else -> buttons.indexOf(DICTATE)
        }
        return ComposerView(buttons, initial, unavailable, threadDraft)
    }

    /** A capture id the contract accepts, fresh for every capture this process starts. */
    fun captureId(generation: Long, sequence: Long): String {
        val id = "nexus-capture-$generation-$sequence"
        check(ComposeContract.isValidCaptureId(id)) { "capture id out of contract" }
        return id
    }

    /** What a reply [text] sent from [body] remembers of it, to tell its page's own copy of it later. */
    fun shown(body: PageBody.Conversation, text: String): ShownMessages =
        Echoes(body).behind(ShownMessages(emptySet()), text, body.messages.map { it.id })

    /**
     * [body]'s own copies of replies sent from its thread. A reply's copy is a message of ours with
     * the same words, spacing aside, after the newest message its page showed when it was sent:
     * the provider records a reply after what it answered, so a message that was merely older, or
     * anyone else's, never stands for it. The page's earlier messages of ours in those words keep
     * their place but not always their id (Relay replaces its record of a reply with the app's own
     * copy), so the newest message shown is the newest of the others, and the messages in those
     * words after it stand first for the earlier ones it was followed by. Each copy stands for one
     * reply, taken oldest reply first.
     */
    class Echoes(private val body: PageBody.Conversation) {
        private val taken = HashSet<String>()

        /** The id of [body]'s copy of the reply [text] sent from a page that showed [shown], now taken; null when none. */
        fun take(text: String, shown: ShownMessages): String? {
            val messages = body.messages
            val said = normalized(text)
            val anchor = messages.indexOfLast { it.id in shown.ids && it.id !in shown.twins }
            val anchorId = messages.getOrNull(anchor)?.id
            val earlier = shown.ids.reversed().takeWhile { it != anchorId }.count { it in shown.twins }
            val echo = messages.subList(anchor + 1, messages.size)
                .filter { it.says(said) }
                .drop(earlier)
                .firstOrNull { it.id !in taken }
                ?: return null
            taken += echo.id
            return echo.id
        }

        /** [shown], with [ids] of [body] shown too, as the reply [text] counts them. */
        fun behind(shown: ShownMessages, text: String, ids: Collection<String>): ShownMessages {
            val said = normalized(text)
            val twins = body.messages.filter { it.id in ids && it.says(said) }.map { it.id }
            return ShownMessages(shown.ids + ids, shown.twins + twins)
        }
    }

    private fun ConversationMessage.says(said: String): Boolean = role == ConversationRole.OURS && normalized(text) == said

    private fun normalized(text: String): String = text.trim().replace(WHITESPACE, " ")

    private val WHITESPACE = Regex("\\s+")

    /** The phone keyboard's ceiling: the page composer's, within the contract's. */
    fun maxChars(body: PageBody.Conversation): Int =
        (body.composer?.maxChars ?: ComposeContract.MAX_TEXT_CHARS).coerceIn(1, ComposeContract.MAX_TEXT_CHARS)
}
