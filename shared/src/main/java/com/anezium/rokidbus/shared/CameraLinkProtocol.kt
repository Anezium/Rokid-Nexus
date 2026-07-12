package com.anezium.rokidbus.shared

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class CameraLinkPacketType(val id: Int) {
    HELLO(1),
    PROBE(2),
    PROBE_ACK(3),
    FROZEN_IMAGE(4),
    VIDEO_CONFIG(6),
    VIDEO_FRAME(7),
    VIDEO_ACK(8);

    companion object {
        fun fromId(id: Int): CameraLinkPacketType =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("Unknown camera link packet type: $id")
    }
}

object CameraLinkPacketFlags {
    const val KEY_FRAME = 1
}

data class CameraLinkPacket(
    val type: CameraLinkPacketType,
    val requestId: Long = 0L,
    val seq: Int = 0,
    val captureNanos: Long = 0L,
    val flags: Int = 0,
    val meta: String = "",
    val payload: ByteArray = ByteArray(0),
)

/** Framing shared by the camera freeze and live-video data planes. */
object CameraLinkProtocol {
    private const val MAGIC = 0x43414d4c // CAML
    const val VERSION = 1
    const val HEADER_BYTES = 36
    const val MAX_META_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun write(output: OutputStream, packet: CameraLinkPacket) {
        val metaBytes = packet.meta.toByteArray(Charsets.UTF_8)
        require(metaBytes.size <= MAX_META_BYTES) { "Camera link metadata too large: ${metaBytes.size}" }
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) {
            "Camera link payload too large: ${packet.payload.size}"
        }
        output.write(
            ByteBuffer.allocate(HEADER_BYTES)
                .order(ByteOrder.BIG_ENDIAN)
                .putInt(MAGIC)
                .put(VERSION.toByte())
                .put(packet.type.id.toByte())
                .putShort(packet.flags.toShort())
                .putLong(packet.requestId)
                .putInt(packet.seq)
                .putLong(packet.captureNanos)
                .putInt(metaBytes.size)
                .putInt(packet.payload.size)
                .array(),
        )
        if (metaBytes.isNotEmpty()) output.write(metaBytes)
        if (packet.payload.isNotEmpty()) output.write(packet.payload)
        output.flush()
    }

    /** Returns null only for a clean EOF before a new header begins. */
    fun read(input: InputStream): CameraLinkPacket? {
        val headerBytes = ByteArray(HEADER_BYTES)
        val headerRead = readFullyOrEof(input, headerBytes)
        if (headerRead == -1) return null
        if (headerRead != HEADER_BYTES) throw EOFException("Short camera link header")
        val header = ByteBuffer.wrap(headerBytes).order(ByteOrder.BIG_ENDIAN)
        require(header.int == MAGIC) { "Invalid camera link magic" }
        val version = header.get().toInt() and 0xff
        require(version == VERSION) { "Unsupported camera link version: $version" }
        val type = CameraLinkPacketType.fromId(header.get().toInt() and 0xff)
        val flags = header.short.toInt() and 0xffff
        val requestId = header.long
        val seq = header.int
        val captureNanos = header.long
        val metaLength = header.int
        val payloadLength = header.int
        require(metaLength in 0..MAX_META_BYTES) { "Invalid camera link metadata length: $metaLength" }
        require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "Invalid camera link payload length: $payloadLength" }
        return CameraLinkPacket(
            type = type,
            requestId = requestId,
            seq = seq,
            captureNanos = captureNanos,
            flags = flags,
            meta = String(readFully(input, metaLength, "metadata"), Charsets.UTF_8),
            payload = readFully(input, payloadLength, "payload"),
        )
    }

    private fun readFully(input: InputStream, size: Int, label: String): ByteArray {
        if (size == 0) return ByteArray(0)
        val bytes = ByteArray(size)
        if (readFullyOrEof(input, bytes) != size) throw EOFException("Short camera link $label")
        return bytes
    }

    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) {
            val count = input.read(bytes, offset, bytes.size - offset)
            if (count < 0) return if (offset == 0) -1 else offset
            if (count > 0) offset += count
        }
        return offset
    }
}
