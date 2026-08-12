package com.anezium.rokidbus.shared

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

enum class MediaLinkPacketType(val id: Int) {
    HELLO(1), VIDEO_CONFIG(2), VIDEO_SAMPLE(3), AUDIO_CONFIG(4), AUDIO_SAMPLE(5),
    WINDOW_UPDATE(6), EOS(7), ERROR(8);

    companion object {
        fun fromId(id: Int): MediaLinkPacketType = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown media link packet type: $id")
    }
}

object MediaLinkPacketFlags { const val KEY_FRAME = 1 }

data class MediaLinkPacket(
    val type: MediaLinkPacketType,
    val epoch: Long = 0L,
    val seq: Int = 0,
    val ptsUs: Long = 0L,
    val flags: Int = 0,
    val meta: String = "",
    val payload: ByteArray = ByteArray(0),
)

/** Bounded, versioned framing for the direct video playback data plane. */
object MediaLinkProtocol {
    private const val MAGIC = 0x4d4c4e4b // MLNK
    const val VERSION = 1
    const val HEADER_BYTES = 36
    const val MAX_META_BYTES = 64 * 1024
    const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024

    fun write(output: OutputStream, packet: MediaLinkPacket) {
        val meta = packet.meta.toByteArray(Charsets.UTF_8)
        require(meta.size <= MAX_META_BYTES) { "Media link metadata too large: ${meta.size}" }
        require(packet.payload.size <= MAX_PAYLOAD_BYTES) { "Media link payload too large: ${packet.payload.size}" }
        output.write(ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION.toByte()).put(packet.type.id.toByte()).putShort(packet.flags.toShort())
            .putLong(packet.epoch).putInt(packet.seq).putLong(packet.ptsUs).putInt(meta.size).putInt(packet.payload.size).array())
        if (meta.isNotEmpty()) output.write(meta)
        if (packet.payload.isNotEmpty()) output.write(packet.payload)
        output.flush()
    }

    fun read(input: InputStream): MediaLinkPacket? {
        val bytes = ByteArray(HEADER_BYTES)
        val read = readFullyOrEof(input, bytes)
        if (read == -1) return null
        if (read != HEADER_BYTES) throw EOFException("Short media link header")
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(header.int == MAGIC) { "Invalid media link magic" }
        require((header.get().toInt() and 0xff) == VERSION) { "Unsupported media link version" }
        val type = MediaLinkPacketType.fromId(header.get().toInt() and 0xff)
        val flags = header.short.toInt() and 0xffff
        val epoch = header.long; val seq = header.int; val ptsUs = header.long
        val metaLength = header.int; val payloadLength = header.int
        require(metaLength in 0..MAX_META_BYTES) { "Invalid media link metadata length: $metaLength" }
        require(payloadLength in 0..MAX_PAYLOAD_BYTES) { "Invalid media link payload length: $payloadLength" }
        return MediaLinkPacket(type, epoch, seq, ptsUs, flags,
            String(readFully(input, metaLength, "metadata"), Charsets.UTF_8), readFully(input, payloadLength, "payload"))
    }

    private fun readFully(input: InputStream, size: Int, label: String): ByteArray {
        if (size == 0) return ByteArray(0)
        return ByteArray(size).also { if (readFullyOrEof(input, it) != size) throw EOFException("Short media link $label") }
    }
    private fun readFullyOrEof(input: InputStream, bytes: ByteArray): Int {
        var offset = 0
        while (offset < bytes.size) { val count = input.read(bytes, offset, bytes.size - offset); if (count < 0) return if (offset == 0) -1 else offset; if (count > 0) offset += count }
        return offset
    }
}
