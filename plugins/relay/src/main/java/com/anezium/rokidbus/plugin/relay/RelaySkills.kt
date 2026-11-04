package com.anezium.rokidbus.plugin.relay

import android.content.Context
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.UUID

internal sealed interface RelaySkillOutcome {
    data class Completed(val data: JSONObject) : RelaySkillOutcome
    data object Accepted : RelaySkillOutcome
    data class Failed(val code: String) : RelaySkillOutcome
    data object Unknown : RelaySkillOutcome
}

/**
 * Replies waiting for the wearer's Send, in memory only. Each names the thread, app and sender the
 * wearer will confirm and holds no reply token: the send asks for the live one.
 */
internal class RelayReplyDrafts(
    private val clock: () -> Long = android.os.SystemClock::elapsedRealtime,
    private val ids: () -> String = { UUID.randomUUID().toString() },
) {
    class Draft(
        val threadId: String,
        val packageName: String,
        val senderTitle: String,
        val text: String,
        val expiresAtMs: Long,
    )

    private val drafts = linkedMapOf<String, Draft>()

    @Synchronized
    fun put(threadId: String, reply: ReplyRepository.PendingReply, text: String): String {
        prune()
        val id = ids()
        drafts[id] = Draft(threadId, reply.content.packageName, reply.content.title, text, clock() + DRAFT_LIFETIME_MS)
        while (drafts.size > MAX_DRAFTS) drafts.remove(drafts.keys.first())
        return id
    }

    /** Taken before the send it authorizes: whatever becomes of it, a draft cannot go twice. */
    @Synchronized
    fun take(id: String): Draft? {
        prune()
        return drafts.remove(id)
    }

    private fun prune() {
        val now = clock()
        drafts.values.removeAll { it.expiresAtMs <= now }
    }

    private companion object {
        const val DRAFT_LIFETIME_MS = 120_000L
        const val MAX_DRAFTS = 8
    }
}

/**
 * Relay's operations for an approved caller. Nothing here reads a message body back, and a thread
 * must be live, repliable and shown by name under Relay's own settings to exist at all. Only
 * [SEND_REPLY] has an effect, and the caller reaches it with a draft this class handed out.
 */
internal class RelaySkills(
    private val context: Context,
    private val hasAccess: () -> Boolean = NotificationControl::isListenerConnected,
    private val drafts: RelayReplyDrafts = sharedDrafts,
    private val wallClock: () -> Long = System::currentTimeMillis,
) {
    private val settings by lazy { RelaySettings(context) }

    fun run(operation: String, arguments: JSONObject): RelaySkillOutcome {
        if (operation !in OPERATIONS) return RelaySkillOutcome.Failed(SkillErrorCodes.UNSUPPORTED_OPERATION)
        if (!hasAccess()) return RelaySkillOutcome.Failed(SkillErrorCodes.SETUP_REQUIRED)
        return when (operation) {
            LIST_REPLY_THREADS -> list(arguments.optString("name"))
            PREPARE_REPLY -> prepare(arguments.optString("thread"), arguments.optString("text"))
            else -> send(arguments.optString("draft"))
        }
    }

    private fun list(name: String): RelaySkillOutcome {
        val wanted = fold(name)
        val threads = if (settings.hideInboxPreviews()) emptyList() else ReplyRepository.replyableThreads(settings)
        val rows = threads
            .filter { wanted.isEmpty() || fold(it.content.title).contains(wanted) }
            .sortedByDescending { it.capturedAtMs }
            .take(MAX_LISTED_THREADS)
        return RelaySkillOutcome.Completed(
            JSONObject().put(
                "threads",
                JSONArray().apply {
                    rows.forEach { thread ->
                        put(
                            JSONObject()
                                .put("thread", thread.id)
                                .put("sender", thread.content.title.take(MAX_SENDER_CHARS))
                                .put("app", thread.content.appLabel.take(MAX_APP_CHARS))
                                .put("age_minutes", ((wallClock() - thread.capturedAtMs) / MINUTE_MS).coerceIn(0L, MAX_AGE_MINUTES)),
                        )
                    }
                },
            ),
        )
    }

    private fun prepare(threadId: String, rawText: String): RelaySkillOutcome {
        val text = rawText.trim()
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return RelaySkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        if (settings.hideInboxPreviews()) return RelaySkillOutcome.Failed(AUTHORITY_LOST)
        val reply = ReplyRepository.replyableThreads(settings).firstOrNull { it.id == threadId }
            ?: return RelaySkillOutcome.Failed(AUTHORITY_LOST)
        return RelaySkillOutcome.Completed(
            JSONObject()
                .put("draft", drafts.put(threadId, reply, text))
                .put("recipient", reply.content.title.take(MAX_SENDER_CHARS))
                .put("via", reply.content.appLabel.take(MAX_APP_CHARS))
                .put("text", text),
        )
    }

    private fun send(draftId: String): RelaySkillOutcome {
        val draft = drafts.take(draftId) ?: return RelaySkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
        val result = ReplyRepository.sendDrafted(context, draft.threadId, draft.packageName, draft.senderTitle, draft.text)
        return when (result) {
            ReplySendResult.Sent -> RelaySkillOutcome.Accepted
            ReplySendResult.Missing,
            ReplySendResult.Blank,
            ReplySendResult.NoFreeFormInput,
            -> RelaySkillOutcome.Failed(AUTHORITY_LOST)
            is ReplySendResult.Failed ->
                if (result.authorityLost) RelaySkillOutcome.Failed(AUTHORITY_LOST) else RelaySkillOutcome.Unknown
        }
    }

    private fun fold(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

    companion object {
        const val LIST_REPLY_THREADS = "list_reply_threads"
        const val PREPARE_REPLY = "prepare_reply"
        const val SEND_REPLY = "send_reply"
        private val OPERATIONS = setOf(LIST_REPLY_THREADS, PREPARE_REPLY, SEND_REPLY)

        const val AUTHORITY_LOST = "authority_lost"
        const val MAX_TEXT_CHARS = 1_000
        const val MAX_LISTED_THREADS = 10
        private const val MAX_SENDER_CHARS = NoticeSurfaceContract.MAX_TITLE_CHARS
        private const val MAX_APP_CHARS = 40
        private const val MINUTE_MS = 60_000L
        private const val MAX_AGE_MINUTES = 1_000_000L
        private val COMBINING_MARKS = Regex("\\p{M}+")

        /** Per process, not per binding: the hub unbinds a provider between prepare and send. */
        private val sharedDrafts = RelayReplyDrafts()
    }
}
