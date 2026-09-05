package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.PluginRegistrationResult

internal const val REPLAY_WINDOW_MS = 120_000L

internal fun pendingShowAgeMs(startedAtMs: Long, nowMs: Long): Long =
    if (nowMs >= startedAtMs) nowMs - startedAtMs else 0L

internal fun isReplayWindowExpired(startedAtMs: Long, nowMs: Long): Boolean =
    pendingShowAgeMs(startedAtMs, nowMs) >= REPLAY_WINDOW_MS

internal fun shouldAbandonPendingShow(
    timerGeneration: Int,
    activeGeneration: Int,
    startedAtMs: Long,
    nowMs: Long,
): Boolean = timerGeneration == activeGeneration && isReplayWindowExpired(startedAtMs, nowMs)

internal fun isTerminalRegistrationResult(result: Int): Boolean = when (result) {
    PluginRegistrationResult.DENIED,
    PluginRegistrationResult.INVALID_DESCRIPTOR,
    PluginRegistrationResult.IDENTITY_MISMATCH,
    PluginRegistrationResult.UNSUPPORTED_API -> true
    else -> false
}

/**
 * A typed-reply commit is stale once the exchange has moved on from the reply it was opened
 * for. `show()` bumps its generation counter on every call, including one that replaces the
 * currently displayed reply with a new notification — the same event that already invalidates
 * in-flight speech. A typing field left open from before that replacement must not have its
 * answer applied to whatever notification now owns the band.
 */
internal fun isTypingCommitStale(openedAtGeneration: Int, currentGeneration: Int): Boolean =
    openedAtGeneration != currentGeneration
