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
    val payload: JSONObject = JSONObject(),
    val binary: ByteArray? = null,
    val v: Int = 1,
)

object FrameProtocol {
    private const val HEADER_BYTES = 4
    private const val MAX_FRAME_BYTES = 2 * 1024 * 1024
    private const val FORMAT_BINARY: Byte = 0x01
    private const val FORMAT_JSON: Byte = 0x7B

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
        val body = toFrameBody(envelope)
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
        return when (body.first()) {
            FORMAT_JSON -> fromJson(JSONObject(String(body, Charsets.UTF_8)))
            FORMAT_BINARY -> fromBinaryBody(body)
            else -> throw IllegalArgumentException("Unknown frame type: ${body.first().toInt() and 0xff}")
        }
    }

    private fun toFrameBody(envelope: BusEnvelope): ByteArray {
        val data = envelope.binary ?: return toJsonBytes(envelope)
        val headerJson = JSONObject()
            .put("v", envelope.v)
            .put("path", envelope.path)
            .put("id", envelope.id)
        if (envelope.payload.length() > 0) {
            headerJson.put("meta", envelope.payload)
        }
        val header = headerJson.toString().toByteArray(Charsets.UTF_8)
        require(header.size <= 0xffff) { "Binary frame header too large: ${header.size}" }
        val bodySize = 1 + 2 + header.size + data.size
        require(bodySize <= MAX_FRAME_BYTES) { "Frame too large: $bodySize" }
        return ByteBuffer.allocate(bodySize)
            .order(ByteOrder.BIG_ENDIAN)
            .put(FORMAT_BINARY)
            .putShort(header.size.toShort())
            .put(header)
            .put(data)
            .array()
    }

    private fun fromBinaryBody(body: ByteArray): BusEnvelope {
        require(body.size >= 3) { "Short binary frame" }
        val headerLength = ByteBuffer.wrap(body, 1, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xffff
        require(headerLength > 0 && 3 + headerLength <= body.size) { "Invalid binary header length: $headerLength" }
        val header = JSONObject(String(body, 3, headerLength, Charsets.UTF_8))
        return BusEnvelope(
            v = header.optInt("v", 1),
            path = header.getString("path"),
            id = header.optString("id").ifBlank { UUID.randomUUID().toString() },
            payload = header.optJSONObject("meta") ?: JSONObject(),
            binary = body.copyOfRange(3 + headerLength, body.size),
        )
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
