package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.util.Base64

/** A bounded, self-contained cover; it never names a URI or another owner's asset. */
data class LauncherArtwork(val width: Int, val height: Int, val data: String, val encoding: String = MONO1) {
    init {
        require(encoding == MONO1 || encoding == JPEG)
        val edge = if (encoding == JPEG) MAX_IMAGE_EDGE else MAX_EDGE
        require(width in 1..edge && height in 1..edge)
        require(data.length <= if (encoding == JPEG) MAX_IMAGE_ENCODED_CHARS else MAX_ENCODED_CHARS)
        val bytes = Base64.getDecoder().decode(data)
        if (encoding == JPEG) {
            require(bytes.isNotEmpty() && bytes.size <= MAX_IMAGE_BYTES)
            require(ImageSurfaceContract.dimensions(ImageSurfaceContract.MIME_JPEG, bytes) == ImageDimensions(width, height))
        } else {
            require(bytes.size == (width * height + 7) / 8)
        }
    }

    fun bytes(): ByteArray = Base64.getDecoder().decode(data)

    fun toJson(): JSONObject = JSONObject().put("encoding", encoding)
        .put("width", width).put("height", height).put("data", data)

    companion object {
        const val MAX_EDGE = 128
        const val MAX_ENCODED_CHARS = 2732
        const val MONO1 = "mono1"
        const val JPEG = "jpeg"
        const val MAX_IMAGE_EDGE = MediaArtworkContract.MAX_EDGE_PIXELS
        // Base64 plus the rest of a Media Deck page must fit the 64 KiB page envelope.
        const val MAX_IMAGE_BYTES = 32_768
        const val MAX_IMAGE_ENCODED_CHARS = 43_692

        fun stripUnsupported(container: JSONObject, negotiatedVersion: Int) {
            if (negotiatedVersion < LauncherInteractiveContract.ARTWORK_VERSION) container.remove("artwork")
            else if (negotiatedVersion < LauncherInteractiveContract.IMAGE_ARTWORK_VERSION &&
                container.optJSONObject("artwork")?.optString("encoding") == JPEG) {
                // An update must clear an older cover rather than leave another track's image.
                container.put("artwork", JSONObject.NULL)
            }
        }

        fun parse(value: Any?): LauncherArtwork? {
            if (value == null || value == JSONObject.NULL) return null
            require(value is JSONObject)
            val encoding = value.opt("encoding") as? String ?: throw IllegalArgumentException("Invalid artwork encoding")
            fun dimension(name: String): Int {
                val number = value.opt(name) as? Number ?: throw IllegalArgumentException("Invalid artwork $name")
                require(number.toDouble() == number.toInt().toDouble())
                return number.toInt()
            }
            return LauncherArtwork(dimension("width"), dimension("height"),
                value.opt("data") as? String ?: throw IllegalArgumentException("Invalid artwork data"), encoding)
        }
    }
}
