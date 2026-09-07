package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.WidgetAnchor
import com.anezium.rokidbus.shared.WidgetTimedLine

/**
 * The glasses-side lyric clock for the ambient widget. It holds the full timed lines plus a
 * playback anchor and selects the current/next line purely from elapsed time — mirroring the
 * timed-lines surface math, so the ambient widget and full-screen surface stay identical.
 *
 * A widget show carries lines once with an anchor; from then on the glasses advance locally
 * ([advanceTo]) until a seek/drift/pause brings a fresh anchor via update. When the anchor is
 * paused the position freezes, so the current line stays put.
 *
 * Android-free: production passes `SystemClock.elapsedRealtime()`, tests pass explicit values.
 */
class LyricsWidgetClock(
    private val timedLines: List<WidgetTimedLine> = emptyList(),
    private var anchor: WidgetAnchor,
) {
    val lineCount: Int
        get() = timedLines.size

    /** The line index active at [now]. Never advances past the last line. */
    fun currentIndexAt(now: Long): Int {
        val position = effectivePositionMs(anchor, now)
        var candidate = -1
        for (index in timedLines.indices) {
            if (timedLines[index].timeMs <= position) candidate = index else break
        }
        return candidate
    }

    fun line(index: Int): WidgetTimedLine? = timedLines.getOrNull(index)

    fun nextIndexFrom(current: Int): Int? = (current + 1).takeIf { it < timedLines.size }

    /** Replace the anchor (seek/drift/pause); line content is unchanged. */
    fun updateAnchor(newAnchor: WidgetAnchor) {
        anchor = newAnchor
    }

    val currentAnchor: WidgetAnchor
        get() = anchor

    companion object {
        /** Extrapolate position from the anchor the same way the surface does. */
        fun effectivePositionMs(anchor: WidgetAnchor, now: Long): Long {
            if (!anchor.playing) return anchor.positionMs
            val localElapsed = (now - anchor.sentAtElapsedRealtime).coerceAtLeast(0L)
            return anchor.positionMs + localElapsed
        }
    }
}