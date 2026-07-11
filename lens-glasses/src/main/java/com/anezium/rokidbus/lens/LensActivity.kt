package com.anezium.rokidbus.lens

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.camera.view.TransformExperimental
import androidx.camera.view.transform.CoordinateTransform
import androidx.camera.view.transform.ImageProxyTransformFactory
import androidx.core.content.ContextCompat
import com.anezium.rokidbus.client.BusClient
import com.anezium.rokidbus.client.BusEvent
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LensWireContract
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.roundToInt

private const val TAG = "LENS"

@TransformExperimental
@ExperimentalCamera2Interop
@ExperimentalGetImage
class LensActivity : AppCompatActivity() {
    private data class CacheKey(
        val normalized: String,
        val mode: OcrScript,
        val targetLang: String,
    )

    private data class CachedTranslation(
        val dst: String,
        val srcLang: String,
    )

    private data class OcrSourceLine(
        val source: String,
        val normalized: String,
        val bounds: Rect,
    )

    private data class OcrSourceBlock(
        val source: String,
        val normalized: String,
        val bounds: Rect,
        val lines: List<OcrSourceLine>,
        val gapBelow: Float = 0f,
        val columnIndex: Int = -1,
    )

    private data class OcrMetrics(
        val avgLatencyMs: Long,
        val hz: Double,
    )

    private data class LastOcrResult(
        val mode: OcrScript,
        val transformMatrix: Matrix?,
        val blocks: List<OcrSourceBlock>,
        val metrics: OcrMetrics,
        val completedAtMs: Long,
        val carriedTranslations: Map<String, CachedTranslation> = emptyMap(),
    )

    private data class ViewOcrBlock(
        val normalized: String,
        val displayRect: Rect,
    )

    private data class MappedLiveFrame(
        val lineCount: Int,
        val paragraphs: List<LiveFrameParagraph>,
    )

    private data class LiveOverlayFrame(
        val blocks: List<LensOverlayBlock>,
        val pendingTranslationParagraphs: Int,
    )

    private class PendingRequest(
        val keyBySource: Map<String, CacheKey>,
        val sentAtMs: Long,
        val frozen: Boolean,
        var downloadingModel: Boolean = false,
        var timeout: Runnable?,
    )

    private data class TranslationRetry(
        val failureCount: Int,
        val retryNotBeforeMs: Long,
    )

    private data class FastFreezeRequest(
        val requestId: Long,
        val mode: OcrScript,
        val liveZoomLevel: LiveZoomLevel,
        val restoreCaptureWithAnalysis: Boolean,
    )

    private inner class HdCaptureSession(
        val requestId: Long,
        val mode: OcrScript,
        val bindStartedAtMs: Long,
    ) {
        lateinit var capture: ImageCapture
        var boundAtMs: Long = -1L
            private set
        @Volatile var firstFrameAtMs: Long = -1L
            private set
        @Volatile var captureIssuedAtMs: Long = -1L
            private set
        @Volatile var capturedAtMs: Long = -1L
        @Volatile var decodedAtMs: Long = -1L
        var frameCount: Int = 0
            private set
        var lastAeState: Int? = null
            private set
        var issueReason: String = "pending"
            private set
        var issued: Boolean = false
            private set
        var cancelled: Boolean = false
            private set
        private var timingLogged = false
        private var deadline: Runnable? = null

        val captureCallback = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: TotalCaptureResult,
            ) {
                val frameAtMs = SystemClock.elapsedRealtime()
                val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                mainHandler.post { onPreviewFrame(frameAtMs, aeState) }
            }
        }

        fun onBound(boundAtMs: Long) {
            this.boundAtMs = boundAtMs
            val timeout = Runnable {
                deadline = null
                issueCapture("deadline_${aeStateLabel(lastAeState)}")
            }
            deadline = timeout
            mainHandler.postDelayed(timeout, HD_AE_SETTLE_MAX_MS)
        }

        private fun onPreviewFrame(frameAtMs: Long, aeState: Int?) {
            if (cancelled || issued || activeHdCaptureSession !== this || !isCurrentFreeze(requestId)) return
            frameCount += 1
            lastAeState = aeState
            if (firstFrameAtMs < 0L) {
                firstFrameAtMs = frameAtMs
                Log.i(
                    TAG,
                    "freezeHdTiming id=$requestId phase=first_frame " +
                        "bindMs=${phaseMs(bindStartedAtMs, boundAtMs)} " +
                        "bindToFirstFrameMs=${phaseMs(boundAtMs, firstFrameAtMs)} " +
                        "ae=${aeStateLabel(aeState)}",
                )
            }
            if (frameCount < HD_AE_SETTLE_MIN_FRAMES) return
            when {
                aeState.isReadyAeState() -> issueCapture("ae_ready")
                aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_INACTIVE -> {
                    issueCapture("three_frames_no_ae")
                }
            }
        }

        fun issueCapture(reason: String) {
            if (issued || cancelled) return
            if (activeHdCaptureSession !== this || !isCurrentFreeze(requestId)) {
                cancel("stale_before_issue")
                return
            }
            deadline?.let(mainHandler::removeCallbacks)
            deadline = null
            issued = true
            issueReason = reason
            captureIssuedAtMs = SystemClock.elapsedRealtime()
            Log.i(
                TAG,
                "freezeHdTiming id=$requestId phase=capture_issued " +
                    "bindToIssueMs=${phaseMs(boundAtMs, captureIssuedAtMs)} " +
                    "firstFrameToIssueMs=${phaseMs(firstFrameAtMs, captureIssuedAtMs)} " +
                    "frames=$frameCount ae=${aeStateLabel(lastAeState)} reason=$reason",
            )
            takeFreezePicture(this)
        }

        fun completeTiming() {
            logTiming("decoded")
            if (activeHdCaptureSession === this) activeHdCaptureSession = null
        }

        fun cancel(reason: String) {
            if (cancelled) return
            cancelled = true
            deadline?.let(mainHandler::removeCallbacks)
            deadline = null
            logTiming(reason)
            if (activeHdCaptureSession === this) activeHdCaptureSession = null
        }

        private fun logTiming(result: String) {
            if (timingLogged) return
            timingLogged = true
            Log.i(
                TAG,
                "freezeHdTiming id=$requestId result=$result " +
                    "bindMs=${phaseMs(bindStartedAtMs, boundAtMs)} " +
                    "bindToFirstFrameMs=${phaseMs(boundAtMs, firstFrameAtMs)} " +
                    "firstFrameToIssueMs=${phaseMs(firstFrameAtMs, captureIssuedAtMs)} " +
                    "issueToCapturedMs=${phaseMs(captureIssuedAtMs, capturedAtMs)} " +
                    "capturedToDecodedMs=${phaseMs(capturedAtMs, decodedAtMs)} " +
                    "bindToDecodedMs=${phaseMs(bindStartedAtMs, decodedAtMs)} " +
                    "frames=$frameCount ae=${aeStateLabel(lastAeState)} reason=$issueReason",
            )
        }
    }

    private data class DecodedFrozenCapture(
        val bitmap: Bitmap,
        val jpegBytes: ByteArray,
        val appliedRotation: Int,
    )

    private data class FrozenCaptureJpeg(
        val bytes: ByteArray,
        val reportedRotation: Int,
    )

    private data class FrozenContent(
        val bitmap: Bitmap,
        val ocrResult: LastOcrResult,
        val sourceStatus: String,
    )

    private data class FrozenRecognition(
        val text: Text,
        val script: OcrScript,
        val latencyMs: Long,
    )

    private lateinit var previewView: PreviewView
    private lateinit var overlayView: LensOverlayView
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private var activeCameraSelector: CameraSelector? = null
    private var activeCameraLabel: String? = null
    private var imageCaptureBoundWithAnalysis = false
    private var temporaryCaptureBindingActive = false
    private var restoreCaptureWithAnalysisAfterTemporary = false
    private var cameraUnboundForFrozen = false
    private var restoreCaptureWithAnalysisAfterFrozen = false
    private var freezeCaptureInFlight = false
    private var rebindCameraAfterFreezeCaptureSettles = false
    private var activeHdCaptureSession: HdCaptureSession? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var latinRecognizer: TextRecognizer
    private lateinit var japaneseRecognizer: TextRecognizer
    private var busClient: BusClient? = null
    private val inputRouter = LensInputRouter()

    @Volatile private var effectiveScript = OcrScript.LATIN
    @Volatile private var liveZoomLevel = LiveZoomLevel.ONE
    /** Wire name of the engine that served the last translation reply (HUD chip). */
    private var lastServingEngine: String? = null
    private val autoScriptLock = Any()
    private var autoScriptState = AutoScriptState()
    @Volatile private var isFrozen = false
    @Volatile private var isActivityResumed = false
    private val analyzerBusy = AtomicBoolean(false)
    private val analyzerGeneration = AtomicLong(0L)
    private val analyzerCadenceLock = Any()
    private var analyzerCadenceState = OcrCadenceState()
    private val freezeRequestSerial = AtomicLong(0L)
    private val pendingFastFreeze = AtomicReference<FastFreezeRequest?>(null)
    private val zoomRequestSerial = AtomicLong(0L)
    private val totalFramesAnalyzed = AtomicLong(0L)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val statsLock = Any()
    private val latencyWindow = ArrayDeque<Long>()
    private val completionWindow = ArrayDeque<Long>()
    private val sessionStartMs = SystemClock.elapsedRealtime()
    private val translationCache = object : LinkedHashMap<CacheKey, CachedTranslation>(
        CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, CachedTranslation>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }
    private val inFlightByKey = linkedMapOf<CacheKey, String>()
    private val pendingRequests = linkedMapOf<String, PendingRequest>()
    private val frozenAttemptedTranslationKeys = linkedSetOf<CacheKey>()
    private val translationRetryByKey = object : LinkedHashMap<CacheKey, TranslationRetry>(
        CACHE_MAX_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<CacheKey, TranslationRetry>?): Boolean =
            size > CACHE_MAX_ENTRIES
    }
    private var translationRetryNotBeforeMs = 0L
    private var waitingForPreviewTransform = false
    private var busState = 0
    private var translationStatus = "STARTING"
    private var frozenSourceStatus: String? = null
    private var lastOcrResult: LastOcrResult? = null
    private var frozenOcrResult: LastOcrResult? = null
    private var liveParagraphReconciliationState = LiveParagraphReconciliationState()
    private var visibleLiveParagraphAnchors: List<LiveParagraphAnchor> = emptyList()
    private var lastLiveMode: OcrScript? = null
    private var cameraProviderRequestInFlight = false
    private var cameraBindRetryCount = 0
    private var cameraRetry: Runnable? = null
    private var frozenBatchContinuation: Runnable? = null
    private var fastFreezeTimeout: Runnable? = null
    private var frozenZoomNoticeTimeout: Runnable? = null
    private var frozenZoomNotice: String? = null
    private var frozenJpegBytes: ByteArray? = null
    private var frozenJpegRotationDegrees = 0
    private var hdFrozenOcrInFlight = false
    private var frozenZoomLevel = FrozenZoomLevel.ONE
    private var frozenBaseLiveZoomLevel = LiveZoomLevel.ONE
    private var frozenScriptState = FrozenScriptState(OcrScript.LATIN)
    private var frozenSelectedScript = OcrScript.LATIN
    // Zoom requested before the HD base OCR is installed; applied as soon as the refine succeeds.
    private var queuedFrozenZoomLevel: FrozenZoomLevel? = null
    private var baseFrozenContent: FrozenContent? = null
    private var zoomFrozenContent: FrozenContent? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        window.decorView.setBackgroundColor(Color.BLACK)

        previewView = PreviewView(this).apply {
            setBackgroundColor(Color.BLACK)
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        overlayView = LensOverlayView(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }
        setContentView(
            FrameLayout(this).apply {
                setBackgroundColor(Color.BLACK)
                addView(previewView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                addView(overlayView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
                setOnClickListener {
                    handleInputDecision(inputRouter.routeTap(SystemClock.elapsedRealtime()))
                }
            },
        )
        applyPreviewScaleForCurrentState()

        cameraExecutor = Executors.newSingleThreadExecutor()
        latinRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        japaneseRecognizer = TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
        startBusClient()
        hideSystemUi()
        ensureCameraPermission()
        Log.i(TAG, "sessionStart state=created")
    }

    override fun onResume() {
        super.onResume()
        isActivityResumed = true
        applyPreviewScaleForCurrentState()
        hideSystemUi()
        applyScreenTimeoutOverride()
        if (hasCameraPermission() && !isFrozen) {
            startCamera()
        } else if (isFrozen) {
            scheduleFrozenBatchContinuation()
        }
        Log.i(TAG, "lifecycle state=resumed frozen=$isFrozen")
    }

    private fun applyScreenTimeoutOverride() {
        if (!Settings.System.canWrite(this)) {
            Log.w(
                TAG,
                "screenTimeoutOverride skipped: WRITE_SETTINGS not granted " +
                    "(grant: adb shell appops set $packageName WRITE_SETTINGS allow)",
            )
            return
        }
        val current = Settings.System.getInt(
            contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            -1,
        )
        if (current == -1) return
        if (current != SCREEN_OFF_TIMEOUT_OVERRIDE_MS) {
            // Only a value that is not our own override can be the stock timeout; a
            // previous kill without onPause leaves the override in place and must not
            // be persisted as stock.
            getSharedPreferences(SCREEN_PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(SCREEN_PREF_STOCK_TIMEOUT_MS, current)
                .apply()
        }
        Settings.System.putInt(
            contentResolver,
            Settings.System.SCREEN_OFF_TIMEOUT,
            SCREEN_OFF_TIMEOUT_OVERRIDE_MS,
        )
        Log.i(TAG, "screenTimeoutOverride applied current=$current override=$SCREEN_OFF_TIMEOUT_OVERRIDE_MS")
    }

    private fun restoreScreenTimeout() {
        if (!Settings.System.canWrite(this)) return
        val stock = getSharedPreferences(SCREEN_PREFS_NAME, MODE_PRIVATE)
            .getInt(SCREEN_PREF_STOCK_TIMEOUT_MS, -1)
        if (stock <= 0) return
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, stock)
        Log.i(TAG, "screenTimeoutOverride restored stock=$stock")
    }

    override fun onPause() {
        isActivityResumed = false
        restoreScreenTimeout()
        cameraRetry?.let(mainHandler::removeCallbacks)
        cameraRetry = null
        cameraBindRetryCount = 0
        cancelFrozenBatchContinuation()
        activeHdCaptureSession?.cancel("lifecycle_pause")
        hdFrozenOcrInFlight = false
        val incompleteFreeze = isFrozen && frozenOcrResult == null
        freezeRequestSerial.incrementAndGet()
        pendingFastFreeze.set(null)
        zoomRequestSerial.incrementAndGet()
        queuedFrozenZoomLevel = null
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        if (incompleteFreeze) {
            isFrozen = false
            frozenSourceStatus = null
            frozenJpegBytes = null
            frozenJpegRotationDegrees = 0
            hdFrozenOcrInFlight = false
            frozenZoomLevel = FrozenZoomLevel.ONE
            overlayView.setFrozenBackground(null)
            recycleFrozenContent(baseFrozenContent)
            recycleFrozenContent(zoomFrozenContent)
            baseFrozenContent = null
            zoomFrozenContent = null
            translationStatus = "PAUSED"
            overlayView.updateOcrResult(
                null,
                emptyList(),
                hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)),
            )
        }
        if (isFrozen) {
            unbindCameraForFrozen()
        } else {
            temporaryCaptureBindingActive = false
            cameraProvider?.unbindAll()
            imageCapture = null
            imageCaptureBoundWithAnalysis = false
            restoreCaptureWithAnalysisAfterTemporary = false
            cameraUnboundForFrozen = false
            restoreCaptureWithAnalysisAfterFrozen = false
        }
        freezeCaptureInFlight = false
        rebindCameraAfterFreezeCaptureSettles = false
        resetAnalyzerPipeline()
        clearLiveTracking()
        Log.i(TAG, "lifecycle state=paused frozen=$isFrozen incompleteFreezeCancelled=$incompleteFreeze")
        super.onPause()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN,
            -> trimRetainedMemory(level)
        }
    }

    override fun onDestroy() {
        isActivityResumed = false
        cameraRetry?.let(mainHandler::removeCallbacks)
        cameraRetry = null
        cancelFrozenBatchContinuation()
        activeHdCaptureSession?.cancel("lifecycle_destroy")
        freezeRequestSerial.incrementAndGet()
        pendingFastFreeze.set(null)
        zoomRequestSerial.incrementAndGet()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        clearPendingRequests("STOPPED")
        cameraProvider?.unbindAll()
        resetAnalyzerPipeline()
        busClient?.close()
        busClient = null
        latinRecognizer.close()
        japaneseRecognizer.close()
        overlayView.setFrozenBackground(null)
        recycleFrozenContent(baseFrozenContent)
        recycleFrozenContent(zoomFrozenContent)
        baseFrozenContent = null
        zoomFrozenContent = null
        frozenJpegBytes = null
        hdFrozenOcrInFlight = false
        cameraExecutor.shutdown()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val decision = inputRouter.routeKey(keyCode, event.repeatCount, event.eventTime)
        if (decision.consumed) {
            handleInputDecision(decision)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (inputRouter.handlesKey(keyCode)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun handleInputDecision(decision: LensInputDecision) {
        when (decision.action) {
            LensInputAction.TOGGLE_FREEZE -> toggleFreeze()
            LensInputAction.ZOOM_IN -> stepZoom(zoomIn = true)
            LensInputAction.ZOOM_OUT -> stepZoom(zoomIn = false)
            null -> Unit
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CAMERA) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            translationStatus = "CAMERA READY"
            refreshHud()
            startCamera()
        } else {
            translationStatus = "CAMERA PERMISSION REQUIRED"
            refreshHud()
        }
    }

    private fun startBusClient() {
        val client = BusClient(
            context = applicationContext,
            clientId = "lens-glasses",
            pathPrefixes = listOf(BusPaths.LENS_TRANSLATE_REPLY),
        ) { event -> handleBusEvent(event) }
        busClient = client
        client.connect()
    }

    private fun handleBusEvent(event: BusEvent) {
        when (event) {
            is BusEvent.LinkState -> {
                busState = event.state
                if (!hasDataLink()) {
                    clearPendingRequests("DATA LINK OFFLINE")
                } else if (translationStatus == "DATA LINK OFFLINE" || translationStatus == "DATA LINK ERROR") {
                    translationStatus = if (isFrozen) {
                        frozenSourceStatus ?: "FROZEN"
                    } else {
                        liveStatusLabel()
                    }
                }
                refreshOverlayFromCache(requestMissing = isFrozen && hasDataLink())
            }
            is BusEvent.Error -> {
                Log.w(TAG, "busError ${event.message}", event.cause)
                translationStatus = if (hasDataLink()) "DATA LINK ERROR" else "DATA LINK OFFLINE"
                refreshHud()
            }
            is BusEvent.Message -> handleBusMessage(event)
            is BusEvent.Binary -> Unit
        }
    }

    private fun handleBusMessage(event: BusEvent.Message) {
        when (event.path) {
            BusPaths.LENS_TRANSLATE_REPLY -> handleTranslateReply(event.id, event.payload)
            BusPaths.ERROR -> handleBusErrorReply(event)
        }
    }

    private fun handleBusErrorReply(event: BusEvent.Message) {
        val forId = event.payload.optString("forId", event.id)
        completePendingFailure(forId, "TRANSLATE ERROR", TRANSLATE_RETRY_BACKOFF_MS)
        Log.w(TAG, "translateError id=$forId status=bus_error")
    }

    private fun handleTranslateReply(envelopeId: String, payload: JSONObject) {
        val id = payload.optString("id", envelopeId).ifBlank { envelopeId }
        val pending = pendingRequests[id] ?: return
        val status = payload.optString("status")
        if (status == "downloading") {
            pending.downloadingModel = true
            pending.timeout?.let(mainHandler::removeCallbacks)
            val timeout = Runnable {
                if (pendingRequests.containsKey(id)) {
                    handleTranslationTimeout(
                        id = id,
                        status = "MODEL DOWNLOAD TIMEOUT",
                        backoffMs = MODEL_DOWNLOAD_RETRY_DELAY_MS,
                        origin = "glasses_model_deadline",
                    )
                }
            }
            pending.timeout = timeout
            mainHandler.postDelayed(timeout, MODEL_DOWNLOAD_TIMEOUT_MS)
            val lang = payload.optString("lang", payload.optString("srcLang", ""))
            val target = payload.optString("targetLang", LensWireContract.DEFAULT_TARGET_LANG)
            translationStatus = "DOWNLOADING ${lang.uppercase(Locale.US)}->${target.uppercase(Locale.US)}"
            refreshHud()
            return
        }
        if (status == "error") {
            if (payload.optString("error").equals(PHONE_TIMEOUT_ERROR_CODE, ignoreCase = true)) {
                handleTranslationTimeout(
                    id = id,
                    status = if (pending.downloadingModel) "MODEL DOWNLOAD TIMEOUT" else "TRANSLATE TIMEOUT",
                    backoffMs = if (pending.downloadingModel) {
                        MODEL_DOWNLOAD_RETRY_DELAY_MS
                    } else {
                        TRANSLATE_RETRY_BACKOFF_MS
                    },
                    origin = "phone_timeout",
                )
                return
            }
            completePendingFailure(id, "TRANSLATE ERROR", TRANSLATE_RETRY_BACKOFF_MS)
            Log.w(TAG, "translateError id=$id status=provider_error")
            return
        }

        pending.timeout?.let(mainHandler::removeCallbacks)
        pendingRequests.remove(id)
        payload.optString("engine").takeIf { it.isNotBlank() }?.let { lastServingEngine = it }
        val translations = payload.optJSONArray("translations") ?: JSONArray()
        val successfulKeys = mutableSetOf<CacheKey>()
        val retryKeys = mutableSetOf<CacheKey>()
        for (index in 0 until translations.length()) {
            val item = translations.optJSONObject(index) ?: continue
            val src = LensWireContract.normalizeText(item.optString("src"))
            val key = pending.keyBySource[src] ?: continue
            val dst = item.optString("dst").trim()
            val fallback = item.optBoolean("fallback", false)
            if (!fallback && dst.isNotBlank()) {
                translationCache[key] = CachedTranslation(
                    dst = dst,
                    srcLang = item.optString("srcLang", ""),
                )
                translationRetryByKey.remove(key)
                successfulKeys += key
            } else {
                retryKeys += key
            }
            inFlightByKey.remove(key)
        }
        retryKeys += pending.keyBySource.values.filterNot(successfulKeys::contains)
        retryKeys.removeAll(successfulKeys)
        markTranslationRetries(retryKeys, TRANSLATE_RETRY_BACKOFF_MS)
        if (pending.frozen) {
            retryKeys.forEach(frozenAttemptedTranslationKeys::remove)
        }
        pending.keyBySource.values.forEach { inFlightByKey.remove(it) }
        val latencyMs = SystemClock.elapsedRealtime() - pending.sentAtMs
        translationRetryNotBeforeMs = 0L
        translationStatus = "TRANSLATED ${translations.length()}"
        Log.i(TAG, "translateRoundTrip id=$id latencyMs=$latencyMs stringCount=${pending.keyBySource.size}")
        // Live continuation: without requestMissing the next batch waits for another OCR frame
        // (0.6-1.2Hz), serializing pending paragraphs into many seconds of dead time.
        refreshOverlayFromCache(requestMissing = !isFrozen)
        if (isFrozen) {
            val nowMs = SystemClock.elapsedRealtime()
            val retryDelayMs = retryKeys
                .mapNotNull { translationRetryByKey[it]?.retryNotBeforeMs }
                .minOrNull()
                ?.let { retryAtMs -> maxOf(FROZEN_BATCH_CONTINUATION_DELAY_MS, retryAtMs - nowMs) }
                ?: FROZEN_BATCH_CONTINUATION_DELAY_MS
            scheduleFrozenBatchContinuation(retryDelayMs)
        }
    }

    private fun handleTranslationTimeout(
        id: String,
        status: String,
        backoffMs: Long,
        origin: String,
    ) {
        val pending = pendingRequests[id] ?: return
        if (pending.frozen) {
            pending.keyBySource.values.forEach(frozenAttemptedTranslationKeys::remove)
        }
        val stringCount = pending.keyBySource.size
        completePendingFailure(id, status, backoffMs)
        Log.w(TAG, "translateTimeout id=$id origin=$origin stringCount=$stringCount backoffMs=$backoffMs")
        if (isFrozen && hasDataLink()) scheduleFrozenBatchContinuation(backoffMs)
    }

    private fun completePendingFailure(id: String, status: String, retryBaseMs: Long? = null) {
        val pending = pendingRequests.remove(id) ?: return
        pending.timeout?.let(mainHandler::removeCallbacks)
        pending.keyBySource.values.forEach { inFlightByKey.remove(it) }
        retryBaseMs?.let { baseMs ->
            val retryUntilMs = markTranslationRetries(pending.keyBySource.values, baseMs)
            translationRetryNotBeforeMs = maxOf(translationRetryNotBeforeMs, retryUntilMs)
        }
        translationStatus = status
        refreshOverlayFromCache()
    }

    private fun markTranslationRetries(keys: Collection<CacheKey>, baseBackoffMs: Long): Long {
        if (keys.isEmpty()) return 0L
        val nowMs = SystemClock.elapsedRealtime()
        var latestRetryMs = 0L
        keys.forEach { key ->
            val failureCount = (translationRetryByKey[key]?.failureCount ?: 0)
                .coerceAtMost(MAX_RETRY_BACKOFF_SHIFT) + 1
            val multiplier = 1L shl (failureCount - 1).coerceAtMost(MAX_RETRY_BACKOFF_SHIFT)
            val delayMs = minOf(MAX_TRANSLATION_RETRY_BACKOFF_MS, baseBackoffMs * multiplier)
            val retry = TranslationRetry(
                failureCount = failureCount,
                retryNotBeforeMs = nowMs + delayMs,
            )
            translationRetryByKey[key] = retry
            latestRetryMs = maxOf(latestRetryMs, retry.retryNotBeforeMs)
        }
        return latestRetryMs
    }

    private fun clearPendingRequests(status: String) {
        pendingRequests.values.forEach { pending ->
            pending.timeout?.let(mainHandler::removeCallbacks)
        }
        pendingRequests.clear()
        inFlightByKey.clear()
        frozenAttemptedTranslationKeys.clear()
        translationRetryByKey.clear()
        translationRetryNotBeforeMs = 0L
        cancelFrozenBatchContinuation()
        translationStatus = status
    }

    private fun scheduleFrozenBatchContinuation(
        delayMs: Long = FROZEN_BATCH_CONTINUATION_DELAY_MS,
    ) {
        if (!isFrozen || !isActivityResumed || !hasDataLink() || isFinishing || isDestroyed) return
        cancelFrozenBatchContinuation()
        val continuation = Runnable {
            frozenBatchContinuation = null
            if (isFrozen && isActivityResumed && hasDataLink() && !isFinishing && !isDestroyed) {
                refreshOverlayFromCache(requestMissing = true)
            }
        }
        frozenBatchContinuation = continuation
        mainHandler.postDelayed(continuation, delayMs)
    }

    private fun cancelFrozenBatchContinuation() {
        frozenBatchContinuation?.let(mainHandler::removeCallbacks)
        frozenBatchContinuation = null
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            translationStatus = "CAMERA READY"
            refreshHud()
            startCamera()
        } else {
            translationStatus = "REQUESTING CAMERA"
            refreshHud()
            requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST_CAMERA)
        }
    }

    private fun hasCameraPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        if (!isActivityResumed || isFrozen || !hasCameraPermission()) return
        val provider = cameraProvider
        if (provider != null) {
            bindCameraUseCases(provider)
            return
        }
        if (cameraProviderRequestInFlight) return
        val future = runCatching { ProcessCameraProvider.getInstance(this) }
            .getOrElse {
                Log.e(TAG, "cameraProviderFailure", it)
                translationStatus = "CAMERA ERROR"
                refreshHud()
                return
            }
        cameraProviderRequestInFlight = true
        future.addListener(
            {
                cameraProviderRequestInFlight = false
                val resolvedProvider = runCatching { future.get() }.getOrElse {
                    Log.e(TAG, "cameraProviderFailure", it)
                    if (isActivityResumed && !isFinishing && !isDestroyed) {
                        translationStatus = "CAMERA ERROR"
                        refreshHud()
                    }
                    return@addListener
                }
                cameraProvider = resolvedProvider
                if (!isActivityResumed || isFrozen || isFinishing || isDestroyed) return@addListener
                bindCameraUseCases(resolvedProvider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    @Suppress("DEPRECATION")
    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        if (!isActivityResumed || isFrozen || isFinishing || isDestroyed) return
        if (!hasCameraPermission()) return
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0

        val candidates = listOf(
            SelectorCandidate("WORLD CAM 0", worldCameraSelector()),
            SelectorCandidate("BACK CAM", CameraSelector.DEFAULT_BACK_CAMERA),
            SelectorCandidate("AUTO CAM", autoCameraSelector()),
        )

        resetAnalyzerPipeline()
        provider.unbindAll()
        imageCapture = null
        activeCameraSelector = null
        activeCameraLabel = null
        imageCaptureBoundWithAnalysis = false
        temporaryCaptureBindingActive = false
        restoreCaptureWithAnalysisAfterTemporary = false
        cameraUnboundForFrozen = false
        restoreCaptureWithAnalysisAfterFrozen = false
        for (candidate in candidates) {
            // Never bind imageCapture with the live session: on this LIMITED HAL a large JPEG
            // stream in the shared session downgrades the YUV analysis stream to 640x480
            // (measured via dumpsys media.camera), and takePicture never runs on the live
            // binding anyway — HD refine always goes through the temporary preview+capture
            // rebind. Live stays preview+analysis so analysis keeps its negotiated 1440x1080.
            if (bindPreviewAnalysisOnly(provider, candidate.selector, candidate.label, updateStatus = true)) {
                Log.i(TAG, "cameraBindSucceeded label=${candidate.label} useCases=preview+analysis captureRequiresTemporaryRebind=true")
                return
            }
        }
        val retryScheduled = scheduleCameraRetry()
        translationStatus = if (retryScheduled) {
            "CAMERA RETRY $cameraBindRetryCount/$CAMERA_BIND_MAX_RETRIES"
        } else {
            "CAMERA UNAVAILABLE"
        }
        refreshHud()
        Log.e(TAG, "cameraBindFailed all candidates")
    }

    private fun buildPreviewUseCase(
        targetRotation: Int,
        sessionCaptureCallback: CameraCaptureSession.CaptureCallback? = null,
    ): Preview {
        val builder = Preview.Builder().setTargetRotation(targetRotation)
        if (sessionCaptureCallback != null) {
            Camera2Interop.Extender(builder).setSessionCaptureCallback(sessionCaptureCallback)
        }
        return builder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
    }

    @Suppress("DEPRECATION")
    private fun buildAnalysisUseCase(targetRotation: Int): ImageAnalysis =
        ImageAnalysis.Builder()
            .setTargetResolution(Size(960, 1280))
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { it.setAnalyzer(cameraExecutor, TextAnalyzer()) }

    @Suppress("DEPRECATION")
    private fun buildImageCaptureUseCase(targetRotation: Int): ImageCapture =
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(90)
            .setFlashMode(ImageCapture.FLASH_MODE_OFF)
            .setTargetResolution(Size(MAX_FROZEN_OCR_BITMAP_EDGE * 3 / 4, MAX_FROZEN_OCR_BITMAP_EDGE))
            .setTargetRotation(targetRotation)
            .build()

    private fun bindPreviewAnalysisOnly(
        provider: ProcessCameraProvider,
        selector: CameraSelector,
        label: String,
        updateStatus: Boolean,
    ): Boolean {
        if (!isActivityResumed || !hasCameraPermission() || isFinishing || isDestroyed) return false
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = buildPreviewUseCase(targetRotation)
        val analysis = buildAnalysisUseCase(targetRotation)
        resetAnalyzerPipeline()
        provider.unbindAll()
        return runCatching {
            provider.bindToLifecycle(this, selector, preview, analysis)
            imageCapture = null
            activeCameraSelector = selector
            activeCameraLabel = label
            imageCaptureBoundWithAnalysis = false
            temporaryCaptureBindingActive = false
            restoreCaptureWithAnalysisAfterTemporary = false
            cameraUnboundForFrozen = false
            restoreCaptureWithAnalysisAfterFrozen = false
            markCameraBound()
            if (updateStatus) {
                translationStatus = label
                refreshHud()
            }
        }.onFailure {
            Log.w(TAG, "cameraBindFailed label=$label useCases=preview+analysis", it)
        }.isSuccess
    }

    private fun worldCameraSelector(): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameras ->
                cameras.filter {
                    runCatching { Camera2CameraInfo.from(it).cameraId == "0" }.getOrDefault(false)
                }.ifEmpty { cameras }
            }
            .build()

    private fun autoCameraSelector(): CameraSelector =
        CameraSelector.Builder()
            .addCameraFilter { cameras -> cameras.take(1) }
            .build()

    private fun toggleFreeze() {
        if (isFrozen) {
            unfreezeLens()
        } else {
            freezeLens()
        }
    }

    private fun freezeLens() {
        if (!isActivityResumed) return
        if (cameraProvider == null || activeCameraSelector == null) {
            translationStatus = "CAMERA STARTING"
            refreshHud()
            startCamera()
            return
        }
        val requestId = freezeRequestSerial.incrementAndGet()
        activeHdCaptureSession?.cancel("new_freeze")
        val modeSnapshot = effectiveScript
        val liveZoomSnapshot = liveZoomLevel
        cancelFrozenBatchContinuation()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        frozenAttemptedTranslationKeys.clear()
        zoomRequestSerial.incrementAndGet()
        frozenJpegBytes = null
        frozenJpegRotationDegrees = 0
        hdFrozenOcrInFlight = false
        frozenZoomLevel = FrozenZoomLevel.ONE
        frozenBaseLiveZoomLevel = liveZoomSnapshot
        frozenScriptState = FrozenScriptState(modeSnapshot)
        frozenSelectedScript = modeSnapshot
        queuedFrozenZoomLevel = null
        baseFrozenContent = null
        zoomFrozenContent = null
        isFrozen = true
        // Live reconciliation state is dead weight for the whole frozen session; drop it
        // now so its anchors/graveyard aren't resident through the HD capture peak.
        clearLiveTracking()
        applyPreviewScaleForCurrentState()
        frozenOcrResult = null
        frozenSourceStatus = null
        translationStatus = "FREEZE NEXT FRAME..."
        overlayView.updateOcrResult(null, emptyList(), hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)))
        val request = FastFreezeRequest(
            requestId = requestId,
            mode = modeSnapshot,
            liveZoomLevel = liveZoomSnapshot,
            restoreCaptureWithAnalysis = imageCaptureBoundWithAnalysis,
        )
        pendingFastFreeze.set(request)
        resetAnalyzerPipeline()
        scheduleFastFreezeTimeout(request)
        Log.i(TAG, "freezeFastArmed id=$requestId mode=${modeSnapshot.name}")
    }

    private fun startHdRefine(request: FastFreezeRequest) {
        if (!isCurrentFreeze(request.requestId)) return
        // Drop rebuildable live/layout state before CameraX allocates its JPEG buffers.
        // The installed FAST result already owns everything needed to carry translations.
        overlayView.clearLayoutCache()
        if (baseFrozenContent != null) {
            lastOcrResult = null
            clearLiveTracking()
        }
        // The kernel OOM-reaps foreground apps on this 1.8GB device when the HD rebind's
        // ION/gralloc demand lands on an already-tight system (observed 2026-07-11, three
        // sweeps in one morning). Keeping the FAST result beats dying with the HD one.
        val availableMemoryMb = availableSystemMemoryMb()
        if (availableMemoryMb in 0 until HD_REFINE_MIN_AVAILABLE_MEMORY_MB) {
            Log.w(TAG, "freezeHdSkipped id=${request.requestId} reason=lowmem availMb=$availableMemoryMb")
            finishHdRefineFailure(request.requestId, "LOWMEM ${availableMemoryMb}MB")
            return
        }
        // The LIMITED HAL cannot takePicture on preview+analysis+capture. Only after the
        // analysis ImageProxy closes do we switch to the proven capture-only binding.
        activeHdCaptureSession?.cancel("replaced")
        val session = bindCaptureOnlyForFreeze(
            requestId = request.requestId,
            mode = frozenSelectedScript,
            restoreCaptureWithAnalysis = request.restoreCaptureWithAnalysis,
        )
        if (session == null) {
            finishHdRefineFailure(request.requestId, "HD REBIND FAILED")
            return
        }
        Log.i(TAG, "freezeHdRefineStart id=${request.requestId} mode=${request.mode.name}")
    }

    private fun takeFreezePicture(session: HdCaptureSession) {
        val requestId = session.requestId
        val mode = session.mode
        freezeCaptureInFlight = true
        runCatching {
            session.capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        session.capturedAtMs = SystemClock.elapsedRealtime()
                        val capturedJpeg = runCatching {
                            copyFrozenCaptureJpeg(image)
                        }.onFailure {
                            Log.w(TAG, "freezeCaptureCopyFailure id=$requestId", it)
                        }.getOrNull()
                        // Release CameraX's native JPEG buffer before allocating decoded bitmaps.
                        image.close()

                        mainHandler.post {
                            if (!isCurrentFreeze(requestId)) {
                                freezeCaptureInFlight = false
                                session.cancel("stale_after_capture")
                                restoreLiveCameraAfterSettledFreezeCapture()
                                return@post
                            }
                            if (capturedJpeg == null) {
                                freezeCaptureInFlight = false
                                finishHdRefineFailure(requestId, "HD COPY FAILED")
                                return@post
                            }

                            // Camera buffers are the largest non-bitmap residents in this path;
                            // unbind the temporary session before starting the bitmap decode.
                            unbindCameraForFrozen(restoreCaptureWithAnalysisAfterTemporary)
                            val liveZoomSnapshot = frozenBaseLiveZoomLevel
                            runCatching {
                                cameraExecutor.execute {
                                    val decodedCapture = runCatching {
                                        decodeFrozenCaptureBitmap(capturedJpeg, liveZoomSnapshot)
                                    }.onFailure {
                                        Log.w(TAG, "freezeCaptureDecodeFailure id=$requestId", it)
                                    }.getOrNull()
                                    session.decodedAtMs = SystemClock.elapsedRealtime()
                                    mainHandler.post {
                                        freezeCaptureInFlight = false
                                        if (!isCurrentFreeze(requestId)) {
                                            decodedCapture?.bitmap?.recycle()
                                            session.cancel("stale_after_decode")
                                            restoreLiveCameraAfterSettledFreezeCapture()
                                            return@post
                                        }
                                        if (decodedCapture == null) {
                                            finishHdRefineFailure(requestId, "HD DECODE FAILED")
                                            return@post
                                        }

                                        frozenJpegBytes = decodedCapture.jpegBytes
                                        frozenJpegRotationDegrees = decodedCapture.appliedRotation
                                        session.completeTiming()
                                        runHdFrozenBitmapOcr(
                                            requestId = requestId,
                                            mode = mode,
                                            bitmap = decodedCapture.bitmap,
                                        )
                                    }
                                }
                            }.onFailure {
                                freezeCaptureInFlight = false
                                finishHdRefineFailure(requestId, "HD DECODE START FAILED", it)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "freezeCaptureFailure id=$requestId", exception)
                        mainHandler.post {
                            freezeCaptureInFlight = false
                            session.cancel("capture_error")
                            finishHdRefineFailure(requestId, "HD CAPTURE FAILED", exception)
                        }
                    }
                },
            )
        }.onFailure {
            freezeCaptureInFlight = false
            session.cancel("capture_start_error")
            Log.w(TAG, "freezeCaptureStartFailure id=$requestId", it)
            mainHandler.post { finishHdRefineFailure(requestId, "HD CAPTURE START FAILED", it) }
        }
    }

    private fun finishHdRefineFailure(
        requestId: Long,
        reason: String,
        cause: Throwable? = null,
    ) {
        hdFrozenOcrInFlight = false
        activeHdCaptureSession?.takeIf { it.requestId == requestId }?.cancel("failure_$reason")
        if (!isCurrentFreeze(requestId)) {
            restoreLiveCameraAfterSettledFreezeCapture()
            return
        }
        unbindCameraForFrozen(restoreCaptureWithAnalysisAfterTemporary)
        if (queuedFrozenZoomLevel != null) {
            queuedFrozenZoomLevel = null
            showFrozenZoomNotice("ZOOM UNAVAILABLE")
        }
        frozenSourceStatus = baseFrozenContent?.sourceStatus ?: frozenSourceStatus ?: "FAST 1080"
        translationStatus = frozenSourceStatus ?: "FAST 1080"
        refreshOverlayFromCache(requestMissing = true)
        Log.w(
            TAG,
            "freezeHdRefineFailure id=$requestId reason=$reason keep=${frozenSourceStatus ?: "FAST"}",
            cause,
        )
    }

    private fun unfreezeLens() {
        activeHdCaptureSession?.cancel("unfreeze")
        freezeRequestSerial.incrementAndGet()
        pendingFastFreeze.set(null)
        zoomRequestSerial.incrementAndGet()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        cancelFrozenBatchContinuation()
        frozenAttemptedTranslationKeys.clear()
        isFrozen = false
        applyPreviewScaleForCurrentState()
        frozenOcrResult = null
        frozenSourceStatus = null
        frozenJpegBytes = null
        frozenJpegRotationDegrees = 0
        hdFrozenOcrInFlight = false
        frozenZoomLevel = FrozenZoomLevel.ONE
        queuedFrozenZoomLevel = null
        lastOcrResult = null
        clearLiveTracking()
        overlayView.setFrozenBackground(null)
        recycleFrozenContent(baseFrozenContent)
        recycleFrozenContent(zoomFrozenContent)
        baseFrozenContent = null
        zoomFrozenContent = null
        translationStatus = liveStatusLabel()
        overlayView.updateOcrResult(null, emptyList(), hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)))
        if (freezeCaptureInFlight) {
            rebindCameraAfterFreezeCaptureSettles = true
        } else if (temporaryCaptureBindingActive || cameraUnboundForFrozen) {
            restoreLiveCameraAfterFrozen()
        } else {
            rebindCameraAfterFreezeCaptureSettles = false
            resetAnalyzerPipeline()
        }
        Log.i(TAG, "freeze disabled")
    }

    private fun bindCaptureOnlyForFreeze(
        requestId: Long,
        mode: OcrScript,
        restoreCaptureWithAnalysis: Boolean = imageCaptureBoundWithAnalysis,
    ): HdCaptureSession? {
        if (!isActivityResumed || isFinishing || isDestroyed) return null
        val provider = cameraProvider ?: return null
        val selector = activeCameraSelector ?: return null
        val label = activeCameraLabel ?: "CAMERA"
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val session = HdCaptureSession(
            requestId = requestId,
            mode = mode,
            bindStartedAtMs = SystemClock.elapsedRealtime(),
        )
        val capture = buildImageCaptureUseCase(targetRotation)
        session.capture = capture
        restoreCaptureWithAnalysisAfterTemporary = restoreCaptureWithAnalysis
        resetAnalyzerPipeline()
        provider.unbindAll()
        activeHdCaptureSession = session
        return runCatching {
            provider.bindToLifecycle(this, selector, capture)
            session.onBound(SystemClock.elapsedRealtime())
            imageCapture = capture
            imageCaptureBoundWithAnalysis = false
            temporaryCaptureBindingActive = true
            cameraUnboundForFrozen = false
            Log.i(TAG, "cameraBindSucceeded label=$label useCases=imageCapture temporary=true")
            // This HAL never delivered a single preview frame or AE state to the old temp
            // preview (deadline always fired blind after 450ms), so a companion preview
            // stream costs ION buffers and latency for nothing. Issue immediately; CameraX
            // queues the still until the capture session is configured, and the ISP's 3A
            // state is still warm from the live session unbound milliseconds ago.
            session.issueCapture("capture_only_immediate")
            session
        }.onFailure {
            session.cancel("bind_error")
            temporaryCaptureBindingActive = false
            imageCapture = null
            Log.w(TAG, "cameraBindFailed label=$label useCases=imageCapture temporary=true", it)
            restoreCameraBinding(restoreCaptureWithAnalysis)
        }.getOrNull()
    }

    private fun restorePreviewAnalysisAfterTemporaryCapture() {
        if (!temporaryCaptureBindingActive) return
        restoreCameraBinding(restoreCaptureWithAnalysisAfterTemporary)
    }

    private fun restoreCameraBinding(restoreCaptureWithAnalysis: Boolean): Boolean {
        val provider = cameraProvider ?: run {
            temporaryCaptureBindingActive = false
            imageCapture = null
            restoreCaptureWithAnalysisAfterTemporary = false
            return false
        }
        val selector = activeCameraSelector ?: run {
            temporaryCaptureBindingActive = false
            imageCapture = null
            restoreCaptureWithAnalysisAfterTemporary = false
            return false
        }
        val label = activeCameraLabel ?: "CAMERA"
        if (restoreCaptureWithAnalysis) {
            // Legacy state may claim capture shared the live session. Never recreate that
            // topology: the LIMITED HAL downgrades analysis when a JPEG stream is present.
            Log.w(TAG, "cameraBindRestore ignoredLegacySharedCapture=true label=$label")
        }
        return if (bindPreviewAnalysisOnly(provider, selector, label, updateStatus = false)) {
            Log.i(TAG, "cameraBindRestored label=$label useCases=preview+analysis")
            true
        } else {
            imageCapture = null
            imageCaptureBoundWithAnalysis = false
            temporaryCaptureBindingActive = false
            restoreCaptureWithAnalysisAfterTemporary = false
            false
        }
    }

    private fun unbindCameraForFrozen(
        restoreCaptureWithAnalysis: Boolean = imageCaptureBoundWithAnalysis || restoreCaptureWithAnalysisAfterTemporary,
    ) {
        val effectiveRestoreCaptureWithAnalysis =
            restoreCaptureWithAnalysis || (cameraUnboundForFrozen && restoreCaptureWithAnalysisAfterFrozen)
        restoreCaptureWithAnalysisAfterFrozen = effectiveRestoreCaptureWithAnalysis
        val shouldLog = !cameraUnboundForFrozen ||
            imageCapture != null ||
            imageCaptureBoundWithAnalysis ||
            temporaryCaptureBindingActive
        resetAnalyzerPipeline()
        cameraProvider?.unbindAll()
        imageCapture = null
        imageCaptureBoundWithAnalysis = false
        temporaryCaptureBindingActive = false
        cameraUnboundForFrozen = true
        if (shouldLog) {
            Log.i(TAG, "cameraUnbound=frozen restoreCaptureWithAnalysis=$effectiveRestoreCaptureWithAnalysis")
        }
    }

    private fun restoreLiveCameraAfterFrozen() {
        if (isFrozen || !hasCameraPermission()) return
        val restoreCaptureWithAnalysis =
            restoreCaptureWithAnalysisAfterFrozen || restoreCaptureWithAnalysisAfterTemporary
        if (restoreCameraBinding(restoreCaptureWithAnalysis)) {
            restoreCaptureWithAnalysisAfterFrozen = false
            cameraUnboundForFrozen = false
            Log.i(TAG, "cameraRebound=live")
        } else {
            startCamera()
        }
    }

    private fun restoreLiveCameraAfterSettledFreezeCapture() {
        if (!rebindCameraAfterFreezeCaptureSettles) return
        rebindCameraAfterFreezeCaptureSettles = false
        restoreLiveCameraAfterFrozen()
    }

    private fun markCameraBound() {
        cameraBindRetryCount = 0
        cameraRetry?.let(mainHandler::removeCallbacks)
        cameraRetry = null
    }

    private fun resetAnalyzerPipeline() {
        analyzerGeneration.incrementAndGet()
        analyzerBusy.set(false)
        resetAnalyzerCadence()
    }

    /** System-wide available memory in MB, or -1 when the service is unavailable. */
    private fun availableSystemMemoryMb(): Int {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as? ActivityManager ?: return -1
        val info = ActivityManager.MemoryInfo()
        return runCatching {
            activityManager.getMemoryInfo(info)
            (info.availMem / (1024L * 1024L)).toInt()
        }.getOrDefault(-1)
    }

    private fun tryAcquireAnalyzerCadence(nowMs: Long): Boolean =
        synchronized(analyzerCadenceLock) {
            val decision = OcrCadencePolicy.evaluate(
                state = analyzerCadenceState,
                nowMs = nowMs,
                minIntervalMs = LIVE_OCR_MIN_INTERVAL_MS,
            )
            analyzerCadenceState = decision.nextState
            decision.shouldStart
        }

    private fun resetAnalyzerCadence() {
        synchronized(analyzerCadenceLock) {
            analyzerCadenceState = OcrCadenceState()
        }
    }

    private fun scheduleCameraRetry(): Boolean {
        if (!isActivityResumed || isFrozen || isFinishing || isDestroyed) return false
        if (cameraBindRetryCount >= CAMERA_BIND_MAX_RETRIES) return false
        cameraBindRetryCount += 1
        cameraRetry?.let(mainHandler::removeCallbacks)
        val attempt = cameraBindRetryCount
        val retry = Runnable {
            cameraRetry = null
            if (!isActivityResumed || isFrozen || isFinishing || isDestroyed) return@Runnable
            Log.i(TAG, "cameraRetry attempt=$attempt max=$CAMERA_BIND_MAX_RETRIES")
            startCamera()
        }
        cameraRetry = retry
        mainHandler.postDelayed(retry, CAMERA_BIND_RETRY_DELAY_MS)
        return true
    }

    private fun scheduleFastFreezeTimeout(request: FastFreezeRequest) {
        cancelFastFreezeTimeout()
        val timeout = Runnable {
            fastFreezeTimeout = null
            if (pendingFastFreeze.compareAndSet(request, null) && isCurrentFreeze(request.requestId)) {
                Log.w(TAG, "freezeFastFrameTimeout id=${request.requestId}")
                completeFastFreezeFromPreview(request, "FRAME TIMEOUT")
            }
        }
        fastFreezeTimeout = timeout
        mainHandler.postDelayed(timeout, FAST_FREEZE_FRAME_TIMEOUT_MS)
    }

    private fun cancelFastFreezeTimeout() {
        fastFreezeTimeout?.let(mainHandler::removeCallbacks)
        fastFreezeTimeout = null
    }

    private fun completeFastFreezeFromPreview(request: FastFreezeRequest, reason: String) {
        if (!isCurrentFreeze(request.requestId)) return
        val previewBitmap = previewView.bitmap
        val bitmap = previewBitmap
            ?.let(::copyAsRgb565AndRecycleSource)
            ?.let { cropBitmapToLiveZoom(it, request.liveZoomLevel) }
        if (bitmap == null) {
            frozenSourceStatus = "FAST UNAVAILABLE"
            translationStatus = frozenSourceStatus ?: "FAST UNAVAILABLE"
            refreshHud()
            Log.w(TAG, "freezeFastPreviewUnavailable id=${request.requestId} reason=$reason")
            startHdRefine(request)
            return
        }
        val previous = lastOcrResult?.snapshotForFreeze()
        val result = previous ?: LastOcrResult(
            mode = request.mode,
            transformMatrix = frozenBitmapToViewMatrix(bitmap),
            blocks = emptyList(),
            metrics = OcrMetrics(avgLatencyMs = 0L, hz = 0.0),
            completedAtMs = SystemClock.elapsedRealtime(),
        )
        installBaseFrozenContent(
            request.requestId,
            FrozenContent(bitmap, result, "FAST PREVIEW"),
        )
        Log.w(TAG, "freezeFastPreviewFallback id=${request.requestId} reason=$reason")
        startHdRefine(request)
    }

    private fun showFastFrozenBitmap(request: FastFreezeRequest, bitmap: Bitmap) {
        if (!isCurrentFreeze(request.requestId)) {
            bitmap.recycle()
            return
        }
        val status = fastSourceStatus(bitmap)
        frozenSourceStatus = status
        translationStatus = status
        overlayView.setFrozenBackground(bitmap)
        overlayView.updateOcrResult(
            null,
            emptyList(),
            hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)),
        )
    }

    private fun completeFastFreezeOcr(
        request: FastFreezeRequest,
        bitmap: Bitmap,
        text: Text,
        script: OcrScript,
        latencyMs: Long,
    ) {
        if (!isCurrentFreeze(request.requestId)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val rawBlocks = extractBlocks(text)
        val filteredBlocks = filterFrozenBlocks(script, rawBlocks)
        val blocks = segmentFrozenParagraphBlocks(filteredBlocks)
        val result = LastOcrResult(
            mode = script,
            transformMatrix = frozenBitmapToViewMatrix(bitmap),
            blocks = blocks,
            metrics = OcrMetrics(avgLatencyMs = latencyMs, hz = 0.0),
            completedAtMs = SystemClock.elapsedRealtime(),
            carriedTranslations = carriedTranslationsFor(script, blocks, lastOcrResult),
        )
        installBaseFrozenContent(
            request.requestId,
            FrozenContent(bitmap, result, fastSourceStatus(bitmap)),
        )
        Log.i(
            TAG,
            "freezeFastOcrSuccess id=${request.requestId} latencyMs=$latencyMs blocks=${blocks.size}",
        )
        startHdRefine(request)
    }

    private fun completeFastFreezeOcrFailure(
        request: FastFreezeRequest,
        bitmap: Bitmap?,
        reason: String,
        cause: Throwable? = null,
    ) {
        if (!isCurrentFreeze(request.requestId)) {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
            return
        }
        if (bitmap == null) {
            completeFastFreezeFromPreview(request, reason)
            return
        }
        val result = LastOcrResult(
            mode = request.mode,
            transformMatrix = frozenBitmapToViewMatrix(bitmap),
            blocks = emptyList(),
            metrics = OcrMetrics(avgLatencyMs = 0L, hz = 0.0),
            completedAtMs = SystemClock.elapsedRealtime(),
        )
        installBaseFrozenContent(
            request.requestId,
            FrozenContent(bitmap, result, fastSourceStatus(bitmap)),
        )
        Log.w(TAG, "freezeFastOcrFailure id=${request.requestId} reason=$reason", cause)
        startHdRefine(request)
    }

    private fun fastSourceStatus(bitmap: Bitmap): String =
        "FAST ${minOf(bitmap.width, bitmap.height)}"

    private fun runHdFrozenBitmapOcr(requestId: Long, mode: OcrScript, bitmap: Bitmap) {
        hdFrozenOcrInFlight = true
        runFrozenBitmapOcr(
            requestId = requestId,
            mode = mode,
            bitmap = bitmap,
            sourceStatus = "HD ${maxOf(bitmap.width, bitmap.height)}",
            previous = baseFrozenContent?.ocrResult ?: lastOcrResult,
            onReady = { content ->
                installBaseFrozenContent(requestId, content)
                hdFrozenOcrInFlight = false
                Log.i(TAG, "freezeHdRefineSuccess id=$requestId blocks=${content.ocrResult.blocks.size}")
                queuedFrozenZoomLevel?.let { queued ->
                    queuedFrozenZoomLevel = null
                    Log.i(TAG, "frozenZoomQueuedApply level=${queued.hudLabel}")
                    applyFrozenZoom(requestId, queued)
                }
            },
            onFailure = { cause ->
                hdFrozenOcrInFlight = false
                finishHdRefineFailure(requestId, "HD OCR FAILED", cause)
            },
        )
    }

    private fun runFrozenBitmapOcr(
        requestId: Long,
        mode: OcrScript,
        bitmap: Bitmap,
        sourceStatus: String,
        previous: LastOcrResult?,
        onReady: (FrozenContent) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        runAutoFrozenRecognition(
            requestId = requestId,
            bitmap = bitmap,
            initialScript = mode,
            onSuccess = frozenSuccess@ { recognition ->
                val text = recognition.text
                if (!isCurrentFreeze(requestId)) {
                    if (!overlayView.isFrozenBackground(bitmap) && !bitmap.isRecycled) bitmap.recycle()
                    return@frozenSuccess
                }
                val latencyMs = recognition.latencyMs
                val recognizedScript = recognition.script
                val rawBlocks = extractBlocks(text)
                val filteredBlocks = filterFrozenBlocks(recognizedScript, rawBlocks)
                val blocks = segmentFrozenParagraphBlocks(filteredBlocks)
                val metrics = OcrMetrics(avgLatencyMs = latencyMs, hz = 0.0)
                val carriedTranslations = carriedTranslationsFor(recognizedScript, blocks, previous)
                val rgb565 = if (bitmap.config == Bitmap.Config.RGB_565) {
                    bitmap
                } else {
                    runCatching { bitmap.copy(Bitmap.Config.RGB_565, false) }
                        .onFailure { Log.w(TAG, "freezeBackgroundRgb565CopyFailed id=$requestId", it) }
                        .getOrNull()
                }
                if (rgb565 == null) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    onFailure(IllegalStateException("RGB_565 conversion failed"))
                    return@frozenSuccess
                }
                if (rgb565 !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                val result = LastOcrResult(
                    mode = recognizedScript,
                    transformMatrix = frozenBitmapToViewMatrix(rgb565),
                    blocks = blocks,
                    metrics = metrics,
                    completedAtMs = SystemClock.elapsedRealtime(),
                    carriedTranslations = carriedTranslations,
                )
                onReady(FrozenContent(rgb565, result, sourceStatus))
                Log.i(TAG, "freezeOcrSuccess id=$requestId source=$sourceStatus script=${recognizedScript.name} latencyMs=$latencyMs blocks=${blocks.size}")
            },
            onFailure = { cause ->
                Log.w(TAG, "freezeOcrFailure id=$requestId source=$sourceStatus", cause)
                if (!bitmap.isRecycled) bitmap.recycle()
                if (isCurrentFreeze(requestId)) onFailure(cause)
            },
        )
    }

    private fun runAutoFrozenRecognition(
        requestId: Long,
        bitmap: Bitmap,
        initialScript: OcrScript,
        onSuccess: (FrozenRecognition) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        val executor = ContextCompat.getMainExecutor(this)

        fun recognize(script: OcrScript, primary: FrozenRecognition?) {
            val task = runCatching {
                recognizerFor(script).process(InputImage.fromBitmap(bitmap, 0))
            }.getOrElse { cause ->
                if (primary != null) onSuccess(primary) else onFailure(cause)
                return
            }
            task
                .addOnSuccessListener(executor) { text ->
                    if (!isCurrentFreeze(requestId)) {
                        if (!overlayView.isFrozenBackground(bitmap) && !bitmap.isRecycled) bitmap.recycle()
                        return@addOnSuccessListener
                    }
                    val recognition = FrozenRecognition(
                        text = text,
                        script = script,
                        latencyMs = SystemClock.elapsedRealtime() - startedAtMs,
                    )
                    if (primary != null) {
                        val winningScript = AutoScriptPolicy.betterFrozenScript(
                            primaryScript = primary.script,
                            primaryText = primary.text.text,
                            retryScript = recognition.script,
                            retryText = recognition.text.text,
                        )
                        val winner = if (winningScript == recognition.script) recognition else primary.copy(
                            latencyMs = recognition.latencyMs,
                        )
                        frozenSelectedScript = winner.script
                        onSuccess(winner)
                        return@addOnSuccessListener
                    }

                    val observation = AutoScriptPolicy.observeFrozenResult(
                        state = frozenScriptState,
                        script = script,
                        text = text.text,
                    )
                    frozenScriptState = observation.nextState
                    val retryScript = observation.retryScript
                    if (retryScript != null) {
                        Log.i(TAG, "freezeAutoRetry id=$requestId from=${script.name} to=${retryScript.name}")
                        recognize(retryScript, recognition)
                    } else {
                        frozenSelectedScript = recognition.script
                        onSuccess(recognition)
                    }
                }
                .addOnFailureListener(executor) { cause ->
                    if (primary != null) {
                        frozenSelectedScript = primary.script
                        onSuccess(primary.copy(latencyMs = SystemClock.elapsedRealtime() - startedAtMs))
                    } else if (isCurrentFreeze(requestId)) {
                        onFailure(cause)
                    }
                }
        }

        recognize(initialScript, primary = null)
    }

    private fun recognizerFor(script: OcrScript): TextRecognizer =
        if (script == OcrScript.LATIN) latinRecognizer else japaneseRecognizer

    private fun installBaseFrozenContent(requestId: Long, content: FrozenContent) {
        if (!isCurrentFreeze(requestId)) {
            recycleFrozenContent(content)
            return
        }
        val previous = baseFrozenContent
        baseFrozenContent = content
        frozenSourceStatus = content.sourceStatus
        if (frozenZoomLevel == FrozenZoomLevel.ONE) {
            overlayView.setFrozenBackground(content.bitmap)
            frozenOcrResult = content.ocrResult
            translationStatus = content.sourceStatus
            refreshOverlayFromCache(requestMissing = true)
        } else if (previous?.bitmap?.let(overlayView::isFrozenBackground) == true) {
            // A zoom request can be decoding while the FAST base is still displayed. Move the
            // display to the refined base before recycling FAST; the crop replaces it when ready.
            overlayView.setFrozenBackground(content.bitmap)
            frozenOcrResult = content.ocrResult
            refreshOverlayFromCache(requestMissing = true)
        } else {
            recycleFrozenContent(previous)
        }
        if (previous != null && previous !== content && !previous.bitmap.isRecycled) {
            previous.bitmap.recycle()
        }
    }

    private fun installZoomFrozenContent(
        requestId: Long,
        zoomSerial: Long,
        zoomLevel: FrozenZoomLevel,
        content: FrozenContent,
    ) {
        if (!isCurrentFreeze(requestId) ||
            zoomRequestSerial.get() != zoomSerial ||
            frozenZoomLevel != zoomLevel
        ) {
            recycleFrozenContent(content)
            return
        }
        val baseIsDisplayed = baseFrozenContent?.bitmap?.let(overlayView::isFrozenBackground) == true
        val previousZoom = zoomFrozenContent
        overlayView.setFrozenBackground(content.bitmap, recyclePrevious = !baseIsDisplayed)
        zoomFrozenContent = content
        frozenOcrResult = content.ocrResult
        translationStatus = "ZOOM ${zoomLevel.hudLabel}"
        refreshOverlayFromCache(requestMissing = true)
        if (previousZoom != null && previousZoom !== content && !previousZoom.bitmap.isRecycled) {
            previousZoom.bitmap.recycle()
        }
    }

    private fun recycleFrozenContent(content: FrozenContent?) {
        val bitmap = content?.bitmap ?: return
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    private fun trimRetainedMemory(level: Int) {
        overlayView.clearLayoutCache()
        translationCache.clear()
        translationRetryByKey.clear()
        val releasedBitmaps = releaseNonDisplayedFrozenBitmaps()
        if (isFrozen && baseFrozenContent != null) {
            lastOcrResult = null
            clearLiveTracking()
        } else if (level == ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN && !isFrozen) {
            lastOcrResult = null
            clearLiveTracking()
            overlayView.updateOcrResult(
                null,
                emptyList(),
                hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)),
            )
        }
        Log.i(
            TAG,
            "memoryTrim level=$level releasedBitmaps=$releasedBitmaps frozen=$isFrozen",
        )
    }

    private fun releaseNonDisplayedFrozenBitmaps(): Int {
        var released = 0
        baseFrozenContent?.let { base ->
            if (!overlayView.isFrozenBackground(base.bitmap)) {
                recycleFrozenContent(base)
                baseFrozenContent = null
                released += 1
            }
        }
        zoomFrozenContent?.let { zoom ->
            if (!overlayView.isFrozenBackground(zoom.bitmap)) {
                recycleFrozenContent(zoom)
                zoomFrozenContent = null
                released += 1
            }
        }
        return released
    }

    private fun copyAsRgb565AndRecycleSource(source: Bitmap): Bitmap? {
        if (source.config == Bitmap.Config.RGB_565) return source
        val rgb565 = runCatching { source.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
        if (!source.isRecycled) source.recycle()
        return rgb565
    }

    private fun copyFrozenCaptureJpeg(imageProxy: ImageProxy): FrozenCaptureJpeg {
        val buffer = imageProxy.planes.firstOrNull()?.buffer ?: error("missing JPEG plane")
        val bytesBuffer = buffer.asReadOnlyBuffer().apply { rewind() }
        val bytes = ByteArray(bytesBuffer.remaining())
        bytesBuffer.get(bytes)
        return FrozenCaptureJpeg(
            bytes = bytes,
            reportedRotation = imageProxy.imageInfo.rotationDegrees,
        )
    }

    @Suppress("DEPRECATION")
    private fun decodeFrozenCaptureBitmap(
        capture: FrozenCaptureJpeg,
        liveZoomLevel: LiveZoomLevel,
    ): DecodedFrozenCapture {
        val bytes = capture.bytes
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "JPEG bounds decode failed" }
        val reportedRotation = capture.reportedRotation
        // The glasses HAL sometimes pre-rotates JPEG pixels while CameraX still reports a
        // rotation (same trap as the analysis stream). EXIF describes the actual pixel
        // data, so trust it when present and fall back to the reported value otherwise.
        val exifRotation = runCatching {
            val exif = ExifInterface(java.io.ByteArrayInputStream(bytes))
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED)) {
                ExifInterface.ORIENTATION_NORMAL -> 0
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> null
            }
        }.getOrNull()
        val appliedRotation = exifRotation ?: reportedRotation
        val normalizedRotation = ((appliedRotation % 360) + 360) % 360
        val rawCrop = FrozenZoomPolicy.centerCropInRawCoordinates(
            rawWidth = bounds.outWidth,
            rawHeight = bounds.outHeight,
            rotationDegrees = normalizedRotation,
            zoomScale = liveZoomLevel.scale,
        )
        val decodeSampleSize = frozenDecodeSampleSize(rawCrop.width, rawCrop.height)
        val decoder = BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)
            ?: error("JPEG region decoder unavailable")
        val preferredBitmap = try {
            decoder.decodeRegion(
                Rect(rawCrop.left, rawCrop.top, rawCrop.right, rawCrop.bottom),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = decodeSampleSize
                },
            ) ?: error("JPEG decode failed")
        } finally {
            decoder.recycle()
        }
        val decoded = copyAsRgb565AndRecycleSource(preferredBitmap)
            ?: error("JPEG RGB_565 conversion failed")
        val decodedWidth = decoded.width
        val decodedHeight = decoded.height
        val rotatedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) decodedHeight else decodedWidth
        val rotatedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) decodedWidth else decodedHeight
        val rotatedLongEdge = maxOf(rotatedWidth, rotatedHeight)
        val scale = if (rotatedLongEdge > MAX_FROZEN_OCR_BITMAP_EDGE) {
            MAX_FROZEN_OCR_BITMAP_EDGE.toFloat() / rotatedLongEdge.toFloat()
        } else {
            1f
        }
        val transformed = try {
            if (normalizedRotation == 0 && scale >= 1f) {
                decoded
            } else {
                val matrix = Matrix().apply {
                    postRotate(normalizedRotation.toFloat())
                    if (scale < 1f) postScale(scale, scale)
                }
                Bitmap.createBitmap(decoded, 0, 0, decodedWidth, decodedHeight, matrix, true)
            }
        } catch (cause: Throwable) {
            if (!decoded.isRecycled) decoded.recycle()
            throw cause
        }
        if (transformed !== decoded) decoded.recycle()
        Log.i(
            TAG,
            "freezeCaptureDecoded rawCrop=${rawCrop.width}x${rawCrop.height} " +
                "width=$decodedWidth height=$decodedHeight liveZoom=${liveZoomLevel.hudLabel} " +
                "outputWidth=${transformed.width} outputHeight=${transformed.height} " +
                "reportedRotation=$reportedRotation exifRotation=$exifRotation " +
                "appliedRotation=$appliedRotation scale=$scale sample=$decodeSampleSize config=${transformed.config}",
        )
        return DecodedFrozenCapture(
            bitmap = transformed,
            jpegBytes = bytes,
            appliedRotation = normalizedRotation,
        )
    }

    private fun frozenDecodeSampleSize(width: Int, height: Int): Int {
        val longEdge = maxOf(width, height)
        var sampleSize = 1
        while (longEdge / sampleSize > MAX_FROZEN_DECODE_LONG_EDGE && sampleSize <= Int.MAX_VALUE / 2) {
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun frozenBitmapToViewMatrix(bitmap: Bitmap): Matrix {
        val viewWidth = overlayView.width.takeIf { it > 0 }
            ?: previewView.width.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val viewHeight = overlayView.height.takeIf { it > 0 }
            ?: previewView.height.takeIf { it > 0 }
            ?: resources.displayMetrics.heightPixels
        return fillCenterMatrix(bitmap.width, bitmap.height, viewWidth, viewHeight)
    }

    private fun fillCenterMatrix(sourceWidth: Int, sourceHeight: Int, viewWidth: Int, viewHeight: Int): Matrix {
        val safeSourceWidth = maxOf(1, sourceWidth)
        val safeSourceHeight = maxOf(1, sourceHeight)
        val safeViewWidth = maxOf(1, viewWidth)
        val safeViewHeight = maxOf(1, viewHeight)
        val scale = maxOf(
            safeViewWidth.toFloat() / safeSourceWidth.toFloat(),
            safeViewHeight.toFloat() / safeSourceHeight.toFloat(),
        )
        val dx = (safeViewWidth - safeSourceWidth * scale) * 0.5f
        val dy = (safeViewHeight - safeSourceHeight * scale) * 0.5f
        return Matrix().apply {
            postScale(scale, scale)
            postTranslate(dx, dy)
        }
    }

    private fun isCurrentFreeze(requestId: Long): Boolean =
        isFrozen && freezeRequestSerial.get() == requestId && !isFinishing && !isDestroyed

    private fun phaseMs(startMs: Long, endMs: Long): Long =
        if (startMs >= 0L && endMs >= startMs) endMs - startMs else -1L

    private fun Int?.isReadyAeState(): Boolean =
        this == CaptureResult.CONTROL_AE_STATE_CONVERGED ||
            this == CaptureResult.CONTROL_AE_STATE_LOCKED ||
            this == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED

    private fun aeStateLabel(state: Int?): String = when (state) {
        null -> "unreported"
        CaptureResult.CONTROL_AE_STATE_INACTIVE -> "inactive"
        CaptureResult.CONTROL_AE_STATE_SEARCHING -> "searching"
        CaptureResult.CONTROL_AE_STATE_CONVERGED -> "converged"
        CaptureResult.CONTROL_AE_STATE_LOCKED -> "locked"
        CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED -> "flash_required"
        CaptureResult.CONTROL_AE_STATE_PRECAPTURE -> "precapture"
        else -> "unknown_$state"
    }

    private fun stepZoom(zoomIn: Boolean) {
        if (isFrozen) {
            stepFrozenZoom(zoomIn)
            return
        }
        val nextLevel = if (zoomIn) {
            LiveZoomPolicy.zoomIn(liveZoomLevel)
        } else {
            LiveZoomPolicy.zoomOut(liveZoomLevel)
        }
        if (nextLevel == liveZoomLevel) {
            translationStatus = "ZOOM ${nextLevel.hudLabel}"
            overlayView.showModeFlash("ZOOM ${nextLevel.hudLabel}")
            refreshHud()
            return
        }
        liveZoomLevel = nextLevel
        applyPreviewScaleForCurrentState()
        resetAnalyzerCadence()
        clearRollingStats()
        clearLiveTracking()
        lastOcrResult = null
        translationStatus = "ZOOM ${nextLevel.hudLabel}"
        overlayView.showModeFlash("ZOOM ${nextLevel.hudLabel}")
        Log.i(TAG, "liveZoom level=${nextLevel.hudLabel}")
        refreshOverlayFromCache()
    }

    private fun applyPreviewScaleForCurrentState() {
        val scale = if (isFrozen) 1f else liveZoomLevel.scale
        if (previewView.width == 0 || previewView.height == 0) {
            previewView.post {
                if (previewView.width > 0 && previewView.height > 0) {
                    previewView.pivotX = previewView.width / 2f
                    previewView.pivotY = previewView.height / 2f
                    previewView.scaleX = if (isFrozen) 1f else liveZoomLevel.scale
                    previewView.scaleY = if (isFrozen) 1f else liveZoomLevel.scale
                }
            }
            return
        }
        previewView.pivotX = previewView.width / 2f
        previewView.pivotY = previewView.height / 2f
        previewView.scaleX = scale
        previewView.scaleY = scale
    }

    private fun stepFrozenZoom(zoomIn: Boolean) {
        val requestId = freezeRequestSerial.get()
        if (!isCurrentFreeze(requestId)) return
        val currentLevel = queuedFrozenZoomLevel ?: frozenZoomLevel
        val nextLevel = if (zoomIn) {
            FrozenZoomPolicy.zoomIn(currentLevel)
        } else {
            FrozenZoomPolicy.zoomOut(currentLevel)
        }
        if (nextLevel == currentLevel) {
            showFrozenZoomNotice("ZOOM ${currentLevel.hudLabel}")
            return
        }
        if (nextLevel == FrozenZoomLevel.ONE) {
            queuedFrozenZoomLevel = null
            restoreOneXFrozenContent(requestId)
            return
        }
        if (FrozenZoomPolicy.shouldQueueUntilHdBaseReady(
                hasHdJpeg = frozenJpegBytes != null,
                hdBaseOcrInFlight = hdFrozenOcrInFlight,
            )
        ) {
            // HD refine still in flight: queue instead of no-op so the swipe is never lost.
            queuedFrozenZoomLevel = nextLevel
            showFrozenZoomNotice("ZOOM ${nextLevel.hudLabel} WAITING HD")
            val reason = if (hdFrozenOcrInFlight) "hd_ocr" else "no_jpeg"
            Log.i(TAG, "frozenZoomQueued level=${nextLevel.hudLabel} reason=$reason")
            return
        }
        queuedFrozenZoomLevel = null
        applyFrozenZoom(requestId, nextLevel)
    }

    private fun applyFrozenZoom(requestId: Long, nextLevel: FrozenZoomLevel) {
        if (hdFrozenOcrInFlight) {
            queuedFrozenZoomLevel = nextLevel
            showFrozenZoomNotice("ZOOM ${nextLevel.hudLabel} WAITING HD")
            return
        }
        val previousLevel = frozenZoomLevel
        val jpegBytes = frozenJpegBytes
        if (jpegBytes == null) {
            showFrozenZoomNotice("ZOOM NEEDS HD")
            return
        }
        if (baseFrozenContent == null) {
            showFrozenZoomNotice("ZOOM NOT READY")
            return
        }

        val zoomSerial = zoomRequestSerial.incrementAndGet()
        frozenZoomLevel = nextLevel
        clearFrozenZoomNotice()
        translationStatus = "ZOOM ${nextLevel.hudLabel}"
        refreshHud()
        cameraExecutor.execute {
            val bitmap = runCatching {
                decodeFrozenZoomBitmap(jpegBytes, frozenJpegRotationDegrees, nextLevel)
            }.onFailure {
                Log.w(TAG, "frozenZoomDecodeFailure level=${nextLevel.hudLabel}", it)
            }.getOrNull()
            mainHandler.post {
                if (!isCurrentFreeze(requestId) ||
                    zoomRequestSerial.get() != zoomSerial ||
                    frozenZoomLevel != nextLevel
                ) {
                    bitmap?.recycle()
                    return@post
                }
                if (bitmap == null) {
                    frozenZoomLevel = previousLevel
                    showFrozenZoomNotice("ZOOM FAILED")
                    return@post
                }
                val mode = baseFrozenContent?.ocrResult?.mode ?: frozenSelectedScript
                runFrozenBitmapOcr(
                    requestId = requestId,
                    mode = mode,
                    bitmap = bitmap,
                    sourceStatus = "ZOOM ${nextLevel.hudLabel}",
                    previous = frozenOcrResult ?: baseFrozenContent?.ocrResult,
                    onReady = { content ->
                        installZoomFrozenContent(requestId, zoomSerial, nextLevel, content)
                        Log.i(
                            TAG,
                            "frozenZoomOcrSuccess level=${nextLevel.hudLabel} blocks=${content.ocrResult.blocks.size}",
                        )
                    },
                    onFailure = {
                        if (zoomRequestSerial.get() == zoomSerial && isCurrentFreeze(requestId)) {
                            frozenZoomLevel = previousLevel
                            showFrozenZoomNotice("ZOOM OCR FAILED")
                        }
                    },
                )
            }
        }
    }

    private fun restoreOneXFrozenContent(requestId: Long) {
        val base = baseFrozenContent
        if (base == null || base.bitmap.isRecycled) {
            showFrozenZoomNotice("1.0x NOT READY")
            return
        }
        zoomRequestSerial.incrementAndGet()
        frozenZoomLevel = FrozenZoomLevel.ONE
        clearFrozenZoomNotice()
        overlayView.setFrozenBackground(base.bitmap)
        recycleFrozenContent(zoomFrozenContent)
        zoomFrozenContent = null
        frozenOcrResult = base.ocrResult
        frozenSourceStatus = base.sourceStatus
        translationStatus = base.sourceStatus
        refreshOverlayFromCache(requestMissing = true)
        Log.i(TAG, "frozenZoomRestored level=1.0x source=${base.sourceStatus} id=$requestId")
    }

    @Suppress("DEPRECATION")
    private fun decodeFrozenZoomBitmap(
        jpegBytes: ByteArray,
        rotationDegrees: Int,
        zoomLevel: FrozenZoomLevel,
    ): Bitmap {
        val decoder = BitmapRegionDecoder.newInstance(jpegBytes, 0, jpegBytes.size, false)
            ?: error("JPEG region decoder unavailable")
        val crop = try {
            FrozenZoomPolicy.centerCropInRawCoordinates(
                rawWidth = decoder.width,
                rawHeight = decoder.height,
                rotationDegrees = rotationDegrees,
                zoomScale = frozenBaseLiveZoomLevel.scale * zoomLevel.scale,
            )
        } catch (cause: Throwable) {
            decoder.recycle()
            throw cause
        }
        val preferredBitmap = try {
            decoder.decodeRegion(
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.RGB_565
                    inSampleSize = 1
                },
            ) ?: error("JPEG region decode failed")
        } finally {
            decoder.recycle()
        }
        val decoded = copyAsRgb565AndRecycleSource(preferredBitmap)
            ?: error("JPEG region RGB_565 conversion failed")
        val transformed = rotateBitmap(decoded, rotationDegrees)
        Log.i(
            TAG,
            "frozenZoomDecoded level=${zoomLevel.hudLabel} rawCrop=${crop.width}x${crop.height} " +
                "output=${transformed.width}x${transformed.height} rotation=$rotationDegrees " +
                    "sample=1 config=${transformed.config}",
        )
        return transformed
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val transformed = try {
            Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) },
                true,
            )
        } catch (cause: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw cause
        }
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private fun cropBitmapToLiveZoom(bitmap: Bitmap, zoomLevel: LiveZoomLevel): Bitmap {
        if (zoomLevel == LiveZoomLevel.ONE) return bitmap
        val crop = LiveZoomPolicy.centerCrop(bitmap.width, bitmap.height, zoomLevel)
        val cropped = try {
            Bitmap.createBitmap(bitmap, crop.left, crop.top, crop.width, crop.height)
        } catch (cause: Throwable) {
            if (!bitmap.isRecycled) bitmap.recycle()
            throw cause
        }
        if (cropped !== bitmap && !bitmap.isRecycled) bitmap.recycle()
        return cropped
    }

    private fun showFrozenZoomNotice(message: String) {
        clearFrozenZoomNotice()
        val requestId = freezeRequestSerial.get()
        frozenZoomNotice = message
        refreshHud()
        val timeout = Runnable {
            frozenZoomNoticeTimeout = null
            if (isCurrentFreeze(requestId)) {
                frozenZoomNotice = null
                refreshHud()
            }
        }
        frozenZoomNoticeTimeout = timeout
        mainHandler.postDelayed(timeout, FROZEN_ZOOM_NOTICE_MS)
    }

    private fun clearFrozenZoomNotice() {
        frozenZoomNoticeTimeout?.let(mainHandler::removeCallbacks)
        frozenZoomNoticeTimeout = null
        frozenZoomNotice = null
    }

    private fun processOcrResult(
        mode: OcrScript,
        transformMatrix: Matrix?,
        blocks: List<OcrSourceBlock>,
        latencyMs: Long,
    ) {
        if (!isActivityResumed || isFrozen || isFinishing || isDestroyed) return
        val nowMs = SystemClock.elapsedRealtime()
        val metrics = recordOcrCompletion(latencyMs, nowMs)
        waitingForPreviewTransform = transformMatrix == null
        if (translationStatus == OCR_RETRY_STATUS) {
            translationStatus = "LIVE ${displayModeLabel(mode)}"
        }

        if (lastLiveMode != mode) {
            clearLiveTracking()
            lastLiveMode = mode
        }

        val previous = lastOcrResult
        val carriedTranslations = carriedTranslationsFor(mode, blocks, previous)
        lastOcrResult = LastOcrResult(
            mode = mode,
            transformMatrix = transformMatrix?.let(::Matrix),
            blocks = blocks,
            metrics = metrics,
            completedAtMs = nowMs,
            carriedTranslations = carriedTranslations,
        )

        val mappedFrame = mapLiveFrameToParagraphs(blocks, transformMatrix)
        val reconciliation = LiveParagraphReconciler.reconcile(
            state = liveParagraphReconciliationState,
            observations = mappedFrame.paragraphs.map { it.toLiveParagraphObservation() },
            nowMs = nowMs,
        )
        liveParagraphReconciliationState = reconciliation.state
        visibleLiveParagraphAnchors = reconciliation.visibleAnchors
        val misses = linkedMapOf<CacheKey, String>()
        val overlayFrame = overlayBlocksForTracked(mode, misses)
        overlayView.updateOcrResult(
            transformMatrix = Matrix(),
            blocks = overlayFrame.blocks,
            hudState = hudState(metrics),
            onLayoutStats = { layoutStats ->
                Log.i(
                    TAG,
                    String.format(
                        Locale.US,
                        "mode=%s latencyMs=%d avgMs=%d hz=%.1f blocks=%d lines=%d matched=%d revived=%d " +
                            "paragraphAnchors=%d visible=%d new=%d dropped=%d pendingParagraphs=%d " +
                            "suppressedPanels=%d truncatedPanels=%d transformReady=%s",
                        mode.name,
                        latencyMs,
                        metrics.avgLatencyMs,
                        metrics.hz,
                        blocks.size,
                        mappedFrame.lineCount,
                        reconciliation.stats.matchedCount,
                        reconciliation.stats.revivedCount,
                        reconciliation.stats.paragraphAnchorCount,
                        overlayFrame.blocks.size,
                        reconciliation.stats.newCount,
                        reconciliation.stats.droppedCount,
                        overlayFrame.pendingTranslationParagraphs,
                        layoutStats.suppressedPanels,
                        layoutStats.truncatedPanels,
                        transformMatrix != null,
                    ),
                )
            },
        )
        requestMissingTranslations(mode, misses)
    }

    private fun mapLiveLineToView(line: OcrSourceLine, transformMatrix: Matrix?): ViewOcrBlock? {
        val matrix = transformMatrix ?: return null
        val mapped = RectF(line.bounds)
        matrix.mapRect(mapped)
        if (mapped.width() < MIN_TRACKED_RECT_SIZE_PX || mapped.height() < MIN_TRACKED_RECT_SIZE_PX) {
            return null
        }
        val rect = Rect(
            mapped.left.roundToInt(),
            mapped.top.roundToInt(),
            mapped.right.roundToInt(),
            mapped.bottom.roundToInt(),
        )
        if (rect.width() < MIN_TRACKED_RECT_SIZE_PX || rect.height() < MIN_TRACKED_RECT_SIZE_PX) return null
        return ViewOcrBlock(
            normalized = line.normalized,
            displayRect = rect,
        )
    }

    private fun mapLiveFrameToParagraphs(
        blocks: List<OcrSourceBlock>,
        transformMatrix: Matrix?,
    ): MappedLiveFrame {
        var lineCount = 0
        val mappedBlocks = blocks.mapNotNull { block ->
            val mappedLines = block.lines.mapNotNull { line ->
                mapLiveLineToView(line, transformMatrix)?.let { mapped ->
                    lineCount += 1
                    LiveFrameParagraphLine(
                        source = mapped.normalized,
                        bounds = mapped.displayRect.toFrozenLayoutRect(),
                    )
                }
            }
            mappedLines.takeIf { it.isNotEmpty() }?.let(::LiveFrameParagraphBlock)
        }
        return MappedLiveFrame(
            lineCount = lineCount,
            paragraphs = segmentLiveFrameParagraphs(mappedBlocks),
        )
    }

    private fun LiveFrameParagraph.toLiveParagraphObservation(): LiveParagraphObservation =
        LiveParagraphObservation(
            sourceText = source,
            bounds = bounds.toLiveParagraphRect(),
            memberBounds = lineBounds.map { it.toLiveParagraphRect() },
            columnIndex = columnIndex,
            gapBelow = gapBelow,
        )

    private fun overlayBlocksForTracked(
        mode: OcrScript,
        misses: MutableMap<CacheKey, String>? = null,
    ): LiveOverlayFrame {
        var pendingTranslationParagraphs = 0
        val overlayBlocks = visibleLiveParagraphAnchors.map { anchor ->
            val key = CacheKey(anchor.sourceText, mode, LensWireContract.DEFAULT_TARGET_LANG)
            // Committed text stays displayable and requestable while a candidate debounces:
            // OCR that alternates between two readings keeps candidateSourceText non-null
            // forever, and gating on it starved every request (field 2026-07-11, 7-byte batches).
            val cached = translationCache[key]
            if (cached == null) pendingTranslationParagraphs += 1
            if (cached == null && !inFlightByKey.containsKey(key)) {
                misses?.putIfAbsent(key, anchor.sourceText)
            }
            val displayTranslation = cached
            LensOverlayBlock(
                source = anchor.sourceText,
                normalized = LensWireContract.normalizeText(anchor.sourceText),
                bounds = anchor.bounds.toAndroidRect(),
                lineBounds = anchor.memberBounds.map { it.toAndroidRect() },
                translation = displayTranslation?.dst,
                sourceLang = displayTranslation?.srcLang,
                stableId = anchor.stableId,
                gapBelow = anchor.gapBelow,
                columnIndex = anchor.columnIndex,
            )
        }
        return LiveOverlayFrame(
            blocks = overlayBlocks,
            pendingTranslationParagraphs = pendingTranslationParagraphs,
        )
    }

    private fun clearLiveTracking() {
        liveParagraphReconciliationState = LiveParagraphReconciliationState()
        visibleLiveParagraphAnchors = emptyList()
        lastLiveMode = null
    }

    private fun overlayBlocksFor(
        mode: OcrScript,
        blocks: List<OcrSourceBlock>,
        misses: MutableMap<CacheKey, String>? = null,
        carriedTranslations: Map<String, CachedTranslation> = emptyMap(),
    ): List<LensOverlayBlock> =
        blocks.mapIndexed { index, block ->
            val key = CacheKey(block.normalized, mode, LensWireContract.DEFAULT_TARGET_LANG)
            val cached = translationCache[key]
            val displayTranslation = cached ?: carriedTranslations[block.normalized]
            if (cached == null && !inFlightByKey.containsKey(key)) {
                misses?.putIfAbsent(key, block.normalized)
            }
            LensOverlayBlock(
                source = block.source,
                normalized = block.normalized,
                bounds = Rect(block.bounds),
                lineBounds = block.lines.map { Rect(it.bounds) },
                translation = displayTranslation?.dst,
                sourceLang = displayTranslation?.srcLang,
                stableId = index.toLong(),
                gapBelow = block.gapBelow,
                columnIndex = block.columnIndex,
            )
        }

    private fun carriedTranslationsFor(
        mode: OcrScript,
        blocks: List<OcrSourceBlock>,
        previous: LastOcrResult?,
    ): Map<String, CachedTranslation> {
        if (blocks.isEmpty() || previous == null || previous.mode != mode || previous.blocks.isEmpty()) {
            return emptyMap()
        }

        val carried = linkedMapOf<String, CachedTranslation>()
        for (block in blocks) {
            val key = CacheKey(block.normalized, mode, LensWireContract.DEFAULT_TARGET_LANG)
            if (translationCache[key] != null) continue

            var bestIou = 0f
            var bestTranslation: CachedTranslation? = null
            for (candidate in previous.blocks) {
                if (!normalizedTextIsSimilar(block.normalized, candidate.normalized)) continue
                val iou = rectIou(block.bounds, candidate.bounds)
                if (iou <= RECT_MATCH_IOU || iou <= bestIou) continue
                val candidateTranslation = translationForPreviousBlock(previous, candidate) ?: continue
                bestIou = iou
                bestTranslation = candidateTranslation
            }
            if (bestTranslation != null) carried[block.normalized] = bestTranslation
        }
        return carried
    }

    private fun translationForPreviousBlock(
        previous: LastOcrResult,
        block: OcrSourceBlock,
    ): CachedTranslation? {
        val key = CacheKey(block.normalized, previous.mode, LensWireContract.DEFAULT_TARGET_LANG)
        return translationCache[key] ?: previous.carriedTranslations[block.normalized]
    }

    private fun rectIou(a: Rect, b: Rect): Float {
        val left = maxOf(a.left, b.left)
        val top = maxOf(a.top, b.top)
        val right = minOf(a.right, b.right)
        val bottom = minOf(a.bottom, b.bottom)
        val intersectionWidth = maxOf(0, right - left)
        val intersectionHeight = maxOf(0, bottom - top)
        val intersection = intersectionWidth * intersectionHeight
        if (intersection <= 0) return 0f
        val union = a.width() * a.height() + b.width() * b.height() - intersection
        return if (union <= 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun normalizedTextIsSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        val maxLength = maxOf(a.length, b.length)
        if (maxLength == 0) return false
        val maxDistance = maxOf(1, minOf(4, maxLength / 4))
        if (kotlin.math.abs(a.length - b.length) > maxDistance) return false
        return boundedLevenshtein(a, b, maxDistance) <= maxDistance
    }

    private fun boundedLevenshtein(a: String, b: String, maxDistance: Int): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            var rowMin = current[0]
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(
                    previous[j] + 1,
                    current[j - 1] + 1,
                    previous[j - 1] + cost,
                )
                rowMin = minOf(rowMin, current[j])
            }
            if (rowMin > maxDistance) return maxDistance + 1
            val swap = previous
            previous = current
            current = swap
        }
        return previous[b.length]
    }

    private fun LastOcrResult.snapshot(): LastOcrResult =
        copy(
            transformMatrix = transformMatrix?.let(::Matrix),
            blocks = blocks.map { block ->
                block.copy(
                    bounds = Rect(block.bounds),
                    lines = block.lines.map { line -> line.copy(bounds = Rect(line.bounds)) },
                )
            },
            carriedTranslations = carriedTranslations.toMap(),
        )

    private fun LastOcrResult.snapshotForFreeze(): LastOcrResult {
        val snapshot = snapshot()
        return snapshot.copy(blocks = segmentFrozenParagraphBlocks(snapshot.blocks))
    }

    private fun segmentFrozenParagraphBlocks(blocks: List<OcrSourceBlock>): List<OcrSourceBlock> =
        segmentFrozenParagraphs(
            blocks.map { block ->
                FrozenLayoutBlock(
                    lines = block.lines.map { line ->
                        FrozenLayoutLine(
                            text = line.source,
                            bounds = line.bounds.toFrozenLayoutRect(),
                        )
                    },
                )
            },
        ).map { paragraph ->
            val lines = paragraph.lines.map { line ->
                val normalized = LensWireContract.normalizeText(line.text)
                OcrSourceLine(
                    source = line.text,
                    normalized = normalized,
                    bounds = line.bounds.toAndroidRect(),
                )
            }
            OcrSourceBlock(
                source = paragraph.source,
                normalized = LensWireContract.normalizeText(paragraph.source),
                bounds = paragraph.bounds.toAndroidRect(),
                lines = lines,
                gapBelow = paragraph.gapBelow,
                columnIndex = paragraph.columnIndex,
            )
        }

    private fun Rect.toFrozenLayoutRect(): FrozenLayoutRect =
        FrozenLayoutRect(left = left, top = top, right = right, bottom = bottom)

    private fun FrozenLayoutRect.toLiveParagraphRect(): LiveParagraphRect =
        LiveParagraphRect(
            left = left.toFloat(),
            top = top.toFloat(),
            right = right.toFloat(),
            bottom = bottom.toFloat(),
        )

    private fun LiveParagraphRect.toAndroidRect(): Rect =
        Rect(left.roundToInt(), top.roundToInt(), right.roundToInt(), bottom.roundToInt())

    private fun FrozenLayoutRect.toAndroidRect(): Rect =
        Rect(left, top, right, bottom)

    private fun filterFrozenBlocks(mode: OcrScript, blocks: List<OcrSourceBlock>): List<OcrSourceBlock> {
        if (blocks.isEmpty()) {
            Log.i(TAG, "frozenFilter kept=0 dropped=0 reasons=[size=0,garbage=0]")
            return blocks
        }

        val dominantLineHeight = if (mode == OcrScript.LATIN) dominantFrozenLineHeight(blocks) else 0f
        var sizeDropped = 0
        var garbageDropped = 0
        val kept = blocks.filter { block ->
            val tooSmall = mode == OcrScript.LATIN &&
                dominantLineHeight > 0f &&
                frozenBlockLineHeight(block) < dominantLineHeight * FROZEN_MIN_LINE_HEIGHT_FRACTION
            val tooShort = compactTextLength(block.normalized) < FROZEN_MIN_TEXT_CHARS
            val tooGarbage = tooShort || (
                mode == OcrScript.LATIN &&
                    latinNoVowelWordFraction(block.normalized) > FROZEN_GARBAGE_WORD_FRACTION
                )

            when {
                tooSmall -> {
                    sizeDropped += 1
                    false
                }
                tooGarbage -> {
                    garbageDropped += 1
                    false
                }
                else -> true
            }
        }

        Log.i(
            TAG,
            "frozenFilter kept=${kept.size} dropped=${blocks.size - kept.size} " +
                "reasons=[size=$sizeDropped,garbage=$garbageDropped]",
        )
        return kept
    }

    private fun dominantFrozenLineHeight(blocks: List<OcrSourceBlock>): Float {
        val weightedHeights = blocks
            .map { block ->
                frozenBlockLineHeight(block) to compactTextLength(block.normalized).coerceAtLeast(1)
            }
            .filter { it.first > 0f }
            .sortedBy { it.first }
        if (weightedHeights.isEmpty()) return 0f

        val totalWeight = weightedHeights.sumOf { it.second }
        var cumulativeWeight = 0
        weightedHeights.forEach { (height, weight) ->
            cumulativeWeight += weight
            if (cumulativeWeight * 2 >= totalWeight) return height
        }
        return weightedHeights.last().first
    }

    private fun frozenBlockLineHeight(block: OcrSourceBlock): Float {
        val heights = block.lines.map { it.bounds.height().toFloat() }.filter { it > 0f }.sorted()
        if (heights.isEmpty()) return block.bounds.height().toFloat().coerceAtLeast(1f)
        val middle = heights.size / 2
        return if (heights.size % 2 == 0) {
            (heights[middle - 1] + heights[middle]) / 2f
        } else {
            heights[middle]
        }
    }

    private fun latinNoVowelWordFraction(text: String): Float {
        val words = latinAlphabeticWordRegex.findAll(text).map { it.value }.toList()
        if (words.isEmpty()) return 0f
        val noVowelCount = words.count { word -> !latinVowelRegex.containsMatchIn(word) }
        return noVowelCount.toFloat() / words.size.toFloat()
    }

    private fun compactTextLength(text: String): Int =
        text.count { !it.isWhitespace() }

    private fun extractBlocks(text: Text): List<OcrSourceBlock> =
        text.textBlocks.mapNotNull { block ->
            val box = block.boundingBox ?: return@mapNotNull null
            val source = block.text.trim()
            val normalized = LensWireContract.normalizeText(source)
            if (normalized.isBlank()) {
                null
            } else {
                val extractedLines = block.lines.mapNotNull { line ->
                    val lineBox = line.boundingBox ?: return@mapNotNull null
                    val lineSource = line.text.trim()
                    val lineNormalized = LensWireContract.normalizeText(lineSource)
                    if (lineNormalized.isBlank()) {
                        null
                    } else {
                        OcrSourceLine(
                            source = lineSource,
                            normalized = lineNormalized,
                            bounds = Rect(lineBox),
                        )
                    }
                }
                val lines = if (extractedLines.size == block.lines.size && extractedLines.isNotEmpty()) {
                    extractedLines.sortedWith(
                        compareBy<OcrSourceLine> { it.bounds.top }
                            .thenBy { it.bounds.left }
                            .thenBy { it.bounds.bottom }
                            .thenBy { it.bounds.right }
                            .thenBy { it.source },
                    )
                } else {
                    listOf(OcrSourceLine(source, normalized, Rect(box)))
                }
                OcrSourceBlock(
                    source = source,
                    normalized = normalized,
                    bounds = Rect(box),
                    lines = lines,
                )
            }
        }

    private fun requestMissingTranslations(mode: OcrScript, misses: Map<CacheKey, String>) {
        if (misses.isEmpty()) return
        if (!hasDataLink()) {
            translationStatus = "DATA LINK OFFLINE"
            refreshHud()
            return
        }
        val frozenRequest = isFrozen
        val nowMs = SystemClock.elapsedRealtime()
        if (!canStartLensTranslationRequest(
                dataLinkUp = hasDataLink(),
                pendingRequestCount = pendingRequests.size,
                nowMs = nowMs,
                retryNotBeforeMs = translationRetryNotBeforeMs,
            )
        ) {
            if (frozenRequest && pendingRequests.isEmpty() && nowMs < translationRetryNotBeforeMs) {
                scheduleFrozenBatchContinuation(
                    maxOf(FROZEN_BATCH_CONTINUATION_DELAY_MS, translationRetryNotBeforeMs - nowMs),
                )
            }
            return
        }

        val batch = selectLensTranslationBatch(
            misses
                .asSequence()
                .filter { (key, _) ->
                    val retry = translationRetryByKey[key]
                    retry == null || nowMs >= retry.retryNotBeforeMs
                }
                .filter { (key, _) -> !frozenRequest || key !in frozenAttemptedTranslationKeys }
                .map { (key, value) -> LensTranslationCandidate(key, value) }
                .asIterable(),
        )
        if (frozenRequest) {
            batch.skipped.forEach { frozenAttemptedTranslationKeys += it.key }
        }
        if (batch.selected.isEmpty()) {
            if (batch.skipped.isNotEmpty()) {
                translationStatus = "TEXT TOO LONG"
                refreshHud()
            }
            return
        }

        val id = UUID.randomUUID().toString()
        val strings = JSONArray()
        val keyBySource = linkedMapOf<String, CacheKey>()
        batch.selected.forEach { candidate ->
            strings.put(candidate.source)
            keyBySource[candidate.source] = candidate.key
            inFlightByKey[candidate.key] = id
            if (frozenRequest) frozenAttemptedTranslationKeys += candidate.key
        }

        val timeout = Runnable {
            if (pendingRequests.containsKey(id)) {
                handleTranslationTimeout(
                    id = id,
                    status = "TRANSLATE TIMEOUT",
                    backoffMs = TRANSLATE_RETRY_BACKOFF_MS,
                    origin = "glasses_deadline",
                )
            }
        }
        pendingRequests[id] = PendingRequest(
            keyBySource = keyBySource,
            sentAtMs = SystemClock.elapsedRealtime(),
            frozen = frozenRequest,
            timeout = timeout,
        )
        mainHandler.postDelayed(timeout, TRANSLATE_TIMEOUT_MS)

        val payload = JSONObject()
            .put("id", id)
            .put("targetLang", LensWireContract.DEFAULT_TARGET_LANG)
            .put("mode", mode.name)
            .put("strings", strings)
        busClient?.send(BusPaths.LENS_TRANSLATE_REQUEST, id, payload)
        translationStatus = "TRANSLATING ${strings.length()}"
        Log.i(
            TAG,
            "translateBatchSent id=$id stringCount=${strings.length()} " +
                "sourceBytes=${batch.selected.sumOf { it.source.toByteArray(Charsets.UTF_8).size }} " +
                "deferredCount=${batch.deferred.size} skippedCount=${batch.skipped.size} frozen=$frozenRequest",
        )
        refreshHud()
    }

    private fun refreshOverlayFromCache(requestMissing: Boolean = false) {
        if (!isFrozen) {
            val metrics = lastOcrResult?.metrics ?: OcrMetrics(avgLatencyMs = 0L, hz = 0.0)
            val misses = if (requestMissing) linkedMapOf<CacheKey, String>() else null
            val liveOverlayFrame = overlayBlocksForTracked(effectiveScript, misses)
            overlayView.updateOcrResult(
                transformMatrix = Matrix(),
                blocks = liveOverlayFrame.blocks,
                hudState = hudState(metrics),
            )
            if (misses != null) requestMissingTranslations(effectiveScript, misses)
            return
        }

        val last = if (isFrozen) frozenOcrResult else lastOcrResult
        if (last == null) {
            refreshHud()
            return
        }
        val misses = if (requestMissing) linkedMapOf<CacheKey, String>() else null
        overlayView.updateOcrResult(
            transformMatrix = last.transformMatrix,
            blocks = overlayBlocksFor(last.mode, last.blocks, misses, last.carriedTranslations),
            hudState = hudState(last.metrics),
            onLayoutStats = if (isFrozen) {
                { stats ->
                    if (stats.suppressedPanels > 0 || stats.truncatedPanels > 0) {
                        Log.i(
                            TAG,
                            "frozenLayout suppressedPanels=${stats.suppressedPanels} " +
                                "truncatedPanels=${stats.truncatedPanels}",
                        )
                    }
                }
            } else {
                null
            },
        )
        if (misses != null) requestMissingTranslations(last.mode, misses)
    }

    private fun refreshHud() {
        val metrics = (if (isFrozen) frozenOcrResult else lastOcrResult)?.metrics
            ?: OcrMetrics(avgLatencyMs = 0L, hz = 0.0)
        overlayView.updateHud(hudState(metrics))
    }

    private fun hudState(metrics: OcrMetrics): LensHudState =
        LensHudState(
            mode = hudModeLabel(),
            targetLang = LensWireContract.DEFAULT_TARGET_LANG,
            cacheEntries = translationCache.size,
            busLabel = if (hasDataLink()) "DATA LINK UP" else "DATA LINK OFFLINE",
            ocrHz = metrics.hz,
            status = hudStatus(),
            frozen = isFrozen,
            linkUp = hasDataLink(),
            engine = lastServingEngine,
            frozenSource = frozenSourceStatus?.let { status ->
                when {
                    status.startsWith("HD") -> "HD"
                    status.startsWith("FAST") -> "FAST"
                    else -> null
                }
            },
            zoomLabel = liveZoomLevel.takeIf { !isFrozen && it != LiveZoomLevel.ONE }?.hudLabel,
        )

    private fun hudStatus(): String {
        if (!isFrozen && waitingForPreviewTransform) return WAITING_PREVIEW_STATUS
        if (!isFrozen && visibleLiveTrackCount() > LIVE_DENSE_VISIBLE_TRACK_COUNT) return LIVE_DENSE_STATUS
        if (!isFrozen) return translationStatus
        frozenZoomNotice?.let { return it }
        if (translationStatus.startsWith("DOWNLOADING") ||
            translationStatus == "DATA LINK OFFLINE" ||
            translationStatus == "TRANSLATE ERROR" ||
            translationStatus.endsWith("TIMEOUT")
        ) {
            return translationStatus
        }
        if (frozenZoomLevel != FrozenZoomLevel.ONE) return "ZOOM ${frozenZoomLevel.hudLabel}"
        return frozenSourceStatus ?: translationStatus
    }

    private fun displayModeLabel(mode: OcrScript): String =
        when (mode) {
            OcrScript.LATIN -> "AUTO·ABC"
            OcrScript.JAPANESE -> "AUTO·JP"
        }

    private fun hudModeLabel(): String {
        val script = if (isFrozen) frozenOcrResult?.mode ?: frozenSelectedScript else effectiveScript
        return "${displayModeLabel(script)} ${liveZoomLevel.hudLabel}"
    }

    private fun liveStatusLabel(): String = "LIVE ${displayModeLabel(effectiveScript)} ${liveZoomLevel.hudLabel}"

    private fun visibleLiveTrackCount(): Int =
        visibleLiveParagraphAnchors.sumOf { it.memberBounds.size }

    private fun hasDataLink(): Boolean = isLensTranslationDataLinkUp(busState)

    private fun recordOcrCompletion(latencyMs: Long, nowMs: Long): OcrMetrics {
        synchronized(statsLock) {
            latencyWindow.addLast(latencyMs)
            while (latencyWindow.size > LATENCY_WINDOW_SIZE) latencyWindow.removeFirst()

            completionWindow.addLast(nowMs)
            while (completionWindow.isNotEmpty() && nowMs - completionWindow.peekFirst()!! > THROUGHPUT_WINDOW_MS) {
                completionWindow.removeFirst()
            }

            val avg = if (latencyWindow.isEmpty()) {
                0L
            } else {
                latencyWindow.sum().toDouble().div(latencyWindow.size).toLong()
            }
            val windowStart = maxOf(sessionStartMs, nowMs - THROUGHPUT_WINDOW_MS)
            val windowSeconds = maxOf(1_000L, nowMs - windowStart) / 1000.0
            return OcrMetrics(
                avgLatencyMs = avg,
                hz = completionWindow.size / windowSeconds,
            )
        }
    }

    private fun clearRollingStats() {
        synchronized(statsLock) {
            latencyWindow.clear()
            completionWindow.clear()
        }
    }

    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.systemBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        }
    }

    private fun analyzeFastFreezeFrame(imageProxy: ImageProxy, request: FastFreezeRequest) {
        if (imageProxy.image == null) {
            pendingFastFreeze.compareAndSet(null, request)
            imageProxy.close()
            return
        }
        cancelFastFreezeTimeout()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = runCatching {
            analysisImageToFrozenBitmap(imageProxy, rotationDegrees, request.liveZoomLevel)
        }.onFailure {
            Log.w(TAG, "freezeFastBitmapFailure id=${request.requestId}", it)
        }.getOrNull()
        if (bitmap == null) {
            imageProxy.close()
            mainHandler.post {
                completeFastFreezeOcrFailure(request, null, "YUV CONVERSION FAILED")
            }
            return
        }
        imageProxy.close()
        mainHandler.post {
            showFastFrozenBitmap(request, bitmap)
            runAutoFrozenRecognition(
                requestId = request.requestId,
                bitmap = bitmap,
                initialScript = request.mode,
                onSuccess = { recognition ->
                    completeFastFreezeOcr(
                        request = request,
                        bitmap = bitmap,
                        text = recognition.text,
                        script = recognition.script,
                        latencyMs = recognition.latencyMs,
                    )
                },
                onFailure = { cause ->
                    completeFastFreezeOcrFailure(request, bitmap, "OCR FAILED", cause)
                },
            )
        }
    }

    private fun analysisImageToFrozenBitmap(
        imageProxy: ImageProxy,
        rotationDegrees: Int,
        zoomLevel: LiveZoomLevel,
    ): Bitmap {
        val width = imageProxy.width
        val height = imageProxy.height
        val planes = imageProxy.planes
        require(planes.size >= 3) { "YUV image requires three planes" }
        val yBuffer = planes[0].buffer.asReadOnlyBuffer().apply { rewind() }
        val uBuffer = planes[1].buffer.asReadOnlyBuffer().apply { rewind() }
        val vBuffer = planes[2].buffer.asReadOnlyBuffer().apply { rewind() }
        val yRowStride = planes[0].rowStride
        val yPixelStride = planes[0].pixelStride
        val uRowStride = planes[1].rowStride
        val uPixelStride = planes[1].pixelStride
        val vRowStride = planes[2].rowStride
        val vPixelStride = planes[2].pixelStride
        val row = IntArray(width)
        val rawBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)

        for (y in 0 until height) {
            val yRow = y * yRowStride
            val uRow = (y / 2) * uRowStride
            val vRow = (y / 2) * vRowStride
            for (x in 0 until width) {
                val yValue = yBuffer.get(yRow + x * yPixelStride).toInt() and 0xff
                val uValue = uBuffer.get(uRow + (x / 2) * uPixelStride).toInt() and 0xff
                val vValue = vBuffer.get(vRow + (x / 2) * vPixelStride).toInt() and 0xff
                val c = (yValue - 16).coerceAtLeast(0)
                val d = uValue - 128
                val e = vValue - 128
                val red = ((298 * c + 409 * e + 128) shr 8).coerceIn(0, 255)
                val green = ((298 * c - 100 * d - 208 * e + 128) shr 8).coerceIn(0, 255)
                val blue = ((298 * c + 516 * d + 128) shr 8).coerceIn(0, 255)
                row[x] = Color.rgb(red, green, blue)
            }
            rawBitmap.setPixels(row, 0, width, 0, y, width, 1)
        }

        val rotated = rotateBitmap(rawBitmap, rotationDegrees)
        val rgb565 = copyAsRgb565AndRecycleSource(rotated)
            ?: error("analysis RGB_565 conversion failed")
        val cropped = cropBitmapToLiveZoom(rgb565, zoomLevel)
        Log.i(
            TAG,
            "analysisBitmap input=${width}x$height output=${cropped.width}x${cropped.height} " +
                "liveZoom=${zoomLevel.hudLabel} " +
                "rotation=$rotationDegrees yStride=$yRowStride uvStride=$uRowStride/$vRowStride " +
                "uvPixelStride=$uPixelStride/$vPixelStride",
        )
        return cropped
    }

    private fun analysisImageToNv21Crop(
        imageProxy: ImageProxy,
        rawCrop: LiveCropRect,
        destination: ByteArray,
    ): Nv21AssemblyResult {
        val planes = imageProxy.planes
        require(planes.size >= 3) { "YUV image requires three planes" }
        return Nv21CropAssembler.copy(
            yPlane = YuvPlaneData(planes[0].buffer, planes[0].rowStride, planes[0].pixelStride),
            uPlane = YuvPlaneData(planes[1].buffer, planes[1].rowStride, planes[1].pixelStride),
            vPlane = YuvPlaneData(planes[2].buffer, planes[2].rowStride, planes[2].pixelStride),
            crop = rawCrop,
            destination = destination,
        )
    }

    @TransformExperimental
    private inner class TextAnalyzer : ImageAnalysis.Analyzer {
        private var nv21Scratch = ByteArray(0)

        override fun analyze(imageProxy: ImageProxy) {
            val fastFreezeRequest = pendingFastFreeze.getAndSet(null)
            if (fastFreezeRequest != null && isCurrentFreeze(fastFreezeRequest.requestId)) {
                analyzeFastFreezeFrame(imageProxy, fastFreezeRequest)
                return
            }
            if (isFrozen) {
                imageProxy.close()
                return
            }
            totalFramesAnalyzed.incrementAndGet()
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return
            }
            if (!analyzerBusy.compareAndSet(false, true)) {
                imageProxy.close()
                return
            }
            val generation = analyzerGeneration.get()
            val processStartMs = SystemClock.elapsedRealtime()
            if (!tryAcquireAnalyzerCadence(processStartMs)) {
                if (analyzerGeneration.get() == generation) analyzerBusy.set(false)
                imageProxy.close()
                return
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val sourceTransform = ImageProxyTransformFactory().apply {
                isUsingRotationDegrees = true
            }.getOutputTransform(imageProxy)
            val zoomSnapshot = liveZoomLevel
            val normalizedRotation = ((rotationDegrees % 360) + 360) % 360
            val orientedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) {
                imageProxy.height
            } else {
                imageProxy.width
            }
            val orientedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) {
                imageProxy.width
            } else {
                imageProxy.height
            }
            val requestedCrop = LiveZoomPolicy.centerCrop(orientedWidth, orientedHeight, zoomSnapshot)
            val nv21Crop = if (zoomSnapshot == LiveZoomLevel.ONE) {
                null
            } else {
                LiveZoomPolicy.nv21Crop(
                    rawWidth = imageProxy.width,
                    rawHeight = imageProxy.height,
                    rotationDegrees = rotationDegrees,
                    level = zoomSnapshot,
                )
            }
            val crop = nv21Crop?.oriented ?: requestedCrop
            val scriptPlan = synchronized(autoScriptLock) {
                AutoScriptPolicy.planLiveFrame(autoScriptState, processStartMs).also {
                    autoScriptState = it.nextState
                }
            }
            val modeSnapshot = scriptPlan.script
            val recognizer = recognizerFor(modeSnapshot)
            val inputNv21 = if (nv21Crop == null) {
                null
            } else {
                val cropStartMs = SystemClock.elapsedRealtime()
                val requiredBytes = nv21Crop.raw.width * nv21Crop.raw.height * 3 / 2
                if (nv21Scratch.size < requiredBytes) nv21Scratch = ByteArray(requiredBytes)
                runCatching {
                    analysisImageToNv21Crop(imageProxy, nv21Crop.raw, nv21Scratch)
                    nv21Scratch
                }
                    .onFailure { Log.w(TAG, "liveZoomCropFailure level=${zoomSnapshot.hudLabel}", it) }
                    .also {
                        Log.i(
                            TAG,
                            "nv21Crop input=${imageProxy.width}x${imageProxy.height} " +
                                "crop=${nv21Crop.raw.width}x${nv21Crop.raw.height} " +
                                "ms=${SystemClock.elapsedRealtime() - cropStartMs}",
                        )
                    }
                    .getOrNull()
            }
            val mainExecutor = ContextCompat.getMainExecutor(this@LensActivity)
            if (nv21Crop != null && inputNv21 == null) {
                if (analyzerGeneration.get() == generation) analyzerBusy.set(false)
                imageProxy.close()
                return
            }
            val inputImage = if (inputNv21 != null && nv21Crop != null) {
                InputImage.fromByteArray(
                    inputNv21,
                    nv21Crop.raw.width,
                    nv21Crop.raw.height,
                    rotationDegrees,
                    InputImage.IMAGE_FORMAT_NV21,
                )
            } else {
                InputImage.fromMediaImage(mediaImage, rotationDegrees)
            }

            val task = runCatching { recognizer.process(inputImage) }.getOrElse {
                Log.w(TAG, "ocrStartFailure mode=${modeSnapshot.name}", it)
                if (analyzerGeneration.get() == generation) analyzerBusy.set(false)
                imageProxy.close()
                mainExecutor.execute {
                    if (analyzerGeneration.get() == generation &&
                        isActivityResumed && !isFrozen && !isFinishing && !isDestroyed
                    ) {
                        translationStatus = OCR_RETRY_STATUS
                        refreshHud()
                    }
                }
                return
            }

            task
                .addOnSuccessListener(mainExecutor) { text ->
                    if (analyzerGeneration.get() != generation || liveZoomLevel != zoomSnapshot) {
                        return@addOnSuccessListener
                    }
                    val latencyMs = SystemClock.elapsedRealtime() - processStartMs
                    val scriptObservation = synchronized(autoScriptLock) {
                        AutoScriptPolicy.observeLiveResult(
                            state = autoScriptState,
                            nowMs = SystemClock.elapsedRealtime(),
                            script = modeSnapshot,
                            isProbe = scriptPlan.isProbe,
                            text = text.text,
                        ).also {
                            autoScriptState = it.nextState
                            effectiveScript = it.effectiveScript
                        }
                    }
                    if (scriptObservation.switched) {
                        Log.i(TAG, "autoScriptSwitch effective=${scriptObservation.effectiveScript.name}")
                    }
                    if (!scriptObservation.acceptResult) {
                        Log.i(TAG, "autoProbeDiscarded chars=${AutoScriptPolicy.textStats(text.text).nonSpaceCharacters}")
                        refreshHud()
                        return@addOnSuccessListener
                    }
                    val viewTransform = previewView.outputTransform
                    val transformMatrix = viewTransform?.let { outputTransform ->
                        runCatching {
                            Matrix().also { matrix ->
                                CoordinateTransform(sourceTransform, outputTransform).transform(matrix)
                                if (zoomSnapshot != LiveZoomLevel.ONE) {
                                    val values = FloatArray(9)
                                    matrix.getValues(values)
                                    val cropToView = LiveZoomPolicy.composeCropToView(values, crop)
                                    matrix.setValues(
                                        LiveZoomPolicy.postScaleAboutViewCenter(
                                            transform = cropToView,
                                            scale = zoomSnapshot.scale,
                                            viewWidth = previewView.width.toFloat(),
                                            viewHeight = previewView.height.toFloat(),
                                        ),
                                    )
                                }
                            }
                        }.onFailure {
                            Log.w(TAG, "ocrTransformFailure mode=${modeSnapshot.name}", it)
                        }.getOrNull()
                    }
                    processOcrResult(
                        mode = modeSnapshot,
                        transformMatrix = transformMatrix,
                        blocks = extractBlocks(text),
                        latencyMs = latencyMs,
                    )
                }
                .addOnFailureListener(mainExecutor) {
                    Log.w(TAG, "ocrFailure mode=${modeSnapshot.name}", it)
                    if (analyzerGeneration.get() == generation &&
                        isActivityResumed && !isFrozen && !isFinishing && !isDestroyed
                    ) {
                        translationStatus = OCR_RETRY_STATUS
                        refreshHud()
                    }
                }
                .addOnCompleteListener(mainExecutor) {
                    if (analyzerGeneration.get() == generation) analyzerBusy.set(false)
                    imageProxy.close()
                }
        }
    }

    private data class SelectorCandidate(
        val label: String,
        val selector: CameraSelector,
    )

    companion object {
        private const val REQUEST_CAMERA = 42
        // The Rokid sprite OS ignores FLAG_KEEP_SCREEN_ON (its LightCtrlSVC sleeps the
        // display after the system screen_off_timeout, stock 10 s, regardless of window
        // flags) but honors Settings.System.SCREEN_OFF_TIMEOUT writes — so Lens holds
        // the screen by overriding the timeout while foreground and restoring on pause.
        private const val SCREEN_OFF_TIMEOUT_OVERRIDE_MS = 600_000
        private const val SCREEN_PREFS_NAME = "lens_screen"
        private const val SCREEN_PREF_STOCK_TIMEOUT_MS = "stock_screen_off_timeout_ms"
        private const val CACHE_MAX_ENTRIES = 512
        private const val TRANSLATE_TIMEOUT_MS = LensWireContract.GLASSES_REQUEST_TIMEOUT_MS
        private const val TRANSLATE_RETRY_BACKOFF_MS = 750L
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = LensWireContract.GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS
        private const val MODEL_DOWNLOAD_RETRY_DELAY_MS = 3_000L
        private const val MAX_TRANSLATION_RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RETRY_BACKOFF_SHIFT = 16
        private const val FROZEN_BATCH_CONTINUATION_DELAY_MS = 200L
        private const val FAST_FREEZE_FRAME_TIMEOUT_MS = 1_200L
        private const val HD_AE_SETTLE_MAX_MS = 450L
        private const val HD_AE_SETTLE_MIN_FRAMES = 3
        // Field-calibrated 2026-07-11: with the camera session up this device OPERATES at
        // ~265-300MB availMem, so 300 disabled HD entirely. 150 marks genuine pressure.
        private const val HD_REFINE_MIN_AVAILABLE_MEMORY_MB = 150
        private const val FROZEN_ZOOM_NOTICE_MS = 1_200L
        private const val PHONE_TIMEOUT_ERROR_CODE = "TIMEOUT"
        private const val CAMERA_BIND_MAX_RETRIES = 3
        private const val CAMERA_BIND_RETRY_DELAY_MS = 750L
        private const val OCR_RETRY_STATUS = "OCR ERROR - RETRYING"
        private const val WAITING_PREVIEW_STATUS = "WAITING PREVIEW..."
        private const val LIVE_OCR_MIN_INTERVAL_MS = 300L
        private const val THROUGHPUT_WINDOW_MS = 10_000L
        private const val LATENCY_WINDOW_SIZE = 30
        private const val RECT_MATCH_IOU = 0.5f
        private const val LIVE_DENSE_VISIBLE_TRACK_COUNT = 35
        private const val LIVE_DENSE_STATUS = "DENSE - FREEZE ADVISED"
        private const val MIN_TRACKED_RECT_SIZE_PX = 4
        private const val FROZEN_MIN_LINE_HEIGHT_FRACTION = 0.55f
        private const val FROZEN_GARBAGE_WORD_FRACTION = 0.5f
        private const val FROZEN_MIN_TEXT_CHARS = 3
        // M2.1 memory diet: keep the capture/OCR long edge bounded on the 1.6 GB device.
        private const val MAX_FROZEN_OCR_BITMAP_EDGE = 2048
        private const val MAX_FROZEN_DECODE_LONG_EDGE = MAX_FROZEN_OCR_BITMAP_EDGE * 3 / 2
        private val latinAlphabeticWordRegex = Regex("\\p{Alpha}{3,}")
        private val latinVowelRegex = Regex("[AEIOUaeiou]")
    }
}
