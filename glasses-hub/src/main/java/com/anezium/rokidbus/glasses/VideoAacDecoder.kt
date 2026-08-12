package com.anezium.rokidbus.glasses

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import java.nio.ByteBuffer

/** AAC-to-AudioTrack path used only when the sender explicitly enables sound. */
internal class VideoAacDecoder : AutoCloseable {
    private var codec: MediaCodec? = null
    private var track: AudioTrack? = null

    fun configure(sampleRate: Int, channelCount: Int, codecConfig: ByteArray) {
        close()
        require(sampleRate in 8_000..96_000 && channelCount in 1..2) { "Unsupported audio format" }
        val channelMask = if (channelCount == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
        require(minBuffer > 0) { "Audio output unavailable" }
        track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(sampleRate).setChannelMask(channelMask).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(minBuffer * 4)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build().also { it.play() }
        val format = MediaFormat.createAudioFormat("audio/mp4a-latm", sampleRate, channelCount).apply {
            if (codecConfig.isNotEmpty()) setByteBuffer(MediaFormat.KEY_CSD_0, ByteBuffer.wrap(codecConfig))
        }
        codec = MediaCodec.createDecoderByType("audio/mp4a-latm").also { it.configure(format, null, null, 0); it.start() }
    }

    fun queue(sample: ByteArray, ptsUs: Long) {
        val active = codec ?: return
        val inputIndex = active.dequeueInputBuffer(5_000)
        if (inputIndex >= 0) {
            val input = active.getInputBuffer(inputIndex)
            if (input == null || sample.size > input.capacity()) {
                active.queueInputBuffer(inputIndex, 0, 0, ptsUs.coerceAtLeast(0L), 0)
                return
            }
            input.clear()
            input.put(sample)
            active.queueInputBuffer(inputIndex, 0, sample.size, ptsUs.coerceAtLeast(0L), 0)
        }
        drain()
    }

    private fun drain() {
        val active = codec ?: return
        val info = MediaCodec.BufferInfo()
        while (true) {
            val index = active.dequeueOutputBuffer(info, 0)
            if (index < 0) return
            active.getOutputBuffer(index)?.let { output ->
                output.position(info.offset)
                output.limit(info.offset + info.size)
                track?.write(output, info.size, AudioTrack.WRITE_NON_BLOCKING)
            }
            active.releaseOutputBuffer(index, false)
        }
    }

    override fun close() {
        val activeCodec = codec; codec = null
        runCatching { activeCodec?.stop() }; runCatching { activeCodec?.release() }
        val activeTrack = track; track = null
        runCatching { activeTrack?.pause() }; runCatching { activeTrack?.flush() }; runCatching { activeTrack?.release() }
    }
}
