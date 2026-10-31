package com.anezium.rokidbus.media

import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import kotlin.math.abs

/** Position anchors change on a seek or speed change, never on ordinary advancing callbacks. */
internal class MediaDeckPageProgress {
    var snapshot: MediaDeckSnapshot? = null
        private set
    private var observedAtMs = 0L
    var revision = 0L
        private set

    fun observe(next: MediaDeckSnapshot?, nowMs: Long): Boolean {
        // Artwork decoding can notify again with the exact observation, whose anchor is older.
        if (next === snapshot) return false
        val previous = snapshot
        val expected = position(nowMs)
        val moved = when {
            previous == null || next == null -> previous != next
            previous.identity != next.identity || previous.track.key != next.track.key -> true
            previous.advancing != next.advancing || previous.playbackSpeed != next.playbackSpeed -> true
            else -> abs(next.positionMs - (expected ?: 0)) > 1_000L
        }
        snapshot = next
        observedAtMs = nowMs
        if (moved) revision++
        return moved
    }

    fun position(nowMs: Long): Long? {
        val current = snapshot ?: return null
        val elapsed = if (current.advancing) (nowMs - observedAtMs).coerceAtLeast(0) else 0L
        val speed = current.playbackSpeed.takeIf { it.isFinite() }?.coerceIn(-16f, 16f) ?: 0f
        return (current.positionMs + (elapsed * speed).toLong()).coerceIn(0, current.durationMs?.coerceAtLeast(0) ?: 604_800_000L)
    }
}
