package com.anezium.rokidbus.glasses

/**
 * Pure policy for the karaoke display-hold episode. Clone of the renewable wake-lock discipline
 * the assistant uses ([AssistantDisplayEpisode]), but for the ambient widget and with the bounds
 * the widget contract requires:
 *
 *  - A hold is active only while `display && playing && holdDisplay` (Karaoke mode).
 *    Glance mode (`holdDisplay == false`) and Off mode (no widget) must never hold or wake.
 *  - Each hold is a short renewable episode; once playing/pausing makes it unwarranted, the hold
 *    is released within a short grace.
 *  - A hard per-track ceiling resets on a contentKey change; a single track can never hold the
 *    display past it.
 *
 * Android-free so acceptance #7 drives it directly with an injected clock and a recorded
 * acquire/release log.
 */
class KaraokeHoldPolicy(
    private val now: () -> Long,
    private val holdMs: Long = DEFAULT_HOLD_MS,
    private val ceilingMs: Long = DEFAULT_CEILING_MS,
    private val releaseGraceMs: Long = DEFAULT_RELEASE_GRACE_MS,
) {
    private var held = false
    private var heldSince = 0L
    private var ceilingFrom = 0L
    private var trackKey: String? = null
    private var sawCeilingReset = false

    val isHeld: Boolean
        get() = held

    /**
     * Drive on widget state change / tick. `holdDisplay` is Karaoke mode. Returns what the
     * wake-lock adapter must do.
     */
    fun update(
        display: Boolean,
        playing: Boolean,
        holdDisplay: Boolean,
        contentKey: String,
    ): Action {
        val wantHold = display && playing && holdDisplay
        if (contentKey != trackKey) {
            trackKey = contentKey
            ceilingFrom = now()
        }
        val nowAt = now()
        val pastCeiling = held && nowAt - ceilingFrom >= ceilingMs
        val idleOut = held && !wantHold && nowAt - heldSince >= releaseGraceMs
        if (held && (pastCeiling || idleOut)) {
            held = false
            return Action.Release
        }
        if (!held && wantHold) {
            held = true
            heldSince = nowAt
            return Action.Acquire(holdMs)
        }
        return Action.Nothing
    }

    /** Force-release (e.g. hub down or service destroyed). */
    fun forceRelease(): Action {
        if (!held) return Action.Nothing
        held = false
        return Action.Release
    }

    sealed interface Action {
        data object Nothing : Action
        data class Acquire(val holdMs: Long) : Action
        data object Release : Action
    }

    companion object {
        const val DEFAULT_HOLD_MS = 8_000L
        const val DEFAULT_CEILING_MS = 10 * 60_000L
        const val DEFAULT_RELEASE_GRACE_MS = 5_000L
    }
}