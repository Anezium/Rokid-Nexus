package com.anezium.rokidbus.phone

import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioRouting
import android.media.AudioTrack
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.shared.GlassesAudioRelayContract as C
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

internal enum class AudioRelayRouteAdmission { WAIT, PLAY, REJECT }

internal class PhoneAudioRelayPlayer(
    private val ready: C.Ready,
    private val glassesAddress: String?,
    private val route: () -> PhoneTtsRoute,
    private val onRouteInvalid: (PhoneAudioRelayRouteError) -> Unit,
    private val onFailure: () -> Unit,
) : AudioRelayPlayback {
    private val running = AtomicBoolean(true)
    private val jitter = AudioRelayJitterBuffer()
    @Volatile private var track: AudioTrack? = null
    private val thread = Thread(::play, "rokidbus-audio-player").apply { isDaemon = true }
    init { thread.start() }

    override fun offer(seq: Long, packets: List<ByteArray>) { if (running.get()) jitter.offer(seq, packets) }

    private fun play() {
        var codec: MediaCodec? = null
        var audio: AudioTrack? = null
        var routing: AudioRouting.OnRoutingChangedListener? = null
        try {
            if (!running.get()) return
            if (route() != PhoneTtsRoute.EXTERNAL_SINK) { rejectRoute(); return }
            val decoder = MediaCodec.createDecoderByType("audio/opus")
            codec = decoder
            decoder.configure(decoderFormat(ready), null, null, 0)
            decoder.start()
            val output = AudioTrack.Builder()
                .setAudioAttributes(audioAttributes())
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                // Measured on a Galaxy S23 streaming A2DP to earbuds: RFCOMM arrivals jitter past 80 ms, so the
                // track must hold the whole primed cushion; a low-latency fast track would cap it.
                .setTransferMode(AudioTrack.MODE_STREAM)
                .setBufferSizeInBytes(12 * 960 * 2).build()
            audio = output
            track = output
            routing = AudioRouting.OnRoutingChangedListener {
                val device = output.routedDevice
                if (running.get() && (routeAdmission(device, glassesAddress) == AudioRelayRouteAdmission.REJECT || route() != PhoneTtsRoute.EXTERNAL_SINK)) rejectRoute(device)
            }
            output.addOnRoutingChangedListener(routing, Handler(Looper.getMainLooper()))
            val info = MediaCodec.BufferInfo()
            var ptsUs = 0L
            var playing = false
            var routeWaitStarted: Long? = null
            fun drain() {
                while (running.get()) {
                    val index = decoder.dequeueOutputBuffer(info, 0)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(decoder.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE) == 48000)
                        check(decoder.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT) == 1)
                        continue
                    }
                    if (index < 0) return
                    try {
                        val pcm = decoder.getOutputBuffer(index)!!.duplicate()
                        pcm.position(info.offset).limit(info.offset + info.size)
                        while (pcm.hasRemaining() && running.get()) {
                            if (route() != PhoneTtsRoute.EXTERNAL_SINK) { rejectRoute(output.routedDevice); return }
                            // play() selects an output, but no PCM may enter the track before it is known.
                            if (!playing) { output.play(); playing = true }
                            when (routeAdmission(output.routedDevice, glassesAddress)) {
                                AudioRelayRouteAdmission.WAIT -> {
                                    val now = SystemClock.elapsedRealtime()
                                    val started = routeWaitStarted ?: now.also { routeWaitStarted = it }
                                    check(now - started < C.START_TIMEOUT_MS) { "Audio route unavailable" }
                                    Thread.sleep(2)
                                    continue
                                }
                                AudioRelayRouteAdmission.REJECT -> { rejectRoute(output.routedDevice); return }
                                AudioRelayRouteAdmission.PLAY -> routeWaitStarted = null
                            }
                            val written = output.write(pcm, pcm.remaining(), AudioTrack.WRITE_NON_BLOCKING)
                            check(written >= 0) { "AudioTrack write failed" }
                            if (written == 0) Thread.sleep(2)
                        }
                    } finally { decoder.releaseOutputBuffer(index, false) }
                }
            }
            while (running.get()) {
                val frame = jitter.poll()
                if (frame == null) { drain(); Thread.sleep(2); continue }
                for (packet in frame.packets) {
                    var index = decoder.dequeueInputBuffer(10000)
                    while (index < 0 && running.get()) { drain(); index = decoder.dequeueInputBuffer(10000) }
                    if (!running.get()) break
                    decoder.getInputBuffer(index)!!.apply { clear(); put(packet) }
                    decoder.queueInputBuffer(index, 0, packet.size, ptsUs, 0)
                    ptsUs += C.FRAME_MS * 1000L
                    drain()
                }
            }
        } catch (_: Exception) {
            if (running.getAndSet(false)) onFailure()
        } finally {
            track = null
            if (audio != null && routing != null) audio.removeOnRoutingChangedListener(routing)
            runCatching { audio?.pause() }; runCatching { audio?.flush() }; runCatching { audio?.release() }
            runCatching { codec?.stop() }; runCatching { codec?.release() }
        }
    }

    private fun rejectRoute(device: AudioDeviceInfo? = null) {
        val error = if (!glassesAddress.isNullOrBlank() && device?.address.equals(glassesAddress, ignoreCase = true))
            PhoneAudioRelayRouteError.ROUTE_IS_GLASSES else PhoneAudioRelayRouteError.NO_EXTERNAL_SINK
        close()
        onRouteInvalid(error)
    }
    override fun close() {
        running.set(false)
        runCatching { track?.pause() }; runCatching { track?.flush() }
        thread.interrupt()
    }

    companion object {
        fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

        fun routeAdmission(device: AudioDeviceInfo?, glassesAddress: String?): AudioRelayRouteAdmission =
            if (device == null) AudioRelayRouteAdmission.WAIT else routeAdmission(device.type, device.address, glassesAddress)

        fun routeAdmission(type: Int, address: String?, glassesAddress: String?): AudioRelayRouteAdmission = when {
            !glassesAddress.isNullOrBlank() && address.equals(glassesAddress, ignoreCase = true) -> AudioRelayRouteAdmission.REJECT
            type in setOf(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_EARPIECE, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE) -> AudioRelayRouteAdmission.REJECT
            // An unidentified Bluetooth output could be the glasses, which would feed their sound back to them.
            phoneTtsRouteDeviceType(type) == PhoneTtsRouteDeviceType.BLUETOOTH_EXTERNAL &&
                (address.isNullOrBlank() || glassesAddress.isNullOrBlank()) -> AudioRelayRouteAdmission.REJECT
            else -> AudioRelayRouteAdmission.PLAY
        }

        fun decoderFormat(ready: C.Ready): MediaFormat {
            require(ready.codec == C.OPUS && ready.csd.size == 3 && ready.csd[1].size == 8 && ready.csd[2].size == 8)
            return MediaFormat.createAudioFormat("audio/opus", 48000, 1).apply {
                // CSD 1/2 are already little-endian u64 nanoseconds. Preserve every byte.
                ready.csd.forEachIndexed { index, bytes -> setByteBuffer("csd-$index", ByteBuffer.wrap(bytes.copyOf())) }
            }
        }
    }
}
