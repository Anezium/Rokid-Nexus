package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraFrameGeometry
import org.json.JSONObject

/** Phone-side adapter around the shared encoded-frame geometry contract. */
internal object LiveFrameGeometry {
    fun fromVideoConfigMetadata(metadata: JSONObject): CameraFrameGeometry =
        CameraFrameGeometry.fromVideoConfigMetadata(metadata)

    /** MediaCodec's output crop is the raster ML Kit actually receives, so it stays authoritative. */
    fun withDecoderCrop(
        configured: CameraFrameGeometry,
        width: Int,
        height: Int,
    ): CameraFrameGeometry = CameraFrameGeometry(
        rasterWidth = width,
        rasterHeight = height,
        appliedRotationDegrees = configured.appliedRotationDegrees,
        remainingRotationDegrees = configured.remainingRotationDegrees,
    )
}
