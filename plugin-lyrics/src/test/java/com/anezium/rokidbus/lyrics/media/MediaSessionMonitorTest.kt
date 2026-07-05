package com.anezium.rokidbus.lyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMonitorTest {
    @Test
    fun controllerPlaybackPriority_prefersPlayingOverPausedAndIdle() {
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_PLAYING) > controllerPlaybackPriority(PlaybackState.STATE_PAUSED))
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_PAUSED) > controllerPlaybackPriority(PlaybackState.STATE_STOPPED))
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_BUFFERING) > controllerPlaybackPriority(PlaybackState.STATE_CONNECTING))
    }
}
