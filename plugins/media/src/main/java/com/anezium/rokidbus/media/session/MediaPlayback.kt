package com.anezium.rokidbus.media.session

import android.media.session.PlaybackState

/** Where playback stands, as the immersion, the activity, the page and every command read it. */
internal enum class MediaPlaybackPhase {
    PLAYING,
    PAUSED,
    STOPPED,
}

/**
 * The one definition of "playing": the states the platform's `PlaybackState.isActive()` (API 31)
 * counts as playback in progress or about to start. A session that is buffering, seeking,
 * skipping or connecting intends to play, so the control it offers is Pause; anything else offers
 * Play. The snapshot, the activity, the page and the check a command runs before it reaches the
 * session all derive their phase here, so what the wearer saw and what a command checks cannot
 * disagree.
 */
internal object MediaPlayback {
    fun phaseOf(state: Int?): MediaPlaybackPhase = when (state) {
        PlaybackState.STATE_PLAYING,
        PlaybackState.STATE_BUFFERING,
        PlaybackState.STATE_FAST_FORWARDING,
        PlaybackState.STATE_REWINDING,
        PlaybackState.STATE_SKIPPING_TO_NEXT,
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM,
        PlaybackState.STATE_CONNECTING,
        -> MediaPlaybackPhase.PLAYING
        PlaybackState.STATE_PAUSED -> MediaPlaybackPhase.PAUSED
        else -> MediaPlaybackPhase.STOPPED
    }

    /**
     * Whether the position moves by itself, for the immersion's local clock. This is not
     * "playing": a buffering session intends to play but its position stands still.
     */
    fun advances(state: Int?, speed: Float): Boolean = state == PlaybackState.STATE_PLAYING && speed > 0f
}
