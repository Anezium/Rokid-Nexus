package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherWindowPolicyTest {
    @Test
    fun `visible rows use the measured height and full row stride`() {
        assertEquals(1, LauncherWindowPolicy.visibleRowCount(0, 60))
        assertEquals(4, LauncherWindowPolicy.visibleRowCount(299, 60))
        assertEquals(5, LauncherWindowPolicy.visibleRowCount(300, 60))
    }

    @Test
    fun `short lists show every entry without hints`() {
        listOf(1, 3, 5).forEach { entryCount ->
            val window = LauncherWindowPolicy.window(
                entryCount = entryCount,
                visibleRows = 8,
                selectedIndex = entryCount - 1,
            )

            assertEquals(0, window.startIndex)
            assertEquals(entryCount, window.endIndexExclusive)
            assertFalse(window.hasEntriesAbove)
            assertFalse(window.hasEntriesBelow)
        }
    }

    @Test
    fun `selection at the top keeps the first window visible`() {
        val window = LauncherWindowPolicy.window(entryCount = 8, visibleRows = 4, selectedIndex = 0)

        assertEquals(0, window.startIndex)
        assertEquals(4, window.endIndexExclusive)
        assertFalse(window.hasEntriesAbove)
        assertTrue(window.hasEntriesBelow)
    }

    @Test
    fun `middle selection moves the window with hints on both sides`() {
        val window = LauncherWindowPolicy.window(entryCount = 45, visibleRows = 4, selectedIndex = 20)

        assertEquals(17, window.startIndex)
        assertEquals(21, window.endIndexExclusive)
        assertTrue(window.hasEntriesAbove)
        assertTrue(window.hasEntriesBelow)
    }

    @Test
    fun `window moves only after selection crosses an edge`() {
        val atBottomEdge = LauncherWindowPolicy.window(8, 4, selectedIndex = 3, currentStartIndex = 0)
        val crossedBottom = LauncherWindowPolicy.window(8, 4, selectedIndex = 4, currentStartIndex = 0)
        val movedBackInside = LauncherWindowPolicy.window(
            entryCount = 8,
            visibleRows = 4,
            selectedIndex = 3,
            currentStartIndex = crossedBottom.startIndex,
        )

        assertEquals(0, atBottomEdge.startIndex)
        assertEquals(1, crossedBottom.startIndex)
        assertEquals(1, movedBackInside.startIndex)
    }

    @Test
    fun `selection at the bottom keeps the final window visible`() {
        val window = LauncherWindowPolicy.window(entryCount = 45, visibleRows = 4, selectedIndex = 44)

        assertEquals(41, window.startIndex)
        assertEquals(45, window.endIndexExclusive)
        assertTrue(window.hasEntriesAbove)
        assertFalse(window.hasEntriesBelow)
    }

    @Test
    fun `wrap around rewindows from the last entry to the first`() {
        val last = LauncherWindowPolicy.window(entryCount = 45, visibleRows = 4, selectedIndex = 44)
        val first = LauncherWindowPolicy.window(
            entryCount = 45,
            visibleRows = 4,
            selectedIndex = 0,
            currentStartIndex = last.startIndex,
        )

        assertTrue(44 in last.startIndex until last.endIndexExclusive)
        assertTrue(0 in first.startIndex until first.endIndexExclusive)
        assertEquals(41, last.startIndex)
        assertEquals(0, first.startIndex)
    }

    @Test
    fun `every entry in a large list can be selected inside a full window`() {
        var startIndex = 0
        repeat(45) { selectedIndex ->
            val window = LauncherWindowPolicy.window(45, 4, selectedIndex, startIndex)

            assertEquals(4, window.endIndexExclusive - window.startIndex)
            assertTrue(selectedIndex in window.startIndex until window.endIndexExclusive)
            startIndex = window.startIndex
        }
    }
}
