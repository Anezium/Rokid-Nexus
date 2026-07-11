package com.anezium.rokidbus.shared

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class LensLinkPacketType(val id: Int) {
    HELLO(1),
    PROBE(2),
    PROBE_ACK(3),
    FROZEN_IMAGE(4),
    FROZEN_BLOCKS(5);

    companion object {
        fun fromId(id: Int): LensLinkPacketType =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown lens link packet type: $id")
    }
}

data class LensLinkPacket(
    val type: LensLinkPacketType,
    val requestId: Long,
    val seq: Int = 0,
    val meta: String = "",
    val payload: ByteArray = ByteArray(0),
)

/** Pure stream codec used only on the optional Wi-Fi Direct lens image link. */
object LensLinkProtocol {
    const val VERSION = 1
    const val HEADER_BYTES = 22
    const val MAX_META_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun write(output: OutputStream, packet: LensLinkPacket) {
        val metaBytes = packet.meta.toByteArray(Charsets.UTF_8)
        require(metaBytes.size <= MAX_META_BYTES) { "Lens link metadata too large: ${metaBytes.size}" }
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) {
            "Lens link payload too large: ${packet.payload.size}"
        }
        val header = ByteBuffer.allocate(HEADER_BYTES)
            .order(ByteOrder.BIG_ENDIAN)
            .put(VERSION.toByte())
            .put(packet.type.id.toByte())
            .putLong(packet.requestId)
            .putInt(packet.seq)
            .putInt(metaBytes.size)
            .putInt(packet.payload.size)
            .array()
        output.write(header)
        if (metaBytes.isNotEmpty()) output.write(metaBytes)
        if (packet.payload.isNotEmpty()) output.write(packet.payload)
        output.flush()
    }

    /** Returns null only for a clean EOF before the next header starts. */
    fun read(input: InputStream): LensLinkPacket? {
        val header = ByteArray(HEADER_BYTES)
        val headerRead = readFullyOrEof(input, header)
        if (headerRead == -1) return null
        if (headerRead != HEADER_BYTES) throw EOFException("Short lens link header")

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get().toInt() and 0xff
        require(version == VERSION) { "Unsupported lens link version: $version" }
        val type = LensLinkPacketType.fromId(buffer.get().toInt() and 0xff)
        val requestId = buffer.long
        val seq = buffer.int
        val metaLength = buffer.int
        val payloadLength = buffer.int
        require(metaLength in 0..MAX_META_BYTES) { "Invalid lens link metadata length: $metaLength" }
        require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "Invalid lens link payload length: $payloadLength" }

        val metaBytes = readFully(input, metaLength, "metadata")
        val payload = readFully(input, payloadLength, "payload")
        return LensLinkPacket(
            type = type,
            requestId = requestId,
            seq = seq,
            meta = String(metaBytes, Charsets.UTF_8),
            payload = payload,
        )
    }

    private fun readFully(input: InputStream, size: Int, label: String): ByteArray {
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        if (readFullyOrEof(input, bytes) != size) {
            throw EOFException("Short lens link $label")
        }
        return bytes
    }

    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return if (offset == 0) -1 else offset
            if (count == 0) continue
            offset += count
        }
        return offset
    }
}
