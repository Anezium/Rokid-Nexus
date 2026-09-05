package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.PluginRegistrationResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayNoticeReplayPolicyTest {
    @Test
    fun `replay window expires at two minutes`() {
        val startedAtMs = 50L

        assertFalse(isReplayWindowExpired(startedAtMs, startedAtMs + REPLAY_WINDOW_MS - 1L))
        assertTrue(isReplayWindowExpired(startedAtMs, startedAtMs + REPLAY_WINDOW_MS))
        assertFalse(isReplayWindowExpired(startedAtMs, startedAtMs - 1L))
    }

    @Test
    fun `an old generation cannot abandon the current pending show`() {
        val startedAtMs = 1_000L
        val expiredAtMs = startedAtMs + REPLAY_WINDOW_MS

        assertFalse(
            shouldAbandonPendingShow(
                timerGeneration = 7,
                activeGeneration = 8,
                startedAtMs = startedAtMs,
                nowMs = expiredAtMs,
            ),
        )
        assertTrue(
            shouldAbandonPendingShow(
                timerGeneration = 8,
                activeGeneration = 8,
                startedAtMs = startedAtMs,
                nowMs = expiredAtMs,
            ),
        )
    }

    @Test
    fun `a typing field opened for a reply that show() has since replaced is stale`() {
        // Reply A: typing opens while show()'s generation is 1.
        assertFalse(isTypingCommitStale(openedAtGeneration = 1, currentGeneration = 1))

        // A notification from a different conversation, B, arrives mid-type: show() bumps the
        // generation to replace the band's content before the wearer ever submits A's field.
        assertTrue(isTypingCommitStale(openedAtGeneration = 1, currentGeneration = 2))
    }

    @Test
    fun `only nonrecoverable registration results are terminal`() {
        listOf(
            PluginRegistrationResult.DENIED,
            PluginRegistrationResult.INVALID_DESCRIPTOR,
            PluginRegistrationResult.IDENTITY_MISMATCH,
            PluginRegistrationResult.UNSUPPORTED_API,
        ).forEach { result -> assertTrue(isTerminalRegistrationResult(result)) }

        listOf(
            PluginRegistrationResult.APPROVED,
            PluginRegistrationResult.PENDING_USER_APPROVAL,
            PluginRegistrationResult.REGISTRATION_FAILED,
            Int.MAX_VALUE,
        ).forEach { result -> assertFalse(isTerminalRegistrationResult(result)) }
    }
}
