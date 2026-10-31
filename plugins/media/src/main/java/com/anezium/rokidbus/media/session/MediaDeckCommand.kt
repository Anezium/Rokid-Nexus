package com.anezium.rokidbus.media.session

import com.anezium.rokidbus.client.plugin.NexusActionType

/** A transport command. Play and pause are desired states, never a toggle. */
internal enum class MediaDeckCommand(val typed: NexusActionType) {
    PREVIOUS(NexusActionType.MediaPrevious),
    PLAY(NexusActionType.MediaPlay),
    PAUSE(NexusActionType.MediaPause),
    NEXT(NexusActionType.MediaNext),
    ;

    companion object {
        fun of(typed: NexusActionType?): MediaDeckCommand? = entries.firstOrNull { it.typed == typed }

        /** The play/pause command a state offers: the opposite of what it shows. */
        fun desiredFor(phase: MediaPlaybackPhase): MediaDeckCommand =
            if (phase == MediaPlaybackPhase.PLAYING) PAUSE else PLAY
    }
}

/** What a command was decided on: the session the wearer saw, at the revision it was shown. */
internal data class MediaDeckTarget(val identity: MediaSessionIdentity, val revision: Long) {
    companion object {
        fun of(state: MediaDeckState): MediaDeckTarget? =
            (state as? MediaDeckState.Active)?.let { MediaDeckTarget(it.identity, it.revision) }
    }
}

/**
 * The outcome of one command. [DONE] means it was handed to the session the wearer saw; what the
 * player then does is observed, never assumed. Nothing is ever retried, whatever the outcome.
 */
internal enum class MediaDeckControlOutcome(val status: Status) {
    DONE(Status.DONE),
    RESUME_REQUESTED(Status.DONE),

    /** Another session took over since the wearer saw this one. */
    STALE_SESSION(Status.STALE),

    /** The same session moved on (another item, or playing changed) since that revision. */
    STALE_REVISION(Status.STALE),
    NO_SESSION(Status.REJECTED),
    ACCESS_REQUIRED(Status.REJECTED),
    UNAVAILABLE(Status.REJECTED),

    /** The session refused the command. */
    FAILED(Status.REJECTED),

    /** Not one of the commands Media Deck offered there, or no longer offered at all. */
    NOT_OFFERED(Status.REJECTED),
    ;

    enum class Status { DONE, STALE, REJECTED }

    /** Short copy for the result, at most 64 characters. */
    val message: String
        get() = when (this) {
            DONE -> "Sent to the player"
            RESUME_REQUESTED -> "Resume requested on phone"
            STALE_SESSION -> "Another player took over"
            STALE_REVISION -> "Playback changed"
            NO_SESSION -> "Nothing is playing"
            ACCESS_REQUIRED -> "Media access is off"
            UNAVAILABLE -> "Media control unavailable"
            FAILED -> "The player refused it"
            NOT_OFFERED -> "Not available here"
        }
}

/**
 * Whether a command decided on [target] may go to the session observed in the current state. Every
 * caller that controls playback (the immersion, the activity, the page, and any later one) goes
 * through this one rule: a command reaches only the session it was decided on, at the revision it
 * was decided on, and never "whichever session is active now".
 */
internal object MediaDeckCommandPolicy {
    sealed interface Decision {
        data class Send(val session: MediaDeckState.Active) : Decision
        data class Refuse(val outcome: MediaDeckControlOutcome) : Decision
    }

    fun decide(state: MediaDeckState, target: MediaDeckTarget?, command: MediaDeckCommand): Decision = when (state) {
        // Nothing observed is nothing to act on; never pick some session instead.
        is MediaDeckState.Unknown -> Decision.Refuse(MediaDeckControlOutcome.NO_SESSION)
        is MediaDeckState.NoSession -> Decision.Refuse(MediaDeckControlOutcome.NO_SESSION)
        is MediaDeckState.AccessRequired -> Decision.Refuse(MediaDeckControlOutcome.ACCESS_REQUIRED)
        is MediaDeckState.Unavailable -> Decision.Refuse(MediaDeckControlOutcome.UNAVAILABLE)
        is MediaDeckState.Active -> when {
            target == null -> Decision.Refuse(MediaDeckControlOutcome.STALE_REVISION)
            target.identity != state.identity -> Decision.Refuse(MediaDeckControlOutcome.STALE_SESSION)
            target.revision != state.revision -> Decision.Refuse(MediaDeckControlOutcome.STALE_REVISION)
            // The same revision offered the other one of play and pause.
            command == MediaDeckCommand.PLAY && state.playing -> Decision.Refuse(MediaDeckControlOutcome.NOT_OFFERED)
            command == MediaDeckCommand.PAUSE && !state.playing -> Decision.Refuse(MediaDeckControlOutcome.NOT_OFFERED)
            else -> Decision.Send(state)
        }
    }
}
