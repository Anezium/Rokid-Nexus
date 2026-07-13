package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraOverlayBounds
import kotlin.math.max

internal data class CameraFillCenterViewport(
    val scale: Float,
    val offsetX: Float,
    val offsetY: Float,
    val displayedWidth: Float,
    val displayedHeight: Float,
)

internal data class CameraLiveFrameGeometry(
    val rawWidth: Int,
    val rawHeight: Int,
    val rotationDegrees: Int,
) {
    val orientedSize: CameraPixelSize
        get() = CameraOrientation.orientedSize(rawWidth, rawHeight, rotationDegrees)
}

/** Maps oriented stream coordinates through PreviewView's old FILL_CENTER viewport policy. */
internal object CameraPreviewGeometry {
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
        geometry: CameraLiveFrameGeometry,
        viewWidth: Int,
        viewHeight: Int,
    ): FloatArray? {
        if (geometry.rawWidth <= 0 || geometry.rawHeight <= 0) return null
        val rotation = CameraOrientation.normalizeRightAngle(geometry.rotationDegrees)
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
}
