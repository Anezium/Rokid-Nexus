package com.anezium.rokidbus.glasses.audio

import android.media.MediaCodec
import android.media.MediaFormat
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import java.io.Closeable
import java.nio.ByteOrder
import java.util.TreeMap

internal class OpusEncoderPump(
    private val header: (ByteArray) -> Unit,
    private val packet: (ByteArray) -> Unit,
) : Closeable {
    private val codec = MediaCodec.createEncoderByType("audio/opus")
    private val suppression = TreeMap<Long, Boolean>()
    private var timeUs = 0L
    private var configured = false

    init {
        try {
            codec.configure(MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 32000)
            }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (failure: Throwable) {
            codec.release()
            throw failure
        }
    }

    fun feed(samples: ShortArray, silent: Boolean) {
        val input = codec.dequeueInputBuffer(10000)
        if (input < 0) {
            drain()
            error("Opus encoder input stalled")
        }
        codec.getInputBuffer(input)!!.apply { clear(); order(ByteOrder.LITTLE_ENDIAN); asShortBuffer().put(samples) }
        suppression[timeUs] = silent
        codec.queueInputBuffer(input, 0, samples.size * 2, timeUs, 0)
        timeUs += C.FRAME_MS * 1000L
        check(suppression.size <= 100) { "Opus encoder output stalled" }
        drain()
    }

    private fun drain() {
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = codec.dequeueOutputBuffer(info, 0)
            if (index >= 0) {
                try {
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        check(!configured) { "Opus codec config emitted twice" }
                        val buffer = codec.getOutputBuffer(index)!!.duplicate()
                        buffer.position(info.offset).limit(info.offset + info.size)
                        val csd = OpusCodecConfig.csd(ByteArray(info.size).also(buffer::get))
                        header(C.ready(C.OPUS, csd).apply { remove("v") }.toString().toByteArray(Charsets.UTF_8))
                        configured = true
                    } else if (info.size > 0) {
                        check(configured)
                        val suppress = suppression.floorEntry(info.presentationTimeUs)?.value ?: true
                        suppression.headMap(info.presentationTimeUs, true).clear()
                        if (!suppress) {
                            val buffer = codec.getOutputBuffer(index)!!.duplicate()
                            buffer.position(info.offset).limit(info.offset + info.size)
                            packet(ByteArray(info.size).also(buffer::get))
                        }
                    }
                } finally { codec.releaseOutputBuffer(index, false) }
            } else if (index != MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) return
        }
    }

    override fun close() { runCatching { codec.stop() }; codec.release() }
}
