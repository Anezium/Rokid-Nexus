package com.anezium.rokidbus.plugin.lens

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.SystemClock
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketFlags
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.nio.ByteBuffer
import java.util.LinkedHashMap
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class Nv21BufferPool(private val capacity: Int = 3) : AutoCloseable {
    private val buffers = ArrayDeque<ByteArray>()
    private var closed = false

    @Synchronized
    fun acquire(size: Int): ByteArray {
        val iterator = buffers.iterator()
        while (iterator.hasNext()) {
            val candidate = iterator.next()
            if (candidate.size == size) {
                iterator.remove()
                return candidate
            }
        }
        return ByteArray(size)
    }

    @Synchronized
    fun recycle(buffer: ByteArray) {
        if (!closed && buffers.size < capacity) buffers.addLast(buffer)
    }

    @Synchronized
    override fun close() {
        closed = true
        buffers.clear()
    }
}

internal class DecodedFrame(
    val nv21: ByteArray,
    val width: Int,
    val height: Int,
    val frameId: Long,
    private val pool: Nv21BufferPool,
) : AutoCloseable {
    private val released = AtomicBoolean(false)
    override fun close() {
        if (released.compareAndSet(false, true)) pool.recycle(nv21)
    }
}

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

/** No-surface AVC decoder using flexible YUV output and stride-aware pooled NV21 conversion. */
internal class LatestFrameDecoder(
    private val holder: LatestFrameHolder,
    private val pool: Nv21BufferPool,
    private val onFrameReady: () -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private val running = AtomicBoolean(true)
    private val frames = LinkedBlockingDeque<EncodedFrame>(FRAME_QUEUE_CAPACITY)
    private val received = LinkedHashMap<Long, Unit>()
    private val worker = Thread(::decodeLoop, "lens-live-decoder").also { it.start() }
    private var codec: MediaCodec? = null
    private var config: ByteArray? = null
    @Volatile private var waitingForKeyFrame = true

    fun configure(configPayload: ByteArray, width: Int, height: Int, fps: Int) {
        if (!running.get() || width !in 1..MAX_EDGE || height !in 1..MAX_EDGE) return
        synchronized(lock) {
            if (config?.contentEquals(configPayload) == true && codec != null) return
            val sets = parameterSets(configPayload) ?: return onError("Incomplete H.264 codec config", null)
            frames.clear()
            received.clear()
            releaseCodecLocked()
            runCatching {
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                    setByteBuffer("csd-0", ByteBuffer.wrap(sets.first))
                    setByteBuffer("csd-1", ByteBuffer.wrap(sets.second))
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, MAX_PACKET_BYTES)
                    setInteger(MediaFormat.KEY_FRAME_RATE, fps.coerceIn(1, 60))
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
                config = configPayload.copyOf()
                waitingForKeyFrame = true
            }.onFailure { onError("H.264 decoder configure failed", it) }
        }
    }

    fun queue(packet: CameraLinkPacket) {
        if (!running.get() || packet.type != CameraLinkPacketType.VIDEO_FRAME) return
        val keyFrame = packet.flags and CameraLinkPacketFlags.KEY_FRAME != 0
        if (waitingForKeyFrame && !keyFrame) return
        if (keyFrame) waitingForKeyFrame = false
        val frame = EncodedFrame(packet.requestId, keyFrame, packet.payload)
        if (!frames.offerLast(frame)) {
            frames.clear()
            waitingForKeyFrame = !keyFrame
            if (keyFrame) frames.offerLast(frame)
        }
    }

    private fun decodeLoop() {
        while (running.get()) {
            val frame = runCatching { frames.poll(250, TimeUnit.MILLISECONDS) }.getOrNull() ?: continue
            decode(frame)
        }
    }

    private fun decode(frame: EncodedFrame) = synchronized(lock) {
        val decoder = codec ?: return@synchronized
        runCatching {
            drainLocked(decoder)
            val index = decoder.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (index < 0) return@runCatching
            val input = decoder.getInputBuffer(index) ?: error("Decoder input buffer unavailable")
            require(frame.payload.size <= input.capacity()) { "Encoded frame exceeds input buffer" }
            input.clear()
            input.put(frame.payload)
            received[frame.id] = Unit
            while (received.size > MAX_RECEIVE_IDS) received.remove(received.keys.first())
            decoder.queueInputBuffer(index, 0, frame.payload.size, frame.id, 0)
            drainLocked(decoder)
        }.onFailure {
            received.remove(frame.id)
            onError("H.264 decoder input failed", it)
        }
    }

    private fun drainLocked(decoder: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        while (true) {
            when (val index = decoder.dequeueOutputBuffer(info, 0)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> return
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                else -> if (index >= 0) {
                    if (info.size <= 0) {
                        decoder.releaseOutputBuffer(index, false)
                        continue
                    }
                    val frameId = info.presentationTimeUs
                    var image: Image? = null
                    try {
                        image = decoder.getOutputImage(index) ?: error("Decoder output image unavailable")
                        val converted = imageToNv21(image)
                        if (received.remove(frameId) != null) {
                            holder.offer(DecodedFrame(converted.bytes, converted.width, converted.height, frameId, pool))
                            onFrameReady()
                        } else {
                            pool.recycle(converted.bytes)
                        }
                    } catch (failure: Throwable) {
                        onError("H.264 output conversion failed", failure)
                    } finally {
                        image?.close()
                        decoder.releaseOutputBuffer(index, false)
                    }
                }
            }
        }
    }

    private fun imageToNv21(image: Image): Nv21Frame {
        require(image.planes.size == 3) { "Expected YUV_420_888 output" }
        val crop = image.cropRect
        val width = crop.width()
        val height = crop.height()
        require(width > 0 && height > 0) { "Invalid output crop" }
        val chromaWidth = (width + 1) / 2
        val chromaHeight = (height + 1) / 2
        val ySize = width * height
        val output = pool.acquire(ySize + chromaWidth * chromaHeight * 2)
        try {
            copyPlane(image.planes[0], crop.left, crop.top, width, height, output, 0, 1)
            copyPlane(image.planes[2], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, output, ySize, 2)
            copyPlane(image.planes[1], crop.left / 2, crop.top / 2, chromaWidth, chromaHeight, output, ySize + 1, 2)
            return Nv21Frame(output, width, height)
        } catch (failure: Throwable) {
            pool.recycle(output)
            throw failure
        }
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
        val start = buffer.position()
        var outputIndex = outputOffset
        repeat(height) { row ->
            var inputIndex = start + (cropTop + row) * plane.rowStride + cropLeft * plane.pixelStride
            repeat(width) {
                output[outputIndex] = buffer.get(inputIndex)
                inputIndex += plane.pixelStride
                outputIndex += outputPixelStride
            }
        }
    }

    private fun parameterSets(payload: ByteArray): Pair<ByteArray, ByteArray>? {
        val units = annexBUnits(payload)
        return (units.firstOrNull { nalType(it) == 7 } ?: return null) to
            (units.firstOrNull { nalType(it) == 8 } ?: return null)
    }

    private fun annexBUnits(payload: ByteArray): List<ByteArray> {
        val starts = mutableListOf<Int>()
        var index = 0
        while (index <= payload.size - 3) {
            val length = startCodeLength(payload, index)
            if (length > 0) {
                starts += index
                index += length
            } else index++
        }
        return starts.mapIndexed { i, start -> payload.copyOfRange(start, starts.getOrNull(i + 1) ?: payload.size) }
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
            received.clear()
        }
        holder.close()
    }

    private data class EncodedFrame(val id: Long, val keyFrame: Boolean, val payload: ByteArray)
    private data class Nv21Frame(val bytes: ByteArray, val width: Int, val height: Int)

    private companion object {
        const val FRAME_QUEUE_CAPACITY = 8
        const val MAX_RECEIVE_IDS = 32
        const val MAX_PACKET_BYTES = 4 * 1024 * 1024
        const val MAX_EDGE = 4096
        const val DEQUEUE_TIMEOUT_US = 2_000L
    }
}

/**
 * Holds recognition ownership until the consumer finishes translation. This keeps one complete
 * OCR/translation operation in flight while decoded-frame replacement preserves newest-wins.
 */
internal class LiveOcrRunner(
    private val holder: LatestFrameHolder,
    private val onRecognized: (DecodedFrame, Text, OcrScript, () -> Unit) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    private val inFlight = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)
    private val current = AtomicReference<DecodedFrame?>(null)
    private val recognizerLock = Any()
    private val recognizers = mutableMapOf<OcrScript, TextRecognizer>()
    private val policyLock = Any()
    private var cadenceState = OcrCadenceState()
    private var autoScriptState = AutoScriptState()
    private var lastNonLatinScript: OcrScript? = null

    fun kick() {
        if (closed.get() || !inFlight.compareAndSet(false, true)) return
        val startedAtMs = SystemClock.elapsedRealtime()
        val cadenceAccepted = synchronized(policyLock) {
            OcrCadencePolicy.evaluate(
                state = cadenceState,
                nowMs = startedAtMs,
                minIntervalMs = LIVE_OCR_MIN_INTERVAL_MS,
            ).also { cadenceState = it.nextState }.shouldStart
        }
        if (!cadenceAccepted) {
            inFlight.set(false)
            return
        }
        val frame = holder.takeNewest()
        if (frame == null) {
            inFlight.set(false)
            return
        }
        current.set(frame)
        val scriptPlan = synchronized(policyLock) {
            AutoScriptPolicy.planLiveFrame(autoScriptState, startedAtMs)
                .also { autoScriptState = it.nextState }
        }
        val task = runCatching {
            recognizerFor(scriptPlan.script).process(
                InputImage.fromByteArray(
                    frame.nv21,
                    frame.width,
                    frame.height,
                    0,
                    InputImage.IMAGE_FORMAT_NV21,
                ),
            )
        }.getOrElse {
            finish(frame, null, scriptPlan, it.message)
            return
        }
        task.addOnCompleteListener {
            finish(
                frame,
                if (it.isSuccessful) it.result else null,
                scriptPlan,
                it.exception?.message,
            )
        }
    }

    private fun finish(
        frame: DecodedFrame,
        result: Text?,
        scriptPlan: LiveScriptPlan,
        error: String?,
    ) {
        if (closed.get() || result == null) {
            if (error != null && !closed.get()) onError(error)
            releaseAndContinue(frame)
            return
        }
        val observation = synchronized(policyLock) {
            val observed = AutoScriptPolicy.observeLiveResult(
                state = autoScriptState,
                nowMs = SystemClock.elapsedRealtime(),
                script = scriptPlan.script,
                isProbe = scriptPlan.isProbe,
                isBurstProbe = scriptPlan.isBurstProbe,
                text = result.text,
            )
            if (observed.switched && observed.effectiveScript != OcrScript.LATIN) {
                lastNonLatinScript = observed.effectiveScript
            }
            autoScriptState = AutoScriptPolicy.biasNextProbe(
                state = observed.nextState,
                script = lastNonLatinScript.takeIf { observed.switched },
            )
            observed
        }
        if (!observation.acceptResult) {
            releaseAndContinue(frame)
            return
        }
        val completed = AtomicBoolean(false)
        val continuation = {
            if (completed.compareAndSet(false, true)) releaseAndContinue(frame)
        }
        runCatching { onRecognized(frame, result, scriptPlan.script, continuation) }
            .onFailure {
                onError(it.message.orEmpty())
                continuation()
            }
    }

    fun resetPolicies() {
        synchronized(policyLock) {
            cadenceState = OcrCadenceState()
            autoScriptState = AutoScriptState()
            lastNonLatinScript = null
        }
    }

    private fun recognizerFor(script: OcrScript): TextRecognizer = synchronized(recognizerLock) {
        check(!closed.get()) { "Live OCR runner is closed" }
        recognizers.getOrPut(script) {
            when (script) {
                OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
                OcrScript.JAPANESE -> TextRecognition.getClient(
                    JapaneseTextRecognizerOptions.Builder().build(),
                )
                OcrScript.CHINESE -> TextRecognition.getClient(
                    ChineseTextRecognizerOptions.Builder().build(),
                )
                OcrScript.KOREAN -> TextRecognition.getClient(
                    KoreanTextRecognizerOptions.Builder().build(),
                )
                OcrScript.DEVANAGARI -> TextRecognition.getClient(
                    DevanagariTextRecognizerOptions.Builder().build(),
                )
            }
        }
    }

    private fun releaseAndContinue(frame: DecodedFrame) {
        current.compareAndSet(frame, null)
        frame.close()
        inFlight.set(false)
        if (!closed.get()) kick()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        holder.close()
        synchronized(recognizerLock) {
            recognizers.values.forEach(TextRecognizer::close)
            recognizers.clear()
        }
        current.getAndSet(null)?.close()
        inFlight.set(false)
    }

    private companion object {
        const val LIVE_OCR_MIN_INTERVAL_MS = 300L
    }
}

internal class OverlayEmissionPolicy {
    private var previous: String? = null

    fun shouldEmit(recognizedText: String): Boolean {
        val normalized = recognizedText.lineSequence()
            .map { it.trim().replace(Regex("\\s+"), " ") }
            .filter(String::isNotBlank)
            .joinToString("\n")
        if (normalized.isBlank() || normalized == previous) return false
        previous = normalized
        return true
    }

    fun reset() {
        previous = null
    }
}
