package com.anezium.rokidbus.plugin.transit

data class BoardCursor(
    val stopIndex: Int = 0,
    val pageIndex: Int = 0,
)

class BoardPager {
    private var stopIds: List<String> = emptyList()
    private var pageCounts: List<Int> = emptyList()
    var cursor: BoardCursor = BoardCursor()
        private set

    fun reset() {
        stopIds = emptyList()
        pageCounts = emptyList()
        cursor = BoardCursor()
    }

    fun updateStops(newStopIds: List<String>, newPageCounts: List<Int>) {
        val oldStopId = stopIds.getOrNull(cursor.stopIndex)
        val nextPageCounts = newStopIds.indices.map { index ->
            newPageCounts.getOrNull(index)?.coerceAtLeast(1) ?: 1
        }
        stopIds = newStopIds
        pageCounts = nextPageCounts
        if (stopIds.isEmpty()) {
            cursor = BoardCursor()
            return
        }

        val nextStopIndex = cursor.stopIndex.coerceIn(0, stopIds.lastIndex)
        val sameStop = oldStopId != null && stopIds.getOrNull(nextStopIndex) == oldStopId
        val nextPageIndex = if (sameStop) {
            cursor.pageIndex.coerceIn(0, pageCounts[nextStopIndex] - 1)
        } else {
            0
        }
        cursor = BoardCursor(nextStopIndex, nextPageIndex)
    }

    fun forward() {
        if (stopIds.isEmpty()) return
        val currentPageCount = pageCounts[cursor.stopIndex].coerceAtLeast(1)
        cursor = if (cursor.pageIndex + 1 < currentPageCount) {
            cursor.copy(pageIndex = cursor.pageIndex + 1)
        } else {
            BoardCursor(
                stopIndex = (cursor.stopIndex + 1) % stopIds.size,
                pageIndex = 0,
            )
        }
    }

    fun backward() {
        if (stopIds.isEmpty()) return
        cursor = if (cursor.pageIndex > 0) {
            cursor.copy(pageIndex = cursor.pageIndex - 1)
        } else {
            val nextStopIndex = (cursor.stopIndex - 1 + stopIds.size) % stopIds.size
            BoardCursor(
                stopIndex = nextStopIndex,
                pageIndex = pageCounts[nextStopIndex].coerceAtLeast(1) - 1,
            )
        }
    }

    fun nextStop() {
        if (stopIds.isEmpty()) return
        cursor = BoardCursor(
            stopIndex = (cursor.stopIndex + 1) % stopIds.size,
            pageIndex = 0,
        )
    }
}
