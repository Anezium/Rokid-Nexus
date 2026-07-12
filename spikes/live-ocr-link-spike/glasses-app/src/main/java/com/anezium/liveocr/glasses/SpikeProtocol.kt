package com.anezium.liveocr.glasses

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal enum class PacketType(val id: Int) {
    HELLO(1), VIDEO_CONFIG(2), VIDEO_FRAME(3), OCR_ACK(4);
    companion object {
        fun fromId(id: Int) = entries.firstOrNull { it.id == id }
            ?: throw IllegalArgumentException("Unknown packet type $id")
    }
}

internal data class SpikePacket(
    val type: PacketType,
    val frameId: Long = 0,
    val captureNanos: Long = 0,
    val flags: Int = 0,
    val payload: ByteArray = ByteArray(0),
)

internal object PacketFlags { const val KEY_FRAME = 1 }

internal object SpikeProtocol {
    private const val MAGIC = 0x4c4f4352 // LOCR
    private const val VERSION = 1
    private const val HEADER_BYTES = 28
    private const val MAX_PAYLOAD_BYTES = 4 * 1024 * 1024

    fun write(output: OutputStream, packet: SpikePacket) {
        require(packet.payload.size <= MAX_PAYLOAD_BYTES)
        output.write(ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN)
            .putInt(MAGIC).put(VERSION.toByte()).put(packet.type.id.toByte())
            .putShort(packet.flags.toShort()).putLong(packet.frameId)
            .putLong(packet.captureNanos).putInt(packet.payload.size).array())
        if (packet.payload.isNotEmpty()) output.write(packet.payload)
        output.flush()
    }

    fun read(input: InputStream): SpikePacket {
        val header = ByteBuffer.wrap(readFully(input, HEADER_BYTES)).order(ByteOrder.BIG_ENDIAN)
        require(header.int == MAGIC) { "Invalid stream magic" }
        require((header.get().toInt() and 0xff) == VERSION) { "Unsupported stream version" }
        val type = PacketType.fromId(header.get().toInt() and 0xff)
        val flags = header.short.toInt() and 0xffff
        val frameId = header.long
        val captureNanos = header.long
        val size = header.int
        require(size in 0..MAX_PAYLOAD_BYTES) { "Invalid payload size $size" }
        return SpikePacket(type, frameId, captureNanos, flags, readFully(input, size))
    }

    private fun readFully(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = input.read(bytes, offset, size - offset)
            if (count < 0) throw EOFException("Stream ended while reading $size bytes")
            offset += count
        }
        return bytes
    }
}
