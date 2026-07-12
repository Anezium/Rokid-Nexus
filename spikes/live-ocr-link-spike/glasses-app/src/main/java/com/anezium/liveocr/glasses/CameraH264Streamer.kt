package com.anezium.liveocr.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong

internal class CameraH264Streamer(
    private val context: Context,
    private val packetSender: (SpikePacket) -> Boolean,
    private val onFrameEnqueued: (frameId: Long, captureNanos: Long) -> Unit,
    private val onStats: (frames: Long, bytes: Long, dropped: Long) -> Unit,
    private val onRunning: () -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private var cameraThread: HandlerThread? = null
    private var encoderThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderHandler: Handler? = null
    private var encoder: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var latestConfig: ByteArray? = null
    private val captureElapsedByPtsUs = ConcurrentSkipListMap<Long, Long>()
    private val nextFrameId = AtomicLong(1)
    @Volatile private var cameraTimestampsAreRealtime = false
    private var frames = 0L
    private var bytes = 0L
    private var dropped = 0L

    fun start() {
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        close()
        cameraThread = HandlerThread("live-ocr-camera").also { it.start() }
        encoderThread = HandlerThread("live-ocr-encoder").also { it.start() }
        cameraHandler = Handler(requireNotNull(cameraThread).looper)
        encoderHandler = Handler(requireNotNull(encoderThread).looper)
        prepareEncoder()
        openCamera()
    }

    fun setBitrate(bitsPerSecond: Int) {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond)
            })
        }.onFailure { onError("Bitrate update failed", it) }
    }

    fun requestKeyFrame() {
        runCatching {
            encoder?.setParameters(Bundle().apply {
                putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            })
        }
    }

    private fun prepareEncoder() {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, DEFAULT_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    val output = codec.getOutputBuffer(index)
                    if (output != null && info.size > 0) sendEncodedOutput(output, info)
                } catch (failure: Throwable) {
                    onError("Encoder output failed", failure)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                onError("Encoder error: ${error.diagnosticInfo}", error)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                latestConfig = codecConfigFromFormat(format)
                latestConfig?.let { packetSender(SpikePacket(PacketType.VIDEO_CONFIG, payload = it)) }
            }
        }, encoderHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        inputSurface = codec.createInputSurface()
        encoder = codec
        codec.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val manager = context.getSystemService(CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { it == "0" }
            ?: manager.cameraIdList.firstOrNull()
            ?: error("No camera found")
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val timestampSource = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        cameraTimestampsAreRealtime = timestampSource == CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        Log.i(TAG, "camera=$cameraId timestampSource=$timestampSource realtime=$cameraTimestampsAreRealtime")
        val rotateAndCrop = selectRotateAndCrop(characteristics)
        val fpsRange = selectFpsRange(characteristics)
        manager.openCamera(cameraId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                camera = device
                createCaptureSession(device, rotateAndCrop, fpsRange)
            }
            override fun onDisconnected(device: CameraDevice) {
                device.close(); camera = null; onError("Camera disconnected", null)
            }
            override fun onError(device: CameraDevice, error: Int) {
                device.close(); camera = null; onError("Camera open error $error", null)
            }
        }, cameraHandler)
    }

    private fun createCaptureSession(device: CameraDevice, rotateAndCrop: Int?, fpsRange: Range<Int>?) {
        val surface = requireNotNull(inputSurface)
        @Suppress("DEPRECATION")
        device.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(next: CameraCaptureSession) {
                session = next
                val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    addTarget(surface)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                    rotateAndCrop?.let { set(CaptureRequest.SCALER_ROTATE_AND_CROP, it) }
                }.build()
                next.setRepeatingRequest(request, captureCallback, cameraHandler)
                onRunning()
            }
            override fun onConfigureFailed(session: CameraCaptureSession) {
                onError("Camera capture session configure failed", null)
            }
        }, cameraHandler)
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            if (cameraTimestampsAreRealtime || timestamp <= 0L) return
            // UNKNOWN sensor timestamps cannot be compared with elapsedRealtimeNanos. Correlate
            // the encoder PTS to an elapsed-realtime sample taken at the capture-start callback.
            captureElapsedByPtsUs[timestamp / 1_000L] = SystemClock.elapsedRealtimeNanos()
            while (captureElapsedByPtsUs.size > MAX_CAPTURE_CLOCK_SAMPLES) {
                captureElapsedByPtsUs.pollFirstEntry()
            }
        }
    }

    private fun selectRotateAndCrop(characteristics: CameraCharacteristics): Int? {
        val modes = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
            ?.toSet().orEmpty()
        return when {
            CaptureRequest.SCALER_ROTATE_AND_CROP_270 in modes -> CaptureRequest.SCALER_ROTATE_AND_CROP_270
            CaptureRequest.SCALER_ROTATE_AND_CROP_AUTO in modes -> CaptureRequest.SCALER_ROTATE_AND_CROP_AUTO
            else -> null
        }
    }

    private fun selectFpsRange(characteristics: CameraCharacteristics): Range<Int>? {
        val ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.filter { FPS in it.lower..it.upper }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { kotlin.math.abs(it.upper - FPS) })
            ?: ranges.minByOrNull { kotlin.math.abs(it.upper - FPS) + kotlin.math.abs(it.lower - FPS) }
    }

    private fun sendEncodedOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val payload = ByteArray(info.size).also(buffer::get)
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig) {
            latestConfig = payload
            packetSender(SpikePacket(PacketType.VIDEO_CONFIG, payload = payload))
            return
        }
        if (isKey) latestConfig?.let { packetSender(SpikePacket(PacketType.VIDEO_CONFIG, payload = it)) }

        val frameId = nextFrameId.getAndIncrement()
        // Camera-to-surface timestamps become encoder presentation timestamps. On Android camera2
        // REALTIME sources share elapsedRealtimeNanos' timebase, keeping capture and ACK on glasses.
        val captureNanos = when {
            info.presentationTimeUs <= 0L -> SystemClock.elapsedRealtimeNanos()
            cameraTimestampsAreRealtime -> info.presentationTimeUs * 1_000L
            else -> captureElapsedByPtsUs.remove(info.presentationTimeUs)
                ?: SystemClock.elapsedRealtimeNanos()
        }
        val packet = SpikePacket(
            type = PacketType.VIDEO_FRAME,
            frameId = frameId,
            captureNanos = captureNanos,
            flags = if (isKey) PacketFlags.KEY_FRAME else 0,
            payload = payload,
        )
        if (packetSender(packet)) {
            frames++
            bytes += payload.size
            onFrameEnqueued(frameId, captureNanos)
        } else {
            dropped++
        }
        onStats(frames, bytes, dropped)
    }

    private fun codecConfigFromFormat(format: MediaFormat): ByteArray {
        val chunks = listOfNotNull(format.getByteBuffer("csd-0")?.copyBytes(), format.getByteBuffer("csd-1")?.copyBytes())
        return chunks.fold(ByteArray(0)) { result, bytes -> result + withStartCode(bytes) }
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val copy = duplicate()
        return ByteArray(copy.remaining()).also(copy::get)
    }

    private fun withStartCode(bytes: ByteArray): ByteArray {
        val hasStart = bytes.size >= 4 && bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
            ((bytes[2] == 1.toByte()) || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))
        return if (hasStart) bytes else byteArrayOf(0, 0, 0, 1) + bytes
    }

    override fun close() {
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { inputSurface?.release() }
        session = null; camera = null; encoder = null; inputSurface = null; latestConfig = null
        captureElapsedByPtsUs.clear()
        cameraTimestampsAreRealtime = false
        cameraThread?.quitSafely(); encoderThread?.quitSafely()
        cameraThread = null; encoderThread = null; cameraHandler = null; encoderHandler = null
    }

    companion object {
        private const val TAG = "LiveOcrGlassesCodec"
        // 960x1280 is not in this camera's stream configuration map; 720x1280 is.
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 20
        const val DEFAULT_BITRATE = 5_000_000
        const val MIN_BITRATE = 2_000_000
        const val MAX_BITRATE = 8_000_000
        private const val IFRAME_SECONDS = 1
        private const val MAX_CAPTURE_CLOCK_SAMPLES = 256
    }
}
