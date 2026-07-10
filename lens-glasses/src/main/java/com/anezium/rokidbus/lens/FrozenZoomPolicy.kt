package com.anezium.rokidbus.lens

import kotlin.math.roundToInt

internal enum class FrozenZoomLevel(
    val scale: Float,
    val hudLabel: String,
) {
    ONE(1.0f, "1.0x"),
    ONE_POINT_SIX(1.6f, "1.6x"),
    TWO_POINT_FIVE(2.5f, "2.5x"),
}

internal data class FrozenCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/** Pure zoom-cycle and JPEG-coordinate math for native-resolution frozen crops. */
internal object FrozenZoomPolicy {
    fun next(level: FrozenZoomLevel): FrozenZoomLevel =
        when (level) {
            FrozenZoomLevel.ONE -> FrozenZoomLevel.ONE_POINT_SIX
            FrozenZoomLevel.ONE_POINT_SIX -> FrozenZoomLevel.TWO_POINT_FIVE
            FrozenZoomLevel.TWO_POINT_FIVE -> FrozenZoomLevel.ONE
        }

    fun centerCropInRawCoordinates(
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        zoomLevel: FrozenZoomLevel,
    ): FrozenCropRect {
        require(rawWidth > 0 && rawHeight > 0)
        if (zoomLevel == FrozenZoomLevel.ONE) {
            return FrozenCropRect(0, 0, rawWidth, rawHeight)
        }

        val rotation = normalizeRightAngleRotation(rotationDegrees)
        val orientedWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val cropWidth = (orientedWidth / zoomLevel.scale).roundToInt().coerceIn(1, orientedWidth)
        val cropHeight = (orientedHeight / zoomLevel.scale).roundToInt().coerceIn(1, orientedHeight)
        val left = (orientedWidth - cropWidth) / 2
        val top = (orientedHeight - cropHeight) / 2
        return mapOrientedRectToRaw(
            rect = FrozenCropRect(left, top, left + cropWidth, top + cropHeight),
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotation,
        )
    }

    fun mapOrientedRectToRaw(
        rect: FrozenCropRect,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
    ): FrozenCropRect {
        require(rawWidth > 0 && rawHeight > 0)
        val rotation = normalizeRightAngleRotation(rotationDegrees)
        val orientedWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        require(rect.left in 0..orientedWidth && rect.right in 0..orientedWidth)
        require(rect.top in 0..orientedHeight && rect.bottom in 0..orientedHeight)
        require(rect.left < rect.right && rect.top < rect.bottom)

        return when (rotation) {
            0 -> rect
            90 -> FrozenCropRect(
                left = rect.top,
                top = rawHeight - rect.right,
                right = rect.bottom,
                bottom = rawHeight - rect.left,
            )
            180 -> FrozenCropRect(
                left = rawWidth - rect.right,
                top = rawHeight - rect.bottom,
                right = rawWidth - rect.left,
                bottom = rawHeight - rect.top,
            )
            270 -> FrozenCropRect(
                left = rawWidth - rect.bottom,
                top = rect.left,
                right = rawWidth - rect.top,
                bottom = rect.right,
            )
            else -> error("unreachable rotation=$rotation")
        }
    }

    private fun normalizeRightAngleRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "rotation must be a multiple of 90: $rotationDegrees" }
        return normalized
    }
}
