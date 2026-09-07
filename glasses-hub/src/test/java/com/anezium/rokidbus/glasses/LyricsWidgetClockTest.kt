package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.WidgetAnchor
import com.anezium.rokidbus.shared.WidgetTimedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LyricsWidgetClockTest {
    private val lines = listOf(
        WidgetTimedLine(0, "line one"),
        WidgetTimedLine(1_000, "line two"),
        WidgetTimedLine(2_000, "line three"),
    )

    @Test
    fun `advances the current line locally from the anchor without new messages`() {
        val sentAt = 100L
        val clock = LyricsWidgetClock(lines, WidgetAnchor(positionMs = 0, playing = true, sentAtElapsedRealtime = sentAt))

        assertEquals(0, clock.currentIndexAt(200))
        // 800 ms of local elapsed (now=1000-100) is before line one's start (timeMs 1000).
        assertEquals(0, clock.currentIndexAt(200 + 800))
        // At exactly line one's start (timeMs 1000) the index moves to line two.
        assertEquals(1, clock.currentIndexAt(200 + 900))
        assertEquals(1, clock.currentIndexAt(200 + 1_100))
        assertEquals(2, clock.currentIndexAt(200 + 2_100))
        // The last line is stable; it never advances past the final entry.
        assertEquals(2, clock.currentIndexAt(200 + 10_000))
    }

    @Test
    fun `paused anchor freezes the current line until an update arrives`() {
        val clock = LyricsWidgetClock(lines, WidgetAnchor(positionMs = 100, playing = false, sentAtElapsedRealtime = 100))
        assertEquals(0, clock.currentIndexAt(100))
        assertEquals(0, clock.currentIndexAt(100 + 5_000))
    }

    @Test
    fun `anchor update after seek selects the new line`() {
        val clock = LyricsWidgetClock(lines, WidgetAnchor(positionMs = 0, playing = true, sentAtElapsedRealtime = 100))
        clock.updateAnchor(WidgetAnchor(positionMs = 1_500, playing = true, sentAtElapsedRealtime = 10_000))
        assertEquals(1, clock.currentIndexAt(10_000))
    }

    @Test
    fun `empty clock returns a clamped index and no next`() {
        val clock = LyricsWidgetClock(emptyList(), WidgetAnchor(0, true, 0))
        assertEquals(0, clock.currentIndexAt(0))
        assertNull(clock.nextIndexFrom(0))
    }
    @Test fun `instrumental lead-in does not show a lyric before its timestamp`() {
        val clock = LyricsWidgetClock(listOf(WidgetTimedLine(5_000L, "first")), WidgetAnchor(0, true, 10_000))
        assertEquals(-1, clock.currentIndexAt(14_999))
        assertEquals(0, clock.currentIndexAt(15_000))
    }

}