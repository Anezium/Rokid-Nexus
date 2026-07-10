package com.anezium.rokidbus.lens

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import android.media.ExifInterface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
    private enum class Mode {
        LATIN,
        JAPANESE,
    }

    private data class CacheKey(
        val normalized: String,
        val mode: Mode,
        val targetLang: String,
    )

    private data class CachedTranslation(
        val dst: String,
        val srcLang: String,
    )

    private data class OcrSourceBlock(
        val source: String,
        val normalized: String,
        val bounds: Rect,
        val lineCount: Int? = null,
    )

    private data class OcrMetrics(
        val avgLatencyMs: Long,
        val hz: Double,
    )

    private data class LastOcrResult(
        val mode: Mode,
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

    private class TrackedBlock(
        var displayRect: Rect,
        var displayText: String,
        var translation: CachedTranslation?,
        var lastSeenMs: Long,
        var seenCount: Int,
        var candidateText: String? = null,
        var candidateCount: Int = 0,
        var candidateLastSeenFrame: Long = -1L,
        var lastSeenFrame: Long = -1L,
    )

    private data class LiveTrackingStats(
        val newCount: Int,
        val droppedCount: Int,
    )

    private data class LiveMatch(
        val trackedIndex: Int,
        val blockIndex: Int,
        val iou: Float,
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
        val mode: Mode,
        val restoreCaptureWithAnalysis: Boolean,
    )

    private data class DecodedFrozenCapture(
        val bitmap: Bitmap,
        val jpegBytes: ByteArray,
        val appliedRotation: Int,
    )

    private data class FrozenContent(
        val bitmap: Bitmap,
        val ocrResult: LastOcrResult,
        val sourceStatus: String,
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
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var latinRecognizer: TextRecognizer
    private lateinit var japaneseRecognizer: TextRecognizer
    private var busClient: BusClient? = null
    private val inputRouter = LensInputRouter()

    @Volatile private var currentMode = Mode.LATIN
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
    private val liveTrackedBlocks = mutableListOf<TrackedBlock>()
    private var liveFrameSerial = 0L
    private var lastLiveMode: Mode? = null
    private var cameraProviderRequestInFlight = false
    private var cameraBindRetryCount = 0
    private var cameraRetry: Runnable? = null
    private var frozenBatchContinuation: Runnable? = null
    private var fastFreezeTimeout: Runnable? = null
    private var frozenZoomNoticeTimeout: Runnable? = null
    private var frozenZoomNotice: String? = null
    private var frozenJpegBytes: ByteArray? = null
    private var frozenJpegRotationDegrees = 0
    private var frozenZoomLevel = FrozenZoomLevel.ONE
    // Zoom requested while the HD JPEG has not landed yet; applied as soon as the refine succeeds.
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
        hideSystemUi()
        if (hasCameraPermission() && !isFrozen) {
            startCamera()
        } else if (isFrozen) {
            scheduleFrozenBatchContinuation()
        }
        Log.i(TAG, "lifecycle state=resumed frozen=$isFrozen")
    }

    override fun onPause() {
        isActivityResumed = false
        cameraRetry?.let(mainHandler::removeCallbacks)
        cameraRetry = null
        cameraBindRetryCount = 0
        cancelFrozenBatchContinuation()
        val incompleteFreeze = isFrozen && frozenOcrResult == null
        freezeRequestSerial.incrementAndGet()
        pendingFastFreeze.set(null)
        zoomRequestSerial.incrementAndGet()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        if (incompleteFreeze) {
            isFrozen = false
            frozenSourceStatus = null
            frozenJpegBytes = null
            frozenJpegRotationDegrees = 0
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

    override fun onDestroy() {
        isActivityResumed = false
        cameraRetry?.let(mainHandler::removeCallbacks)
        cameraRetry = null
        cancelFrozenBatchContinuation()
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
            LensInputAction.SWITCH_OCR_MODE -> toggleMode()
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
                        "LIVE ${displayModeLabel(currentMode)}"
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
        refreshOverlayFromCache()
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

    private fun buildPreviewUseCase(targetRotation: Int): Preview =
        Preview.Builder()
            .setTargetRotation(targetRotation)
            .build()
            .also { it.setSurfaceProvider(previewView.surfaceProvider) }

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
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
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
        val modeSnapshot = currentMode
        cancelFrozenBatchContinuation()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        frozenAttemptedTranslationKeys.clear()
        zoomRequestSerial.incrementAndGet()
        frozenJpegBytes = null
        frozenJpegRotationDegrees = 0
        frozenZoomLevel = FrozenZoomLevel.ONE
        queuedFrozenZoomLevel = null
        baseFrozenContent = null
        zoomFrozenContent = null
        isFrozen = true
        frozenOcrResult = null
        frozenSourceStatus = null
        translationStatus = "FREEZE NEXT FRAME..."
        overlayView.updateOcrResult(null, emptyList(), hudState(OcrMetrics(avgLatencyMs = 0L, hz = 0.0)))
        val request = FastFreezeRequest(
            requestId = requestId,
            mode = modeSnapshot,
            restoreCaptureWithAnalysis = imageCaptureBoundWithAnalysis,
        )
        pendingFastFreeze.set(request)
        resetAnalyzerPipeline()
        scheduleFastFreezeTimeout(request)
        Log.i(TAG, "freezeFastArmed id=$requestId mode=${modeSnapshot.name}")
    }

    private fun startHdRefine(request: FastFreezeRequest) {
        if (!isCurrentFreeze(request.requestId)) return
        // The LIMITED HAL cannot takePicture on preview+analysis+capture. Only after the
        // analysis ImageProxy closes do we switch to the proven preview+capture binding.
        val capture = bindPreviewCaptureOnlyForFreeze(
            restoreCaptureWithAnalysis = request.restoreCaptureWithAnalysis,
        )
        if (capture == null) {
            finishHdRefineFailure(request.requestId, "HD REBIND FAILED")
            return
        }
        Log.i(TAG, "freezeHdRefineStart id=${request.requestId} mode=${request.mode.name}")
        takeFreezePicture(request.requestId, request.mode, capture)
    }

    private fun takeFreezePicture(
        requestId: Long,
        mode: Mode,
        capture: ImageCapture,
    ) {
        freezeCaptureInFlight = true
        runCatching {
            capture.takePicture(
                cameraExecutor,
                object : ImageCapture.OnImageCapturedCallback() {
                    override fun onCaptureSuccess(image: ImageProxy) {
                        val decodedCapture = runCatching {
                            decodeFrozenCaptureBitmap(image)
                        }.onFailure {
                            Log.w(TAG, "freezeCaptureDecodeFailure id=$requestId", it)
                        }.getOrNull()
                        image.close()

                        mainHandler.post {
                            freezeCaptureInFlight = false
                            if (!isCurrentFreeze(requestId)) {
                                decodedCapture?.bitmap?.recycle()
                                restoreLiveCameraAfterSettledFreezeCapture()
                                return@post
                            }
                            if (decodedCapture == null) {
                                finishHdRefineFailure(requestId, "HD DECODE FAILED")
                                return@post
                            }

                            frozenJpegBytes = decodedCapture.jpegBytes
                            frozenJpegRotationDegrees = decodedCapture.appliedRotation
                            unbindCameraForFrozen(restoreCaptureWithAnalysisAfterTemporary)
                            runHdFrozenBitmapOcr(
                                requestId = requestId,
                                mode = mode,
                                bitmap = decodedCapture.bitmap,
                            )
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        Log.w(TAG, "freezeCaptureFailure id=$requestId", exception)
                        mainHandler.post {
                            freezeCaptureInFlight = false
                            finishHdRefineFailure(requestId, "HD CAPTURE FAILED", exception)
                        }
                    }
                },
            )
        }.onFailure {
            freezeCaptureInFlight = false
            Log.w(TAG, "freezeCaptureStartFailure id=$requestId", it)
            mainHandler.post { finishHdRefineFailure(requestId, "HD CAPTURE START FAILED", it) }
        }
    }

    private fun finishHdRefineFailure(
        requestId: Long,
        reason: String,
        cause: Throwable? = null,
    ) {
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
        freezeRequestSerial.incrementAndGet()
        pendingFastFreeze.set(null)
        zoomRequestSerial.incrementAndGet()
        cancelFastFreezeTimeout()
        clearFrozenZoomNotice()
        cancelFrozenBatchContinuation()
        frozenAttemptedTranslationKeys.clear()
        isFrozen = false
        frozenOcrResult = null
        frozenSourceStatus = null
        frozenJpegBytes = null
        frozenJpegRotationDegrees = 0
        frozenZoomLevel = FrozenZoomLevel.ONE
        queuedFrozenZoomLevel = null
        lastOcrResult = null
        clearLiveTracking()
        overlayView.setFrozenBackground(null)
        recycleFrozenContent(baseFrozenContent)
        recycleFrozenContent(zoomFrozenContent)
        baseFrozenContent = null
        zoomFrozenContent = null
        translationStatus = "LIVE ${displayModeLabel(currentMode)}"
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

    private fun bindPreviewCaptureOnlyForFreeze(
        restoreCaptureWithAnalysis: Boolean = imageCaptureBoundWithAnalysis,
    ): ImageCapture? {
        if (!isActivityResumed || isFinishing || isDestroyed) return null
        val provider = cameraProvider ?: return null
        val selector = activeCameraSelector ?: return null
        val label = activeCameraLabel ?: "CAMERA"
        val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
        val preview = buildPreviewUseCase(targetRotation)
        val capture = buildImageCaptureUseCase(targetRotation)
        restoreCaptureWithAnalysisAfterTemporary = restoreCaptureWithAnalysis
        resetAnalyzerPipeline()
        provider.unbindAll()
        return runCatching {
            provider.bindToLifecycle(this, selector, preview, capture)
            imageCapture = capture
            imageCaptureBoundWithAnalysis = false
            temporaryCaptureBindingActive = true
            cameraUnboundForFrozen = false
            Log.i(TAG, "cameraBindSucceeded label=$label useCases=preview+imageCapture temporary=true")
            capture
        }.onFailure {
            temporaryCaptureBindingActive = false
            imageCapture = null
            Log.w(TAG, "cameraBindFailed label=$label useCases=preview+imageCapture temporary=true", it)
            restoreCameraBinding(restoreCaptureWithAnalysis)
        }.getOrNull()
    }

    private fun restorePreviewAnalysisAfterTemporaryCapture() {
        if (!temporaryCaptureBindingActive) return
        restoreCameraBinding(restoreCaptureWithAnalysisAfterTemporary)
    }

    @Suppress("DEPRECATION")
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
            val targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
            val preview = buildPreviewUseCase(targetRotation)
            val analysis = buildAnalysisUseCase(targetRotation)
            val capture = buildImageCaptureUseCase(targetRotation)
            resetAnalyzerPipeline()
            provider.unbindAll()
            val restored = runCatching {
                provider.bindToLifecycle(this, selector, preview, analysis, capture)
                imageCapture = capture
                imageCaptureBoundWithAnalysis = true
                temporaryCaptureBindingActive = false
                restoreCaptureWithAnalysisAfterTemporary = false
                cameraUnboundForFrozen = false
                markCameraBound()
                Log.i(TAG, "cameraBindRestored label=$label useCases=preview+analysis+imageCapture")
            }.onFailure {
                Log.w(TAG, "cameraBindRestoreFailed label=$label useCases=preview+analysis+imageCapture", it)
            }.isSuccess
            if (restored) return true
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
        val bitmap = previewBitmap?.let(::copyAsRgb565AndRecycleSource)
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
        latencyMs: Long,
    ) {
        if (!isCurrentFreeze(request.requestId)) {
            if (!bitmap.isRecycled) bitmap.recycle()
            return
        }
        val rawBlocks = extractBlocks(text)
        val filteredBlocks = filterFrozenBlocks(request.mode, rawBlocks)
        val blocks = mergeFrozenParagraphBlocks(filteredBlocks)
        val result = LastOcrResult(
            mode = request.mode,
            transformMatrix = frozenBitmapToViewMatrix(bitmap),
            blocks = blocks,
            metrics = OcrMetrics(avgLatencyMs = latencyMs, hz = 0.0),
            completedAtMs = SystemClock.elapsedRealtime(),
            carriedTranslations = carriedTranslationsFor(request.mode, blocks, lastOcrResult),
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

    private fun runHdFrozenBitmapOcr(requestId: Long, mode: Mode, bitmap: Bitmap) {
        runFrozenBitmapOcr(
            requestId = requestId,
            mode = mode,
            bitmap = bitmap,
            sourceStatus = "HD ${maxOf(bitmap.width, bitmap.height)}",
            previous = baseFrozenContent?.ocrResult ?: lastOcrResult,
            onReady = { content ->
                installBaseFrozenContent(requestId, content)
                Log.i(TAG, "freezeHdRefineSuccess id=$requestId blocks=${content.ocrResult.blocks.size}")
                queuedFrozenZoomLevel?.let { queued ->
                    queuedFrozenZoomLevel = null
                    Log.i(TAG, "frozenZoomQueuedApply level=${queued.hudLabel}")
                    applyFrozenZoom(requestId, queued)
                }
            },
            onFailure = { cause -> finishHdRefineFailure(requestId, "HD OCR FAILED", cause) },
        )
    }

    private fun runFrozenBitmapOcr(
        requestId: Long,
        mode: Mode,
        bitmap: Bitmap,
        sourceStatus: String,
        previous: LastOcrResult?,
        onReady: (FrozenContent) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        val recognizer = if (mode == Mode.LATIN) latinRecognizer else japaneseRecognizer
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        val processStartMs = SystemClock.elapsedRealtime()
        recognizer.process(inputImage)
            .addOnSuccessListener(ContextCompat.getMainExecutor(this)) { text ->
                if (!isCurrentFreeze(requestId)) {
                    if (!overlayView.isFrozenBackground(bitmap) && !bitmap.isRecycled) bitmap.recycle()
                    return@addOnSuccessListener
                }
                val latencyMs = SystemClock.elapsedRealtime() - processStartMs
                val rawBlocks = extractBlocks(text)
                val filteredBlocks = filterFrozenBlocks(mode, rawBlocks)
                val blocks = mergeFrozenParagraphBlocks(filteredBlocks)
                val metrics = OcrMetrics(avgLatencyMs = latencyMs, hz = 0.0)
                val carriedTranslations = carriedTranslationsFor(mode, blocks, previous)
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
                    return@addOnSuccessListener
                }
                if (rgb565 !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                val result = LastOcrResult(
                    mode = mode,
                    transformMatrix = frozenBitmapToViewMatrix(rgb565),
                    blocks = blocks,
                    metrics = metrics,
                    completedAtMs = SystemClock.elapsedRealtime(),
                    carriedTranslations = carriedTranslations,
                )
                onReady(FrozenContent(rgb565, result, sourceStatus))
                Log.i(TAG, "freezeOcrSuccess id=$requestId source=$sourceStatus latencyMs=$latencyMs blocks=${blocks.size}")
            }
            .addOnFailureListener(ContextCompat.getMainExecutor(this)) { cause ->
                Log.w(TAG, "freezeOcrFailure id=$requestId source=$sourceStatus", cause)
                if (!bitmap.isRecycled) bitmap.recycle()
                if (isCurrentFreeze(requestId)) onFailure(cause)
            }
    }

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

    private fun copyAsRgb565AndRecycleSource(source: Bitmap): Bitmap? {
        if (source.config == Bitmap.Config.RGB_565) return source
        val rgb565 = runCatching { source.copy(Bitmap.Config.RGB_565, false) }.getOrNull()
        if (!source.isRecycled) source.recycle()
        return rgb565
    }

    private fun decodeFrozenCaptureBitmap(imageProxy: ImageProxy): DecodedFrozenCapture {
        val buffer = imageProxy.planes.firstOrNull()?.buffer ?: error("missing JPEG plane")
        val bytesBuffer = buffer.asReadOnlyBuffer().apply { rewind() }
        val bytes = ByteArray(bytesBuffer.remaining())
        bytesBuffer.get(bytes)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("JPEG decode failed")
        val reportedRotation = imageProxy.imageInfo.rotationDegrees
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
        val rotatedWidth = if (normalizedRotation == 90 || normalizedRotation == 270) decoded.height else decoded.width
        val rotatedHeight = if (normalizedRotation == 90 || normalizedRotation == 270) decoded.width else decoded.height
        val rotatedLongEdge = maxOf(rotatedWidth, rotatedHeight)
        val scale = if (rotatedLongEdge > MAX_FROZEN_OCR_BITMAP_EDGE) {
            MAX_FROZEN_OCR_BITMAP_EDGE.toFloat() / rotatedLongEdge.toFloat()
        } else {
            1f
        }
        val transformed = if (normalizedRotation == 0 && scale >= 1f) {
            decoded
        } else {
            val matrix = Matrix().apply {
                postRotate(normalizedRotation.toFloat())
                if (scale < 1f) postScale(scale, scale)
            }
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        }
        Log.i(
            TAG,
            "freezeCaptureDecoded width=${decoded.width} height=${decoded.height} " +
                "outputWidth=${transformed.width} outputHeight=${transformed.height} " +
                "reportedRotation=$reportedRotation exifRotation=$exifRotation " +
                "appliedRotation=$appliedRotation scale=$scale",
        )
        if (transformed !== decoded) decoded.recycle()
        return DecodedFrozenCapture(
            bitmap = transformed,
            jpegBytes = bytes,
            appliedRotation = normalizedRotation,
        )
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

    private fun toggleMode() {
        if (isFrozen) {
            cycleFrozenZoom()
            return
        }
        currentMode = if (currentMode == Mode.LATIN) Mode.JAPANESE else Mode.LATIN
        resetAnalyzerCadence()
        clearRollingStats()
        clearLiveTracking()
        lastOcrResult = null
        val modeLabel = displayModeLabel(currentMode)
        translationStatus = "MODE $modeLabel"
        overlayView.showModeFlash(modeLabel)
        Log.i(TAG, "modeSwitch mode=${currentMode.name}")
        refreshOverlayFromCache()
    }

    private fun cycleFrozenZoom() {
        val requestId = freezeRequestSerial.get()
        if (!isCurrentFreeze(requestId)) return
        val nextLevel = FrozenZoomPolicy.next(queuedFrozenZoomLevel ?: frozenZoomLevel)
        if (nextLevel == FrozenZoomLevel.ONE) {
            queuedFrozenZoomLevel = null
            restoreOneXFrozenContent(requestId)
            return
        }
        if (frozenJpegBytes == null) {
            // HD refine still in flight: queue instead of no-op so the swipe is never lost.
            queuedFrozenZoomLevel = nextLevel
            showFrozenZoomNotice("ZOOM ${nextLevel.hudLabel} WAITING HD")
            Log.i(TAG, "frozenZoomQueued level=${nextLevel.hudLabel} reason=no_jpeg")
            return
        }
        queuedFrozenZoomLevel = null
        applyFrozenZoom(requestId, nextLevel)
    }

    private fun applyFrozenZoom(requestId: Long, nextLevel: FrozenZoomLevel) {
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
                val mode = baseFrozenContent?.ocrResult?.mode ?: currentMode
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
                zoomLevel = zoomLevel,
            )
        } catch (cause: Throwable) {
            decoder.recycle()
            throw cause
        }
        val decoded = try {
            decoder.decodeRegion(
                Rect(crop.left, crop.top, crop.right, crop.bottom),
                BitmapFactory.Options().apply { inSampleSize = 1 },
            ) ?: error("JPEG region decode failed")
        } finally {
            decoder.recycle()
        }
        val transformed = rotateBitmap(decoded, rotationDegrees)
        Log.i(
            TAG,
            "frozenZoomDecoded level=${zoomLevel.hudLabel} rawCrop=${crop.width}x${crop.height} " +
                "output=${transformed.width}x${transformed.height} rotation=$rotationDegrees sample=1",
        )
        return transformed
    }

    private fun rotateBitmap(bitmap: Bitmap, rotationDegrees: Int): Bitmap {
        val rotation = ((rotationDegrees % 360) + 360) % 360
        if (rotation == 0) return bitmap
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            Matrix().apply { postRotate(rotation.toFloat()) },
            true,
        )
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
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
        mode: Mode,
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

        liveFrameSerial += 1L
        val viewBlocks = blocks.mapNotNull { mapLiveBlockToView(it, transformMatrix) }
        val trackingStats = reconcileLiveTrackedBlocks(viewBlocks, nowMs, liveFrameSerial)
        val misses = linkedMapOf<CacheKey, String>()
        val overlayBlocks = overlayBlocksForTracked(mode, misses)
        overlayView.updateOcrResult(Matrix(), overlayBlocks, hudState(metrics))
        requestMissingTranslations(mode, misses)
        Log.i(
            TAG,
            String.format(
                Locale.US,
                "mode=%s latencyMs=%d avgMs=%d hz=%.1f blocks=%d tracked=%d visible=%d new=%d dropped=%d transformReady=%s",
                mode.name,
                latencyMs,
                metrics.avgLatencyMs,
                metrics.hz,
                blocks.size,
                liveTrackedBlocks.size,
                overlayBlocks.size,
                trackingStats.newCount,
                trackingStats.droppedCount,
                transformMatrix != null,
            ),
        )
    }

    private fun mapLiveBlockToView(block: OcrSourceBlock, transformMatrix: Matrix?): ViewOcrBlock? {
        val matrix = transformMatrix ?: return null
        val mapped = RectF(block.bounds)
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
            normalized = block.normalized,
            displayRect = rect,
        )
    }

    private fun reconcileLiveTrackedBlocks(
        viewBlocks: List<ViewOcrBlock>,
        nowMs: Long,
        frameSerial: Long,
    ): LiveTrackingStats {
        val matches = matchLiveBlocks(viewBlocks)
        val matchedTrackIndices = matches.mapTo(mutableSetOf()) { it.trackedIndex }
        val matchedBlockIndices = matches.mapTo(mutableSetOf()) { it.blockIndex }

        matches.forEach { match ->
            val tracked = liveTrackedBlocks[match.trackedIndex]
            val block = viewBlocks[match.blockIndex]
            val wasSeenPreviousFrame = tracked.lastSeenFrame == frameSerial - 1L
            tracked.lastSeenMs = nowMs
            tracked.lastSeenFrame = frameSerial
            if (tracked.seenCount < LIVE_TRACK_VISIBLE_SEEN_COUNT) {
                tracked.seenCount = if (wasSeenPreviousFrame) tracked.seenCount + 1 else 1
            } else if (wasSeenPreviousFrame) {
                tracked.seenCount += 1
            }

            if (centerDistanceSquared(tracked.displayRect, block.displayRect) > LIVE_TRACK_RECT_SNAP_DISTANCE_PX *
                LIVE_TRACK_RECT_SNAP_DISTANCE_PX
            ) {
                tracked.displayRect = Rect(block.displayRect)
            }
            updateTrackedText(tracked, block.normalized, frameSerial)
        }

        var newCount = 0
        viewBlocks.forEachIndexed { index, block ->
            if (index in matchedBlockIndices) return@forEachIndexed
            liveTrackedBlocks += TrackedBlock(
                displayRect = Rect(block.displayRect),
                displayText = block.normalized,
                translation = null,
                lastSeenMs = nowMs,
                seenCount = 1,
                lastSeenFrame = frameSerial,
            )
            newCount += 1
        }

        val beforeDropCount = liveTrackedBlocks.size
        liveTrackedBlocks.removeAll { nowMs - it.lastSeenMs > LIVE_TRACK_STALE_MS }
        return LiveTrackingStats(
            newCount = newCount,
            droppedCount = beforeDropCount - liveTrackedBlocks.size,
        )
    }

    private fun matchLiveBlocks(viewBlocks: List<ViewOcrBlock>): List<LiveMatch> {
        if (liveTrackedBlocks.isEmpty() || viewBlocks.isEmpty()) return emptyList()
        val candidates = mutableListOf<LiveMatch>()
        liveTrackedBlocks.forEachIndexed { trackedIndex, tracked ->
            viewBlocks.forEachIndexed { blockIndex, block ->
                val iou = rectIou(tracked.displayRect, block.displayRect)
                if (iou >= LIVE_TRACK_MATCH_IOU) {
                    candidates += LiveMatch(trackedIndex, blockIndex, iou)
                }
            }
        }

        val usedTracks = mutableSetOf<Int>()
        val usedBlocks = mutableSetOf<Int>()
        val matches = mutableListOf<LiveMatch>()
        candidates.sortedByDescending { it.iou }.forEach { candidate ->
            if (candidate.trackedIndex in usedTracks || candidate.blockIndex in usedBlocks) return@forEach
            usedTracks += candidate.trackedIndex
            usedBlocks += candidate.blockIndex
            matches += candidate
        }
        return matches
    }

    private fun updateTrackedText(tracked: TrackedBlock, normalized: String, frameSerial: Long) {
        if (normalized == tracked.displayText) {
            tracked.candidateText = null
            tracked.candidateCount = 0
            tracked.candidateLastSeenFrame = -1L
            return
        }

        val sameCandidate = tracked.candidateText == normalized &&
            tracked.candidateLastSeenFrame == frameSerial - 1L
        tracked.candidateText = normalized
        tracked.candidateCount = if (sameCandidate) tracked.candidateCount + 1 else 1
        tracked.candidateLastSeenFrame = frameSerial
        if (tracked.candidateCount >= LIVE_TRACK_TEXT_SWAP_FRAMES) {
            tracked.displayText = normalized
            tracked.translation = null
            tracked.candidateText = null
            tracked.candidateCount = 0
            tracked.candidateLastSeenFrame = -1L
        }
    }

    private fun centerDistanceSquared(a: Rect, b: Rect): Int {
        val dx = a.centerX() - b.centerX()
        val dy = a.centerY() - b.centerY()
        return dx * dx + dy * dy
    }

    private fun overlayBlocksForTracked(
        mode: Mode,
        misses: MutableMap<CacheKey, String>? = null,
    ): List<LensOverlayBlock> =
        liveTrackedBlocks
            .asSequence()
            .filter { it.seenCount >= LIVE_TRACK_VISIBLE_SEEN_COUNT }
            .map { tracked ->
                val key = CacheKey(tracked.displayText, mode, LensWireContract.DEFAULT_TARGET_LANG)
                val cached = translationCache[key]
                if (cached != null) tracked.translation = cached
                val displayTranslation = cached ?: tracked.translation
                if (displayTranslation == null && !inFlightByKey.containsKey(key)) {
                    misses?.putIfAbsent(key, tracked.displayText)
                }
                LensOverlayBlock(
                    source = tracked.displayText,
                    normalized = tracked.displayText,
                    bounds = Rect(tracked.displayRect),
                    translation = displayTranslation?.dst,
                    sourceLang = displayTranslation?.srcLang,
                )
            }
            .toList()

    private fun clearLiveTracking() {
        liveTrackedBlocks.clear()
        liveFrameSerial = 0L
        lastLiveMode = null
    }

    private fun overlayBlocksFor(
        mode: Mode,
        blocks: List<OcrSourceBlock>,
        misses: MutableMap<CacheKey, String>? = null,
        carriedTranslations: Map<String, CachedTranslation> = emptyMap(),
    ): List<LensOverlayBlock> =
        blocks.map { block ->
            val key = CacheKey(block.normalized, mode, LensWireContract.DEFAULT_TARGET_LANG)
            val cached = translationCache[key]
            val displayTranslation = cached ?: carriedTranslations[block.normalized]
            if (cached == null && !inFlightByKey.containsKey(key)) {
                misses?.putIfAbsent(key, block.normalized)
            }
            LensOverlayBlock(
                source = block.source,
                normalized = block.normalized,
                bounds = block.bounds,
                translation = displayTranslation?.dst,
                sourceLang = displayTranslation?.srcLang,
            )
        }

    private fun carriedTranslationsFor(
        mode: Mode,
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
            blocks = blocks.map { block -> block.copy(bounds = Rect(block.bounds)) },
            carriedTranslations = carriedTranslations.toMap(),
        )

    private fun LastOcrResult.snapshotForFreeze(): LastOcrResult {
        val snapshot = snapshot()
        return snapshot.copy(blocks = mergeFrozenParagraphBlocks(snapshot.blocks))
    }

    private fun mergeFrozenParagraphBlocks(blocks: List<OcrSourceBlock>): List<OcrSourceBlock> {
        if (blocks.size < 2) return blocks
        val sorted = blocks.sortedWith(compareBy<OcrSourceBlock> { it.bounds.top }.thenBy { it.bounds.left })
        val merged = mutableListOf<OcrSourceBlock>()
        var paragraph = mutableListOf(sorted.first())
        var paragraphChars = sorted.first().source.trim().length

        for (block in sorted.drop(1)) {
            val previous = paragraph.last()
            val blockChars = block.source.trim().length
            // A merged block above the wire limit is silently unbatchable (the batcher skips
            // it forever), so dense pages must split into consecutive sub-paragraph chunks.
            val fitsWireLimit = paragraphChars + 1 + blockChars <= LensWireContract.MAX_STRING_CHARS
            if (fitsWireLimit && belongsToSameFrozenParagraph(previous, block)) {
                paragraph += block
                paragraphChars += 1 + blockChars
            } else {
                merged += mergeParagraphBlocks(paragraph)
                paragraph = mutableListOf(block)
                paragraphChars = blockChars
            }
        }
        merged += mergeParagraphBlocks(paragraph)
        return merged
    }

    private fun mergeParagraphBlocks(blocks: List<OcrSourceBlock>): OcrSourceBlock {
        if (blocks.size == 1) return blocks.first()
        val bounds = Rect(blocks.first().bounds)
        blocks.drop(1).forEach { bounds.union(it.bounds) }
        val source = blocks.joinToString(" ") { it.source.trim() }.trim()
        val lineCounts = blocks.mapNotNull { it.lineCount }
        return OcrSourceBlock(
            source = source,
            normalized = LensWireContract.normalizeText(source),
            bounds = bounds,
            lineCount = lineCounts.takeIf { it.size == blocks.size }?.sum(),
        )
    }

    private fun belongsToSameFrozenParagraph(a: OcrSourceBlock, b: OcrSourceBlock): Boolean {
        val narrowerWidth = minOf(a.bounds.width(), b.bounds.width())
        if (narrowerWidth <= 0) return false
        val horizontalOverlap = minOf(a.bounds.right, b.bounds.right) - maxOf(a.bounds.left, b.bounds.left)
        if (horizontalOverlap < narrowerWidth * FROZEN_PARAGRAPH_MIN_HORIZONTAL_OVERLAP) return false

        val verticalGap = maxOf(0, b.bounds.top - a.bounds.bottom)
        val lineHeight = frozenParagraphLineHeight(a, b)
        return verticalGap < lineHeight * FROZEN_PARAGRAPH_MAX_GAP_LINES
    }

    private fun frozenParagraphLineHeight(a: OcrSourceBlock, b: OcrSourceBlock): Float {
        val aLineCount = a.lineCount
        val bLineCount = b.lineCount
        return if (aLineCount != null && aLineCount > 0 && bLineCount != null && bLineCount > 0) {
            (a.bounds.height().toFloat() / aLineCount + b.bounds.height().toFloat() / bLineCount) / 2f
        } else {
            minOf(a.bounds.height(), b.bounds.height()).toFloat()
        }.coerceAtLeast(1f)
    }

    private fun filterFrozenBlocks(mode: Mode, blocks: List<OcrSourceBlock>): List<OcrSourceBlock> {
        if (blocks.isEmpty()) {
            Log.i(TAG, "frozenFilter kept=0 dropped=0 reasons=[size=0,garbage=0]")
            return blocks
        }

        val dominantLineHeight = if (mode == Mode.LATIN) dominantFrozenLineHeight(blocks) else 0f
        var sizeDropped = 0
        var garbageDropped = 0
        val kept = blocks.filter { block ->
            val tooSmall = mode == Mode.LATIN &&
                dominantLineHeight > 0f &&
                frozenBlockLineHeight(block) < dominantLineHeight * FROZEN_MIN_LINE_HEIGHT_FRACTION
            val tooShort = compactTextLength(block.normalized) < FROZEN_MIN_TEXT_CHARS
            val tooGarbage = tooShort || (
                mode == Mode.LATIN &&
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
        val lineCount = block.lineCount?.coerceAtLeast(1) ?: 1
        return block.bounds.height().toFloat() / lineCount.toFloat()
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
                OcrSourceBlock(
                    source = source,
                    normalized = normalized,
                    bounds = Rect(box),
                    lineCount = block.lines.size,
                )
            }
        }

    private fun requestMissingTranslations(mode: Mode, misses: Map<CacheKey, String>) {
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
            overlayView.updateOcrResult(
                transformMatrix = Matrix(),
                blocks = overlayBlocksForTracked(currentMode, misses),
                hudState = hudState(metrics),
            )
            if (misses != null) requestMissingTranslations(currentMode, misses)
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
            mode = displayModeLabel(currentMode),
            targetLang = LensWireContract.DEFAULT_TARGET_LANG,
            cacheEntries = translationCache.size,
            busLabel = if (hasDataLink()) "DATA LINK UP" else "DATA LINK OFFLINE",
            ocrHz = metrics.hz,
            status = hudStatus(),
            frozen = isFrozen,
        )

    private fun hudStatus(): String {
        if (!isFrozen && waitingForPreviewTransform) return WAITING_PREVIEW_STATUS
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

    private fun displayModeLabel(mode: Mode): String =
        when (mode) {
            Mode.LATIN -> "ABC"
            Mode.JAPANESE -> "JP"
        }

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
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            pendingFastFreeze.compareAndSet(null, request)
            imageProxy.close()
            return
        }
        cancelFastFreezeTimeout()
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        val bitmap = runCatching {
            analysisImageToFrozenBitmap(imageProxy, rotationDegrees)
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

        mainHandler.post { showFastFrozenBitmap(request, bitmap) }
        val recognizer = if (request.mode == Mode.LATIN) latinRecognizer else japaneseRecognizer
        // Keep the working live OCR path exactly: the landscape analysis buffer plus CameraX's
        // rotation metadata is intentionally passed straight to ML Kit.
        val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
        val processStartMs = SystemClock.elapsedRealtime()
        val mainExecutor = ContextCompat.getMainExecutor(this)
        val task = runCatching { recognizer.process(inputImage) }.getOrElse { cause ->
            imageProxy.close()
            mainHandler.post {
                completeFastFreezeOcrFailure(request, bitmap, "OCR START FAILED", cause)
            }
            return
        }
        task
            .addOnSuccessListener(mainExecutor) { text ->
                imageProxy.close()
                completeFastFreezeOcr(
                    request = request,
                    bitmap = bitmap,
                    text = text,
                    latencyMs = SystemClock.elapsedRealtime() - processStartMs,
                )
            }
            .addOnFailureListener(mainExecutor) { cause ->
                imageProxy.close()
                completeFastFreezeOcrFailure(request, bitmap, "OCR FAILED", cause)
            }
    }

    private fun analysisImageToFrozenBitmap(imageProxy: ImageProxy, rotationDegrees: Int): Bitmap {
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
        Log.i(
            TAG,
            "freezeFastFrame input=${width}x$height output=${rgb565.width}x${rgb565.height} " +
                "rotation=$rotationDegrees yStride=$yRowStride uvStride=$uRowStride/$vRowStride " +
                "uvPixelStride=$uPixelStride/$vPixelStride",
        )
        return rgb565
    }

    @TransformExperimental
    private inner class TextAnalyzer : ImageAnalysis.Analyzer {
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
            val modeSnapshot = currentMode
            val recognizer = if (modeSnapshot == Mode.LATIN) latinRecognizer else japaneseRecognizer
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)
            val mainExecutor = ContextCompat.getMainExecutor(this@LensActivity)

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
                    if (analyzerGeneration.get() != generation) return@addOnSuccessListener
                    val latencyMs = SystemClock.elapsedRealtime() - processStartMs
                    val viewTransform = previewView.outputTransform
                    val transformMatrix = viewTransform?.let { outputTransform ->
                        runCatching {
                            Matrix().also { matrix ->
                                CoordinateTransform(sourceTransform, outputTransform).transform(matrix)
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
        private const val CACHE_MAX_ENTRIES = 1500
        private const val TRANSLATE_TIMEOUT_MS = LensWireContract.GLASSES_REQUEST_TIMEOUT_MS
        private const val TRANSLATE_RETRY_BACKOFF_MS = 750L
        private const val MODEL_DOWNLOAD_TIMEOUT_MS = LensWireContract.GLASSES_MODEL_DOWNLOAD_TIMEOUT_MS
        private const val MODEL_DOWNLOAD_RETRY_DELAY_MS = 3_000L
        private const val MAX_TRANSLATION_RETRY_BACKOFF_MS = 30_000L
        private const val MAX_RETRY_BACKOFF_SHIFT = 16
        private const val FROZEN_BATCH_CONTINUATION_DELAY_MS = 200L
        private const val FAST_FREEZE_FRAME_TIMEOUT_MS = 1_200L
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
        private const val LIVE_TRACK_MATCH_IOU = 0.4f
        private const val LIVE_TRACK_RECT_SNAP_DISTANCE_PX = 12
        private const val LIVE_TRACK_VISIBLE_SEEN_COUNT = 2
        private const val LIVE_TRACK_TEXT_SWAP_FRAMES = 3
        private const val LIVE_TRACK_STALE_MS = 1_200L
        private const val MIN_TRACKED_RECT_SIZE_PX = 4
        private const val FROZEN_PARAGRAPH_MIN_HORIZONTAL_OVERLAP = 0.5f
        private const val FROZEN_PARAGRAPH_MAX_GAP_LINES = 0.6f
        private const val FROZEN_MIN_LINE_HEIGHT_FRACTION = 0.55f
        private const val FROZEN_GARBAGE_WORD_FRACTION = 0.5f
        private const val FROZEN_MIN_TEXT_CHARS = 3
        // M2.1 system-wide purge guard: revert this single cap to 2048 if "has died" waves return.
        private const val MAX_FROZEN_OCR_BITMAP_EDGE = 2560
        private val latinAlphabeticWordRegex = Regex("\\p{Alpha}{3,}")
        private val latinVowelRegex = Regex("[AEIOUaeiou]")
    }
}
