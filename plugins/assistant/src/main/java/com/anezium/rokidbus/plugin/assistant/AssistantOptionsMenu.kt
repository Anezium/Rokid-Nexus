package com.anezium.rokidbus.plugin.assistant

/**
 * What a swipe on the Assistant card opens: today one option, which way the assist button goes.
 *
 * Pure state, so the wording and the key handling can be tested without a hub. The service owns
 * the bus traffic — it asks the hub on [open], sends the [Action.Set] a tap produces, and feeds
 * the answers back through [onStatus] and [onError].
 */
internal class AssistantOptionsMenu {
    sealed interface State {
        data object Closed : State

        /** Waiting for the hub to say where the switch is. */
        data object Loading : State

        data class Ready(val takeover: Boolean) : State

        /** The switch cannot be moved from here; [reason] is what to tell the wearer. */
        data class Unavailable(val reason: String) : State
    }

    sealed interface Action {
        data object None : Action

        /** Ask the hub to move the switch to [takeover]. */
        data class Set(val takeover: Boolean) : Action
    }

    /** One row and its framing, ready to render. */
    data class View(
        val text: String,
        val sub: String,
        val footer: String,
    )

    var state: State = State.Closed
        private set

    val isOpen: Boolean
        get() = state != State.Closed

    /**
     * Opens the menu. Returns true when the hub has to be asked for the switch position; false
     * when the answer is already known because the grant that would let us ask is missing.
     */
    fun open(assistantGranted: Boolean): Boolean {
        state = if (assistantGranted) State.Loading else State.Unavailable(REASON_NOT_GRANTED)
        return assistantGranted
    }

    fun onStatus(takeover: Boolean) {
        if (isOpen) state = State.Ready(takeover)
    }

    fun onError(code: String) {
        if (!isOpen) return
        state = State.Unavailable(
            when (code) {
                // An older phone hub does not know the route at all.
                "PLUGIN_NAMESPACE_DENIED", "SYSTEM_ROUTE_DENIED" -> REASON_HUB_TOO_OLD
                "CAPABILITY_REQUIRED_ASSISTANT" -> REASON_NOT_GRANTED
                else -> "Nexus did not answer ($code)."
            },
        )
    }

    /** A tap on the row: flips the switch when its position is known, otherwise nothing. */
    fun onConfirm(): Action {
        val ready = state as? State.Ready ?: return Action.None
        state = State.Loading
        return Action.Set(!ready.takeover)
    }

    fun close() {
        state = State.Closed
    }

    fun view(): View? = when (val current = state) {
        State.Closed -> null
        State.Loading -> View(TEXT_UNKNOWN, "Checking…", FOOTER_CLOSE)
        is State.Ready -> if (current.takeover) {
            View(TEXT_NEXUS, "Tap to hand it back to Rokid's assistant.", FOOTER_SWITCH)
        } else {
            View(TEXT_ROKID, "Tap to make it open Nexus Assistant.", FOOTER_SWITCH)
        }
        is State.Unavailable -> View(TEXT_UNKNOWN, current.reason, FOOTER_CLOSE)
    }

    companion object {
        const val TEXT_NEXUS = "Assist button: Nexus"
        const val TEXT_ROKID = "Assist button: Rokid"
        const val TEXT_UNKNOWN = "Assist button"
        const val FOOTER_SWITCH = "tap to switch · back to close"
        const val FOOTER_CLOSE = "back to close"
        const val REASON_NOT_GRANTED =
            "Allow \"Replace the glasses assistant\" for Assistant in Nexus on your phone."
        const val REASON_HUB_TOO_OLD = "Update Rokid Nexus on your phone to switch this here."
    }
}
