package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.skills.SkillAvailability
import com.anezium.rokidbus.shared.skills.SkillCatalogEntry
import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import com.anezium.rokidbus.shared.skills.SkillInvokeRequest
import com.anezium.rokidbus.shared.skills.SkillResultEnvelope
import com.anezium.rokidbus.shared.skills.SkillStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.UUID

/**
 * One send split in two: the model may call [prepare], and only the wearer's chip reaches
 * [commit]. Effect alone cannot say what needs this (`pause` is an action too), and a catalog field
 * would invalidate the catalog on every hub already shipped, so the rule lives here, per provider.
 */
internal data class GatedSkillOperation(
    val providerId: String,
    val prepare: String,
    val commit: String,
    /** Who a successful commit hands the message to; null when that is the preview's channel. */
    val handoffTarget: String? = null,
)

internal object GatedSkillOperations {
    private val ALL = listOf(
        GatedSkillOperation("relay", prepare = "prepare_reply", commit = "send_reply"),
        GatedSkillOperation(
            "sms",
            prepare = "prepare_sms",
            commit = "send_sms",
            handoffTarget = "the mobile network",
        ),
    )

    fun isCommit(entry: SkillCatalogEntry): Boolean =
        ALL.any { it.providerId == entry.providerId && it.commit == entry.operationId }

    fun prepareOf(entry: SkillCatalogEntry): GatedSkillOperation? =
        ALL.firstOrNull { it.providerId == entry.providerId && it.prepare == entry.operationId }

    /**
     * What the model may be offered from [entries]: never a commit, and a prepare only when its
     * commit is approved and ready and the band that asks the wearer can be drawn. The second
     * value is the commit a prepare stages.
     */
    fun modelOffer(
        entries: List<SkillCatalogEntry>,
        confirmable: Boolean,
    ): List<Pair<SkillCatalogEntry, GatedCommit?>> = entries.mapNotNull { entry ->
        val gated = prepareOf(entry)
        when {
            isCommit(entry) -> null
            gated == null -> entry to null
            !confirmable -> null
            else -> entries
                .firstOrNull { it.providerId == gated.providerId && it.operationId == gated.commit }
                ?.takeIf { it.availability == SkillAvailability.READY }
                ?.let { commit -> entry to GatedCommit(gated, commit) }
        }
    }
}

internal data class GatedCommit(val operation: GatedSkillOperation, val entry: SkillCatalogEntry)

/**
 * What the provider says the send will do, in its own words. Plain untrusted text: it is displayed
 * and read aloud, never given to the model as an instruction. Past [MAX_TEXT_CHARS] it is refused
 * rather than clipped, since the wearer must be able to read all of what would go.
 */
internal data class ConfirmationPreview(
    val draft: String,
    val recipient: String,
    val via: String,
    val text: String,
    val detail: String?,
) {
    val lines: List<String>
        get() = listOfNotNull("To $recipient · $via", text, detail)

    val spoken: String
        get() = listOfNotNull("To $recipient, $via.", text, detail).joinToString(" ").take(TtsContract.MAX_TEXT_CHARS)

    /**
     * Whether the message fits a band with a row of two, which cannot page and clips at eight lines:
     * measured on the glasses, seven lines of about 37 columns are left under the recipient, less
     * what wrapping loses. A longer one gets Send alone, which leaves swipes free to turn pages.
     */
    val fitsTwoChipBand: Boolean
        get() = columns <= MAX_TWO_CHIP_COLUMNS

    /** Past the fourteen lines a page holds, so saying it can be swiped is not a promise it breaks. */
    val spansPages: Boolean
        get() = columns > MIN_PAGED_COLUMNS

    private val columns: Int
        get() = text.codePoints().toArray().sumOf { if (it >= WIDE_FROM) 2 else 1 as Int } +
            (if (detail != null) COLUMNS_PER_LINE else 0)

    companion object {
        const val MAX_TEXT_CHARS = 1_000
        private const val MAX_TWO_CHIP_COLUMNS = 200
        private const val MIN_PAGED_COLUMNS = 400
        private const val COLUMNS_PER_LINE = 37

        /** From Hangul up: CJK and emoji draw about two columns of the band's monospace face. */
        private const val WIDE_FROM = 0x1100
        private const val MAX_RECIPIENT_CHARS = 64
        private const val MAX_VIA_CHARS = 40
        private const val MAX_DETAIL_CHARS = 80
        private val BIDI_CONTROLS = Regex("[\\u202A-\\u202E\\u2066-\\u2069]")
        private val WHITESPACE = Regex("\\s+")

        fun parse(data: JSONObject?): ConfirmationPreview? {
            if (data == null) return null
            val draft = data.optString("draft").takeIf(String::isNotBlank) ?: return null
            val recipient = clean(data.optString("recipient")) ?: return null
            val via = clean(data.optString("via")) ?: return null
            val text = clean(data.optString("text")) ?: return null
            val detail = clean(data.optString("detail"))
            if (recipient.length > MAX_RECIPIENT_CHARS || via.length > MAX_VIA_CHARS ||
                text.length > MAX_TEXT_CHARS || (detail?.length ?: 0) > MAX_DETAIL_CHARS
            ) {
                return null
            }
            return ConfirmationPreview(draft, recipient, via, text, detail)
        }

        private fun clean(value: String): String? =
            value.replace(BIDI_CONTROLS, "").replace(WHITESPACE, " ").trim().takeIf(String::isNotEmpty)
    }
}

/** The turn a prepare runs in, as far as the confirmation it may stage needs to remember. */
internal data class ConfirmationTurn(
    val id: String,
    val session: String,
    val conversation: AssistantConversationContext?,
    /** False once a Workspace source this turn used was withdrawn. */
    val effectsAllowed: () -> Boolean = { true },
)

internal data class PendingConfirmation(
    val token: String,
    val turn: ConfirmationTurn,
    val commit: GatedCommit,
    val preview: ConfirmationPreview,
) {
    /** What the conversation keeps for the turn: the model later reads this, not a claim. */
    val conversationNote: String
        get() = "Draft waiting for the wearer's confirmation, to ${preview.recipient} on ${preview.via}: " +
            "\"${preview.text}\". Not sent unless a later message says so."
}

/**
 * Holds the one confirmation that can exist. A prepare stages it, the band presenting it makes it
 * answerable, and a chip's token takes it exactly once, so a second tap, a replay or a stale chip
 * finds nothing. Dropping is always safe: the provider's draft just expires, unsent.
 */
internal class AssistantConfirmationGate(
    private val tokens: () -> String = { UUID.randomUUID().toString().replace("-", "").take(16) },
) {
    private val lock = Any()
    private var staged: PendingConfirmation? = null
    private var presented = false

    fun hasStaged(turnId: String): Boolean = synchronized(lock) { staged?.turn?.id == turnId }

    fun stagedFor(turnId: String): PendingConfirmation? = synchronized(lock) {
        staged?.takeIf { it.turn.id == turnId && !presented }
    }

    /** False when this turn already staged one: a turn asks the wearer about one send only. */
    fun stage(turn: ConfirmationTurn, commit: GatedCommit, preview: ConfirmationPreview): Boolean =
        synchronized(lock) {
            if (staged?.turn?.id == turn.id) return false
            staged = PendingConfirmation(tokens(), turn, commit, preview)
            presented = false
            true
        }

    fun markPresented(token: String): Boolean = synchronized(lock) {
        if (staged?.token != token) return false
        presented = true
        true
    }

    val isPresented: Boolean
        get() = synchronized(lock) { staged != null && presented }

    /** A chip's answer: only the token of the confirmation on the band, and only once. */
    fun take(token: String): PendingConfirmation? = synchronized(lock) {
        val current = staged?.takeIf { it.token == token && presented } ?: return null
        staged = null
        presented = false
        current
    }

    fun drop(): Boolean = synchronized(lock) {
        val had = staged != null
        staged = null
        presented = false
        had
    }

    /** A turn that ended without reaching the band takes its draft with it. */
    fun dropUnpresented(turnId: String) = synchronized(lock) {
        if (staged?.turn?.id == turnId && !presented) staged = null
    }

    companion object {
        const val SEND_PREFIX = "confirm-send:"
        const val CANCEL_PREFIX = "confirm-cancel:"

        const val LONG_DRAFT_FOOTER = "Back to cancel"
        const val PAGED_DRAFT_FOOTER = "Swipe to read · Back to cancel"

        fun actions(token: String, withCancel: Boolean = true): List<NexusNoticeAction> = buildList {
            add(NexusNoticeAction("$SEND_PREFIX$token", "send", "Send"))
            if (withCancel) add(NexusNoticeAction("$CANCEL_PREFIX$token", "close", "Cancel"))
        }
    }
}

/** The prepare side of a gated operation, attached to the tool the model calls. */
internal class ConfirmationStaging(
    private val gate: AssistantConfirmationGate,
    private val commit: GatedCommit,
    private val turn: ConfirmationTurn,
) {
    val alreadyStaged: Boolean
        get() = gate.hasStaged(turn.id)

    /**
     * What the model reads back. A completed prepare stages the draft and tells it only that the
     * wearer now decides: the reference and the preview stay out of its reach. Nothing here can
     * read as sent, so a provider answering a prepare with accepted or unknown is a refusal too.
     */
    fun modelResult(entry: SkillCatalogEntry, result: SkillResultEnvelope): JSONObject = when (result.status) {
        SkillStatus.COMPLETED -> {
            val preview = ConfirmationPreview.parse(result.data)
            when {
                preview == null -> refused(entry, PREVIEW_REFUSED)
                !gate.stage(turn, commit, preview) -> refused(entry, "already_used")
                else -> JSONObject()
                    .put("status", AWAITING_WEARER)
                    .put("provider", entry.providerName.ifBlank { entry.providerId })
                    .put("operation", entry.label)
                    .put(
                        "note",
                        "Nothing has been sent. The wearer now sees the draft on the glasses and decides. " +
                            "End your turn with one short line and do not repeat the message.",
                    )
            }
        }
        SkillStatus.NEEDS_INPUT, SkillStatus.FAILED -> PluginSkillTool.modelResult(entry, result)
        SkillStatus.ACCEPTED, SkillStatus.UNKNOWN -> refused(entry, SkillErrorCodes.INVALID_RESULT)
    }

    private fun refused(entry: SkillCatalogEntry, code: String): JSONObject = JSONObject()
        .put("status", SkillStatus.FAILED.wireValue)
        .put("provider", entry.providerName.ifBlank { entry.providerId })
        .put("operation", entry.label)
        .put("code", code)
        .put("dispatch", SkillDispatch.NONE.wireValue)
        .put(
            "note",
            if (code == PREVIEW_REFUSED) {
                "Nothing was sent. The message must fit in ${ConfirmationPreview.MAX_TEXT_CHARS} characters."
            } else {
                "Nothing was sent."
            },
        )

    companion object {
        const val AWAITING_WEARER = "awaiting_wearer"
        const val PREVIEW_REFUSED = "preview_refused"
    }
}

/** The band's side of a confirmation; the UI controller draws all four. */
internal interface AssistantConfirmationUi {
    /** False when the band could not be raised, in which case the wearer was asked nothing. */
    fun showConfirmation(lines: List<String>, actions: List<NexusNoticeAction>, footer: String? = null): Boolean
    fun showConfirmationProgress(body: String)
    fun showConfirmationResult(body: String)
    fun showConfirmationNotice(body: String)
}

/**
 * Takes a staged confirmation to the wearer and carries out the answer. The commit runs in its own
 * job: it cannot be taken back, so a new question or a dismissed band must not cancel it or lose
 * what became of it. It is never retried, whatever the result.
 */
internal class AssistantConfirmationFlow(
    private val scope: CoroutineScope,
    private val gate: AssistantConfirmationGate,
    private val gateway: AssistantSkillGateway,
    private val ui: AssistantConfirmationUi,
    private val speak: (String) -> Unit,
    private val stopSpeech: () -> Unit,
    /** Something newer holds the band, so a late result goes to the conversation only. */
    private val bandBusy: () -> Boolean,
    private val record: suspend (AssistantConversationContext, String, String) -> Unit,
) {
    private var commitJob: Job? = null

    val commitInFlight: Boolean
        get() = commitJob?.isActive == true

    fun stagedFor(turnId: String): PendingConfirmation? = gate.stagedFor(turnId)

    /** Replaces the turn's answer with the provider's preview. False when it could not be shown. */
    fun present(pending: PendingConfirmation): Boolean {
        val preview = pending.preview
        gate.markPresented(pending.token)
        val shown = ui.showConfirmation(
            preview.lines,
            AssistantConfirmationGate.actions(pending.token, withCancel = preview.fitsTwoChipBand),
            when {
                preview.fitsTwoChipBand -> null
                preview.spansPages -> AssistantConfirmationGate.PAGED_DRAFT_FOOTER
                else -> AssistantConfirmationGate.LONG_DRAFT_FOOTER
            },
        )
        if (!shown) {
            gate.drop()
            ui.showConfirmationNotice(COULD_NOT_ASK)
            return false
        }
        speak(preview.spoken)
        return true
    }

    /** True when [id] is a confirmation chip, answered or stale. */
    fun onAction(id: String): Boolean {
        when {
            id.startsWith(AssistantConfirmationGate.SEND_PREFIX) ->
                gate.take(id.removePrefix(AssistantConfirmationGate.SEND_PREFIX))?.let(::send)
            id.startsWith(AssistantConfirmationGate.CANCEL_PREFIX) ->
                gate.take(id.removePrefix(AssistantConfirmationGate.CANCEL_PREFIX))?.let(::cancel)
            else -> return false
        }
        return true
    }

    /** The band left, for whatever reason: nothing that is still on it can be answered now. */
    fun onBandLost() {
        if (gate.drop()) stopSpeech()
    }

    private fun cancel(pending: PendingConfirmation) {
        stopSpeech()
        ui.showConfirmationNotice(CANCELLED)
        keep(pending, TAPPED_CANCEL, CANCELLED)
    }

    private fun send(pending: PendingConfirmation) {
        stopSpeech()
        if (!pending.turn.effectsAllowed()) {
            ui.showConfirmationNotice(SOURCE_WITHDRAWN)
            keep(pending, TAPPED_SEND, SOURCE_WITHDRAWN)
            return
        }
        ui.showConfirmationProgress(SENDING)
        commitJob = scope.launch {
            val result = gateway.invoke(
                SkillInvokeRequest(
                    pending.turn.session,
                    "$REQUEST_KEY_PREFIX${pending.token}",
                    pending.commit.entry.alias,
                    JSONObject().put(DRAFT_ARGUMENT, pending.preview.draft),
                ),
            )
            val outcome = confirmationOutcome(pending, result)
            if (!bandBusy()) {
                ui.showConfirmationResult(outcome)
                speak(outcome)
            }
            pending.turn.conversation?.let { record(it, TAPPED_SEND, outcome) }
        }
    }

    private fun keep(pending: PendingConfirmation, userText: String, assistantText: String) {
        val conversation = pending.turn.conversation ?: return
        scope.launch { record(conversation, userText, assistantText) }
    }

    companion object {
        private const val REQUEST_KEY_PREFIX = "confirm:"
        private const val DRAFT_ARGUMENT = "draft"
        const val COULD_NOT_ASK = "Couldn't ask you to confirm. Nothing was sent."
        const val CANCELLED = "Cancelled. Nothing was sent."
        const val SENDING = "Sending…"
        const val SOURCE_WITHDRAWN = "Not sent: a document it used was withdrawn."
        const val TAPPED_SEND = "[The wearer tapped Send on the glasses.]"
        const val TAPPED_CANCEL = "[The wearer tapped Cancel on the glasses.]"
    }
}

/**
 * Written by Assistant from the status alone, so a provider's words can never claim more. A handoff
 * is the most anyone here can know: not that the message arrived, and never that it was read.
 */
internal fun confirmationOutcome(pending: PendingConfirmation, result: SkillResultEnvelope): String {
    val preview = pending.preview
    val target = pending.commit.operation.handoffTarget ?: preview.via
    return when (result.status) {
        SkillStatus.COMPLETED, SkillStatus.ACCEPTED ->
            "Handed to $target for ${preview.recipient}."
        SkillStatus.NEEDS_INPUT -> "Not sent."
        SkillStatus.FAILED -> result.error?.takeIf { it.dispatch == SkillDispatch.NONE }
            ?.let { "Not sent: ${notSentReason(it.code)}." }
            ?: MAY_HAVE_GONE
        SkillStatus.UNKNOWN -> MAY_HAVE_GONE
    }
}

private fun notSentReason(code: String): String = when (code) {
    "authority_lost" -> "that conversation can no longer be answered"
    "recipient_changed" -> "that contact changed"
    SkillErrorCodes.PERMISSION_REQUIRED, SkillErrorCodes.SETUP_REQUIRED -> "the plugin needs setup on the phone"
    SkillErrorCodes.STALE_REFERENCE -> "the draft expired"
    SkillErrorCodes.BUSY -> "the plugin was busy"
    SkillErrorCodes.CANCELLED -> "it was cancelled"
    else -> "the plugin couldn't send it"
}

internal const val MAY_HAVE_GONE = "It may or may not have gone. Not retried."
