package com.anezium.rokidbus.glasses

internal data class CameraPixelSize(
    val width: Int,
    val height: Int,
)

internal object CameraOrientation {
    fun displayRotationDegrees(surfaceRotation: Int): Int = when (surfaceRotation) {
        0 -> 0
        1 -> 90
        2 -> 180
        3 -> 270
        else -> 0
    }

    fun sensorToDisplayRotationDegrees(
        sensorOrientation: Int,
        displayRotationDegrees: Int,
        frontFacing: Boolean,
    ): Int {
        val sensor = normalizeRightAngle(sensorOrientation)
        val display = normalizeRightAngle(displayRotationDegrees)
        return normalizeRightAngle(
            if (frontFacing) sensor + display else sensor - display,
        )
    }

    fun orientedSize(width: Int, height: Int, rotationDegrees: Int): CameraPixelSize {
        require(width > 0 && height > 0)
        return when (normalizeRightAngle(rotationDegrees)) {
            90, 270 -> CameraPixelSize(height, width)
            else -> CameraPixelSize(width, height)
        }
    }

    fun normalizeRightAngle(rotationDegrees: Int): Int {
        val normalized = ((rotationDegrees % 360) + 360) % 360
        require(normalized % 90 == 0) { "rotation must be a right angle" }
        return normalized
    }
}
