package com.anezium.rokidbus.glasses

internal data class LauncherWindow(
    val startIndex: Int,
    val endIndexExclusive: Int,
    val hasEntriesAbove: Boolean,
    val hasEntriesBelow: Boolean,
)

internal object LauncherWindowPolicy {
    fun visibleRowCount(availableHeightPx: Int, rowStridePx: Int): Int {
        require(rowStridePx > 0)
        return (availableHeightPx.coerceAtLeast(0) / rowStridePx).coerceAtLeast(1)
    }

    fun window(
        entryCount: Int,
        visibleRows: Int,
        selectedIndex: Int,
        currentStartIndex: Int = 0,
    ): LauncherWindow {
        require(entryCount >= 0)
        require(visibleRows > 0)
        if (entryCount == 0) {
            return LauncherWindow(0, 0, hasEntriesAbove = false, hasEntriesBelow = false)
        }
        require(selectedIndex in 0 until entryCount)

        val windowSize = visibleRows.coerceAtMost(entryCount)
        val maximumStart = entryCount - windowSize
        val currentStart = currentStartIndex.coerceIn(0, maximumStart)
        val start = when {
            selectedIndex < currentStart -> selectedIndex
            selectedIndex >= currentStart + windowSize -> selectedIndex - windowSize + 1
            else -> currentStart
        }.coerceIn(0, maximumStart)
        val end = start + windowSize
        return LauncherWindow(
            startIndex = start,
            endIndexExclusive = end,
            hasEntriesAbove = start > 0,
            hasEntriesBelow = end < entryCount,
        )
    }
}
