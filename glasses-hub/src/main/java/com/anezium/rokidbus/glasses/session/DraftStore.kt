package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageDestination

/**
 * [READY] may be sent from its own thread; [UNCONFIRMED] was sent and never answered, so it is
 * kept for editing and never sent again as is; [AUTHORITY_LOST] can no longer be sent from here.
 */
internal enum class DraftState { READY, UNCONFIRMED, AUTHORITY_LOST }

/**
 * The one composer draft in glasses hub memory (plan 028a §7.1), bound to one exact recipient:
 * an owner, a thread and the page it was written on. It is never persisted. [reopened] marks an
 * [DraftState.AUTHORITY_LOST] draft whose thread received a new replyable notice, or showed a
 * newer replyable revision of its page ([pageReopened]): Type may then reopen it prefilled, as a
 * new capture, and only that capture's completion makes it ready. [lostRevision] is the newest
 * revision of the thread's page shown when the authority was lost, when one was. [sent] is what its
 * page showed when it was sent and left unconfirmed, to tell the page's own copy of it.
 */
internal data class Draft(
    val ownerPluginId: String,
    val threadKey: String,
    val destination: PageDestination,
    val recipient: String,
    val text: String,
    val state: DraftState = DraftState.READY,
    val reopened: Boolean = false,
    val lostRevision: Long? = null,
    val pageReopened: Boolean = false,
    val sent: ShownMessages? = null,
) {
    fun isFor(ownerPluginId: String?, threadKey: String?): Boolean =
        this.ownerPluginId == ownerPluginId && this.threadKey == threadKey
}

/** One compose send awaiting its result; the text is the draft's as it was sent, from [from]. */
internal data class PendingSend(
    val invocationId: String,
    val ownerPluginId: String,
    val threadKey: String,
    val text: String,
    val from: SentFrom,
)

/**
 * What a conversation page showed when a reply was sent from it: its messages' [ids], and [ahead],
 * how many messages of ours in the reply's words come before its own copy there (those it showed,
 * and the earlier replies in those words still waiting for theirs), [ComposerRules.carries].
 */
internal data class ShownMessages(val ids: Set<String>, val ahead: Int = 0)

/**
 * The conversation frame a reply was sent from: the session [generation], the wire [frameIndex],
 * the page, the provider registration that answered it ([providerEpoch]), and the messages it
 * showed then ([shown]).
 */
internal data class SentFrom(
    val generation: Long,
    val frameIndex: Int,
    val pageId: String,
    val providerEpoch: String?,
    val shown: ShownMessages,
) {
    fun isFrame(generation: Long, frameIndex: Int, frame: Frame): Boolean =
        this.generation == generation && this.frameIndex == frameIndex && pageId == frame.pageId && providerEpoch == frame.providerEpoch

    fun sameFrameAs(other: SentFrom): Boolean =
        generation == other.generation && frameIndex == other.frameIndex && pageId == other.pageId && providerEpoch == other.providerEpoch
}

/**
 * A reply handed to its app, shown as ours on the frame it was sent from until that page carries
 * it (its own copy of the reply, [ComposerRules.carries]). It lives no longer than that frame under
 * the registration that took it, and goes with its owner's purge or invalidation: a page answered
 * later, or by another registration, says what its provider knows.
 */
internal data class Handoff(
    val ownerPluginId: String,
    val threadKey: String,
    val text: String,
    val from: SentFrom,
)

/**
 * The draft's rules, apart from the reducer that applies them. The draft is replaced only when a
 * capture completes; a cancelled capture leaves it as it was; an empty committed typing is the
 * one explicit discard; lost authority keeps the text but never lets it be sent as is.
 */
internal object DraftStore {
    /**
     * A completed capture for [capture]'s thread: the new draft, whoever the old one was for. A
     * thread whose newest event was withdrawn meanwhile gets a draft that cannot be sent as is,
     * unless its page itself, in a newer revision, said it can reply again ([previous] reopened
     * by the page) and nothing withdrew it since.
     */
    fun completed(
        capture: Capture,
        text: String,
        history: NotificationHistory = NotificationHistory(),
        previous: Draft? = null,
        revision: Long? = null,
    ): Draft {
        val pageSaysReplyable = previous != null && previous.isFor(capture.ownerPluginId, capture.threadKey) && previous.pageReopened
        val withdrawn = history.threadWithdrawn(capture.ownerPluginId, capture.threadKey) && !pageSaysReplyable
        return Draft(
            ownerPluginId = capture.ownerPluginId,
            threadKey = capture.threadKey,
            destination = capture.destination,
            recipient = capture.recipient,
            text = text.take(capture.maxChars),
            state = if (withdrawn) DraftState.AUTHORITY_LOST else DraftState.READY,
            // Lost on the revision on screen at completion: only a newer one may reopen it.
            lostRevision = if (withdrawn) revision else null,
        )
    }

    /**
     * An empty commit discards the draft it was prefilled with, its own thread's. Another
     * recipient's draft was never shown in that field, so it is kept.
     */
    fun committedEmpty(draft: Draft?, capture: Capture): Draft? =
        draft?.takeUnless { it.isFor(capture.ownerPluginId, capture.threadKey) }

    /**
     * Authority over [ownerPluginId]'s thread [threadKey] (or every thread, when null) is gone.
     * [revision] is the newest revision of the draft's thread page on screen then, if any: only
     * a newer one may reopen it.
     */
    fun authorityLost(draft: Draft?, ownerPluginId: String, threadKey: String? = null, revision: Long? = null): Draft? {
        draft ?: return null
        if (draft.ownerPluginId != ownerPluginId || (threadKey != null && draft.threadKey != threadKey)) return draft
        return draft.copy(state = DraftState.AUTHORITY_LOST, reopened = false, pageReopened = false, lostRevision = revision)
    }

    /**
     * The draft's exact thread page, in a revision newer than the one its authority was lost on,
     * says it can reply again: like a new notice for the thread, Type may reopen it prefilled.
     */
    fun pageReplyable(draft: Draft?, ownerPluginId: String?, threadKey: String?, revision: Long): Draft? {
        draft ?: return null
        if (draft.state != DraftState.AUTHORITY_LOST || draft.reopened || !draft.isFor(ownerPluginId, threadKey)) return draft
        // Lost with no revision on screen (the page closed or still loading): the first answer seen
        // may have been computed before the loss and crossed it on another transport, so it only
        // marks the revision; a newer one, or a new notice for the thread, reopens it.
        val lost = draft.lostRevision ?: return draft.copy(lostRevision = revision)
        if (revision <= lost) return draft
        return draft.copy(reopened = true, pageReopened = true)
    }

    /** A new replyable notice for the draft's exact thread lets Type reopen a lost draft. */
    fun noticeArrived(draft: Draft?, notice: NoticeEntry): Draft? {
        draft ?: return null
        if (draft.state != DraftState.AUTHORITY_LOST || !draft.isFor(notice.ownerPluginId, notice.threadKey)) return draft
        return draft.copy(reopened = true)
    }

    /** A send whose result never came: the text stays, to be edited and sent again deliberately. */
    fun unconfirmed(draft: Draft?, send: PendingSend): Draft? =
        if (draft != null && draft.matches(send)) draft.copy(state = DraftState.UNCONFIRMED, sent = send.from.shown) else draft

    /** The app took the reply: the draft that was sent is gone. */
    fun handedOff(draft: Draft?, send: PendingSend): Draft? = draft?.takeUnless { it.matches(send) }

    private fun Draft.matches(send: PendingSend): Boolean =
        isFor(send.ownerPluginId, send.threadKey) && text == send.text
}
