package com.anezium.rokidbus.glasses

import kotlin.math.roundToInt

internal enum class LiveZoomLevel(val scale: Float, val hudLabel: String) {
    ONE(1.0f, "1.0x"),
    ONE_POINT_FIVE(1.5f, "1.5x"),
    TWO(2.0f, "2.0x"),
}

internal data class LiveCropRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Port of Lens' discrete live zoom policy, now applied to the camera2 crop region. */
internal object LiveZoomPolicy {
    fun zoomIn(level: LiveZoomLevel): LiveZoomLevel =
        LiveZoomLevel.entries[(level.ordinal + 1).coerceAtMost(LiveZoomLevel.entries.lastIndex)]

    fun zoomOut(level: LiveZoomLevel): LiveZoomLevel =
        LiveZoomLevel.entries[(level.ordinal - 1).coerceAtLeast(0)]

    fun centerCrop(width: Int, height: Int, level: LiveZoomLevel): LiveCropRect {
        require(width > 0 && height > 0)
        val cropWidth = centeredSize(width, level.scale)
        val cropHeight = centeredSize(height, level.scale)
        val left = (width - cropWidth) / 2
        val top = (height - cropHeight) / 2
        return LiveCropRect(left, top, left + cropWidth, top + cropHeight)
    }

    private fun centeredSize(fullSize: Int, zoomScale: Float): Int {
        var size = (fullSize / zoomScale).roundToInt().coerceIn(1, fullSize)
        if ((size and 1) != (fullSize and 1)) {
            size = if (size > 1) size - 1 else (size + 1).coerceAtMost(fullSize)
        }
        return size
    }
}
