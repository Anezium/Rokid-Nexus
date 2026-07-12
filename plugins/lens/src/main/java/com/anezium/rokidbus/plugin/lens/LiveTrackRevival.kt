package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import kotlin.math.hypot

internal const val LIVE_TRACK_STALE_FRAMES = 3L
internal const val LIVE_TRACK_STALE_HARD_MS = 4_000L
internal const val LIVE_TRACK_GRAVEYARD_MS = 2_500L
internal const val LIVE_TRACK_GRAVEYARD_CAPACITY = 64

private const val LIVE_TRACK_REVIVAL_MAX_CENTER_HEIGHTS = 1.5
private const val LIVE_TRACK_REVIVAL_MAX_EDIT_DISTANCE = 0.25

internal data class LiveTrackRevivalRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}

internal data class LiveTrackRevivalEntry(
    val token: Long,
    val stableId: Long,
    val text: String,
    val bounds: LiveTrackRevivalRect,
    val droppedAtMs: Long,
)

internal data class LiveTrackRevivalDecision(
    val match: LiveTrackRevivalEntry?,
    val remainingEntries: List<LiveTrackRevivalEntry>,
)

/**
 * Drops analyzer tracks by missed-frame age, with a wall-clock backstop for stalled analysis.
 */
internal fun shouldDropLiveTrack(
    currentFrameSerial: Long,
    lastSeenFrameSerial: Long,
    nowMs: Long,
    lastSeenMs: Long,
): Boolean =
    currentFrameSerial - lastSeenFrameSerial >= LIVE_TRACK_STALE_FRAMES ||
        nowMs - lastSeenMs >= LIVE_TRACK_STALE_HARD_MS

/**
 * Pure graveyard matcher. Expired entries are removed and a selected entry is consumed in the
 * returned state, allowing callers to apply the decision without hidden mutable state.
 */
internal fun consumeBestLiveTrackRevival(
    entries: List<LiveTrackRevivalEntry>,
    newText: String,
    newBounds: LiveTrackRevivalRect,
    nowMs: Long,
): LiveTrackRevivalDecision {
    val available = entries.filter { nowMs - it.droppedAtMs < LIVE_TRACK_GRAVEYARD_MS }
    val normalizedNewText = LensWireContract.normalizeText(newText)
    val ranked = available.mapNotNull { entry ->
        val normalizedOldText = LensWireContract.normalizeText(entry.text)
        val editDistance = normalizedLevenshteinDistance(normalizedNewText, normalizedOldText)
        val centerDistance = hypot(
            (newBounds.centerX - entry.bounds.centerX).toDouble(),
            (newBounds.centerY - entry.bounds.centerY).toDouble(),
        )
        val geometryNearby = newBounds.height > 0f &&
            centerDistance <= LIVE_TRACK_REVIVAL_MAX_CENTER_HEIGHTS * newBounds.height
        val compatibleTextNearby = geometryNearby &&
            editDistance <= LIVE_TRACK_REVIVAL_MAX_EDIT_DISTANCE
        if (!compatibleTextNearby) return@mapNotNull null
        RankedRevival(entry, editDistance, centerDistance)
    }
    val match = ranked.minWithOrNull(
        compareBy<RankedRevival> { it.editDistance }
            .thenBy { it.centerDistance }
            .thenBy { it.entry.droppedAtMs }
            .thenBy { it.entry.stableId }
            .thenBy { it.entry.token },
    )?.entry
    return LiveTrackRevivalDecision(
        match = match,
        remainingEntries = if (match == null) available else available.filterNot { it.token == match.token },
    )
}

private data class RankedRevival(
    val entry: LiveTrackRevivalEntry,
    val editDistance: Double,
    val centerDistance: Double,
)

private fun normalizedLevenshteinDistance(a: String, b: String): Double {
    val length = maxOf(a.length, b.length)
    if (length == 0) return 0.0
    var previous = IntArray(b.length + 1) { it }
    var current = IntArray(b.length + 1)
    for (aIndex in 1..a.length) {
        current[0] = aIndex
        for (bIndex in 1..b.length) {
            val substitutionCost = if (a[aIndex - 1] == b[bIndex - 1]) 0 else 1
            current[bIndex] = minOf(
                previous[bIndex] + 1,
                current[bIndex - 1] + 1,
                previous[bIndex - 1] + substitutionCost,
            )
        }
        val swap = previous
        previous = current
        current = swap
    }
    return previous[b.length].toDouble() / length.toDouble()
}

