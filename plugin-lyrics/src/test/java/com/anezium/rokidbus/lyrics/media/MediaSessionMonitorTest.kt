package com.anezium.rokidbus.lyrics.media

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaSessionMonitorTest {
    @Test
    fun controllerPlaybackPriority_prefersPlayingOverPausedAndIdle() {
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_PLAYING) > controllerPlaybackPriority(PlaybackState.STATE_PAUSED))
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_PAUSED) > controllerPlaybackPriority(PlaybackState.STATE_STOPPED))
        assertTrue(controllerPlaybackPriority(PlaybackState.STATE_BUFFERING) > controllerPlaybackPriority(PlaybackState.STATE_CONNECTING))
    }

    @Test
    fun spotifyTrackIdFor_extractsOnlySpotifyTrackMediaIds() {
        val trackId = "0VjIjW4GlUZAMYd2vXMi3b"

        assertEquals(
            trackId,
            spotifyTrackIdFor("com.spotify.music", "spotify:track:$trackId"),
        )
        assertNull(spotifyTrackIdFor("com.spotify.music", "spotify:episode:$trackId"))
        assertNull(spotifyTrackIdFor("com.example.player", "spotify:track:$trackId"))
        assertNull(spotifyTrackIdFor("com.spotify.music", null))
    }
}
