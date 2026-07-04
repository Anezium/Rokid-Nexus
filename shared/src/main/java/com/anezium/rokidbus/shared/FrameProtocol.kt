package com.anezium.rokidbus.shared

import org.json.JSONObject
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

data class BusEnvelope(
    val path: String,
    val id: String = UUID.randomUUID().toString(),
    // TODO raw binary frames: Round A carries binary payloads as payload.bin base64.
    val payload: JSONObject = JSONObject(),
    val v: Int = 1,
)

object FrameProtocol {
    private const val HEADER_BYTES = 4
    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024

    fun toJsonBytes(envelope: BusEnvelope): ByteArray =
        toJson(envelope).toString().toByteArray(Charsets.UTF_8)

    fun fromJsonBytes(bytes: ByteArray): BusEnvelope =
        fromJson(JSONObject(String(bytes, Charsets.UTF_8)))

    fun toJson(envelope: BusEnvelope): JSONObject =
        JSONObject()
            .put("v", envelope.v)
            .put("path", envelope.path)
            .put("id", envelope.id)
            .put("payload", envelope.payload)

    fun fromJson(json: JSONObject): BusEnvelope =
        BusEnvelope(
            v = json.optInt("v", 1),
            path = json.getString("path"),
            id = json.optString("id").ifBlank { UUID.randomUUID().toString() },
            payload = json.optJSONObject("payload") ?: JSONObject(),
        )

    fun write(output: OutputStream, envelope: BusEnvelope) {
        val body = toJsonBytes(envelope)
        require(body.size <= MAX_FRAME_BYTES) { "Frame too large: ${body.size}" }
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .putInt(body.size)
            .array()
        output.write(header)
        output.write(body)
        output.flush()
    }

    fun read(input: InputStream): BusEnvelope? {
        val header = ByteArray(HEADER_BYTES)
        val headerBytes = readFullyOrEof(input, header)
        if (headerBytes == -1) return null
        if (headerBytes != HEADER_BYTES) throw EOFException("Short frame header")

        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        require(length in 1..MAX_FRAME_BYTES) { "Invalid frame length: $length" }

        val body = ByteArray(length)
        val bodyBytes = readFullyOrEof(input, body)
        if (bodyBytes != length) throw EOFException("Short frame body")
        return fromJson(JSONObject(String(body, Charsets.UTF_8)))
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read == -1) return if (offset == 0) -1 else offset
            offset += read
        }
        return offset
    }
}
