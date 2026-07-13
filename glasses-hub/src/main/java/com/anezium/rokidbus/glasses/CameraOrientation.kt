package com.anezium.rokidbus.glasses

internal data class CameraPixelSize(
    val width: Int,
    val height: Int,
)

internal data class CameraStreamPlan(
    val rasterSize: CameraPixelSize,
    val requestedHardwareRotationDegrees: Int,
    val remainingRotationDegrees: Int,
) {
    val orientedSize: CameraPixelSize
        get() = CameraOrientation.orientedSize(
            rasterSize.width,
            rasterSize.height,
            remainingRotationDegrees,
        )
}

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

    fun selectStreamPlan(
        sensorToDisplayRotationDegrees: Int,
        availableHardwareRotationDegrees: Set<Int>,
        availableOutputSizes: Set<CameraPixelSize>,
        portraitSize: CameraPixelSize = CameraPixelSize(720, 1_280),
        landscapeSize: CameraPixelSize = CameraPixelSize(1_280, 720),
    ): CameraStreamPlan? {
        val requiredRotation = normalizeRightAngle(sensorToDisplayRotationDegrees)
        if (landscapeSize in availableOutputSizes && 0 in availableHardwareRotationDegrees &&
            requiredRotation in setOf(90, 270)
        ) {
            return CameraStreamPlan(
                rasterSize = landscapeSize,
                requestedHardwareRotationDegrees = 0,
                remainingRotationDegrees = requiredRotation,
            )
        }
        if (portraitSize in availableOutputSizes && 0 in availableHardwareRotationDegrees &&
            requiredRotation in setOf(0, 180)
        ) {
            return CameraStreamPlan(
                rasterSize = portraitSize,
                requestedHardwareRotationDegrees = 0,
                remainingRotationDegrees = requiredRotation,
            )
        }
        return null
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
