package com.anezium.rokidbus.lyrics.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRuntimeEngineTest {
    @Test
    fun shouldPreserveVisibleLyrics_returnsTrueForSameTrack() {
        val snapshot = LyricsSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            trackTitle = " Need Your Love ",
            artistName = "OneRepublic",
            provider = "NETEASE",
            synced = true,
            lines = listOf(LyricsLine(startTimeMs = 1000L, text = "Line 1")),
        )

        val preserve = shouldPreserveVisibleLyrics(
            current = snapshot,
            request = LyricsLookupRequest(
                title = "need your love",
                artist = "onerepublic",
                album = "Human",
                durationSeconds = 209,
            ),
        )

        assertTrue(preserve)
    }

    @Test
    fun buildLookupLoadingSnapshot_keepsResolvedLyricsWhenPreserving() {
        val snapshot = LyricsSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            trackTitle = "Need Your Love",
            artistName = "OneRepublic",
            albumName = "Human",
            durationSeconds = 209,
            provider = "NETEASE",
            sourceSummary = "NETEASE provided synced lyrics.",
            synced = true,
            progressMs = 42000L,
            currentLineIndex = 1,
            lines = listOf(
                LyricsLine(startTimeMs = 1000L, text = "Line 1"),
                LyricsLine(startTimeMs = 2000L, text = "Line 2"),
            ),
        )

        val loadingSnapshot = buildLookupLoadingSnapshot(
            current = snapshot,
            request = LyricsLookupRequest(
                title = "Need Your Love",
                artist = "OneRepublic",
                album = "Human",
                durationSeconds = 209,
            ),
            fromMedia = true,
            mediaSourceLabel = "Spotify",
            preserveVisibleLyrics = true,
        )

        assertEquals(LyricsSessionState.READY, loadingSnapshot.sessionState)
        assertEquals("NETEASE", loadingSnapshot.provider)
        assertTrue(loadingSnapshot.synced)
        assertEquals(2, loadingSnapshot.lines.size)
        assertEquals("NETEASE provided synced lyrics.", loadingSnapshot.sourceSummary)
        assertNull(loadingSnapshot.errorMessage)
    }

    @Test
    fun buildLookupFailureSnapshot_keepsPreviousLyricsVisible() {
        val preserved = LyricsSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            trackTitle = "Need Your Love",
            artistName = "OneRepublic",
            provider = "NETEASE",
            sourceSummary = "NETEASE provided synced lyrics.",
            synced = true,
            lines = listOf(LyricsLine(startTimeMs = 1000L, text = "Line 1")),
        )

        val failureSnapshot = buildLookupFailureSnapshot(
            current = LyricsSnapshot(sessionState = LyricsSessionState.LOADING),
            errorMessage = "timeout",
            preservedSnapshot = preserved,
        )

        assertEquals(LyricsSessionState.READY, failureSnapshot.sessionState)
        assertEquals("NETEASE", failureSnapshot.provider)
        assertEquals(1, failureSnapshot.lines.size)
        assertNull(failureSnapshot.errorMessage)
        assertFalse(failureSnapshot.sourceSummary.isBlank())
    }
}
