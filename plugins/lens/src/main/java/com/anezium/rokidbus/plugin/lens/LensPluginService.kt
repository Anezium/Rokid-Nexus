package com.anezium.rokidbus.plugin.lens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.CameraLinkPacket
import com.anezium.rokidbus.shared.CameraLinkPacketType
import com.anezium.rokidbus.shared.CameraOverlayContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.google.mlkit.vision.text.Text
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal interface LensRuntimeHost {
    fun send(path: String, id: String, payload: JSONObject): Boolean
    fun log(message: String)
}

internal interface LensSessionEngine : AutoCloseable {
    fun message(path: String, id: String, payload: JSONObject)
    fun binary(path: String, id: String, payload: JSONObject, data: ByteArray) = Unit
}

internal fun interface LensSessionEngineFactory {
    fun create(): LensSessionEngine
}

internal fun paragraphLayoutJson(layout: LiveOverlayParagraphLayout): JSONObject =
    JSONObject()
        .put("kind", CameraOverlayContract.PARAGRAPH_LAYOUT_KIND)
        .put("version", CameraOverlayContract.PARAGRAPH_LAYOUT_VERSION)
        .put("medianLineHeight", layout.medianLineHeight)
        .put("growDown", layout.growDown)
        .apply { layout.column?.let { put("column", it) } }

internal fun normalizedParagraphLayoutJson(
    medianLineHeight: Float,
    growDown: Float,
    frameHeight: Int,
    column: Int,
): JSONObject {
    require(frameHeight > 0)
    return paragraphLayoutJson(
        LiveOverlayParagraphLayout(
            medianLineHeight = (medianLineHeight / frameHeight).coerceIn(0f, 1f),
            growDown = (growDown / frameHeight).coerceIn(0f, 1f),
            column = column.takeIf { it in 0..CameraOverlayContract.MAX_LAYOUT_COLUMN },
        ),
    )
}

internal fun frozenRemainingRotation(metadata: JSONObject): Int? {
    val key = when {
        metadata.has("remainingRotationDegrees") -> "remainingRotationDegrees"
        metadata.has("rotationDegrees") -> "rotationDegrees"
        metadata.has("rotation") -> "rotation"
        else -> return null
    }
    val value = metadata.opt(key) as? Number ?: return null
    val long = value.toLong()
    if (value.toDouble() != long.toDouble() || long !in Int.MIN_VALUE..Int.MAX_VALUE) return null
    val normalized = ((long.toInt() % 360) + 360) % 360
    return normalized.takeIf { it % 90 == 0 }
}

internal class LensLifecycle(private val factory: LensSessionEngineFactory) : AutoCloseable {
    private var engine: LensSessionEngine? = null

    @Synchronized
    fun open() {
        engine?.close()
        engine = factory.create()
    }

    @Synchronized
    fun message(path: String, id: String, payload: JSONObject) {
        engine?.message(path, id, payload)
    }

    @Synchronized
    fun binary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        engine?.binary(path, id, payload, data)
    }

    @Synchronized
    override fun close() {
        engine?.close()
        engine = null
    }

    @Synchronized
    internal fun isActive(): Boolean = engine != null
}

class LensPluginService : NexusPluginService() {
    private val runtimeHost = object : LensRuntimeHost {
        override fun send(path: String, id: String, payload: JSONObject): Boolean =
            nexusClient?.send(path, id, payload) == true

        override fun log(message: String) {
            Log.i(TAG, message)
        }
    }
    private val lifecycle by lazy {
        LensLifecycle { LensCameraSession(applicationContext, runtimeHost) }
    }

    override fun onNexusOpen() = lifecycle.open()
    override fun onNexusClose() = lifecycle.close()
    override fun onNexusInput(event: NexusInputEvent) = Unit
    override fun onNexusMessage(path: String, id: String, payload: JSONObject) =
        lifecycle.message(path, id, payload)
    override fun onNexusBinaryMessage(
        path: String,
        id: String,
        payload: JSONObject,
        data: ByteArray,
    ) = lifecycle.binary(path, id, payload, data)

    override fun onNexusRegistrationState(result: Int) {
        if (result != PluginRegistrationResult.APPROVED) lifecycle.close()
    }

    override fun onDestroy() {
        lifecycle.close()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "NexusLens"
    }
}

internal class LensCameraSession(
    context: Context,
    private val host: LensRuntimeHost,
) : LensSessionEngine {
    private data class OcrLine(
        val source: String,
        val box: Rect,
        val medianLineHeight: Float,
        val growDown: Float,
        val column: Int,
    )

    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val seq = AtomicLong(0L)
    private val preferences = lensPreferences(appContext)
    private val translator: TranslationProvider = TranslationEngineRouter(appContext)
    private val frozenOcr = PhoneFrozenOcr()
    private val frozenExecutor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lens-frozen-pipeline").apply { isDaemon = true }
    }
    private val pool = Nv21BufferPool()
    private val holder = LatestFrameHolder()
    private val overlayComposer = LiveOverlayComposer()
    private val sessionGeneration = AtomicLong(0L)
    private val activeFrozenBitmaps = ConcurrentHashMap.newKeySet<Bitmap>()
    private val activeFrozenTranslations = ConcurrentHashMap.newKeySet<TranslationCall>()
    private val frozenChunkAssembler = FrozenImageChunkAssembler()
    private val processedFrozenRequests = linkedSetOf<Pair<String, Long>>()
    private val liveTranslation = LiveTranslationScheduler(
        translator = translator,
        log = host::log,
        onUpdates = ::onLiveTranslations,
    )
    private val liveOcr: LiveOcrRunner
    private val frozenProcessingGate: FrozenProcessingGate
    private val decoder: LatestFrameDecoder
    private val imageLink: PhoneLensImageLink
    private val firstFrameLogged = AtomicBoolean(false)
    @Volatile private var sessionId: String? = null
    @Volatile private var sessionStartedAtMs = 0L

    init {
        liveOcr = LiveOcrRunner(
            holder = holder,
            onRecognized = ::onLiveRecognized,
            onError = { host.log("live OCR failed") },
        )
        frozenProcessingGate = FrozenProcessingGate(
            pauseLive = liveOcr::pause,
            cancelLiveTranslations = liveTranslation::reset,
            resumeLive = liveOcr::resume,
        )
        decoder = LatestFrameDecoder(
            holder = holder,
            pool = pool,
            onFrameReady = {
                if (firstFrameLogged.compareAndSet(false, true)) {
                    host.log("cameraLinkStage stage=first_frame elapsedMs=${sessionElapsedMs()}")
                }
                frozenProcessingGate.runIfLive(liveOcr::kick)
            },
            onGeometry = { geometry ->
                val oriented = geometry.orientedSize
                host.log(
                    "decoder crop=${geometry.rasterWidth}x${geometry.rasterHeight} " +
                        "remainingRotation=${geometry.remainingRotationDegrees} " +
                        "oriented=${oriented.width}x${oriented.height}",
                )
            },
            onError = { message, failure ->
                host.log("$message type=${failure?.javaClass?.simpleName ?: "unknown"}")
            },
        )
        imageLink = PhoneLensImageLink(
            context = appContext,
            logger = host::log,
            onPacket = ::onLinkPacket,
            stageElapsedMs = ::sessionElapsedMs,
        )
    }

    override fun message(path: String, id: String, payload: JSONObject) {
        if (closed.get()) return
        when (path) {
            BusPaths.CAMERA_SESSION_STATE -> handleSessionState(payload)
            BusPaths.CAMERA_LINK_OFFER -> handleLinkOffer(payload)
        }
    }

    override fun binary(path: String, id: String, payload: JSONObject, data: ByteArray) {
        if (closed.get() || path != BusPaths.CAMERA_FREEZE_IMAGE_CHUNK) return
        val assembled = frozenChunkAssembler.accept(payload, data) ?: return
        if (assembled.metadata.sessionId != sessionId) return
        processFrozen(
            CameraLinkPacket(
                type = CameraLinkPacketType.FROZEN_IMAGE,
                requestId = assembled.metadata.requestId,
                meta = JSONObject()
                    .put("sessionId", assembled.metadata.sessionId)
                    .put("requestId", assembled.metadata.requestId)
                    .put("mimeType", "image/jpeg")
                    .put("width", assembled.metadata.width)
                    .put("height", assembled.metadata.height)
                    .put("rotationDegrees", assembled.metadata.rotationDegrees)
                    .toString(),
                payload = assembled.jpeg,
            ),
            transport = "spp",
        )
    }

    private fun handleSessionState(payload: JSONObject) {
        val incomingSession = payload.optString("sessionId")
        if (incomingSession.isBlank()) return
        when (payload.optString("state")) {
            "opened" -> {
                val config = payload.optJSONObject("config") ?: return
                if (config.optInt("protocolVersion") != 1 ||
                    config.optInt("width") !in 1..MAX_IMAGE_EDGE ||
                    config.optInt("height") !in 1..MAX_IMAGE_EDGE ||
                    config.optInt("fps") !in 1..60
                ) return
                sessionGeneration.incrementAndGet()
                sessionId = incomingSession
                sessionStartedAtMs = SystemClock.elapsedRealtime()
                firstFrameLogged.set(false)
                frozenChunkAssembler.clear()
                synchronized(processedFrozenRequests) { processedFrozenRequests.clear() }
                liveTranslation.reset()
                overlayComposer.reset()
                liveOcr.resetPolicies()
                host.log("camera session opened")
            }
            "closed" -> if (sessionId == incomingSession) close()
        }
    }

    private fun handleLinkOffer(payload: JSONObject) {
        val offer = PhoneLensLinkOffer.parse(payload) ?: return
        if (offer.sessionId != sessionId) return
        imageLink.updateOffer(payload)
    }

    private fun sessionElapsedMs(): Long =
        (SystemClock.elapsedRealtime() - sessionStartedAtMs).coerceAtLeast(0L)

    private fun onLinkPacket(packet: CameraLinkPacket) {
        if (closed.get() || sessionId == null) return
        when (packet.type) {
            CameraLinkPacketType.VIDEO_CONFIG -> {
                val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
                if (meta.optString("mimeType") != "video/avc") return
                val geometry = runCatching { LiveFrameGeometry.fromVideoConfigMetadata(meta) }
                    .getOrNull() ?: return
                val fps = meta.optInt("fps")
                if (geometry.rasterWidth !in 1..MAX_IMAGE_EDGE ||
                    geometry.rasterHeight !in 1..MAX_IMAGE_EDGE || fps !in 1..60
                ) return
                val oriented = geometry.orientedSize
                host.log(
                    "video config raster=${geometry.rasterWidth}x${geometry.rasterHeight} " +
                        "remainingRotation=${geometry.remainingRotationDegrees} " +
                        "oriented=${oriented.width}x${oriented.height}",
                )
                decoder.configure(packet.payload, geometry, fps)
            }
            CameraLinkPacketType.VIDEO_FRAME -> decoder.queue(packet)
            CameraLinkPacketType.FROZEN_IMAGE -> processFrozen(packet, transport = "wifi")
            else -> Unit
        }
    }

    private fun onLiveRecognized(
        frame: DecodedFrame,
        result: Text,
        script: OcrScript,
        continuation: () -> Unit,
    ) {
        val activeSession = sessionId
        if (closed.get() || activeSession == null || frozenProcessingGate.isActive) {
            continuation()
            return
        }
        val orientedSize = frame.geometry.orientedSize
        val targetLanguage = targetLanguage()
        val activeGeneration = sessionGeneration.get()
        val pending = runCatching {
            overlayComposer.observe(
                blocks = liveOcrBlocks(result),
                script = script,
                frameWidth = orientedSize.width,
                frameHeight = orientedSize.height,
                nowMs = SystemClock.elapsedRealtime(),
                targetLanguage = targetLanguage,
            )
        }.getOrElse {
            host.log("live overlay composition failed")
            continuation()
            return
        }
        overlayComposer.complete(pending, emptyMap())?.let { items ->
            if (!closed.get() && sessionId == activeSession && sessionGeneration.get() == activeGeneration) {
                sendLiveOverlay(activeSession, items)
            }
        }
        val candidates = overlayComposer.translationCandidates(pending, activeGeneration)
        continuation()
        frozenProcessingGate.runIfLive { liveTranslation.submit(candidates) }
    }

    private fun onLiveTranslations(updates: List<LiveTranslationUpdate>) {
        if (closed.get() || frozenProcessingGate.isActive || updates.isEmpty()) return
        val activeSession = sessionId ?: return
        val activeGeneration = sessionGeneration.get()
        val eligible = updates.filter { it.candidate.sessionGeneration == activeGeneration }
        val application = overlayComposer.applyTranslations(eligible, activeGeneration)
        host.log(
            "live translation callback candidates=${updates.size} requested=${eligible.size} " +
                "covered=${application.applied - application.fallback} fallback=${application.fallback} " +
                "stale=${application.rejected + updates.size - eligible.size}",
        )
        application.emitted?.let { items ->
            if (!closed.get() && sessionId == activeSession && sessionGeneration.get() == activeGeneration) {
                sendLiveOverlay(activeSession, items)
            }
        }
    }

    private fun processFrozen(packet: CameraLinkPacket, transport: String) {
        val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
        val activeSession = sessionId ?: return
        val packetSession = meta.optString("sessionId")
        val requestId = meta.optLong("requestId", Long.MIN_VALUE)
        val width = meta.optInt("width")
        val height = meta.optInt("height")
        val rotation = frozenRemainingRotation(meta) ?: return
        val crop = meta.optJSONArray("crop")?.takeIf { it.length() == 4 }?.let {
            Rect(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3))
        }
        if (packetSession != activeSession || requestId == Long.MIN_VALUE ||
            requestId != packet.requestId || meta.optString("mimeType") != "image/jpeg" ||
            width !in 1..MAX_IMAGE_EDGE || height !in 1..MAX_IMAGE_EDGE ||
            rotation !in setOf(0, 90, 180, 270) || packet.payload.isEmpty()
        ) return
        val requestKey = activeSession to requestId
        synchronized(processedFrozenRequests) {
            if (!processedFrozenRequests.add(requestKey)) return
            while (processedFrozenRequests.size > MAX_PROCESSED_FROZEN_REQUESTS) {
                processedFrozenRequests.remove(processedFrozenRequests.first())
            }
        }
        val frozenWork = runCatching { frozenProcessingGate.begin() }
            .getOrElse { return }
            ?: return
        val targetScript = liveOcr.activeScript().toPhoneOcrScript()
        val jpeg = packet.payload
        runCatching {
            logFrozenStage(
                stage = "frozen_received",
                requestId = requestId,
                detail = "transport=$transport bytes=${packet.payload.size}",
            )
            frozenExecutor.execute {
                val bitmap = runCatching { decodeFrozenJpeg(jpeg, width, height, crop, rotation) }
                    .getOrElse {
                        failFrozenProcessing(requestId, "decode", null, frozenWork, it)
                        return@execute
                    }
                runCatching {
                    logFrozenStage(
                        stage = "frozen_decoded",
                        requestId = requestId,
                        detail = "width=${bitmap.width} height=${bitmap.height}",
                    )
                    activeFrozenBitmaps += bitmap
                    if (closed.get()) {
                        finishFrozenProcessing(bitmap, frozenWork)
                        return@execute
                    }
                    frozenOcr.recognize(
                        bitmap = bitmap,
                        targetScriptPlan = listOf(targetScript),
                    ) { recognized ->
                        if (closed.get()) {
                            finishFrozenProcessing(bitmap, frozenWork)
                            return@recognize
                        }
                        recognized.fold(
                            onSuccess = { result ->
                                startFrozenTranslation(
                                    activeSession = activeSession,
                                    requestId = requestId,
                                    bitmap = bitmap,
                                    result = result,
                                    frozenWork = frozenWork,
                                )
                            },
                            onFailure = {
                                failFrozenProcessing(requestId, "ocr", bitmap, frozenWork, it)
                            },
                        )
                    }
                }.onFailure {
                    failFrozenProcessing(requestId, "pipeline_start", bitmap, frozenWork, it)
                }
            }
        }.onFailure {
            failFrozenProcessing(requestId, "queue", null, frozenWork, it)
        }
    }

    private fun startFrozenTranslation(
        activeSession: String,
        requestId: Long,
        bitmap: Bitmap,
        result: PhoneFrozenOcrResult,
        frozenWork: FrozenProcessingGate.Lease,
    ) {
        var translation: TranslationCall? = null
        runCatching {
            val lines = frozenOcrLines(result.text)
            logFrozenStage(
                stage = "frozen_ocr_done",
                requestId = requestId,
                detail = "script=${result.script.wireValue} items=${lines.size}",
            )
            val batch = FrozenTranslationBatch(
                translator = translator,
                requestPrefix = "freeze-$requestId",
                targetLanguage = targetLanguage(),
                mode = result.script.toRecognizerMode(),
                sources = lines.map(OcrLine::source),
                log = host::log,
                onComplete = { translations ->
                    try {
                        translation?.let { activeFrozenTranslations -= it }
                        logFrozenStage(
                            stage = "frozen_translated",
                            requestId = requestId,
                            detail = "requested=${lines.size} results=${translations.size}",
                        )
                        if (!closed.get() && sessionId == activeSession) {
                            sendOverlay(
                                BusPaths.CAMERA_FREEZE_RESULT, activeSession, requestId,
                                lines, translations, bitmap.width, bitmap.height,
                            )
                        }
                    } finally {
                        finishFrozenProcessing(bitmap, frozenWork)
                    }
                },
            )
            translation = batch
            activeFrozenTranslations += batch
            batch.start()
        }.onFailure { failure ->
            translation?.let {
                activeFrozenTranslations -= it
                it.cancel()
            }
            failFrozenProcessing(requestId, "translation_start", bitmap, frozenWork, failure)
        }
    }

    private fun failFrozenProcessing(
        requestId: Long,
        step: String,
        bitmap: Bitmap?,
        frozenWork: FrozenProcessingGate.Lease,
        failure: Throwable,
    ) {
        try {
            if (!closed.get()) {
                logFrozenStage(
                    stage = "frozen_failed",
                    requestId = requestId,
                    detail = "step=$step type=${failure.javaClass.simpleName}",
                )
            }
        } finally {
            finishFrozenProcessing(bitmap, frozenWork)
        }
    }

    private fun finishFrozenProcessing(
        bitmap: Bitmap?,
        frozenWork: FrozenProcessingGate.Lease,
    ) {
        try {
            bitmap?.let(::recycleFrozen)
        } finally {
            frozenWork.close()
        }
    }

    private fun logFrozenStage(stage: String, requestId: Long, detail: String) {
        host.log(
            "cameraLinkStage stage=$stage requestId=$requestId $detail " +
                "elapsedMs=${sessionElapsedMs()}",
        )
    }

    private fun sendOverlay(
        path: String,
        activeSession: String,
        requestId: Long?,
        lines: List<OcrLine>,
        translations: Map<String, TranslationResult>,
        width: Int,
        height: Int,
    ) {
        val items = JSONArray()
        var fallbackCount = 0
        lines.take(CameraOverlayContract.MAX_ITEMS).forEach { line ->
            val box = normalizedBox(line.box, width, height) ?: return@forEach
            val display = overlayDisplay(line.source, translations[line.source])
            if (display.fallback) fallbackCount += 1
            items.put(
                JSONObject()
                    .put("text", display.text)
                    .put(
                        "box",
                        JSONObject()
                            .put("left", box[0]).put("top", box[1])
                            .put("right", box[2]).put("bottom", box[3]),
                    )
                    .put("role", display.role)
                    .apply { display.reason?.let { put("reason", it) } }
                    .put("layout", paragraphLayoutJson(line, height)),
            )
        }
        val payload = JSONObject()
            .put("version", CameraOverlayContract.VERSION)
            .put("sessionId", activeSession)
            .put("seq", seq.incrementAndGet())
            .put("items", items)
        if (requestId != null) payload.put("requestId", requestId)
        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8).size
        val dispatchAccepted = host.send(
            path,
            requestId?.toString() ?: UUID.randomUUID().toString(),
            payload,
        )
        host.log(
            "overlay dispatch path=$path requestId=${requestId ?: "-"} " +
                "candidates=${lines.size} requested=${translations.size} " +
                "covered=${items.length() - fallbackCount} fallback=$fallbackCount " +
                "itemCount=${items.length()} jsonUtf8Bytes=$payloadBytes " +
                "warningOver3KiB=${payloadBytes > THREE_KIB} dispatchAccepted=$dispatchAccepted",
        )
    }

    private fun sendLiveOverlay(
        activeSession: String,
        composed: List<ComposedLiveOverlayItem>,
    ) {
        val items = JSONArray()
        composed.take(CameraOverlayContract.MAX_ITEMS).forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id.take(CameraOverlayContract.MAX_ID_CHARS))
                    .put("text", item.text)
                    .put(
                        "box",
                        JSONObject()
                            .put("left", item.box.left)
                            .put("top", item.box.top)
                            .put("right", item.box.right)
                            .put("bottom", item.box.bottom),
                    )
                    .put("role", item.role)
                    .apply { item.reason?.let { put("reason", it) } }
                    .put("layout", paragraphLayoutJson(item.layout)),
            )
        }
        val payload = JSONObject()
            .put("version", CameraOverlayContract.VERSION)
            .put("sessionId", activeSession)
            .put("seq", seq.incrementAndGet())
            .put("items", items)
        val payloadBytes = payload.toString().toByteArray(Charsets.UTF_8).size
        val fallbackCount = composed.count { it.role != ROLE_TRANSLATION }
        val dispatchAccepted = host.send(
            BusPaths.CAMERA_OVERLAY,
            UUID.randomUUID().toString(),
            payload,
        )
        host.log(
            "overlay dispatch path=${BusPaths.CAMERA_OVERLAY} candidates=${composed.size} " +
                "requested=${composed.size} covered=${composed.size - fallbackCount} " +
                "fallback=$fallbackCount itemCount=${items.length()} jsonUtf8Bytes=$payloadBytes " +
                "warningOver3KiB=${payloadBytes > THREE_KIB} dispatchAccepted=$dispatchAccepted",
        )
    }

    private fun paragraphLayoutJson(line: OcrLine, height: Int): JSONObject =
        normalizedParagraphLayoutJson(
            medianLineHeight = line.medianLineHeight,
            growDown = line.growDown,
            frameHeight = height,
            column = line.column,
        )

    private fun liveOcrBlocks(text: Text): List<LiveFrameParagraphBlock> =
        text.textBlocks.mapNotNull { block ->
            val lines = block.lines.mapNotNull { line ->
                val source = normalizeSource(line.text)
                val box = line.boundingBox
                if (source.isBlank() || box == null || box.width() <= 0 || box.height() <= 0) {
                    null
                } else {
                    LiveFrameParagraphLine(
                        source = source,
                        bounds = FrozenLayoutRect(box.left, box.top, box.right, box.bottom),
                    )
                }
            }
            lines.takeIf { it.isNotEmpty() }?.let(::LiveFrameParagraphBlock)
        }

    private fun frozenOcrLines(text: Text): List<OcrLine> {
        val blocks = text.textBlocks.mapNotNull { block ->
            val lines = block.lines.mapNotNull { line ->
                val source = normalizeSource(line.text)
                val box = line.boundingBox
                if (source.isBlank() || box == null || box.width() <= 0 || box.height() <= 0) {
                    null
                } else {
                    FrozenLayoutLine(
                        text = source,
                        bounds = FrozenLayoutRect(box.left, box.top, box.right, box.bottom),
                    )
                }
            }
            lines.takeIf { it.isNotEmpty() }?.let(::FrozenLayoutBlock)
        }
        return aggregateFrozenOverlayLines(blocks)
            .map { line ->
                OcrLine(
                    source = line.source,
                    box = Rect(line.bounds.left, line.bounds.top, line.bounds.right, line.bounds.bottom),
                    medianLineHeight = line.medianLineHeight,
                    growDown = line.growDown,
                    column = line.column,
                )
            }
            .take(CameraOverlayContract.MAX_ITEMS)
    }

    private fun normalizeSource(value: String): String =
        value.trim().replace(Regex("\\s+"), " ")

    private fun normalizedBox(box: Rect, width: Int, height: Int): FloatArray? {
        if (width <= 0 || height <= 0) return null
        val values = floatArrayOf(
            (box.left.toFloat() / width).coerceIn(0f, 1f),
            (box.top.toFloat() / height).coerceIn(0f, 1f),
            (box.right.toFloat() / width).coerceIn(0f, 1f),
            (box.bottom.toFloat() / height).coerceIn(0f, 1f),
        )
        return values.takeIf { it[0] < it[2] && it[1] < it[3] }
    }

    private fun targetLanguage(): String = preferences.getString(
        LENS_TRANSLATION_PREF_TARGET_LANG,
        LENS_TRANSLATION_TARGET_LANG_DEFAULT,
    ).orEmpty().trim().takeIf(String::isNotBlank) ?: LENS_TRANSLATION_TARGET_LANG_DEFAULT

    @Suppress("DEPRECATION")
    private fun decodeFrozenJpeg(
        jpeg: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        crop: Rect?,
        rotationDegrees: Int,
    ): Bitmap {
        var bitmap = if (crop != null) {
            require(crop.width() > 0 && crop.height() > 0)
            val regionDecoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
                ?: error("JPEG region decoder unavailable")
            try {
                regionDecoder.decodeRegion(crop, BitmapFactory.Options()) ?: error("JPEG crop decode failed")
            } finally {
                regionDecoder.recycle()
            }
        } else {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size) ?: error("JPEG decode failed")
        }
        if (rotationDegrees != 0) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotationDegrees.toFloat()) }, true,
            )
            if (rotated !== bitmap) bitmap.recycle()
            bitmap = rotated
        }
        if (bitmap.width != targetWidth || bitmap.height != targetHeight) {
            val scaled = Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true)
            if (scaled !== bitmap) bitmap.recycle()
            bitmap = scaled
        }
        return bitmap
    }

    private fun PhoneOcrScript.toRecognizerMode(): LensRecognizerMode = when (this) {
        PhoneOcrScript.LATIN -> LensRecognizerMode.LATIN
        PhoneOcrScript.JAPANESE -> LensRecognizerMode.JAPANESE
        PhoneOcrScript.CHINESE -> LensRecognizerMode.CHINESE
        PhoneOcrScript.KOREAN -> LensRecognizerMode.KOREAN
        PhoneOcrScript.DEVANAGARI -> LensRecognizerMode.DEVANAGARI
    }

    private fun OcrScript.toRecognizerMode(): LensRecognizerMode = when (this) {
        OcrScript.LATIN -> LensRecognizerMode.LATIN
        OcrScript.JAPANESE -> LensRecognizerMode.JAPANESE
        OcrScript.CHINESE -> LensRecognizerMode.CHINESE
        OcrScript.KOREAN -> LensRecognizerMode.KOREAN
        OcrScript.DEVANAGARI -> LensRecognizerMode.DEVANAGARI
    }

    private fun OcrScript.toPhoneOcrScript(): PhoneOcrScript = when (this) {
        OcrScript.LATIN -> PhoneOcrScript.LATIN
        OcrScript.JAPANESE -> PhoneOcrScript.JAPANESE
        OcrScript.CHINESE -> PhoneOcrScript.CHINESE
        OcrScript.KOREAN -> PhoneOcrScript.KOREAN
        OcrScript.DEVANAGARI -> PhoneOcrScript.DEVANAGARI
    }

    private fun recycleFrozen(bitmap: Bitmap) {
        activeFrozenBitmaps -= bitmap
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionId = null
        frozenProcessingGate.close()
        frozenChunkAssembler.clear()
        synchronized(processedFrozenRequests) { processedFrozenRequests.clear() }
        imageLink.close()
        decoder.close()
        liveOcr.close()
        sessionGeneration.incrementAndGet()
        liveTranslation.close()
        activeFrozenTranslations.toList().forEach(TranslationCall::cancel)
        activeFrozenTranslations.clear()
        frozenExecutor.shutdownNow()
        frozenOcr.close()
        translator.close()
        activeFrozenBitmaps.toList().forEach(::recycleFrozen)
        holder.close()
        pool.close()
        overlayComposer.reset()
        host.log("camera session closed")
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 4096
        const val MAX_PROCESSED_FROZEN_REQUESTS = 64
        const val THREE_KIB = 3 * 1024
    }
}
