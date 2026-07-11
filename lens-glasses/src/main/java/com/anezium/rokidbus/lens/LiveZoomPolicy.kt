package com.anezium.rokidbus.lens

import kotlin.math.roundToInt

internal enum class LiveZoomLevel(
    val scale: Float,
    val hudLabel: String,
) {
    ONE(1.0f, "1.0x"),
    ONE_POINT_FIVE(1.5f, "1.5x"),
    TWO(2.0f, "2.0x"),
}

internal data class LiveCropRect(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

internal data class LiveNv21Crop(
    val raw: LiveCropRect,
    val oriented: LiveCropRect,
)

/** Android-free crop and 3x3 transform math used by the live OCR pipeline. */
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

    fun nv21Crop(
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
        level: LiveZoomLevel,
    ): LiveNv21Crop {
        require(rawWidth > 0 && rawHeight > 0)
        val rotation = normalizeRightAngleRotation(rotationDegrees)
        val orientedWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        val requestedOrientedCrop = centerCrop(orientedWidth, orientedHeight, level)
        val rawCrop = mapOrientedCropToEvenRaw(
            rect = requestedOrientedCrop,
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotation,
        )
        return LiveNv21Crop(
            raw = rawCrop,
            oriented = mapRawRectToOriented(
                rect = rawCrop,
                rawWidth = rawWidth,
                rawHeight = rawHeight,
                rotationDegrees = rotation,
            ),
        )
    }

    fun mapOrientedCropToEvenRaw(
        rect: LiveCropRect,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
    ): LiveCropRect {
        val mapped = FrozenZoomPolicy.mapOrientedRectToRaw(
            rect = rect.toFrozenCropRect(),
            rawWidth = rawWidth,
            rawHeight = rawHeight,
            rotationDegrees = rotationDegrees,
        )
        val evenLeft = mapped.left and -2
        val evenTop = mapped.top and -2
        val evenWidth = mapped.width and -2
        val evenHeight = mapped.height and -2
        require(evenWidth > 0 && evenHeight > 0) { "NV21 crop must contain at least one chroma sample" }
        return LiveCropRect(
            left = evenLeft,
            top = evenTop,
            right = evenLeft + evenWidth,
            bottom = evenTop + evenHeight,
        )
    }

    /** Returns frameToView * cropToFrame for Android Matrix-compatible row-major values. */
    fun composeCropToView(frameToView: FloatArray, crop: LiveCropRect): FloatArray {
        require(frameToView.size == 9)
        val left = crop.left.toFloat()
        val top = crop.top.toFloat()
        return frameToView.copyOf().also { result ->
            result[2] = frameToView[0] * left + frameToView[1] * top + frameToView[2]
            result[5] = frameToView[3] * left + frameToView[4] * top + frameToView[5]
            result[8] = frameToView[6] * left + frameToView[7] * top + frameToView[8]
        }
    }

    /** Returns scaleAboutViewCenter * transform for Android Matrix-compatible row-major values. */
    fun postScaleAboutViewCenter(
        transform: FloatArray,
        scale: Float,
        viewWidth: Float,
        viewHeight: Float,
    ): FloatArray {
        require(transform.size == 9)
        require(scale > 0f)
        val pivotX = viewWidth / 2f
        val pivotY = viewHeight / 2f
        val translateX = pivotX * (1f - scale)
        val translateY = pivotY * (1f - scale)
        return floatArrayOf(
            scale * transform[0] + translateX * transform[6],
            scale * transform[1] + translateX * transform[7],
            scale * transform[2] + translateX * transform[8],
            scale * transform[3] + translateY * transform[6],
            scale * transform[4] + translateY * transform[7],
            scale * transform[5] + translateY * transform[8],
            transform[6],
            transform[7],
            transform[8],
        )
    }

    fun mapPoint(transform: FloatArray, x: Float, y: Float): Pair<Float, Float> {
        require(transform.size == 9)
        val denominator = transform[6] * x + transform[7] * y + transform[8]
        require(denominator != 0f)
        return Pair(
            (transform[0] * x + transform[1] * y + transform[2]) / denominator,
            (transform[3] * x + transform[4] * y + transform[5]) / denominator,
        )
    }

    private fun centeredSize(fullSize: Int, zoomScale: Float): Int {
        var size = (fullSize / zoomScale).roundToInt().coerceIn(1, fullSize)
        if ((size and 1) != (fullSize and 1)) {
            size = if (size > 1) size - 1 else (size + 1).coerceAtMost(fullSize)
        }
        return size
    }

    private fun mapRawRectToOriented(
        rect: LiveCropRect,
        rawWidth: Int,
        rawHeight: Int,
        rotationDegrees: Int,
    ): LiveCropRect {
        val rotation = normalizeRightAngleRotation(rotationDegrees)
        val orientedWidth = if (rotation == 90 || rotation == 270) rawHeight else rawWidth
        val orientedHeight = if (rotation == 90 || rotation == 270) rawWidth else rawHeight
        return FrozenZoomPolicy.mapOrientedRectToRaw(
            rect = rect.toFrozenCropRect(),
            rawWidth = orientedWidth,
            rawHeight = orientedHeight,
            rotationDegrees = (360 - rotation) % 360,
        ).toLiveCropRect()
    }

    private fun normalizeRightAngleRotation(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "rotation must be a multiple of 90: $rotationDegrees" }
        return normalized
    }

    private fun LiveCropRect.toFrozenCropRect(): FrozenCropRect =
        FrozenCropRect(left, top, right, bottom)

    private fun FrozenCropRect.toLiveCropRect(): LiveCropRect =
        LiveCropRect(left, top, right, bottom)
}
