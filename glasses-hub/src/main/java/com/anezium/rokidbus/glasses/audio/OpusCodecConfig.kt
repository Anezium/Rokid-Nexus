package com.anezium.rokidbus.glasses.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Android's Opus encoder reports its setup as a CODEC_CONFIG output buffer, not as csd-* format
 * keys. Codec2 wraps the three decoder inputs in `AOPUSHDR`/`AOPUSDLY`/`AOPUSPRL` markers (each
 * followed by a little-endian u64 length); older encoders emit a bare `OpusHead`, whose delay and
 * pre-roll the decoder then needs derived. Returns csd-0 (OpusHead), csd-1 (codec delay, ns) and
 * csd-2 (seek pre-roll, ns), both as little-endian longs.
 */
internal object OpusCodecConfig {
    private const val HEADER = "AOPUSHDR"
    private const val DELAY = "AOPUSDLY"
    private const val PREROLL = "AOPUSPRL"
    private const val SEEK_PREROLL_NS = 80_000_000L

    fun csd(config: ByteArray): List<ByteArray> {
        if (config.startsWith(HEADER, 0)) {
            val sections = HashMap<String, ByteArray>()
            var offset = 0
            while (offset + 16 <= config.size) {
                val marker = String(config, offset, 8, Charsets.US_ASCII)
                val length = ByteBuffer.wrap(config, offset + 8, 8).order(ByteOrder.LITTLE_ENDIAN).long
                require(length in 0..(config.size - offset - 16).toLong()) { "Opus config section length" }
                sections[marker] = config.copyOfRange(offset + 16, offset + 16 + length.toInt())
                offset += 16 + length.toInt()
            }
            val head = requireNotNull(sections[HEADER]) { "Opus config without header" }
            require(head.startsWith("OpusHead", 0)) { "Opus config header" }
            return listOf(
                head,
                sections[DELAY] ?: long(preSkipNs(head)),
                sections[PREROLL] ?: long(SEEK_PREROLL_NS),
            )
        }
        require(config.startsWith("OpusHead", 0)) { "Unrecognised Opus codec config" }
        return listOf(config.copyOf(), long(preSkipNs(config)), long(SEEK_PREROLL_NS))
    }

    private fun preSkipNs(head: ByteArray): Long {
        require(head.size >= 19) { "OpusHead too short" }
        val preSkip = ByteBuffer.wrap(head, 10, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xffff
        return preSkip * 1_000_000_000L / 48_000L
    }

    private fun long(value: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array()

    private fun ByteArray.startsWith(prefix: String, at: Int): Boolean {
        val bytes = prefix.toByteArray(Charsets.US_ASCII)
        return size >= at + bytes.size && bytes.indices.all { this[at + it] == bytes[it] }
    }
}
