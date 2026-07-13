package com.anezium.rokidbus.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.ExifInterface
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketFlags
import com.anezium.rokidbus.shared.CameraLinkPacketType
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/** One camera2 owner for preview, H.264 streaming, zoom, and HD JPEG freeze capture. */
internal class CameraH264Streamer(
    private val context: Context,
    private val packetSender: (CameraLinkPacket) -> Boolean,
    private val displayRotationDegrees: Int,
    private val onLiveFrameGeometry: (width: Int, height: Int, rotationDegrees: Int) -> Unit,
    private val onRunning: () -> Unit,
    private val onRetry: (attempt: Int, delayMs: Long, reason: String) -> Unit,
    private val onFrozenJpeg: (
        requestId: Long,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) -> Unit,
    private val onError: (String, Throwable?) -> Unit,
) : AutoCloseable {
    private var cameraThread: HandlerThread? = null
    private var encoderThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var encoderHandler: Handler? = null
    private var encoder: MediaCodec? = null
    private var encoderSurface: Surface? = null
    private var previewSurface: Surface? = null
    private var imageReader: ImageReader? = null
    private var camera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var cameraId: String? = null
    private var characteristics: CameraCharacteristics? = null
    private var fpsRange: Range<Int>? = null
    private var rotateAndCropMode: Int? = null
    private var frameRotationDegrees = 0
    private var latestConfig: ByteArray? = null
    private val pendingFreezeIds = ArrayDeque<Long>()
    private val captureElapsedByPtsUs = ConcurrentSkipListMap<Long, Long>()
    private val nextFrameId = AtomicLong(1L)
    private var retryRunnable: Runnable? = null
    private var openRetryAttempt = 0
    private var currentZoomScale = 1f
    @Volatile private var running = false
    @Volatile private var cameraTimestampsAreRealtime = false

    fun start(surface: Surface) {
        check(context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
        close()
        running = true
        previewSurface = surface
        cameraThread = HandlerThread("camera-domain-device").also { it.start() }
        encoderThread = HandlerThread("camera-domain-encoder").also { it.start() }
        cameraHandler = Handler(requireNotNull(cameraThread).looper)
        encoderHandler = Handler(requireNotNull(encoderThread).looper)
        try {
            prepareCameraMetadata()
            prepareEncoder()
            openCamera()
        } catch (failure: Throwable) {
            onError("CAMERA START FAILED", failure)
            close()
        }
    }

    fun setZoom(scale: Float) {
        currentZoomScale = scale.coerceAtLeast(1f)
        cameraHandler?.post { updateRepeatingRequest() }
    }

    fun captureFrozenJpeg(requestId: Long) {
        cameraHandler?.post {
            val device = camera
            val activeSession = session
            val target = imageReader?.surface
            if (!running || device == null || activeSession == null || target == null) {
                onError("CAMERA NOT READY", null)
                return@post
            }
            val request = runCatching {
                device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE).apply {
                    addTarget(target)
                    set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                    set(CaptureRequest.JPEG_QUALITY, 95.toByte())
                    set(CaptureRequest.JPEG_ORIENTATION, frameRotationDegrees)
                    rotateAndCropMode?.let { set(CaptureRequest.SCALER_ROTATE_AND_CROP, it) }
                    cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
                }.build()
            }.getOrElse {
                onError("FREEZE CAPTURE FAILED", it)
                return@post
            }
            pendingFreezeIds.addLast(requestId)
            runCatching {
                activeSession.capture(request, object : CameraCaptureSession.CaptureCallback() {
                    override fun onCaptureFailed(
                        session: CameraCaptureSession,
                        request: CaptureRequest,
                        failure: CaptureFailure,
                    ) {
                        pendingFreezeIds.remove(requestId)
                        onError("FREEZE CAPTURE FAILED", null)
                    }
                }, cameraHandler)
            }.onFailure {
                pendingFreezeIds.remove(requestId)
                onError("FREEZE CAPTURE FAILED", it)
            }
        }
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
            setInteger(MediaFormat.KEY_BIT_RATE, BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_SECONDS)
            setInteger(MediaFormat.KEY_BITRATE_MODE, MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(codec: MediaCodec, index: Int) = Unit

            override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
                try {
                    codec.getOutputBuffer(index)?.takeIf { info.size > 0 }?.let { sendEncodedOutput(it, info) }
                } catch (failure: Throwable) {
                    onError("ENCODER OUTPUT FAILED", failure)
                } finally {
                    runCatching { codec.releaseOutputBuffer(index, false) }
                }
            }

            override fun onError(codec: MediaCodec, error: MediaCodec.CodecException) {
                onError("ENCODER FAILED", error)
            }

            override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
                latestConfig = codecConfigFromFormat(format)
                latestConfig?.let(::sendVideoConfig)
            }
        }, encoderHandler)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        encoderSurface = codec.createInputSurface()
        encoder = codec
        codec.start()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!running) return
        prepareCameraMetadata()
        val manager = context.getSystemService(CameraManager::class.java)
        val selectedId = cameraId ?: error("No camera found")
        manager.openCamera(selectedId, object : CameraDevice.StateCallback() {
            override fun onOpened(device: CameraDevice) {
                if (!running) {
                    device.close()
                    return
                }
                camera = device
                createCaptureSession(device)
            }

            override fun onDisconnected(device: CameraDevice) {
                device.close()
                if (camera === device) camera = null
                scheduleOpenRetry("disconnected")
            }

            override fun onError(device: CameraDevice, error: Int) {
                device.close()
                if (camera === device) camera = null
                scheduleOpenRetry("open error $error")
            }
        }, cameraHandler)
    }

    private fun prepareCameraMetadata() {
        if (characteristics != null && cameraId != null && imageReader != null) return
        val manager = context.getSystemService(CameraManager::class.java)
        val selectedId = cameraId ?: manager.cameraIdList.firstOrNull { it == "0" }
            ?: manager.cameraIdList.firstOrNull()
            ?: error("No camera found")
        val selectedCharacteristics = characteristics ?: manager.getCameraCharacteristics(selectedId)
        cameraId = selectedId
        characteristics = selectedCharacteristics
        cameraTimestampsAreRealtime = selectedCharacteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
            CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME
        val sensorOrientation = selectedCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val frontFacing = selectedCharacteristics.get(CameraCharacteristics.LENS_FACING) ==
            CameraCharacteristics.LENS_FACING_FRONT
        frameRotationDegrees = CameraOrientation.sensorToDisplayRotationDegrees(
            sensorOrientation,
            displayRotationDegrees,
            frontFacing,
        )
        onLiveFrameGeometry(WIDTH, HEIGHT, frameRotationDegrees)
        // Keep sensor pixels unrotated here. Camera2 rotate-and-crop discards FOV; the preview
        // Surface and the phone geometry pipeline apply the orientation without that crop.
        rotateAndCropMode = selectUnrotatedOutput(selectedCharacteristics)
        fpsRange = selectFpsRange(selectedCharacteristics)
        if (imageReader == null) prepareImageReader(selectedCharacteristics)
    }

    private fun prepareImageReader(cameraCharacteristics: CameraCharacteristics) {
        val sizes = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?.getOutputSizes(ImageFormat.JPEG).orEmpty()
        val active = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
        val selected = selectFullFovJpegSize(
            candidates = sizes.map { CameraOutputSize(it.width, it.height) },
            sensorWidth = active?.width() ?: 4,
            sensorHeight = active?.height() ?: 3,
        )?.let { Size(it.width, it.height) }
            ?: sizes.firstOrNull()
            ?: Size(2_048, 1_536)
        imageReader = ImageReader.newInstance(selected.width, selected.height, ImageFormat.JPEG, 2).apply {
            setOnImageAvailableListener({ reader ->
                val image = reader.acquireNextImage() ?: return@setOnImageAvailableListener
                try {
                    val requestId = if (pendingFreezeIds.isEmpty()) null else pendingFreezeIds.removeFirst()
                    if (requestId == null) return@setOnImageAvailableListener
                    val buffer = image.planes[0].buffer
                    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
                    val rawSize = jpegPixelSize(bytes) ?: CameraPixelSize(image.width, image.height)
                    val rotation = jpegRotationDegrees(bytes, frameRotationDegrees)
                    val oriented = CameraOrientation.orientedSize(rawSize.width, rawSize.height, rotation)
                    onFrozenJpeg(requestId, bytes, oriented.width, oriented.height, rotation)
                } finally {
                    image.close()
                }
            }, cameraHandler)
        }
    }

    private fun createCaptureSession(device: CameraDevice) {
        val targets = listOfNotNull(previewSurface, encoderSurface, imageReader?.surface)
        @Suppress("DEPRECATION")
        device.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(next: CameraCaptureSession) {
                if (!running || camera !== device) {
                    next.close()
                    return
                }
                session = next
                openRetryAttempt = 0
                updateRepeatingRequest()
                onRunning()
            }

            override fun onConfigureFailed(failed: CameraCaptureSession) {
                failed.close()
                if (session === failed) session = null
                scheduleOpenRetry("session configure failed")
            }
        }, cameraHandler)
    }

    private fun updateRepeatingRequest() {
        val device = camera ?: return
        val activeSession = session ?: return
        val preview = previewSurface ?: return
        val encode = encoderSurface ?: return
        runCatching {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(encode)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                rotateAndCropMode?.let { set(CaptureRequest.SCALER_ROTATE_AND_CROP, it) }
                cropRegion()?.let { set(CaptureRequest.SCALER_CROP_REGION, it) }
            }.build()
            activeSession.setRepeatingRequest(request, captureCallback, cameraHandler)
        }.onFailure { scheduleOpenRetry("repeat request failed") }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            timestamp: Long,
            frameNumber: Long,
        ) {
            if (cameraTimestampsAreRealtime || timestamp <= 0L) return
            captureElapsedByPtsUs[timestamp / 1_000L] = SystemClock.elapsedRealtimeNanos()
            while (captureElapsedByPtsUs.size > MAX_CAPTURE_CLOCK_SAMPLES) captureElapsedByPtsUs.pollFirstEntry()
        }
    }

    private fun cropRegion(): Rect? {
        val active = characteristics?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
        val maxZoom = characteristics?.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
        val zoom = currentZoomScale.coerceIn(1f, maxZoom)
        if (zoom <= 1f) return Rect(active)
        val width = ((active.width() / zoom).toInt() and -2).coerceAtLeast(2)
        val height = ((active.height() / zoom).toInt() and -2).coerceAtLeast(2)
        val left = active.left + (active.width() - width) / 2
        val top = active.top + (active.height() - height) / 2
        return Rect(left, top, left + width, top + height)
    }

    private fun scheduleOpenRetry(reason: String) {
        if (!running || retryRunnable != null) return
        runCatching { session?.close() }
        runCatching { camera?.close() }
        session = null
        camera = null
        openRetryAttempt += 1
        val delayMs = (CAMERA_RETRY_BASE_MS shl (openRetryAttempt - 1).coerceAtMost(4))
            .coerceAtMost(CAMERA_RETRY_MAX_MS)
        onRetry(openRetryAttempt, delayMs, reason)
        retryRunnable = Runnable {
            retryRunnable = null
            if (running) runCatching(::openCamera).onFailure {
                onError("CAMERA RETRY FAILED", it)
                scheduleOpenRetry("open threw")
            }
        }.also { cameraHandler?.postDelayed(it, delayMs) }
    }

    private fun selectUnrotatedOutput(value: CameraCharacteristics): Int? {
        val modes = value.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)?.toSet().orEmpty()
        return CaptureRequest.SCALER_ROTATE_AND_CROP_NONE.takeIf { it in modes }
    }

    @Suppress("DEPRECATION")
    private fun jpegRotationDegrees(jpeg: ByteArray, fallback: Int): Int = runCatching {
        when (
            ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_UNDEFINED,
            )
        ) {
            ExifInterface.ORIENTATION_NORMAL -> 0
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> null
        }
    }.getOrNull() ?: fallback

    private fun jpegPixelSize(jpeg: ByteArray): CameraPixelSize? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        return if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            CameraPixelSize(bounds.outWidth, bounds.outHeight)
        } else null
    }

    private fun selectFpsRange(value: CameraCharacteristics): Range<Int>? {
        val ranges = value.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        return ranges.filter { FPS in it.lower..it.upper }
            .minWithOrNull(compareBy<Range<Int>> { it.upper - it.lower }.thenBy { abs(it.upper - FPS) })
            ?: ranges.minByOrNull { abs(it.upper - FPS) + abs(it.lower - FPS) }
    }

    private fun sendEncodedOutput(buffer: ByteBuffer, info: MediaCodec.BufferInfo) {
        buffer.position(info.offset)
        buffer.limit(info.offset + info.size)
        val payload = ByteArray(info.size).also(buffer::get)
        val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
        val isKey = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (isConfig) {
            latestConfig = payload
            sendVideoConfig(payload)
            return
        }
        if (isKey) latestConfig?.let(::sendVideoConfig)
        val frameId = nextFrameId.getAndIncrement()
        val captureNanos = when {
            info.presentationTimeUs <= 0L -> SystemClock.elapsedRealtimeNanos()
            cameraTimestampsAreRealtime -> info.presentationTimeUs * 1_000L
            else -> captureElapsedByPtsUs.remove(info.presentationTimeUs) ?: SystemClock.elapsedRealtimeNanos()
        }
        packetSender(
            CameraLinkPacket(
                type = CameraLinkPacketType.VIDEO_FRAME,
                requestId = frameId,
                captureNanos = captureNanos,
                flags = if (isKey) CameraLinkPacketFlags.KEY_FRAME else 0,
                payload = payload,
            ),
        )
    }

    private fun sendVideoConfig(bytes: ByteArray) {
        packetSender(
            CameraLinkPacket(
                type = CameraLinkPacketType.VIDEO_CONFIG,
                meta = JSONObject()
                    .put("mimeType", MediaFormat.MIMETYPE_VIDEO_AVC)
                    .put("width", WIDTH)
                    .put("height", HEIGHT)
                    .put("fps", FPS)
                    .put("bitrate", BITRATE)
                    .put("rotationDegrees", frameRotationDegrees)
                    .toString(),
                payload = bytes,
            ),
        )
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
            (bytes[2] == 1.toByte() || (bytes[2] == 0.toByte() && bytes[3] == 1.toByte()))
        return if (hasStart) bytes else byteArrayOf(0, 0, 0, 1) + bytes
    }

    override fun close() {
        running = false
        retryRunnable?.let { cameraHandler?.removeCallbacks(it) }
        retryRunnable = null
        runCatching { session?.stopRepeating() }
        runCatching { session?.close() }
        runCatching { camera?.close() }
        runCatching { imageReader?.close() }
        runCatching { encoder?.stop() }
        runCatching { encoder?.release() }
        runCatching { encoderSurface?.release() }
        session = null
        camera = null
        imageReader = null
        encoder = null
        encoderSurface = null
        previewSurface = null
        latestConfig = null
        pendingFreezeIds.clear()
        captureElapsedByPtsUs.clear()
        cameraThread?.quitSafely()
        encoderThread?.quitSafely()
        cameraThread = null
        encoderThread = null
        cameraHandler = null
        encoderHandler = null
    }

    companion object {
        private const val TAG = "CameraH264Streamer"
        // Field-validated on RG: 960x1280 is absent from the stream map.
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val FPS = 20
        const val BITRATE = 5_000_000
        private const val IFRAME_SECONDS = 1
        private const val MAX_CAPTURE_CLOCK_SAMPLES = 256
        private const val CAMERA_RETRY_BASE_MS = 250L
        private const val CAMERA_RETRY_MAX_MS = 4_000L
    }
}
