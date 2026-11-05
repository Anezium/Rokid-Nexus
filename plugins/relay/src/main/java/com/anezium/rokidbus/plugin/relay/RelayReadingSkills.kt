package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillLimits
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.text.Normalizer

/** Text is returned only by READ_THREAD, separately approved from discovery and sending. */
internal class RelayReadingSkills(
    private val settings: RelaySettings,
    private val wallClock: () -> Long,
) {
    fun list(name: String): RelaySkillOutcome.Completed {
        val wanted = fold(name)
        val rows = ReplyRepository.readableThreads(settings)
            .filter { wanted.isEmpty() || fold(it.snapshot.sender).contains(wanted) }
            .take(RelaySkills.MAX_LISTED_THREADS)
        return RelaySkillOutcome.Completed(JSONObject().put("threads", JSONArray().apply {
            rows.forEach { row ->
                put(metadata(row).put("thread", reference(row)))
            }
        }))
    }

    fun read(arguments: JSONObject): RelaySkillOutcome {
        val scope = arguments.optString("scope")
        if (scope != CONVERSATION && scope != LATEST_RECEIVED) {
            return RelaySkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        }
        val thread = ReplyRepository.readableThreads(settings)
            .firstOrNull { reference(it) == arguments.optString("thread") }
            ?: return RelaySkillOutcome.Failed(RelaySkills.AUTHORITY_LOST)
        val transcript = RelayTranscript.merge(thread.snapshot.messages, thread.snapshot.handoffs)
        val selected = if (scope == LATEST_RECEIVED) {
            transcript.lastOrNull { it.role != RelayMessageRole.OURS }?.let(::listOf).orEmpty()
        } else {
            transcript
        }
        val chunks = selected.flatMap(::chunks)
        // A cursor binds both the requested scope and this exact capture, without keeping text
        // in a second store. A changed thread requires a fresh read rather than mixed pages.
        val revision = digest(thread.id + scope + JSONArray(chunks).toString())
        val cursor = arguments.optString("cursor")
        val end = if (cursor.isEmpty()) chunks.size else {
            val index = cursor.substringAfter(':', "").toIntOrNull()
            if (cursor.substringBefore(':') != revision || index == null || index !in 1 until chunks.size) {
                return RelaySkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
            }
            index
        }
        fun page(start: Int): JSONObject = metadata(thread)
            .put("scope", scope)
            .put("coverage", "captured_notifications_only")
            .put("messages", JSONArray(chunks.subList(start, end)))
            .put("has_more", start > 0)
            .apply { if (start > 0) put("cursor", "$revision:$start") }

        var start = end
        while (start > 0 && end - start < SkillLimits.MAX_ARRAY_ITEMS) {
            if (page(start - 1).toString().toByteArray(Charsets.UTF_8).size > SkillLimits.MAX_RESULT_BYTES) break
            start--
        }
        if (start == end && end > 0) return RelaySkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        return RelaySkillOutcome.Completed(page(start))
    }

    private fun metadata(thread: RelayInboxEntry): JSONObject = JSONObject()
        .put("sender", thread.snapshot.sender.take(64))
        .put("app", thread.snapshot.appLabel.take(40))
        .put("age_minutes", ((wallClock() - thread.snapshot.capturedAtMs) / 60_000L).coerceIn(0L, 1_000_000L))
        .put("can_reply", thread.availability == RelayReplyAvailability.REPLIABLE)

    private fun reference(thread: RelayInboxEntry): String = thread.id + ":" +
        digest(thread.snapshot.sourcePackage + "\u0000" + thread.snapshot.sender)

    private fun chunks(entry: RelayTranscriptEntry): List<JSONObject> {
        val texts = buildList {
            var start = 0
            while (start < entry.text.length) {
                var end = (start + TEXT_CHUNK_CHARS).coerceAtMost(entry.text.length)
                if (end < entry.text.length && Character.isHighSurrogate(entry.text[end - 1]) &&
                    Character.isLowSurrogate(entry.text[end])) end--
                add(entry.text.substring(start, end))
                start = end
            }
        }.ifEmpty { listOf("") }
        return texts.mapIndexed { index, text ->
            JSONObject()
                .put("id", entry.id)
                .put("role", entry.role.name.lowercase())
                .put("sender", entry.sender.orEmpty().take(64))
                .put("text", text)
                .put("part", index + 1)
                .put("parts", texts.size)
                .put("status", when (entry.status) {
                    null -> "captured"
                    RelayHandoffStatus.HANDED_OFF -> "handed_to_app"
                    RelayHandoffStatus.UNCONFIRMED -> "unconfirmed"
                })
        }
    }

    private fun fold(value: String): String =
        Normalizer.normalize(value.trim(), Normalizer.Form.NFD).replace(COMBINING_MARKS, "").lowercase()

    private fun digest(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 0xff) }

    companion object {
        const val LIST_READABLE_THREADS = "list_readable_threads"
        const val READ_THREAD = "read_thread"
        const val CONVERSATION = "conversation"
        const val LATEST_RECEIVED = "latest_received"
        private const val TEXT_CHUNK_CHARS = 1_024
        private val COMBINING_MARKS = Regex("\\p{M}+")
    }
}
