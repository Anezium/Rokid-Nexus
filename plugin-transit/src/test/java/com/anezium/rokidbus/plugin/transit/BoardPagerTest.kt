package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Test

class BoardPagerTest {
    @Test
    fun forwardWrapsAcrossStopsWithDifferentPageCounts() {
        val pager = BoardPager()
        pager.updateStops(listOf("a", "b", "c"), listOf(2, 1, 3))

        assertEquals(BoardCursor(0, 0), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(0, 1), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(1, 0), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(2, 0), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(2, 1), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(2, 2), pager.cursor)
        pager.forward()
        assertEquals(BoardCursor(0, 0), pager.cursor)
    }

    @Test
    fun backwardWrapsAcrossStopsWithDifferentPageCounts() {
        val pager = BoardPager()
        pager.updateStops(listOf("a", "b", "c"), listOf(2, 1, 3))

        pager.backward()
        assertEquals(BoardCursor(2, 2), pager.cursor)
        pager.backward()
        assertEquals(BoardCursor(2, 1), pager.cursor)
        pager.backward()
        assertEquals(BoardCursor(2, 0), pager.cursor)
        pager.backward()
        assertEquals(BoardCursor(1, 0), pager.cursor)
        pager.backward()
        assertEquals(BoardCursor(0, 1), pager.cursor)
    }

    @Test
    fun nextStopMovesToPageZeroAndWraps() {
        val pager = BoardPager()
        pager.updateStops(listOf("a", "b"), listOf(3, 2))
        pager.forward()
        pager.forward()

        assertEquals(BoardCursor(0, 2), pager.cursor)
        pager.nextStop()
        assertEquals(BoardCursor(1, 0), pager.cursor)
        pager.nextStop()
        assertEquals(BoardCursor(0, 0), pager.cursor)
    }

    @Test
    fun updateStopsClampsAfterPageCountShrinkAndResetsWhenStopIdentityChanges() {
        val pager = BoardPager()
        pager.updateStops(listOf("a", "b"), listOf(3, 1))
        pager.forward()
        pager.forward()

        pager.updateStops(listOf("a", "b"), listOf(1, 1))
        assertEquals(BoardCursor(0, 0), pager.cursor)

        pager.forward()
        assertEquals(BoardCursor(1, 0), pager.cursor)
        pager.updateStops(listOf("a", "c"), listOf(1, 4))
        assertEquals(BoardCursor(1, 0), pager.cursor)
    }
}
