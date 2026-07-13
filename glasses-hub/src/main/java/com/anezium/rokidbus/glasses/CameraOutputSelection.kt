package com.anezium.rokidbus.glasses

import kotlin.math.abs

internal data class CameraOutputSize(
    val width: Int,
    val height: Int,
) {
    val longEdge: Int get() = maxOf(width, height)
}

/** Chooses the old Lens 2048-edge, sensor-aspect JPEG instead of a larger cropped aspect. */
internal fun selectFullFovJpegSize(
    candidates: List<CameraOutputSize>,
    sensorWidth: Int,
    sensorHeight: Int,
    targetLongEdge: Int = 2_048,
): CameraOutputSize? {
    if (sensorWidth <= 0 || sensorHeight <= 0) return null
    val valid = candidates.filter { it.width > 0 && it.height > 0 }
    if (valid.isEmpty()) return null
    val sensorAspect = sensorWidth.toDouble() / sensorHeight.toDouble()
    val fullFov = valid.filter { size ->
        relativeAspectError(size, sensorAspect) <= FULL_FOV_ASPECT_ERROR
    }
    val pool = fullFov.ifEmpty { valid }
    return pool.minWithOrNull(
        compareBy<CameraOutputSize> { abs(it.longEdge - targetLongEdge) }
            .thenBy { relativeAspectError(it, sensorAspect) }
            .thenByDescending { it.width.toLong() * it.height.toLong() },
    )
}

private fun relativeAspectError(size: CameraOutputSize, sensorAspect: Double): Double =
    abs(size.width.toDouble() / size.height.toDouble() / sensorAspect - 1.0)

private const val FULL_FOV_ASPECT_ERROR = 0.01
