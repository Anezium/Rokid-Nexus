package com.anezium.rokidbus.media.session

import android.media.session.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaDeckStateMachineTest {
    private val machine = MediaDeckStateMachine()

    @Test
    fun `the revision moves only when what the wearer can act on changes`() {
        assertTrue(machine.observe(observed()))
        val first = machine.state as MediaDeckState.Active
        assertTrue(first.playing)

        // The same session, item and phase again: a position tick or a repeated callback.
        assertFalse(machine.observe(observed()))
        assertEquals(first.revision, machine.state.revision)

        assertTrue(machine.observe(observed(phase = MediaPlaybackPhase.PAUSED)))
        val paused = machine.state as MediaDeckState.Active
        assertFalse(paused.playing)
        assertEquals(first.revision + 1, paused.revision)

        assertTrue(machine.observe(observed(track = track(title = "Angel"), phase = MediaPlaybackPhase.PAUSED)))
        assertEquals(paused.revision + 1, machine.state.revision)
    }

    @Test
    fun `another session is a session change and a later state of it is not`() {
        machine.observe(observed(identity = SESSION_A))
        assertFalse((machine.state as MediaDeckState.Active).sessionChanged)

        machine.observe(observed(identity = SESSION_B))
        assertTrue((machine.state as MediaDeckState.Active).sessionChanged)

        machine.observe(observed(identity = SESSION_B, phase = MediaPlaybackPhase.PAUSED))
        assertFalse((machine.state as MediaDeckState.Active).sessionChanged)
    }

    @Test
    fun `access lost after it worked reads as revoked`() {
        machine.observe(MediaDeckObservation.AccessRequired)
        assertEquals(MediaDeckState.AccessRequired(1, revoked = false), machine.state)

        machine.observe(MediaDeckObservation.NoSession)
        assertTrue(machine.state is MediaDeckState.NoSession)

        machine.observe(MediaDeckObservation.AccessRequired)
        assertTrue((machine.state as MediaDeckState.AccessRequired).revoked)
    }

    @Test
    fun `every state change rises and forgetting makes everything shown before older`() {
        val revisions = mutableListOf<Long>()
        listOf(
            observed(),
            MediaDeckObservation.NoSession,
            MediaDeckObservation.Unavailable,
            MediaDeckObservation.AccessRequired,
            observed(),
        ).forEach { observation ->
            assertTrue(machine.observe(observation))
            revisions += machine.state.revision
        }
        assertEquals(revisions.sorted().distinct(), revisions)

        val before = machine.state.revision
        machine.forget()
        assertTrue(machine.state is MediaDeckState.Unknown)
        assertTrue(machine.state.revision > before)
        assertTrue(machine.observe(observed()))
        assertTrue(machine.state.revision > before + 1)
    }

    @Test
    fun `playing has one definition for what is drawn and what a tap sends`() {
        // Buffering intends to play: it is drawn playing and offers Pause, on a standing clock.
        assertEquals(MediaPlaybackPhase.PLAYING, MediaPlayback.phaseOf(PlaybackState.STATE_BUFFERING))
        assertEquals(MediaDeckCommand.PAUSE, MediaDeckCommand.desiredFor(MediaPlayback.phaseOf(PlaybackState.STATE_BUFFERING)))
        assertFalse(MediaPlayback.advances(PlaybackState.STATE_BUFFERING, 1f))

        assertEquals(MediaDeckCommand.PAUSE, MediaDeckCommand.desiredFor(MediaPlayback.phaseOf(PlaybackState.STATE_PLAYING)))
        assertTrue(MediaPlayback.advances(PlaybackState.STATE_PLAYING, 1f))
        assertFalse(MediaPlayback.advances(PlaybackState.STATE_PLAYING, 0f))

        assertEquals(MediaDeckCommand.PLAY, MediaDeckCommand.desiredFor(MediaPlayback.phaseOf(PlaybackState.STATE_PAUSED)))
        assertEquals(MediaDeckCommand.PLAY, MediaDeckCommand.desiredFor(MediaPlayback.phaseOf(PlaybackState.STATE_STOPPED)))
        assertEquals(MediaDeckCommand.PLAY, MediaDeckCommand.desiredFor(MediaPlayback.phaseOf(null)))
    }

    @Test
    fun `a session keeps its identity and a new one never reuses it`() {
        val identities = MediaSessionIdentities(capacity = 2)
        val tokenA = Any()
        val tokenB = Any()

        val a = identities.identityOf("com.example.player", tokenA)
        assertEquals(a, identities.identityOf("com.example.player", tokenA))
        assertNotEquals(a, identities.identityOf("com.example.player", tokenB))
        assertNotEquals(a, identities.identityOf("com.example.other", tokenA))
    }
}
