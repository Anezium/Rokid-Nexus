package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.lyrics.settings.LyricsWidgetMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsWidgetDriverTest {
    private class Harness {
        var now = 0L
        val driver = LyricsWidgetDriver(now = { now }, graceMs = 5_000L)

        fun decide(
            snapshot: LyricsSnapshot?,
            mode: LyricsWidgetMode = LyricsWidgetMode.KARAOKE,
            fullScreenVisible: Boolean = false,
        ) = driver.decide(mode, fullScreenVisible, snapshot)
    }

    @Test
    fun playingWithSyncedLyricsShowsTheWidget() {
        val h = Harness()
        val decision = h.decide(playingSynced())
        assertTrue(decision.show)
        assertFalse(decision.hide)
        assertTrue(h.driver.isShowing)
    }

    @Test
    fun pauseLongerThanGraceHidesTheWidget() {
        val h = Harness()
        h.decide(playingSynced())
        assertTrue(h.driver.isShowing)

        h.now = 1_000
        val stillHeld = h.decide(pausedSynced())
        assertFalse(stillHeld.show)
        assertFalse(stillHeld.hide)

        h.now = 6_000
        val hidden = h.decide(pausedSynced())
        assertFalse(hidden.show)
        assertTrue(hidden.hide)
        assertFalse(h.driver.isShowing)
    }

    @Test
    fun noLyricsDoesNotShow() {
        val h = Harness()
        val unsynced = LyricsSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            synced = false,
            lines = emptyList(),
        )
        val decision = h.decide(unsynced)
        assertFalse(decision.show)
        assertFalse(decision.hide)
        assertFalse(h.driver.isShowing)
    }

    private fun playingSynced(): LyricsSnapshot = LyricsSnapshot(
        sessionState = LyricsSessionState.PLAYING,
        trackTitle = "Track",
        synced = true,
        lines = listOf(LyricsLine(0L, "one"), LyricsLine(1_000L, "two")),
    )

    private fun pausedSynced(): LyricsSnapshot =
        playingSynced().copy(sessionState = LyricsSessionState.READY)
}
