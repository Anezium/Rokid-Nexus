package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveTrackRevivalTest {
    @Test
    fun exactNormalizedTextStillRequiresNearbyGeometry() {
        val entry = entry(1, "  exact   text ", left = 500f)

        val decision = consumeBestLiveTrackRevival(
            entries = listOf(entry),
            newText = "exact text",
            newBounds = rect(left = 0f),
            nowMs = 1_000L,
        )

        assertNull(decision.match)
    }

    @Test
    fun fuzzyTextMatchesWithinOneAndAHalfNewRectHeights() {
        val decision = consumeBestLiveTrackRevival(
            entries = listOf(entry(2, "dense text", left = 12f)),
            newText = "dense test",
            newBounds = rect(left = 0f),
            nowMs = 1_000L,
        )

        assertEquals(2L, decision.match?.stableId)
    }

    @Test
    fun fuzzyTextMissesBeyondDistanceLimit() {
        val decision = consumeBestLiveTrackRevival(
            entries = listOf(entry(3, "dense text", left = 31f)),
            newText = "dense test",
            newBounds = rect(left = 0f),
            nowMs = 1_000L,
        )

        assertNull(decision.match)
    }

    @Test
    fun expiredEntryCannotMatch() {
        val decision = consumeBestLiveTrackRevival(
            entries = listOf(entry(4, "same", droppedAtMs = 0L)),
            newText = "same",
            newBounds = rect(),
            nowMs = LIVE_TRACK_GRAVEYARD_MS,
        )

        assertNull(decision.match)
        assertTrue(decision.remainingEntries.isEmpty())
    }

    @Test
    fun matchedEntryIsConsumedAndCannotDoubleInherit() {
        val first = consumeBestLiveTrackRevival(
            entries = listOf(entry(5, "same")),
            newText = "same",
            newBounds = rect(),
            nowMs = 1_000L,
        )
        val second = consumeBestLiveTrackRevival(
            entries = first.remainingEntries,
            newText = "same",
            newBounds = rect(),
            nowMs = 1_001L,
        )

        assertEquals(5L, first.match?.stableId)
        assertNull(second.match)
    }

    @Test
    fun bestMatchUsesEditDistanceThenCenterDistanceThenOldestDrop() {
        val target = rect()
        val lowestEdit = consumeBestLiveTrackRevival(
            entries = listOf(
                entry(10, "dense test", left = 1f),
                entry(11, "dense text", left = 12f),
            ),
            newText = "dense text",
            newBounds = target,
            nowMs = 1_000L,
        )
        val nearest = consumeBestLiveTrackRevival(
            entries = listOf(
                entry(20, "dense text", left = 10f),
                entry(21, "dense text", left = 2f),
            ),
            newText = "dense text",
            newBounds = target,
            nowMs = 1_000L,
        )
        val oldestEntries = listOf(
            entry(30, "dense text", droppedAtMs = 800L),
            entry(31, "dense text", droppedAtMs = 700L),
        )
        val oldest = consumeBestLiveTrackRevival(oldestEntries, "dense text", target, 1_000L)
        val oldestReversed = consumeBestLiveTrackRevival(oldestEntries.reversed(), "dense text", target, 1_000L)

        assertEquals(11L, lowestEdit.match?.stableId)
        assertEquals(21L, nearest.match?.stableId)
        assertEquals(31L, oldest.match?.stableId)
        assertEquals(oldest.match, oldestReversed.match)
    }

    @Test
    fun staleDropUsesThreeMissedFramesOrFourSecondBackstop() {
        assertFalse(shouldDropLiveTrack(12L, 10L, nowMs = 3_999L, lastSeenMs = 0L))
        assertTrue(shouldDropLiveTrack(13L, 10L, nowMs = 100L, lastSeenMs = 0L))
        assertTrue(shouldDropLiveTrack(11L, 10L, nowMs = 4_000L, lastSeenMs = 0L))
    }

    private fun entry(
        stableId: Long,
        text: String,
        left: Float = 0f,
        droppedAtMs: Long = 500L,
    ): LiveTrackRevivalEntry =
        LiveTrackRevivalEntry(
            token = stableId,
            stableId = stableId,
            text = text,
            bounds = rect(left),
            droppedAtMs = droppedAtMs,
        )

    private fun rect(left: Float = 0f): LiveTrackRevivalRect =
        LiveTrackRevivalRect(left = left, top = 0f, right = left + 20f, bottom = 20f)
}
