package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import com.anezium.rokidbus.shared.CameraOverlayItem
import kotlin.math.max

internal data class CameraFillCenterViewport(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val displayedWidth: Float,
    val displayedHeight: Float,
)

internal data class CameraSourceViewport(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

internal data class CameraLiveFrameGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val remainingRotationDegrees: Int,
) {
    val orientedSize: CameraPixelSize
        get() = CameraOrientation.orientedSize(rawWidth, rawHeight, remainingRotationDegrees)
}

internal data class CameraPreviewConsumerGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val clockwiseRotationDegrees: Int,
) {
    val orientedSize: CameraPixelSize
        get() = CameraOrientation.orientedSize(rawWidth, rawHeight, clockwiseRotationDegrees)
}

/** Maps oriented stream coordinates through PreviewView's old FILL_CENTER viewport policy. */
internal object CameraPreviewGeometry {
    fun previewConsumerGeometry(streamPlan: CameraStreamPlan): CameraPreviewConsumerGeometry =
        CameraPreviewConsumerGeometry(
            // SurfaceTexture already presents the camera producer transform locally. Use the
            // logical portrait dimensions for aspect correction without applying the encoded
            // consumer's remaining quarter turn a second time.
            rawWidth = streamPlan.orientedSize.width,
            rawHeight = streamPlan.orientedSize.height,
            clockwiseRotationDegrees = 0,
        )

    /**
     * Crop inside a frozen frame that reproduces the field shown by the live stream and its
     * FILL_CENTER viewport. The full JPEG remains available to OCR; only presentation is cropped.
     */
    fun matchingFrozenSourceViewport(
        frozenWidth: Int,
        frozenHeight: Int,
        liveWidth: Int,
        liveHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): CameraSourceViewport? {
        if (frozenWidth <= 0 || frozenHeight <= 0 || liveWidth <= 0 || liveHeight <= 0 ||
            viewWidth <= 0 || viewHeight <= 0
        ) return null
        val liveAspect = liveWidth.toFloat() / liveHeight
        val viewAspect = viewWidth.toFloat() / viewHeight
        val frozenAspect = frozenWidth.toFloat() / frozenHeight
        val liveCropWidth: Float
        val liveCropHeight: Float
        if (frozenAspect > liveAspect) {
            liveCropHeight = frozenHeight.toFloat()
            liveCropWidth = liveCropHeight * liveAspect
        } else {
            liveCropWidth = frozenWidth.toFloat()
            liveCropHeight = liveCropWidth / liveAspect
        }
        val displayedWidth: Float
        val displayedHeight: Float
        if (liveAspect > viewAspect) {
            displayedHeight = liveCropHeight
            displayedWidth = displayedHeight * viewAspect
        } else {
            displayedWidth = liveCropWidth
            displayedHeight = displayedWidth / viewAspect
        }
        val centerX = frozenWidth / 2f
        val centerY = frozenHeight / 2f
        return CameraSourceViewport(
            left = centerX - displayedWidth / 2f,
            top = centerY - displayedHeight / 2f,
            right = centerX + displayedWidth / 2f,
            bottom = centerY + displayedHeight / 2f,
        )
    }

    fun fillCenterViewport(
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): CameraFillCenterViewport? {
        if (sourceWidth <= 0 || sourceHeight <= 0 || viewWidth <= 0 || viewHeight <= 0) return null
        val scale = max(
            viewWidth.toFloat() / sourceWidth.toFloat(),
            viewHeight.toFloat() / sourceHeight.toFloat(),
        )
        val displayedWidth = sourceWidth * scale
        val displayedHeight = sourceHeight * scale
        return CameraFillCenterViewport(
            scale = scale,
            offsetX = (viewWidth - displayedWidth) / 2f,
            offsetY = (viewHeight - displayedHeight) / 2f,
            displayedWidth = displayedWidth,
            displayedHeight = displayedHeight,
        )
    }

    /** Destination corners for TextureView's default-stretched raw buffer, in raw corner order. */
    fun textureDestinationCorners(
        geometry: CameraPreviewConsumerGeometry,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray? {
        if (geometry.rawWidth <= 0 || geometry.rawHeight <= 0) return null
        val rotation = CameraOrientation.normalizeRightAngle(geometry.clockwiseRotationDegrees)
        val oriented = geometry.orientedSize
        val viewport = fillCenterViewport(oriented.width, oriented.height, viewWidth, viewHeight) ?: return null
        val rawCorners = intArrayOf(
            0, 0,
            geometry.rawWidth, 0,
            geometry.rawWidth, geometry.rawHeight,
            0, geometry.rawHeight,
        )
        return FloatArray(rawCorners.size).also { destination ->
            for (index in rawCorners.indices step 2) {
                val x = rawCorners[index]
                val y = rawCorners[index + 1]
                val (orientedX, orientedY) = when (rotation) {
                    0 -> x to y
                    90 -> geometry.rawHeight - y to x
                    180 -> geometry.rawWidth - x to geometry.rawHeight - y
                    270 -> y to geometry.rawWidth - x
                    else -> error("unreachable")
                }
                destination[index] = orientedX * viewport.scale + viewport.offsetX
                destination[index + 1] = orientedY * viewport.scale + viewport.offsetY
            }
        }
    }

    fun mapFillCenterDistanceY(
        normalizedDistance: Float,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): Float? {
        if (!normalizedDistance.isFinite() || normalizedDistance < 0f) return null
        val viewport = fillCenterViewport(sourceWidth, sourceHeight, viewWidth, viewHeight) ?: return null
        return (normalizedDistance * sourceHeight * viewport.scale / viewHeight).coerceAtMost(1f)
    }

    fun mapFillCenter(
        box: CameraOverlayBounds,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): CameraOverlayBounds? {
        val viewport = fillCenterViewport(sourceWidth, sourceHeight, viewWidth, viewHeight) ?: return null
        val left = (box.left * sourceWidth * viewport.scale + viewport.offsetX) / viewWidth
        val top = (box.top * sourceHeight * viewport.scale + viewport.offsetY) / viewHeight
        val right = (box.right * sourceWidth * viewport.scale + viewport.offsetX) / viewWidth
        val bottom = (box.bottom * sourceHeight * viewport.scale + viewport.offsetY) / viewHeight
        if (right <= 0f || left >= 1f || bottom <= 0f || top >= 1f) return null
        return CameraOverlayBounds(
            left = left.coerceIn(0f, 1f),
            top = top.coerceIn(0f, 1f),
            right = right.coerceIn(0f, 1f),
            bottom = bottom.coerceIn(0f, 1f),
        ).takeIf { it.left < it.right && it.top < it.bottom }
    }

    fun mapFillCenter(
        item: CameraOverlayItem,
        sourceWidth: Int,
        sourceHeight: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): CameraOverlayItem? {
        val box = mapFillCenter(item.box, sourceWidth, sourceHeight, viewWidth, viewHeight) ?: return null
        val layout = item.layout?.let { value ->
            val medianLineHeight = mapFillCenterDistanceY(
                value.medianLineHeight,
                sourceWidth,
                sourceHeight,
                viewWidth,
                viewHeight,
            ) ?: return null
            val growDown = mapFillCenterDistanceY(
                value.growDown,
                sourceWidth,
                sourceHeight,
                viewWidth,
                viewHeight,
            ) ?: return null
            value.copy(medianLineHeight = medianLineHeight, growDown = growDown)
        }
        return item.copy(box = box, layout = layout)
    }
}
