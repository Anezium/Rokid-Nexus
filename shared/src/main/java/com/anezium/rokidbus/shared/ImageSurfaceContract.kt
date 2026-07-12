package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.security.MessageDigest

data class ImageSurfaceMetadata(
    val contentKey: String,
    val mimeType: String,
    val pixelWidth: Int,
    val pixelHeight: Int,
    val sha256: String,
    val title: String?,
    val caption: String?,
    val footer: String?,
    val handlesBack: Boolean,
)

data class ImageDimensions(val width: Int, val height: Int)

sealed interface ImageSurfaceValidationResult {
    data class Valid(val metadata: ImageSurfaceMetadata) : ImageSurfaceValidationResult
    data class Invalid(val code: String, val reason: String) : ImageSurfaceValidationResult
}

/** Pure image-surface v1 wire validation; intentionally has no Android dependencies. */
object ImageSurfaceContract {
    const val KIND = "image"
    const val VERSION = 1
    const val MIME_JPEG = "image/jpeg"
    const val MIME_PNG = "image/png"
    const val MAX_IMAGE_BYTES = 65_536
    const val MAX_EDGE_PIXELS = 512
    const val MAX_TOTAL_PIXELS = MAX_EDGE_PIXELS * MAX_EDGE_PIXELS
    const val MAX_CONTENT_KEY_CHARS = 128
    const val MAX_TITLE_CHARS = 120
    const val MAX_TEXT_CHARS = 240
    const val MIN_FRAME_INTERVAL_MS = 150L

    const val ERROR_CAPABILITY_NOT_AVAILABLE = "CAPABILITY_NOT_AVAILABLE"
    const val ERROR_INVALID_IMAGE = "INVALID_IMAGE"
    const val ERROR_IMAGE_TOO_LARGE = "IMAGE_TOO_LARGE"
    const val ERROR_IMAGE_RATE_LIMITED = "IMAGE_RATE_LIMITED"

    private val SHA256_HEX = Regex("[0-9a-f]{64}")
    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
    )

    fun validate(payload: JSONObject, binary: ByteArray?): ImageSurfaceValidationResult {
        if (payload.opt("kind") != KIND) return invalid("kind must be image")
        if (integer(payload.opt("imageVersion")) != VERSION) return invalid("imageVersion must be 1")

        val contentKey = requiredString(payload, "contentKey") ?: return invalid("contentKey is required")
        if (contentKey.isBlank() || contentKey.length > MAX_CONTENT_KEY_CHARS) {
            return invalid("contentKey length must be 1..$MAX_CONTENT_KEY_CHARS")
        }

        val mimeType = requiredString(payload, "mimeType") ?: return invalid("mimeType is required")
        if (mimeType != MIME_JPEG && mimeType != MIME_PNG) return invalid("unsupported mimeType")

        val width = integer(payload.opt("pixelWidth")) ?: return invalid("pixelWidth must be an integer")
        val height = integer(payload.opt("pixelHeight")) ?: return invalid("pixelHeight must be an integer")
        if (width !in 1..MAX_EDGE_PIXELS || height !in 1..MAX_EDGE_PIXELS) {
            return invalid("decoded edge exceeds $MAX_EDGE_PIXELS")
        }
        if (width.toLong() * height.toLong() > MAX_TOTAL_PIXELS) {
            return invalid("decoded pixel count exceeds $MAX_TOTAL_PIXELS")
        }

        val expectedHash = requiredString(payload, "sha256") ?: return invalid("sha256 is required")
        if (!SHA256_HEX.matches(expectedHash)) return invalid("sha256 must be 64 lowercase hex characters")

        val title = optionalString(payload, "title", MAX_TITLE_CHARS)
            ?: if (payload.has("title")) return invalid("invalid title") else null
        val caption = optionalString(payload, "caption", MAX_TEXT_CHARS)
            ?: if (payload.has("caption")) return invalid("invalid caption") else null
        val footer = optionalString(payload, "footer", MAX_TEXT_CHARS)
            ?: if (payload.has("footer")) return invalid("invalid footer") else null
        val handlesBackValue = payload.opt("handlesBack")
        if (payload.has("handlesBack") && handlesBackValue !is Boolean) {
            return invalid("handlesBack must be boolean")
        }

        if (binary == null || binary.isEmpty()) return invalid("binary image body is required")
        if (binary.size > MAX_IMAGE_BYTES) {
            return ImageSurfaceValidationResult.Invalid(ERROR_IMAGE_TOO_LARGE, "image body exceeds $MAX_IMAGE_BYTES")
        }
        if (!hasSignature(mimeType, binary)) return invalid("binary signature does not match mimeType")
        val encodedDimensions = dimensions(mimeType, binary) ?: return invalid("image dimensions cannot be read")
        if (encodedDimensions.width != width || encodedDimensions.height != height) {
            return invalid("declared dimensions do not match image body")
        }
        if (sha256(binary) != expectedHash) return invalid("sha256 does not match binary body")

        return ImageSurfaceValidationResult.Valid(
            ImageSurfaceMetadata(
                contentKey = contentKey,
                mimeType = mimeType,
                pixelWidth = width,
                pixelHeight = height,
                sha256 = expectedHash,
                title = title,
                caption = caption,
                footer = footer,
                handlesBack = handlesBackValue as? Boolean ?: false,
            ),
        )
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { byte -> "%02x".format(byte) }

    fun dimensions(mimeType: String, bytes: ByteArray): ImageDimensions? = when (mimeType) {
        MIME_JPEG -> jpegDimensions(bytes)
        MIME_PNG -> pngDimensions(bytes)
        else -> null
    }

    private fun requiredString(payload: JSONObject, key: String): String? =
        payload.opt(key) as? String

    private fun optionalString(payload: JSONObject, key: String, maxChars: Int): String? {
        if (!payload.has(key)) return null
        val value = payload.opt(key) as? String ?: return null
        return value.takeIf { it.length <= maxChars }
    }

    private fun integer(value: Any?): Int? {
        val number = value as? Number ?: return null
        val long = number.toLong()
        if (number.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) return null
        return long.toInt()
    }

    private fun hasSignature(mimeType: String, bytes: ByteArray): Boolean = when (mimeType) {
        MIME_JPEG -> bytes.size >= 3 &&
            bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte()
        MIME_PNG -> bytes.size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }
        else -> false
    }

    private fun pngDimensions(bytes: ByteArray): ImageDimensions? {
        if (!hasSignature(MIME_PNG, bytes) || bytes.size < 24) return null
        if (bytes[12] != 'I'.code.toByte() || bytes[13] != 'H'.code.toByte() ||
            bytes[14] != 'D'.code.toByte() || bytes[15] != 'R'.code.toByte()
        ) return null
        val width = readInt32(bytes, 16)
        val height = readInt32(bytes, 20)
        return if (width > 0 && height > 0) ImageDimensions(width, height) else null
    }

    private fun jpegDimensions(bytes: ByteArray): ImageDimensions? {
        if (!hasSignature(MIME_JPEG, bytes)) return null
        var offset = 2
        while (offset + 3 < bytes.size) {
            while (offset < bytes.size && bytes[offset] != 0xff.toByte()) offset++
            while (offset < bytes.size && bytes[offset] == 0xff.toByte()) offset++
            if (offset >= bytes.size) return null
            val marker = bytes[offset].toInt() and 0xff
            offset++
            if (marker == 0xd8 || marker == 0xd9 || marker == 0x01 || marker in 0xd0..0xd7) continue
            if (offset + 1 >= bytes.size) return null
            val length = readUInt16(bytes, offset)
            if (length < 2 || offset + length > bytes.size) return null
            if (marker in SOF_MARKERS && length >= 7) {
                val height = readUInt16(bytes, offset + 3)
                val width = readUInt16(bytes, offset + 5)
                return if (width > 0 && height > 0) ImageDimensions(width, height) else null
            }
            offset += length
        }
        return null
    }

    private fun readUInt16(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 8) or (bytes[offset + 1].toInt() and 0xff)

    private fun readInt32(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private fun invalid(reason: String) =
        ImageSurfaceValidationResult.Invalid(ERROR_INVALID_IMAGE, reason)

    private val SOF_MARKERS = setOf(
        0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7,
        0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf,
    )
}
