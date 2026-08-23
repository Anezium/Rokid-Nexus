package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONException
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Alleycat stream framing: big-endian u32 length prefix + UTF-8 JSON,
 * 1 MiB payload cap. Length is checked before any payload allocation.
 */
object AlleycatFraming {
    const val MAX_PAYLOAD_BYTES: Int = 1 shl 20

    fun encode(payload: JSONObject): ByteArray {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        if (body.size > MAX_PAYLOAD_BYTES) {
            throw AlleycatException("JSON payload exceeds 1 MiB cap (${body.size} bytes)")
        }
        val frame = ByteArray(4 + body.size)
        ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(body.size)
        System.arraycopy(body, 0, frame, 4, body.size)
        return frame
    }

    fun decode(frame: ByteArray): JSONObject {
        if (frame.size < 4) {
            throw AlleycatException("frame shorter than length prefix")
        }
        val length = ByteBuffer.wrap(frame, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            throw AlleycatException("rejected frame length $length (cap $MAX_PAYLOAD_BYTES)")
        }
        if (frame.size < 4 + length) {
            throw AlleycatException("truncated frame: declared $length, have ${frame.size - 4}")
        }
        return parseUtf8Json(frame, 4, length)
    }

    fun write(transport: AlleycatTransport, payload: JSONObject) {
        transport.send(encode(payload))
    }

    fun read(transport: AlleycatTransport): JSONObject {
        val header = transport.receiveExactly(4)
        val length = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN).int
        if (length <= 0 || length > MAX_PAYLOAD_BYTES) {
            throw AlleycatException("rejected frame length $length (cap $MAX_PAYLOAD_BYTES)")
        }
        val body = transport.receiveExactly(length)
        return parseUtf8Json(body, 0, body.size)
    }

    private fun parseUtf8Json(bytes: ByteArray, offset: Int, length: Int): JSONObject {
        val text = String(bytes, offset, length, Charsets.UTF_8)
        return try {
            JSONObject(text)
        } catch (e: JSONException) {
            throw AlleycatException("frame payload is not JSON", e)
        }
    }
}
