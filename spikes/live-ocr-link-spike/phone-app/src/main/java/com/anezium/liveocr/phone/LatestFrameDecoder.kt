package com.anezium.liveocr.phone

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal data class DecodedFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val frameId: Long,
    val recvNanos: Long,
    val decodeDoneNanos: Long,
) : AutoCloseable {
    override fun close() = Unit
}

/** Owns exactly one not-yet-recognized frame; replacing it drops the stale byte array. */
internal class LatestFrameHolder : AutoCloseable {
    private val latest = AtomicReference<DecodedFrame?>(null)

    fun offer(frame: DecodedFrame) {
        latest.getAndSet(frame)?.close()
    }

    fun takeNewest(): DecodedFrame? = latest.getAndSet(null)

    override fun close() {
        latest.getAndSet(null)?.close()
    }
}

/** Hardware AVC decoder producing flexible YUV buffers that are converted to NV21. */
internal class LatestFrameDecoder(
    private val holder: LatestFrameHolder,
    private val onFrameReady: () -> Unit,
    private val onDecodeFps: (Double, Long, Long) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val running = AtomicBoolean(true)
    private val frames = LinkedBlockingDeque<EncodedFrame>(FRAME_QUEUE_CAPACITY)
    private val recvNanosByFrameId = LinkedHashMap<Long, Long>()
    private val worker = Thread(::decodeLoop, "live-ocr-phone-decoder").also { it.start() }

    private var codec: MediaCodec? = null
    private var currentConfig: ByteArray? = null
    private var waitingForKeyFrame = false
    private var decodedFrames = 0L
    private var droppedFrames = 0L
    private var fpsFrames = 0L
    private var fpsStartedNanos = SystemClock.elapsedRealtimeNanos()

    fun configure(configPayload: ByteArray) {
        if (!running.get()) return
        synchronized(lock) {
            if (currentConfig?.contentEquals(configPayload) == true && codec != null) return
            val parameterSets = parameterSets(configPayload)
            if (parameterSets == null) {
                onError("Incomplete H.264 codec config", null)
                return
            }
            frames.clear()
            recvNanosByFrameId.clear()
            releaseCodecLocked()
            runCatching {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(parameterSets.first))
                    setByteBuffer("csd-1", ByteBuffer.wrap(parameterSets.second))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PACKET_BYTES)
                    setInteger(MediaFormat.KEY_FRAME_RATE, STREAM_FPS)
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                    setInteger(
                        MediaFormat.KEY_COLOR_FORMAT,
                        MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                    )
                }
                codec = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).also {
                    it.configure(format, null, null, 0)
                    it.start()
                }
                currentConfig = configPayload.copyOf()
                waitingForKeyFrame = true
            }.onFailure { onError("H.264 decoder configure failed", it) }
        }
    }

    fun queueFrame(packet: SpikePacket, recvNanos: Long) {
        if (!running.get() || packet.type != PacketType.VIDEO_FRAME) return
        val keyFrame = packet.flags and PacketFlags.KEY_FRAME != 0
        if (waitingForKeyFrame && !keyFrame) {
            droppedFrames++
            return
        }
        if (keyFrame) waitingForKeyFrame = false
        val frame = EncodedFrame(
            frameId = packet.frameId,
            recvNanos = recvNanos,
            keyFrame = keyFrame,
            payload = packet.payload,
        )
        if (!frames.offerLast(frame)) {
            droppedFrames += frames.size.toLong()
            frames.clear()
            waitingForKeyFrame = true
            if (keyFrame) {
                waitingForKeyFrame = false
                frames.offerLast(frame)
            } else {
                droppedFrames++
            }
        }
    }

    private fun decodeLoop() {
        while (running.get()) {
            val frame = runCatching { frames.poll(250, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            decode(frame)
        }
    }

    private fun decode(frame: EncodedFrame) {
        synchronized(lock) {
            val decoder = codec ?: run {
                droppedFrames++
                return
            }
            runCatching {
                drainOutputLocked(decoder)
                val inputIndex = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputIndex < 0) {
                    droppedFrames++
                    return
                }
                val input = decoder.getInputBuffer(inputIndex) ?: error("Decoder input buffer unavailable")
                require(frame.payload.size <= input.capacity()) { "Encoded frame exceeds decoder input buffer" }
                input.clear()
                input.put(frame.payload)
                recvNanosByFrameId[frame.frameId] = frame.recvNanos
                trimReceiveTimes()
                decoder.queueInputBuffer(inputIndex, 0, frame.payload.size, frame.frameId, 0)
                drainOutputLocked(decoder)
            }.onFailure {
                recvNanosByFrameId.remove(frame.frameId)
                droppedFrames++
                onError("H.264 decoder input failed", it)
            }
        }
    }

    private fun drainOutputLocked(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Log.i(TAG, "output=${decoder.outputFormat}")
                else -> if (index >= 0) {
                    if (info.size <= 0) {
                        decoder.releaseOutputBuffer(index, false)
                        continue
                    }
                    val frameId = info.presentationTimeUs
                    val recvNanos = recvNanosByFrameId.remove(frameId)
                    var image: Image? = null
                    try {
                        image = decoder.getOutputImage(index)
                            ?: error("Decoder output image unavailable")
                        val converted = imageToNv21(image)
                        val decodeDoneNanos = SystemClock.elapsedRealtimeNanos()
                        decodedFrames++
                        fpsFrames++
                        if (recvNanos == null) {
                            droppedFrames++
                        } else {
                            holder.offer(
                                DecodedFrame(
                                    nv21 = converted.bytes,
                                    width = converted.width,
                                    height = converted.height,
                                    frameId = frameId,
                                    recvNanos = recvNanos,
                                    decodeDoneNanos = decodeDoneNanos,
                                ),
                            )
                            onFrameReady()
                        }
                        reportDecodeFps(decodeDoneNanos)
                    } catch (failure: Throwable) {
                        droppedFrames++
                        onError("H.264 decoder output conversion failed", failure)
                    } finally {
                        image?.close()
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun reportDecodeFps(decodeDoneNanos: Long) {
        val interval = decodeDoneNanos - fpsStartedNanos
        if (interval >= 1_000_000_000L) {
            onDecodeFps(fpsFrames * 1_000_000_000.0 / interval, decodedFrames, droppedFrames)
            fpsFrames = 0
            fpsStartedNanos = decodeDoneNanos
        }
    }

    private fun imageToNv21(image: Image): Nv21Frame {
        require(image.planes.size == 3) { "Expected YUV_420_888 output" }
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0) { "Invalid decoder output crop: $crop" }

        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width * height
        val output = ByteArray(ySize + chromaWidth * chromaHeight * 2)
        copyPlane(
            plane = image.planes[0],
            cropLeft = crop.left,
            cropTop = crop.top,
            width = width,
            height = height,
            output = output,
            outputOffset = 0,
            outputPixelStride = 1,
        )
        copyPlane(
            plane = image.planes[2],
            cropLeft = crop.left / 2,
            cropTop = crop.top / 2,
            width = chromaWidth,
            height = chromaHeight,
            output = output,
            outputOffset = ySize,
            outputPixelStride = 2,
        )
        copyPlane(
            plane = image.planes[1],
            cropLeft = crop.left / 2,
            cropTop = crop.top / 2,
            width = chromaWidth,
            height = chromaHeight,
            output = output,
            outputOffset = ySize + 1,
            outputPixelStride = 2,
        )
        return Nv21Frame(output, width, height)
    }

    private fun copyPlane(
        plane: Image.Plane,
        cropLeft: Int,
        cropTop: Int,
        width: Int,
        height: Int,
        output: ByteArray,
        outputOffset: Int,
        outputPixelStride: Int,
    ) {
        val buffer = plane.buffer.duplicate()
        val bufferStart = buffer.position()
        var outputIndex = outputOffset
        repeat(height) { row ->
            var inputIndex = bufferStart + (cropTop + row) * plane.rowStride +
                cropLeft * plane.pixelStride
            repeat(width) {
                output[outputIndex] = buffer.get(inputIndex)
                inputIndex += plane.pixelStride
                outputIndex += outputPixelStride
            }
        }
    }

    private fun parameterSets(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        val units = annexBUnits(payload)
        val sps = units.firstOrNull { nalType(it) == 7 }
        val pps = units.firstOrNull { nalType(it) == 8 }
        return if (sps != null && pps != null) sps to pps else null
    }

    private fun annexBUnits(payload: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= payload.size - 3) {
            val length = startCodeLength(payload, index)
            if (length > 0) {
                starts += index
                index += length
            } else {
                index++
            }
        }
        return starts.mapIndexed { unitIndex, start ->
            payload.copyOfRange(start, starts.getOrNull(unitIndex + 1) ?: payload.size)
        }
    }

    private fun nalType(unit: ByteArray): Int {
        val offset = startCodeLength(unit, 0)
        return if (offset in 1 until unit.size) unit[offset].toInt() and 0x1f else -1
    }

    private fun startCodeLength(bytes: ByteArray, offset: Int): Int = when {
        offset + 3 < bytes.size && bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
            bytes[offset + 2] == 0.toByte() && bytes[offset + 3] == 1.toByte() -> 4
        offset + 2 < bytes.size && bytes[offset] == 0.toByte() && bytes[offset + 1] == 0.toByte() &&
            bytes[offset + 2] == 1.toByte() -> 3
        else -> 0
    }

    private fun trimReceiveTimes() {
        while (recvNanosByFrameId.size > MAX_RECEIVE_TIMES) {
            val iterator = recvNanosByFrameId.entries.iterator()
            if (iterator.hasNext()) {
                iterator.next()
                iterator.remove()
            }
        }
    }

    private fun releaseCodecLocked() {
        runCatching { codec?.stop() }
        runCatching { codec?.release() }
        codec = null
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        worker.interrupt()
        frames.clear()
        synchronized(lock) {
            releaseCodecLocked()
            recvNanosByFrameId.clear()
        }
        holder.close()
    }

    private data class EncodedFrame(
        val frameId: Long,
        val recvNanos: Long,
        val keyFrame: Boolean,
        val payload: ByteArray,
    )

    private data class Nv21Frame(
        val bytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    companion object {
        private const val TAG = "LiveOcrPhoneDecoder"
        // Must match CameraH264Streamer on the glasses; 720x1280 is camera-supported there.
        const val WIDTH = 720
        const val HEIGHT = 1280
        private const val STREAM_FPS = 20
        private const val FRAME_QUEUE_CAPACITY = 8
        private const val MAX_RECEIVE_TIMES = 32
        private const val MAX_PACKET_BYTES = 4 * 1024 * 1024
        private const val DEQUEUE_TIMEOUT_US = 2_000L
    }
}
