package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.client.HubTarget
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraLinkProtocol
import com.anezium.rokidbus.shared.CameraOverlayContract
import com.anezium.rokidbus.shared.CameraOverlayItem
import com.anezium.rokidbus.shared.FrozenImageChunkContract
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Foreground-visible generic camera domain. Runs in the isolated :camera app process. */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var previewView: TextureView
    private lateinit var overlayView: CameraOverlayView
    private lateinit var emptyView: TextView
    private val inputRouter = CameraInputRouter()
    private val freezeSerial = AtomicLong(System.currentTimeMillis())
    private val frozenTransferExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "camera-frozen-transfer").apply { isDaemon = true }
    }

    private var busClient: BusClient? = null
    private var cameraLink: CameraLink? = null
    private var streamer: CameraH264Streamer? = null
    private var streamPlan: CameraStreamPlan? = null
    private var rotationFallbackAttempted = false
    private var previewSurface: Surface? = null
    private var sessionId: String? = null
    private var sessionStartedAtMs = 0L
    private var currentFreezeRequestId: Long? = null
    private var resumed = false
    private var ready = false
    private var permissionsRequested = false
    private var isFrozen = false
    private var liveZoomLevel = LiveZoomLevel.ONE
    private var frozenZoomLevel = LiveZoomLevel.ONE
    private var liveFrameGeometry: CameraLiveFrameGeometry? = null
    private var previewConsumerGeometry: CameraPreviewConsumerGeometry? = null
    private var liveSourceItems: List<CameraOverlayItem> = emptyList()
    private var liveItems: List<CameraOverlayItem> = emptyList()
    private var lastLiveSeq = Long.MIN_VALUE
    private var lastFreezeSeq = Long.MIN_VALUE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.decorView.setBackgroundColor(Color.BLACK)
        buildUi()
        startBusClient()
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemUi()
        refreshAvailability()
    }

    override fun onPause() {
        resumed = false
        deactivateCameraSession(sendClosed = true)
        super.onPause()
    }

    override fun onDestroy() {
        deactivateCameraSession(sendClosed = true)
        busClient?.close()
        busClient = null
        previewSurface?.release()
        previewSurface = null
        overlayView.setFrozenBackground(null)
        frozenTransferExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val decision = inputRouter.routeKey(keyCode, event.repeatCount, event.eventTime)
        if (decision.consumed) {
            handleInput(decision)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (inputRouter.handlesKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA_PERMISSIONS) return
        permissionsRequested = false
        if (hasRequiredPermissions()) activateCameraSession()
        else showEmpty("CAMERA PERMISSION REQUIRED\n\nCamera and nearby-device access are needed.")
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        // Preview and encoder must use the same stream size/crop. A separate 4:3 preview receives
        // a wider per-output Camera2 crop, so encoder-derived overlay boxes no longer align.
        val plan = streamPlan ?: selectCameraStreamPlan().also { streamPlan = it }
        if (plan == null) {
            showEmpty("CAMERA ORIENTATION UNSUPPORTED")
            return
        }
        surface.setDefaultBufferSize(plan.rasterSize.width, plan.rasterSize.height)
        previewSurface?.release()
        previewSurface = Surface(surface)
        if (resumed && ready && hasRequiredPermissions()) activateCameraSession()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        refreshMappedLiveOverlay()
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        stopStreamer()
        previewSurface?.release()
        previewSurface = null
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun buildUi() {
        previewView = TextureView(this).apply {
            surfaceTextureListener = this@CameraActivity
        }
        overlayView = CameraOverlayView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        emptyView = TextView(this).apply {
            setBackgroundColor(Color.BLACK)
            setTextColor(Color.rgb(113, 255, 151))
            textSize = 19f
            typeface = android.graphics.Typeface.MONOSPACE
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            text = NO_PLUGIN_MESSAGE
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(emptyView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener {
                    handleInput(inputRouter.routeTap(SystemClock.elapsedRealtime()))
                }
            },
        )
    }

    private fun startBusClient() {
        val client = BusClient(
            context = applicationContext,
            clientId = "glasses-camera-domain",
            pathPrefixes = listOf(BusPaths.CAMERA_OVERLAY, BusPaths.CAMERA_FREEZE_RESULT),
            hubTarget = HubTarget.GLASSES,
        ) { event -> handleBusEvent(event) }
        busClient = client
        client.connect()
    }

    private fun handleBusEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> refreshAvailability()
            is BusEvent.Message -> when (event.path) {
                BusPaths.CAMERA_OVERLAY -> handleOverlay(event.payload)
                BusPaths.CAMERA_FREEZE_RESULT -> handleFreezeResult(event.payload)
            }
            is BusEvent.Error -> {
                overlayView.updateStatus("BUS LINK ERROR", currentZoomLabel())
                refreshAvailability()
            }
            is BusEvent.Binary -> Unit
        }
    }

    private fun refreshAvailability() {
        val advertised = ((busClient?.capabilities() ?: 0) and BusCapabilityBits.CAMERA_CONSUMER_READY) != 0
        if (ready == advertised && (advertised || emptyView.visibility == View.VISIBLE)) {
            if (advertised && resumed) ensurePermissionsAndActivate()
            return
        }
        ready = advertised
        if (!advertised) {
            deactivateCameraSession(sendClosed = true)
            showEmpty(NO_PLUGIN_MESSAGE)
        } else {
            hideEmpty()
            overlayView.updateStatus("CAMERA PLUGIN READY", currentZoomLabel())
            if (resumed) ensurePermissionsAndActivate()
        }
    }

    private fun ensurePermissionsAndActivate() {
        if (hasRequiredPermissions()) {
            activateCameraSession()
        } else if (!permissionsRequested) {
            permissionsRequested = true
            requestPermissions(REQUIRED_PERMISSIONS, REQUEST_CAMERA_PERMISSIONS)
        }
    }

    private fun hasRequiredPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    private fun activateCameraSession() {
        if (!resumed || !ready || !hasRequiredPermissions()) return
        hideEmpty()
        if (sessionId == null) {
            sessionStartedAtMs = SystemClock.elapsedRealtime()
            sessionId = UUID.randomUUID().toString()
            busClient?.send(
                BusPaths.CAMERA_SESSION_STATE,
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("state", "opened")
                    .put(
                        "config",
                        JSONObject()
                            .put("width", streamPlan?.rasterSize?.width ?: CameraH264Streamer.PREFERRED_RASTER_SIZE.width)
                            .put("height", streamPlan?.rasterSize?.height ?: CameraH264Streamer.PREFERRED_RASTER_SIZE.height)
                            .put("fps", CameraH264Streamer.FPS)
                            .put("protocolVersion", CameraLinkProtocol.VERSION),
                    ),
            )
            requestGlassesWifi(true)
        }
        if (cameraLink == null) {
            cameraLink = CameraLink(
                context = applicationContext,
                sessionStartedAtMs = sessionStartedAtMs,
                onOfferReady = ::sendLinkOffer,
                onAuthenticated = { authenticated ->
                    if (authenticated) {
                        streamer?.requestKeyFrame()
                        overlayView.updateStatus("LIVE / PHONE LINKED", currentZoomLabel())
                    } else {
                        overlayView.updateStatus("WAITING FOR PHONE", currentZoomLabel())
                    }
                },
                onFrozenTransferFinished = {
                    streamer?.requestKeyFrame()
                },
                onState = { state -> overlayView.updateStatus(state, currentZoomLabel()) },
            ).also { it.start() }
        }
        startStreamerIfReady()
    }

    private fun deactivateCameraSession(sendClosed: Boolean) {
        stopStreamer()
        cameraLink?.close()
        cameraLink = null
        requestGlassesWifi(false)
        val closingSession = sessionId
        sessionId = null
        sessionStartedAtMs = 0L
        if (sendClosed && closingSession != null) {
            busClient?.send(
                BusPaths.CAMERA_SESSION_STATE,
                JSONObject().put("sessionId", closingSession).put("state", "closed"),
            )
        }
        currentFreezeRequestId = null
        isFrozen = false
        liveFrameGeometry = null
        previewConsumerGeometry = null
        streamPlan = null
        rotationFallbackAttempted = false
        liveSourceItems = emptyList()
        liveItems = emptyList()
        previewView.setTransform(Matrix())
        lastLiveSeq = Long.MIN_VALUE
        lastFreezeSeq = Long.MIN_VALUE
        overlayView.updateOverlay(emptyList())
        overlayView.setMode(CameraOverlayMode.LIVE)
        overlayView.setFrozenBackground(null)
    }

    private fun startStreamerIfReady() {
        if (streamer != null) return
        val surface = previewSurface ?: return
        val link = cameraLink ?: return
        val plan = streamPlan ?: selectCameraStreamPlan()?.also { streamPlan = it } ?: run {
            showEmpty("CAMERA ORIENTATION UNSUPPORTED")
            return
        }
        val displayRotationDegrees = CameraOrientation.displayRotationDegrees(
            previewView.display?.rotation ?: Surface.ROTATION_0,
        )
        previewConsumerGeometry = CameraPreviewGeometry.previewConsumerGeometry(plan)
        streamer = CameraH264Streamer(
            context = applicationContext,
            packetSender = link::enqueue,
            stageElapsedMs = ::cameraSessionElapsedMs,
            displayRotationDegrees = displayRotationDegrees,
            streamPlan = plan,
            onLiveFrameGeometry = { width, height, rotationDegrees ->
                runOnUiThread {
                    liveFrameGeometry = CameraLiveFrameGeometry(width, height, rotationDegrees)
                    refreshMappedLiveOverlay()
                }
            },
            onRunning = {
                runOnUiThread {
                    overlayView.updateStatus(
                        if (link.isAuthenticated) "LIVE / PHONE LINKED" else "WAITING FOR PHONE",
                        currentZoomLabel(),
                    )
                }
            },
            onRetry = { attempt, delayMs, reason ->
                runOnUiThread {
                    overlayView.updateStatus("CAMERA RETRY $attempt (${delayMs}ms)", currentZoomLabel())
                    log("camera retry attempt=$attempt delayMs=$delayMs reason=$reason")
                }
            },
            onRotationMismatch = { expectedDegrees, effectiveDegrees ->
                runOnUiThread { handleRotationMismatch(expectedDegrees, effectiveDegrees) }
            },
            onFrozenJpeg = { requestId, jpeg, width, height, rotationDegrees ->
                runOnUiThread { handleFrozenJpeg(requestId, jpeg, width, height, rotationDegrees) }
            },
            onError = { message, failure ->
                runOnUiThread {
                    logError(message, failure)
                    if (message.startsWith("FREEZE")) {
                        currentFreezeRequestId?.let { requestId ->
                            cameraLink?.cancelFrozenTransfer(requestId)
                        }
                        isFrozen = false
                        currentFreezeRequestId = null
                        overlayView.setMode(CameraOverlayMode.LIVE)
                        overlayView.setFrozenBackground(null)
                        overlayView.updateOverlay(liveItems)
                    }
                    overlayView.updateStatus(message, currentZoomLabel())
                }
            },
        ).also {
            it.start(surface)
            it.setZoom(liveZoomLevel.scale)
        }
    }

    private fun stopStreamer() {
        streamer?.close()
        streamer = null
    }

    private fun handleRotationMismatch(expectedDegrees: Int, effectiveDegrees: Int) {
        log("camera rotate-and-crop mismatch expected=$expectedDegrees effective=$effectiveDegrees")
        val current = streamPlan ?: return
        if (rotationFallbackAttempted || current.requestedHardwareRotationDegrees == 0) {
            stopStreamer()
            showEmpty("CAMERA ORIENTATION UNSUPPORTED")
            return
        }
        val fallback = selectCameraStreamPlan(forceSoftwareFallback = true)
        if (fallback == null) {
            stopStreamer()
            showEmpty("CAMERA ORIENTATION UNSUPPORTED")
            return
        }
        rotationFallbackAttempted = true
        streamPlan = fallback
        previewView.surfaceTexture?.setDefaultBufferSize(
            fallback.rasterSize.width,
            fallback.rasterSize.height,
        )
        stopStreamer()
        startStreamerIfReady()
    }

    private fun selectCameraStreamPlan(forceSoftwareFallback: Boolean = false): CameraStreamPlan? = runCatching {
        val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
        val cameraId = manager.cameraIdList.firstOrNull { it == "0" }
            ?: manager.cameraIdList.firstOrNull()
            ?: return@runCatching null
        val characteristics = manager.getCameraCharacteristics(cameraId)
        val sensorOrientation = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION,
        ) ?: 0
        val frontFacing = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.LENS_FACING,
        ) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_FRONT
        val requiredRotation = CameraOrientation.sensorToDisplayRotationDegrees(
            sensorOrientation = sensorOrientation,
            displayRotationDegrees = CameraOrientation.displayRotationDegrees(
                previewView.display?.rotation ?: Surface.ROTATION_0,
            ),
            frontFacing = frontFacing,
        )
        val modes = buildSet {
            (characteristics.get(
                android.hardware.camera2.CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES,
            ) ?: intArrayOf()).forEach { mode ->
                when (mode) {
                    android.hardware.camera2.CaptureRequest.SCALER_ROTATE_AND_CROP_NONE -> add(0)
                    android.hardware.camera2.CaptureRequest.SCALER_ROTATE_AND_CROP_90 -> add(90)
                    android.hardware.camera2.CaptureRequest.SCALER_ROTATE_AND_CROP_180 -> add(180)
                    android.hardware.camera2.CaptureRequest.SCALER_ROTATE_AND_CROP_270 -> add(270)
                }
            }
        }
        val outputs = characteristics.get(
            android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )?.getOutputSizes(SurfaceTexture::class.java).orEmpty().mapTo(mutableSetOf()) {
            CameraPixelSize(it.width, it.height)
        }
        CameraOrientation.selectStreamPlan(
            sensorToDisplayRotationDegrees = requiredRotation,
            availableHardwareRotationDegrees = if (forceSoftwareFallback) setOf(0) else modes,
            availableOutputSizes = outputs,
        )
    }.onFailure { logError("camera stream plan failed", it) }.getOrNull()

    private fun sendLinkOffer(offer: CameraLinkOffer, offerNumber: Int) {
        val activeSession = sessionId ?: return
        if (!ready || !resumed) return
        busClient?.send(
            BusPaths.CAMERA_LINK_OFFER,
            JSONObject()
                .put("sessionId", activeSession)
                .put("ssid", offer.ssid)
                .put("passphrase", offer.passphrase)
                .put("port", offer.port)
                .put("token", offer.token)
                .put("goIp", offer.goIp),
        )
        val elapsedMs = (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L)
        log("cameraLinkStage stage=offer_sent#$offerNumber elapsedMs=$elapsedMs")
    }

    private fun requestGlassesWifi(enabled: Boolean) {
        busClient?.send(BusPaths.GLASSES_WIFI_REQUEST, JSONObject().put("enabled", enabled))
    }

    private fun handleInput(decision: CameraInputDecision) {
        when (decision.action) {
            CameraInputAction.TOGGLE_FREEZE -> toggleFreeze()
            CameraInputAction.ZOOM_IN -> stepZoom(true)
            CameraInputAction.ZOOM_OUT -> stepZoom(false)
            null -> Unit
        }
    }

    private fun toggleFreeze() {
        if (!ready || sessionId == null) return
        if (isFrozen) {
            currentFreezeRequestId?.let { requestId ->
                cameraLink?.cancelFrozenTransfer(requestId)
            }
            isFrozen = false
            currentFreezeRequestId = null
            frozenZoomLevel = LiveZoomLevel.ONE
            overlayView.setFrozenBackground(null)
            overlayView.setFrozenDisplayScale(1f)
            overlayView.setMode(CameraOverlayMode.LIVE)
            overlayView.updateOverlay(liveItems)
            overlayView.updateStatus("LIVE", liveZoomLevel.hudLabel)
            return
        }
        val activeStreamer = streamer ?: return
        cameraLink?.resendOfferIfDisconnected()
        val requestId = freezeSerial.incrementAndGet()
        currentFreezeRequestId = requestId
        lastFreezeSeq = Long.MIN_VALUE
        isFrozen = true
        cameraLink?.beginFrozenTransfer(requestId)
        overlayView.setMode(CameraOverlayMode.FROZEN)
        overlayView.updateOverlay(emptyList())
        overlayView.updateStatus("CAPTURING HD FRAME", liveZoomLevel.hudLabel)
        activeStreamer.captureFrozenJpeg(requestId)
    }

    private fun handleFrozenJpeg(
        requestId: Long,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ) {
        if (!isFrozen || currentFreezeRequestId != requestId || sessionId == null) return
        val packet = CameraLinkPacket(
            type = CameraLinkPacketType.FROZEN_IMAGE,
            requestId = requestId,
            meta = JSONObject()
                .put("sessionId", sessionId)
                .put("requestId", requestId)
                .put("mimeType", "image/jpeg")
                .put("width", width)
                .put("height", height)
                .put("rotationDegrees", rotationDegrees)
                .toString(),
            payload = jpeg,
        )
        val sent = jpeg.size <= CameraLinkProtocol.MAX_PAYLOAD_BYTES &&
            cameraLink?.enqueue(packet) == true
        if (sent) {
            log(
                "cameraLinkStage stage=frozen_enqueued requestId=$requestId bytes=${jpeg.size} " +
                    "elapsedMs=${cameraSessionElapsedMs()}",
            )
        } else {
            cameraLink?.cancelFrozenTransfer(requestId)
            frozenTransferExecutor.execute {
                val sppSent = sendFrozenOverSpp(
                    requestId = requestId,
                    jpeg = jpeg,
                    width = width,
                    height = height,
                    rotationDegrees = rotationDegrees,
                )
                runOnUiThread {
                    if (!isFrozen || currentFreezeRequestId != requestId) return@runOnUiThread
                    overlayView.updateStatus(
                        if (sppSent) "FROZEN / PROCESSING VIA BLUETOOTH" else "FROZEN / LINK LOST",
                        liveZoomLevel.hudLabel,
                    )
                }
            }
        }

        // Local preview decode runs after dispatch so it overlaps the socket/SPP transfer.
        val bitmap = decodeFrozenPreview(jpeg, rotationDegrees)
        if (bitmap != null) {
            val live = liveFrameGeometry?.orientedSize
            val viewport = live?.let {
                CameraPreviewGeometry.matchingFrozenSourceViewport(
                    frozenWidth = bitmap.width,
                    frozenHeight = bitmap.height,
                    liveWidth = it.width,
                    liveHeight = it.height,
                    viewWidth = previewView.width,
                    viewHeight = previewView.height,
                )
            }
            overlayView.setFrozenBackground(bitmap, viewport)
        }
        overlayView.updateStatus(
            if (sent) "FROZEN / PROCESSING" else "FROZEN / SENDING VIA BLUETOOTH",
            liveZoomLevel.hudLabel,
        )
    }

    private fun sendFrozenOverSpp(
        requestId: Long,
        jpeg: ByteArray,
        width: Int,
        height: Int,
        rotationDegrees: Int,
    ): Boolean {
        val activeSession = sessionId ?: return false
        val capabilities = busClient?.capabilities() ?: 0
        if (capabilities and BusCapabilityBits.CAMERA_FROZEN_SPP == 0 ||
            jpeg.size !in 1..FrozenImageChunkContract.MAX_IMAGE_BYTES
        ) return false
        val transferId = UUID.randomUUID().toString()
        val chunkCount = FrozenImageChunkContract.chunkCount(jpeg.size)
        val sha256 = FrozenImageChunkContract.sha256(jpeg)
        for (chunkIndex in 0 until chunkCount) {
            val start = chunkIndex * FrozenImageChunkContract.CHUNK_BYTES
            val end = minOf(jpeg.size, start + FrozenImageChunkContract.CHUNK_BYTES)
            val metadata = FrozenImageChunkContract.metadataJson(
                FrozenImageChunkContract.Metadata(
                    sessionId = activeSession,
                    requestId = requestId,
                    transferId = transferId,
                    chunkIndex = chunkIndex,
                    chunkCount = chunkCount,
                    totalBytes = jpeg.size,
                    width = width,
                    height = height,
                    rotationDegrees = rotationDegrees,
                    sha256 = sha256,
                ),
            )
            val sent = busClient?.trySendBinary(
                BusPaths.CAMERA_FREEZE_IMAGE_CHUNK,
                "$transferId:$chunkIndex",
                metadata,
                jpeg.copyOfRange(start, end),
            ) == true
            if (!sent) return false
        }
        return true
    }

    private fun handleOverlay(payload: JSONObject) {
        val parsed = CameraOverlayContract.parse(payload, requireRequestId = false) ?: return
        if (parsed.sessionId != sessionId) return
        val seq = parsed.seq
        if (seq != null && seq <= lastLiveSeq) return
        if (seq != null) lastLiveSeq = seq
        liveSourceItems = parsed.items
        liveItems = mapLiveOverlay(parsed.items)
        if (!isFrozen) {
            overlayView.updateOverlay(liveItems)
            overlayView.updateStatus("LIVE", liveZoomLevel.hudLabel)
        }
    }

    private fun refreshMappedLiveOverlay() {
        applyLivePreviewTransform()
        liveItems = mapLiveOverlay(liveSourceItems)
        if (!isFrozen) overlayView.updateOverlay(liveItems)
    }

    private fun applyLivePreviewTransform() {
        val geometry = previewConsumerGeometry ?: return
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        val destination = CameraPreviewGeometry.textureDestinationCorners(
            geometry,
            viewWidth,
            viewHeight,
        ) ?: return
        // TextureView consumes the camera target's local raster orientation. This intentionally
        // differs from the remaining rotation advertised to the encoded-frame consumer.
        previewView.setTransform(
            Matrix().apply {
                setPolyToPoly(
                    floatArrayOf(
                        0f, 0f,
                        viewWidth.toFloat(), 0f,
                        viewWidth.toFloat(), viewHeight.toFloat(),
                        0f, viewHeight.toFloat(),
                    ),
                    0,
                    destination,
                    0,
                    4,
                )
            },
        )
    }

    private fun mapLiveOverlay(source: List<CameraOverlayItem>): List<CameraOverlayItem> {
        val frame = liveFrameGeometry?.orientedSize ?: return source
        val viewWidth = previewView.width
        val viewHeight = previewView.height
        if (viewWidth <= 0 || viewHeight <= 0) return source
        return source.mapNotNull { item ->
            CameraPreviewGeometry.mapFillCenter(
                item = item,
                sourceWidth = frame.width,
                sourceHeight = frame.height,
                viewWidth = viewWidth,
                viewHeight = viewHeight,
            )
        }
    }

    private fun handleFreezeResult(payload: JSONObject) {
        val parsed = CameraOverlayContract.parse(payload, requireRequestId = true) ?: return
        if (parsed.sessionId != sessionId || parsed.requestId != currentFreezeRequestId || !isFrozen) return
        val seq = parsed.seq
        if (seq != null && seq <= lastFreezeSeq) return
        if (seq != null) lastFreezeSeq = seq
        overlayView.updateOverlay(parsed.items)
        overlayView.updateStatus("FROZEN / RESULT", frozenZoomLevel.hudLabel)
    }

    private fun stepZoom(zoomIn: Boolean) {
        if (!ready) return
        if (isFrozen) {
            frozenZoomLevel = if (zoomIn) LiveZoomPolicy.zoomIn(frozenZoomLevel)
            else LiveZoomPolicy.zoomOut(frozenZoomLevel)
            overlayView.setFrozenDisplayScale(frozenZoomLevel.scale)
            overlayView.updateStatus("FROZEN", frozenZoomLevel.hudLabel)
        } else {
            liveZoomLevel = if (zoomIn) LiveZoomPolicy.zoomIn(liveZoomLevel)
            else LiveZoomPolicy.zoomOut(liveZoomLevel)
            streamer?.setZoom(liveZoomLevel.scale)
            overlayView.updateStatus("LIVE", liveZoomLevel.hudLabel)
        }
    }

    private fun currentZoomLabel(): String =
        if (isFrozen) frozenZoomLevel.hudLabel else liveZoomLevel.hudLabel

    private fun cameraSessionElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L)

    private fun decodeFrozenPreview(jpeg: ByteArray, rotationDegrees: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_FROZEN_PREVIEW_EDGE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        ) ?: error("frozen preview decode returned null")
        if (rotationDegrees == 0) {
            decoded
        } else {
            val rotated = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) },
                true,
            )
            if (rotated !== decoded) decoded.recycle()
            rotated
        }
    }.onFailure { logError("frozen preview decode failed", it) }.getOrNull()

    private fun showEmpty(message: String) {
        emptyView.text = message
        emptyView.visibility = View.VISIBLE
        previewView.visibility = View.GONE
        overlayView.visibility = View.GONE
    }

    private fun hideEmpty() {
        emptyView.visibility = View.GONE
        previewView.visibility = View.VISIBLE
        overlayView.visibility = View.VISIBLE
    }

    private fun hideSystemUi() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.insetsController?.apply {
                hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    companion object {
        private const val REQUEST_CAMERA_PERMISSIONS = 51
        private const val MAX_FROZEN_PREVIEW_EDGE = 2_048
        private const val NO_PLUGIN_MESSAGE =
            "NO CAMERA PLUGIN\n\nInstall and approve a camera-capable plugin on your phone."
        private val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
