package com.anezium.rokidbus.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
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
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Foreground-visible generic camera domain. Runs in the isolated :camera app process. */
class CameraActivity : Activity(), TextureView.SurfaceTextureListener {
    private lateinit var previewView: TextureView
    private lateinit var overlayView: CameraOverlayView
    private lateinit var emptyView: TextView
    private val inputRouter = CameraInputRouter()
    private val freezeSerial = AtomicLong(System.currentTimeMillis())

    private var busClient: BusClient? = null
    private var cameraLink: CameraLink? = null
    private var streamer: CameraH264Streamer? = null
    private var previewSurface: Surface? = null
    private var sessionId: String? = null
    private var currentFreezeRequestId: Long? = null
    private var resumed = false
    private var ready = false
    private var permissionsRequested = false
    private var isFrozen = false
    private var liveZoomLevel = LiveZoomLevel.ONE
    private var frozenZoomLevel = LiveZoomLevel.ONE
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
        surface.setDefaultBufferSize(CameraH264Streamer.WIDTH, CameraH264Streamer.HEIGHT)
        previewSurface?.release()
        previewSurface = Surface(surface)
        if (resumed && ready && hasRequiredPermissions()) activateCameraSession()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

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
            sessionId = UUID.randomUUID().toString()
            busClient?.send(
                BusPaths.CAMERA_SESSION_STATE,
                JSONObject()
                    .put("sessionId", sessionId)
                    .put("state", "opened")
                    .put(
                        "config",
                        JSONObject()
                            .put("width", CameraH264Streamer.WIDTH)
                            .put("height", CameraH264Streamer.HEIGHT)
                            .put("fps", CameraH264Streamer.FPS)
                            .put("protocolVersion", CameraLinkProtocol.VERSION),
                    ),
            )
            requestGlassesWifi(true)
        }
        if (cameraLink == null) {
            cameraLink = CameraLink(
                context = applicationContext,
                onOfferReady = ::sendLinkOffer,
                onAuthenticated = { authenticated ->
                    if (authenticated) {
                        streamer?.requestKeyFrame()
                        overlayView.updateStatus("LIVE / PHONE LINKED", currentZoomLabel())
                    } else {
                        overlayView.updateStatus("WAITING FOR PHONE", currentZoomLabel())
                    }
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
        if (sendClosed && closingSession != null) {
            busClient?.send(
                BusPaths.CAMERA_SESSION_STATE,
                JSONObject().put("sessionId", closingSession).put("state", "closed"),
            )
        }
        currentFreezeRequestId = null
        isFrozen = false
        liveItems = emptyList()
        lastLiveSeq = Long.MIN_VALUE
        lastFreezeSeq = Long.MIN_VALUE
        overlayView.updateOverlay(emptyList())
        overlayView.setFrozenBackground(null)
    }

    private fun startStreamerIfReady() {
        if (streamer != null) return
        val surface = previewSurface ?: return
        val link = cameraLink ?: return
        streamer = CameraH264Streamer(
            context = applicationContext,
            packetSender = link::enqueue,
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
            onFrozenJpeg = { requestId, jpeg, width, height ->
                runOnUiThread { handleFrozenJpeg(requestId, jpeg, width, height) }
            },
            onError = { message, failure ->
                runOnUiThread {
                    logError(message, failure)
                    if (message.startsWith("FREEZE")) {
                        isFrozen = false
                        currentFreezeRequestId = null
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

    private fun sendLinkOffer(offer: CameraLinkOffer) {
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
            isFrozen = false
            currentFreezeRequestId = null
            frozenZoomLevel = LiveZoomLevel.ONE
            overlayView.setFrozenBackground(null)
            overlayView.setFrozenDisplayScale(1f)
            overlayView.updateOverlay(liveItems)
            overlayView.updateStatus("LIVE", liveZoomLevel.hudLabel)
            return
        }
        val link = cameraLink
        if (link?.isAuthenticated != true) {
            link?.resendOfferIfDisconnected()
            overlayView.updateStatus("WAITING FOR PHONE LINK", liveZoomLevel.hudLabel)
            return
        }
        val requestId = freezeSerial.incrementAndGet()
        currentFreezeRequestId = requestId
        lastFreezeSeq = Long.MIN_VALUE
        isFrozen = true
        overlayView.updateOverlay(emptyList())
        overlayView.updateStatus("CAPTURING HD FRAME", liveZoomLevel.hudLabel)
        streamer?.captureFrozenJpeg(requestId)
    }

    private fun handleFrozenJpeg(requestId: Long, jpeg: ByteArray, width: Int, height: Int) {
        if (!isFrozen || currentFreezeRequestId != requestId || sessionId == null) return
        val bitmap = decodeFrozenPreview(jpeg)
        if (bitmap != null) overlayView.setFrozenBackground(bitmap)
        val sent = jpeg.size <= CameraLinkProtocol.MAX_PAYLOAD_BYTES && cameraLink?.enqueue(
            CameraLinkPacket(
                type = CameraLinkPacketType.FROZEN_IMAGE,
                requestId = requestId,
                meta = JSONObject()
                    .put("sessionId", sessionId)
                    .put("requestId", requestId)
                    .put("mimeType", "image/jpeg")
                    .put("width", width)
                    .put("height", height)
                    .put("rotationDegrees", 0)
                    .toString(),
                payload = jpeg,
            ),
        ) == true
        overlayView.updateStatus(if (sent) "FROZEN / PROCESSING" else "FROZEN / LINK LOST", liveZoomLevel.hudLabel)
    }

    private fun handleOverlay(payload: JSONObject) {
        val parsed = CameraOverlayContract.parse(payload, requireRequestId = false) ?: return
        if (parsed.sessionId != sessionId) return
        val seq = parsed.seq
        if (seq != null && seq <= lastLiveSeq) return
        if (seq != null) lastLiveSeq = seq
        liveItems = parsed.items
        if (!isFrozen) {
            overlayView.updateOverlay(parsed.items)
            overlayView.updateStatus("LIVE", liveZoomLevel.hudLabel)
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

    private fun decodeFrozenPreview(jpeg: ByteArray): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / sample > MAX_FROZEN_PREVIEW_EDGE) sample *= 2
        BitmapFactory.decodeByteArray(
            jpeg,
            0,
            jpeg.size,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            },
        )
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
