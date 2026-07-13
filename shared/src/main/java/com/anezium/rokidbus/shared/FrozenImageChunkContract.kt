package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.ceil

/** Bounded one-shot JPEG transport used only while the Lens Wi-Fi data plane is unavailable. */
object FrozenImageChunkContract {
    const val VERSION = 1
    const val MIME_TYPE = "image/jpeg"
    const val CHUNK_BYTES = 192 * 1024
    const val MAX_IMAGE_BYTES = 8 * 1024 * 1024
    const val MAX_CHUNKS = 64
    const val MAX_IMAGE_EDGE = 4096

    data class Metadata(
        val sessionId: String,
        val requestId: Long,
        val transferId: String,
        val chunkIndex: Int,
        val chunkCount: Int,
        val totalBytes: Int,
        val width: Int,
        val height: Int,
        val rotationDegrees: Int,
        val sha256: String,
    )

    fun chunkCount(totalBytes: Int): Int {
        require(totalBytes in 1..MAX_IMAGE_BYTES)
        return ceil(totalBytes.toDouble() / CHUNK_BYTES).toInt()
    }

    fun metadataJson(metadata: Metadata): JSONObject = JSONObject()
        .put("version", VERSION)
        .put("sessionId", metadata.sessionId)
        .put("requestId", metadata.requestId)
        .put("transferId", metadata.transferId)
        .put("chunkIndex", metadata.chunkIndex)
        .put("chunkCount", metadata.chunkCount)
        .put("totalBytes", metadata.totalBytes)
        .put("mimeType", MIME_TYPE)
        .put("width", metadata.width)
        .put("height", metadata.height)
        .put("rotationDegrees", metadata.rotationDegrees)
        .put("sha256", metadata.sha256)

    fun parse(payload: JSONObject, chunkSize: Int): Metadata? {
        if (payload.optInt("version") != VERSION || payload.optString("mimeType") != MIME_TYPE) return null
        val sessionId = payload.optString("sessionId")
        val transferId = payload.optString("transferId")
        val requestId = payload.optLong("requestId", Long.MIN_VALUE)
        val chunkIndex = payload.optInt("chunkIndex", -1)
        val chunkCount = payload.optInt("chunkCount", -1)
        val totalBytes = payload.optInt("totalBytes", -1)
        val width = payload.optInt("width", -1)
        val height = payload.optInt("height", -1)
        val rotation = payload.optInt("rotationDegrees", -1)
        val sha256 = payload.optString("sha256")
        if (sessionId.isBlank() || transferId.isBlank() || requestId == Long.MIN_VALUE) return null
        if (totalBytes !in 1..MAX_IMAGE_BYTES || chunkCount !in 1..MAX_CHUNKS) return null
        if (chunkCount != chunkCount(totalBytes) || chunkIndex !in 0 until chunkCount) return null
        if (chunkSize !in 1..CHUNK_BYTES) return null
        val expectedChunkSize = if (chunkIndex == chunkCount - 1) {
            totalBytes - CHUNK_BYTES * (chunkCount - 1)
        } else {
            CHUNK_BYTES
        }
        if (chunkSize != expectedChunkSize) return null
        if (width !in 1..MAX_IMAGE_EDGE || height !in 1..MAX_IMAGE_EDGE) return null
        if (rotation !in setOf(0, 90, 180, 270)) return null
        if (!sha256.matches(Regex("[0-9a-f]{64}"))) return null
        return Metadata(
            sessionId, requestId, transferId, chunkIndex, chunkCount, totalBytes,
            width, height, rotation, sha256,
        )
    }

    fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
