package com.anezium.rokidbus.glasses

import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.anezium.rokidbus.shared.MediaLinkPacketFlags
import java.nio.ByteBuffer

/** Small, surface-only AVC receiver. The sender owns pacing; MediaCodec owns presentation time. */
internal class VideoH264Decoder(private val surface: Surface) : AutoCloseable {
    private var codec: MediaCodec? = null
    private var configured = false

    fun configure(width: Int, height: Int, csd0: ByteArray, csd1: ByteArray? = null) {
        closeCodec()
        require(width in 1..1920 && height in 1..1080) { "Unsupported video size" }
        val format = MediaFormat.createVideoFormat(MIME_AVC, width, height).apply {
            if (csd0.isNotEmpty()) setByteBuffer(MediaFormat.KEY_CSD_0, ByteBuffer.wrap(csd0))
            if (!csd1.isNullOrEmpty()) setByteBuffer("csd-1", ByteBuffer.wrap(csd1))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_SAMPLE_BYTES)
        }
        codec = MediaCodec.createDecoderByType(MIME_AVC).also {
            it.configure(format, surface, null, 0)
            it.start()
        }
        configured = true
    }

    fun queue(sample: ByteArray, ptsUs: Long, flags: Int) {
        if (!configured || sample.size > MAX_SAMPLE_BYTES) return
        val active = codec ?: return
        val inputIndex = active.dequeueInputBuffer(INPUT_TIMEOUT_US)
        if (inputIndex < 0) return
        val input = active.getInputBuffer(inputIndex)
        if (input == null || sample.size > input.remaining()) {
            active.queueInputBuffer(inputIndex, 0, 0, ptsUs.coerceAtLeast(0L), 0)
            return
        }
        input.put(sample)
        active.queueInputBuffer(
            inputIndex,
            0,
            sample.size,
            ptsUs.coerceAtLeast(0L),
            if (flags and MediaLinkPacketFlags.KEY_FRAME != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0,
        )
        drain()
    }

    private fun drain() {
        val active = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val outputIndex = active.dequeueOutputBuffer(info, OUTPUT_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER, MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> return
                else -> if (outputIndex >= 0) active.releaseOutputBuffer(outputIndex, true) else return
            }
        }
    }

    override fun close() = closeCodec()

    private fun closeCodec() {
        configured = false
        val active = codec ?: return
        codec = null
        runCatching { active.stop() }
        runCatching { active.release() }
    }

    private companion object {
        const val MIME_AVC = "video/avc"
        const val MAX_SAMPLE_BYTES = 1_048_576
        const val INPUT_TIMEOUT_US = 5_000L
        const val OUTPUT_TIMEOUT_US = 0L
    }
}
