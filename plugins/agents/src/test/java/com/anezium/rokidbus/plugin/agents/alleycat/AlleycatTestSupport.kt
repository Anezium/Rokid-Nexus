package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal const val TEST_TOKEN: String = "deadbeefcafebabedeadbeefcafebabedeadbeefcafebabedeadbeefcafebabe"

internal fun utf8Frame(json: String): ByteArray {
    val body = json.toByteArray(Charsets.UTF_8)
    val frame = ByteArray(4 + body.size)
    ByteBuffer.wrap(frame).order(ByteOrder.BIG_ENDIAN).putInt(body.size)
    System.arraycopy(body, 0, frame, 4, body.size)
    return frame
}

internal fun decodeFrames(bytes: ByteArray): List<JSONObject> {
    val frames = mutableListOf<JSONObject>()
    var offset = 0
    while (offset < bytes.size) {
        if (offset + 4 > bytes.size) {
            throw AlleycatException("trailing truncated header")
        }
        val length = ByteBuffer.wrap(bytes, offset, 4).order(ByteOrder.BIG_ENDIAN).int
        val end = offset + 4 + length
        if (end > bytes.size) {
            throw AlleycatException("trailing truncated body")
        }
        frames += AlleycatFraming.decode(bytes.copyOfRange(offset, end))
        offset = end
    }
    return frames
}

internal class ScriptedTransport(
    initialInbound: ByteArray = ByteArray(0),
) : AlleycatTransport {
    private var inbound: ByteArray = initialInbound
    private var readAt: Int = 0
    private val outbound = ByteArrayOutputStream()

    fun enqueue(bytes: ByteArray) {
        inbound += bytes
    }

    fun written(): ByteArray = outbound.toByteArray()

    override fun send(bytes: ByteArray) {
        outbound.write(bytes)
    }

    override fun receiveExactly(length: Int): ByteArray {
        if (readAt + length > inbound.size) {
            throw EOFException("need $length, have ${inbound.size - readAt}")
        }
        val slice = inbound.copyOfRange(readAt, readAt + length)
        readAt += length
        return slice
    }
}
