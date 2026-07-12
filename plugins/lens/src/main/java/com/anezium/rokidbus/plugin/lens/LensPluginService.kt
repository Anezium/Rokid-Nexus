package com.anezium.rokidbus.plugin.lens

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
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
import java.io.ByteArrayInputStream
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
}

internal fun interface LensSessionEngineFactory {
    fun create(): LensSessionEngine
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
    private data class OcrLine(val source: String, val box: Rect)

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
    private val emissionPolicy = OverlayEmissionPolicy()
    private val activeFrozenBitmaps = ConcurrentHashMap.newKeySet<Bitmap>()
    private val liveOcr: LiveOcrRunner
    private val decoder: LatestFrameDecoder
    private val imageLink: PhoneLensImageLink
    @Volatile private var sessionId: String? = null

    init {
        liveOcr = LiveOcrRunner(
            holder = holder,
            onRecognized = ::onLiveRecognized,
            onError = { host.log("live OCR failed") },
        )
        decoder = LatestFrameDecoder(
            holder = holder,
            pool = pool,
            onFrameReady = liveOcr::kick,
            onError = { message, failure ->
                host.log("$message type=${failure?.javaClass?.simpleName ?: "unknown"}")
            },
        )
        imageLink = PhoneLensImageLink(appContext, host::log, ::onLinkPacket)
    }

    override fun message(path: String, id: String, payload: JSONObject) {
        if (closed.get()) return
        when (path) {
            BusPaths.CAMERA_SESSION_STATE -> handleSessionState(payload)
            BusPaths.CAMERA_LINK_OFFER -> handleLinkOffer(payload)
        }
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
                sessionId = incomingSession
                emissionPolicy.reset()
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

    private fun onLinkPacket(packet: CameraLinkPacket) {
        if (closed.get() || sessionId == null) return
        when (packet.type) {
            CameraLinkPacketType.VIDEO_CONFIG -> {
                val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
                if (meta.optString("mimeType") != "video/avc") return
                decoder.configure(packet.payload, meta.optInt("width"), meta.optInt("height"), meta.optInt("fps"))
            }
            CameraLinkPacketType.VIDEO_FRAME -> decoder.queue(packet)
            CameraLinkPacketType.FROZEN_IMAGE -> processFrozen(packet)
            else -> Unit
        }
    }

    private fun onLiveRecognized(frame: DecodedFrame, result: Text, continuation: () -> Unit) {
        val activeSession = sessionId
        if (closed.get() || activeSession == null || !emissionPolicy.shouldEmit(result.text)) {
            continuation()
            return
        }
        val lines = ocrLines(result)
        if (lines.isEmpty()) {
            continuation()
            return
        }
        translate(
            requestId = "live-${frame.frameId}-${seq.incrementAndGet()}",
            mode = LensRecognizerMode.LATIN,
            lines = lines,
            onResult = { translations ->
                if (!closed.get() && sessionId == activeSession) {
                    sendOverlay(
                        BusPaths.CAMERA_OVERLAY, activeSession, null, lines, translations,
                        frame.width, frame.height,
                    )
                }
                continuation()
            },
        )
    }

    private fun processFrozen(packet: CameraLinkPacket) {
        val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
        val activeSession = sessionId ?: return
        val packetSession = meta.optString("sessionId")
        val requestId = meta.optLong("requestId", Long.MIN_VALUE)
        val width = meta.optInt("width")
        val height = meta.optInt("height")
        val rotation = meta.optInt("rotationDegrees", meta.optInt("rotation", -1))
        val crop = meta.optJSONArray("crop")?.takeIf { it.length() == 4 }?.let {
            Rect(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3))
        }
        if (packetSession != activeSession || requestId == Long.MIN_VALUE ||
            requestId != packet.requestId || meta.optString("mimeType") != "image/jpeg" ||
            width !in 1..MAX_IMAGE_EDGE || height !in 1..MAX_IMAGE_EDGE ||
            rotation !in setOf(0, 90, 180, 270) || packet.payload.isEmpty()
        ) return
        val jpeg = packet.payload
        frozenExecutor.execute {
            val bitmap = runCatching { decodeFrozenJpeg(jpeg, width, height, crop, rotation) }
                .getOrNull() ?: return@execute
            activeFrozenBitmaps += bitmap
            if (closed.get()) {
                recycleFrozen(bitmap)
                return@execute
            }
            frozenOcr.recognize(bitmap) { recognized ->
                if (closed.get()) {
                    recycleFrozen(bitmap)
                    return@recognize
                }
                recognized.fold(
                    onSuccess = { result ->
                        val lines = ocrLines(result.text)
                        translate(
                            requestId = "freeze-$requestId",
                            mode = result.script.toRecognizerMode(),
                            lines = lines,
                            onResult = { translations ->
                                try {
                                    if (!closed.get() && sessionId == activeSession) {
                                        sendOverlay(
                                            BusPaths.CAMERA_FREEZE_RESULT, activeSession, requestId,
                                            lines, translations, bitmap.width, bitmap.height,
                                        )
                                    }
                                } finally {
                                    recycleFrozen(bitmap)
                                }
                            },
                        )
                    },
                    onFailure = { recycleFrozen(bitmap) },
                )
            }
        }
    }

    private fun translate(
        requestId: String,
        mode: LensRecognizerMode,
        lines: List<OcrLine>,
        onResult: (Map<String, TranslationResult>) -> Unit,
    ) {
        if (closed.get()) {
            onResult(emptyMap())
            return
        }
        val strings = lines.asSequence().map(OcrLine::source).distinct().take(MAX_TRANSLATION_STRINGS).toList()
        if (strings.isEmpty()) {
            onResult(emptyMap())
            return
        }
        val callback = object : TranslationProvider.Callback {
            override fun onDownloading(status: TranslationDownloadStatus) = Unit
            override fun onSuccess(translations: List<TranslationResult>) =
                onResult(translations.associateBy(TranslationResult::src))
            override fun onError(error: TranslationErrorCode) = onResult(emptyMap())
        }
        runCatching {
            translator.translate(TranslationRequest(requestId, targetLanguage(), mode, strings), callback)
        }.onFailure { onResult(emptyMap()) }
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
        lines.take(CameraOverlayContract.MAX_ITEMS).forEach { line ->
            val box = normalizedBox(line.box, width, height) ?: return@forEach
            val translated = translations[line.source]
            val output = translated?.dst?.takeIf(String::isNotBlank) ?: line.source
            items.put(
                JSONObject()
                    .put("text", output.take(CameraOverlayContract.MAX_TEXT_CHARS))
                    .put(
                        "box",
                        JSONObject()
                            .put("left", box[0]).put("top", box[1])
                            .put("right", box[2]).put("bottom", box[3]),
                    )
                    .put(
                        "role",
                        if (translated == null || translated.fallback || translated.dst == line.source) {
                            "source"
                        } else "translation",
                    ),
            )
        }
        val payload = JSONObject()
            .put("version", CameraOverlayContract.VERSION)
            .put("sessionId", activeSession)
            .put("seq", seq.incrementAndGet())
            .put("items", items)
        if (requestId != null) payload.put("requestId", requestId)
        host.send(path, requestId?.toString() ?: UUID.randomUUID().toString(), payload)
    }

    private fun ocrLines(text: Text): List<OcrLine> = text.textBlocks
        .flatMap(Text.TextBlock::getLines)
        .mapNotNull { line ->
            val source = normalizeSource(line.text)
            val box = line.boundingBox
            if (source.isBlank() || box == null || box.width() <= 0 || box.height() <= 0) null
            else OcrLine(source, Rect(box))
        }
        .take(CameraOverlayContract.MAX_ITEMS)

    private fun normalizeSource(value: String): String =
        value.trim().replace(Regex("\\s+"), " ").take(MAX_SOURCE_CHARS)

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
        val rotation = rotationDegrees.takeIf { it >= 0 } ?: runCatching {
            when (
                ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)
        if (rotation != 0) {
            val rotated = Bitmap.createBitmap(
                bitmap, 0, 0, bitmap.width, bitmap.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true,
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

    private fun recycleFrozen(bitmap: Bitmap) {
        activeFrozenBitmaps -= bitmap
        if (!bitmap.isRecycled) bitmap.recycle()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionId = null
        imageLink.close()
        decoder.close()
        liveOcr.close()
        frozenExecutor.shutdownNow()
        frozenOcr.close()
        translator.close()
        activeFrozenBitmaps.toList().forEach(::recycleFrozen)
        holder.close()
        pool.close()
        emissionPolicy.reset()
        host.log("camera session closed")
    }

    private companion object {
        const val MAX_IMAGE_EDGE = 4096
        const val MAX_TRANSLATION_STRINGS = 24
        const val MAX_SOURCE_CHARS = 1_024
    }
}
