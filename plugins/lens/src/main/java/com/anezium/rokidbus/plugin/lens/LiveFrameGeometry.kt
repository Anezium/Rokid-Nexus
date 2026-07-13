package com.anezium.rokidbus.plugin.lens

internal data class OrientedLiveFrameSize(
    val width: Int,
    val height: Int,
)

/**
 * Describes the display-oriented coordinate frame produced when ML Kit receives the decoder
 * raster together with the camera's clockwise sensor-to-display rotation.
 */
internal object LiveFrameGeometry {
    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): OrientedLiveFrameSize {
        require(width > 0 && height > 0)
        return when (normalizeRightAngle(rotationDegrees)) {
            90, 270 -> OrientedLiveFrameSize(height, width)
            else -> OrientedLiveFrameSize(width, height)
        }
    }

    fun normalizeRightAngle(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "rotation must be a right angle" }
        return normalized
    }
}
