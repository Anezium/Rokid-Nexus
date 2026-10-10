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
 * [DraftState.AUTHORITY_LOST] draft whose thread received a new replyable notice: Type may then
 * reopen it prefilled, as a new capture, and only that capture's completion makes it ready.
 */
internal data class Draft(
    val ownerPluginId: String,
    val threadKey: String,
    val destination: PageDestination,
    val recipient: String,
    val text: String,
    val state: DraftState = DraftState.READY,
    val reopened: Boolean = false,
) {
    fun isFor(ownerPluginId: String?, threadKey: String?): Boolean =
        this.ownerPluginId == ownerPluginId && this.threadKey == threadKey
}

/** One compose send awaiting its result; the text is the draft's as it was sent. */
internal data class PendingSend(
    val invocationId: String,
    val ownerPluginId: String,
    val threadKey: String,
    val text: String,
)

/**
 * A reply handed to its app, shown on its thread's page until the page shows a newer revision
 * than the one it was sent from (the provider's page then says what it knows).
 */
internal data class Handoff(val ownerPluginId: String, val threadKey: String, val text: String, val revision: Long)

/**
 * The draft's rules, apart from the reducer that applies them. The draft is replaced only when a
 * capture completes; a cancelled capture leaves it as it was; an empty committed typing is the
 * one explicit discard; lost authority keeps the text but never lets it be sent as is.
 */
internal object DraftStore {
    /**
     * A completed capture for [capture]'s thread: the new draft, whoever the old one was for. A
     * thread whose newest event was withdrawn meanwhile gets a draft that cannot be sent as is.
     */
    fun completed(capture: Capture, text: String, history: NotificationHistory = NotificationHistory()): Draft = Draft(
        ownerPluginId = capture.ownerPluginId,
        threadKey = capture.threadKey,
        destination = capture.destination,
        recipient = capture.recipient,
        text = text.take(capture.maxChars),
        state = if (history.threadWithdrawn(capture.ownerPluginId, capture.threadKey)) DraftState.AUTHORITY_LOST else DraftState.READY,
    )

    /**
     * An empty commit discards the draft it was prefilled with, its own thread's. Another
     * recipient's draft was never shown in that field, so it is kept.
     */
    fun committedEmpty(draft: Draft?, capture: Capture): Draft? =
        draft?.takeUnless { it.isFor(capture.ownerPluginId, capture.threadKey) }

    /** Authority over [ownerPluginId]'s thread [threadKey] (or every thread, when null) is gone. */
    fun authorityLost(draft: Draft?, ownerPluginId: String, threadKey: String? = null): Draft? {
        draft ?: return null
        if (draft.ownerPluginId != ownerPluginId || (threadKey != null && draft.threadKey != threadKey)) return draft
        return draft.copy(state = DraftState.AUTHORITY_LOST, reopened = false)
    }

    /** A new replyable notice for the draft's exact thread lets Type reopen a lost draft. */
    fun noticeArrived(draft: Draft?, notice: NoticeEntry): Draft? {
        draft ?: return null
        if (draft.state != DraftState.AUTHORITY_LOST || !draft.isFor(notice.ownerPluginId, notice.threadKey)) return draft
        return draft.copy(reopened = true)
    }

    /** A send whose result never came: the text stays, to be edited and sent again deliberately. */
    fun unconfirmed(draft: Draft?, send: PendingSend): Draft? =
        if (draft != null && draft.matches(send)) draft.copy(state = DraftState.UNCONFIRMED) else draft

    /** The app took the reply: the draft that was sent is gone. */
    fun handedOff(draft: Draft?, send: PendingSend): Draft? = draft?.takeUnless { it.matches(send) }

    private fun Draft.matches(send: PendingSend): Boolean =
        isFor(send.ownerPluginId, send.threadKey) && text == send.text
}
