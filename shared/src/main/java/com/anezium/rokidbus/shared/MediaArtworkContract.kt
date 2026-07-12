package com.anezium.rokidbus.shared

import org.json.JSONObject

/**
 * Binary album-art validation for a media v1 surface.
 *
 * The actual JPEG/PNG rules intentionally stay owned by [ImageSurfaceContract].
 * This adapter only maps the nested media artwork metadata onto that contract and
 * applies Media Deck's tighter decoded-edge limit.
 */
object MediaArtworkContract {
    const val KIND = "media"
    const val MEDIA_VERSION = 1
    const val ENCODING_BINARY = "binary"
    const val MAX_EDGE_PIXELS = 256

    fun hasBinaryArtwork(payload: JSONObject): Boolean =
        payload.optJSONObject("artwork")?.optString("encoding") == ENCODING_BINARY

    fun validate(payload: JSONObject, binary: ByteArray?): ImageSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be media")
        if (integer(payload.opt("mediaVersion")) != MEDIA_VERSION) {
            return invalid("mediaVersion must be 1")
        }
        val artwork = payload.optJSONObject("artwork") ?: return invalid("artwork is required")
        if (artwork.opt("encoding") != ENCODING_BINARY) {
            return invalid("artwork encoding must be binary")
        }

        val imagePayload = JSONObject()
            .put("kind", ImageSurfaceContract.KIND)
            .put("imageVersion", ImageSurfaceContract.VERSION)
            .put("contentKey", payload.opt("contentKey"))
            .put("mimeType", artwork.opt("mimeType"))
            .put("pixelWidth", artwork.opt("pixelWidth"))
            .put("pixelHeight", artwork.opt("pixelHeight"))
            .put("sha256", artwork.opt("sha256"))
        val validation = ImageSurfaceContract.validate(imagePayload, binary)
        if (validation !is ImageSurfaceValidationResult.Valid) return validation
        if (validation.metadata.pixelWidth > MAX_EDGE_PIXELS ||
            validation.metadata.pixelHeight > MAX_EDGE_PIXELS
        ) {
            return invalid("media artwork edge exceeds $MAX_EDGE_PIXELS")
        }
        return validation
    }

    private fun integer(value: Any?): Int? {
        val number = value as? Number ?: return null
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return long.toInt()
    }

    private fun invalid(reason: String) =
        ImageSurfaceValidationResult.Invalid(ImageSurfaceContract.ERROR_INVALID_IMAGE, reason)
}
