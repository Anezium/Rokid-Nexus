package com.anezium.rokidbus.shared

import org.json.JSONObject

private fun normalizeCameraRotation(rotationDegrees: Int): Int =
    ((rotationDegrees % 360) + 360) % 360

data class CameraFrameSize(
    val width: Int,
    val height: Int,
)

/**
 * Geometry of an encoded camera frame.
 *
 * [appliedRotationDegrees] is already baked into the encoded raster by hardware. Only
 * [remainingRotationDegrees] must still be applied to orient that raster for display or analysis.
 */
data class CameraFrameGeometry(
    val rasterWidth: Int,
    val rasterHeight: Int,
    val appliedRotationDegrees: Int = 0,
    val remainingRotationDegrees: Int = 0,
    val sensorToDisplayRotationDegrees: Int = normalizeCameraRotation(
        appliedRotationDegrees + remainingRotationDegrees,
    ),
) {
    init {
        require(rasterWidth > 0) { "rasterWidth must be positive" }
        require(rasterHeight > 0) { "rasterHeight must be positive" }
        requireRightAngle(appliedRotationDegrees, "appliedRotationDegrees")
        requireRightAngle(remainingRotationDegrees, "remainingRotationDegrees")
        requireRightAngle(sensorToDisplayRotationDegrees, "sensorToDisplayRotationDegrees")
        require(
            sensorToDisplayRotationDegrees == normalizeCameraRotation(
                appliedRotationDegrees + remainingRotationDegrees,
            ),
        ) { "applied and remaining rotations must equal sensor-to-display rotation" }
    }

    val orientedSize: CameraFrameSize
        get() = when (remainingRotationDegrees) {
            90, 270 -> CameraFrameSize(rasterHeight, rasterWidth)
            else -> CameraFrameSize(rasterWidth, rasterHeight)
        }

    fun toVideoConfigMetadata(): JSONObject = JSONObject()
        .put("width", rasterWidth)
        .put("height", rasterHeight)
        .put("sensorToDisplayRotationDegrees", sensorToDisplayRotationDegrees)
        .put("appliedRotationDegrees", appliedRotationDegrees)
        .put("remainingRotationDegrees", remainingRotationDegrees)
        .put("rotationDegrees", remainingRotationDegrees)

    companion object {
        fun fromVideoConfigMetadata(metadata: JSONObject): CameraFrameGeometry {
            val width = requiredInt(metadata, "width")
            val height = requiredInt(metadata, "height")
            val appliedRotation = normalizeRightAngle(
                optionalInt(metadata, "appliedRotationDegrees") ?: 0,
                "appliedRotationDegrees",
            )
            val remainingRotation = normalizeRightAngle(
                optionalInt(metadata, "remainingRotationDegrees")
                    ?: optionalInt(metadata, "rotationDegrees")
                    ?: 0,
                "remainingRotationDegrees",
            )
            optionalInt(metadata, "rotationDegrees")?.let { legacyRotation ->
                require(normalizeRightAngle(legacyRotation, "rotationDegrees") == remainingRotation) {
                    "rotationDegrees must match remainingRotationDegrees"
                }
            }
            val sensorToDisplayRotation = normalizeRightAngle(
                optionalInt(metadata, "sensorToDisplayRotationDegrees")
                    ?: appliedRotation + remainingRotation,
                "sensorToDisplayRotationDegrees",
            )
            return CameraFrameGeometry(
                rasterWidth = width,
                rasterHeight = height,
                appliedRotationDegrees = appliedRotation,
                remainingRotationDegrees = remainingRotation,
                sensorToDisplayRotationDegrees = sensorToDisplayRotation,
            )
        }

        private fun requiredInt(metadata: JSONObject, key: String): Int =
            optionalInt(metadata, key) ?: throw IllegalArgumentException("$key must be an integer")

        private fun optionalInt(metadata: JSONObject, key: String): Int? {
            if (!metadata.has(key)) return null
            val number = metadata.opt(key) as? Number
                ?: throw IllegalArgumentException("$key must be an integer")
            val long = number.toLong()
            require(number.toDouble() == long.toDouble() && long in Int.MIN_VALUE..Int.MAX_VALUE) {
                "$key must be an integer"
            }
            return long.toInt()
        }

        private fun requireRightAngle(rotationDegrees: Int, label: String) {
            require(rotationDegrees in 0..359 && rotationDegrees % 90 == 0) {
                "$label must be one of 0, 90, 180, or 270"
            }
        }

        private fun normalizeRightAngle(rotationDegrees: Int, label: String): Int {
            val normalized = normalizeCameraRotation(rotationDegrees)
            require(normalized % 90 == 0) { "$label must be a right angle" }
            return normalized
        }

    }
}
