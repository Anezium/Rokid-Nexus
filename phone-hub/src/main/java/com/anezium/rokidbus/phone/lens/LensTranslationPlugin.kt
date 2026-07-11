package com.anezium.rokidbus.phone.lens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.graphics.Rect
import android.media.ExifInterface
import com.anezium.rokidbus.shared.LensFrozenOcrResult
import com.anezium.rokidbus.shared.LensLinkPacket
import com.anezium.rokidbus.shared.LensLinkPacketType
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LensWireContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import com.anezium.rokidbus.shared.plugin.NexusSubscription
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class LensTranslationPlugin internal constructor(
    private val injectedProvider: TranslationProvider?,
    private val enableImageLink: Boolean = false,
    private val injectedTargetLanguageSupplier: (() -> String?)? = null,
) : NexusPlugin, AutoCloseable {
    constructor() : this(null, true, null)

    override val id: String = "lens"
    override val displayName: String = "Lens"
    override val launchable: Boolean = false

    private class ActiveRequest(
        var call: TranslationCall = TranslationCall.NONE,
        var deadline: ScheduledFuture<*>? = null,
        var deadlineGeneration: Long = 0L,
    )

    private lateinit var host: NexusPluginHost
    private lateinit var provider: TranslationProvider
    private lateinit var targetLanguageSupplier: () -> String?
    private var subscription: NexusSubscription? = null
    private var linkSubscription: NexusSubscription? = null
    private var imageLink: PhoneLensImageLink? = null
    private var frozenOcr: PhoneFrozenOcr? = null
    private var frozenImageExecutor: ExecutorService? = null
    private val activeRequestsLock = Any()
    private val activeRequests = linkedMapOf<String, ActiveRequest>()
    private val closed = AtomicBoolean(false)
    private val deadlineExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "lens-translate-timeout").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        provider = injectedProvider ?: TranslationEngineRouter(host.context)
        targetLanguageSupplier = injectedTargetLanguageSupplier ?: if (injectedProvider != null) {
            { null }
        } else run {
            val preferences = host.context.applicationContext.getSharedPreferences(
                LENS_TRANSLATION_PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
            val supplier: () -> String? = {
                preferences.getString(
                    LENS_TRANSLATION_PREF_TARGET_LANG,
                    LENS_TRANSLATION_TARGET_LANG_DEFAULT,
                )
            }
            supplier
        }
        // Registry dispatch is prefix-based, so the exact-path guard must reject the /reply path.
        subscription = host.subscribe(BusPaths.LENS_TRANSLATE_REQUEST) { path, envelopeId, payload ->
            if (path == BusPaths.LENS_TRANSLATE_REQUEST) handleRequest(envelopeId, payload)
        }
        if (enableImageLink) {
            frozenOcr = PhoneFrozenOcr()
            frozenImageExecutor = Executors.newSingleThreadExecutor { runnable ->
                Thread(runnable, "lens-frozen-image").apply { isDaemon = true }
            }
            imageLink = PhoneLensImageLink(host.context, host::log, ::handleLinkPacket)
            linkSubscription = host.subscribe(BusPaths.LENS_LINK_OFFER) { path, _, payload ->
                if (path == BusPaths.LENS_LINK_OFFER) imageLink?.updateOffer(payload)
            }
        }
    }

    override fun onOpen() {
        host.log("lens translation service is headless; open the Lens glasses app")
    }

    override fun onClose() = Unit

    override fun onInput(event: NexusInputEvent) = Unit

    internal val imageLinkState: PhoneLensLinkState
        get() = imageLink?.state ?: PhoneLensLinkState.IDLE

    private fun handleLinkPacket(packet: LensLinkPacket) {
        if (closed.get() || packet.type != LensLinkPacketType.FROZEN_IMAGE) return
        val meta = runCatching { JSONObject(packet.meta) }.getOrNull() ?: return
        val freezeId = meta.optLong("freezeId", Long.MIN_VALUE)
        val stage = meta.optString("stage")
        val width = meta.optInt("width")
        val height = meta.optInt("height")
        val scriptPlan = phoneOcrScriptPlan(meta.optString("script"))
        val crop = meta.optJSONArray("crop")?.takeIf { it.length() == 4 }?.let {
            Rect(it.optInt(0), it.optInt(1), it.optInt(2), it.optInt(3))
        }
        val rotation = meta.optInt("rotation", -1).takeIf { it == 0 || it == 90 || it == 180 || it == 270 }
        if (freezeId == Long.MIN_VALUE || freezeId != packet.requestId ||
            (stage != LensWireContract.FROZEN_STAGE_FAST && stage != LensWireContract.FROZEN_STAGE_HD) ||
            width !in 1..MAX_FROZEN_IMAGE_EDGE || height !in 1..MAX_FROZEN_IMAGE_EDGE ||
            packet.payload.isEmpty()
        ) {
            host.log("lens frozen image rejected")
            return
        }
        val jpeg = packet.payload
        frozenImageExecutor?.execute {
            val bitmap = runCatching { decodeFrozenJpeg(jpeg, width, height, crop, rotation) }
                .onFailure { host.log("lens frozen image decode failed type=${it.javaClass.simpleName}") }
                .getOrNull() ?: return@execute
            val ocr = frozenOcr
            if (ocr == null || closed.get()) {
                bitmap.recycle()
                return@execute
            }
            ocr.recognize(bitmap, scriptPlan) { result ->
                try {
                    if (closed.get()) return@recognize
                    result.onSuccess { recognized ->
                        val payload = runCatching {
                            LensWireContract.frozenOcrResultToJson(
                                LensFrozenOcrResult(
                                    freezeId = freezeId,
                                    stage = stage,
                                    script = recognized.script.wireValue,
                                    blocks = recognized.wireBlocks(),
                                ),
                            )
                        }.onFailure {
                            host.log("lens frozen OCR result rejected type=${it.javaClass.simpleName}")
                        }.getOrNull() ?: return@onSuccess
                        postIfOpen {
                            host.send(BusPaths.LENS_FROZEN_OCR_RESULT, freezeId.toString(), payload)
                            host.log(
                                "lens frozen OCR result stage=$stage script=${recognized.script.wireValue} " +
                                    "blocks=${recognized.text.textBlocks.size}",
                            )
                        }
                    }.onFailure {
                        host.log("lens frozen OCR failed stage=$stage type=${it.javaClass.simpleName}")
                    }
                } finally {
                    if (!bitmap.isRecycled) bitmap.recycle()
                }
            }
        }
    }

    private fun decodeFrozenJpeg(
        jpeg: ByteArray,
        targetWidth: Int,
        targetHeight: Int,
        crop: Rect?,
        explicitRotation: Int?,
    ): Bitmap {
        var bitmap = if (crop != null) {
            require(crop.width() > 0 && crop.height() > 0) { "Invalid JPEG crop" }
            val decoder = BitmapRegionDecoder.newInstance(jpeg, 0, jpeg.size, false)
                ?: throw IllegalArgumentException("JPEG region decoder unavailable")
            try {
                decoder.decodeRegion(crop, BitmapFactory.Options())
                    ?: throw IllegalArgumentException("JPEG crop decode returned null")
            } finally {
                decoder.recycle()
            }
        } else {
            BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                ?: throw IllegalArgumentException("JPEG decode returned null")
        }
        val rotation = explicitRotation?.toFloat() ?: runCatching {
            when (
                ExifInterface(ByteArrayInputStream(jpeg)).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            ) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (rotation != 0f) {
            val rotated = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.width,
                bitmap.height,
                Matrix().apply { postRotate(rotation) },
                true,
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

    private fun phoneOcrScriptPlan(wireValue: String): List<PhoneOcrScript> {
        val selected = PhoneOcrScript.entries.firstOrNull { it.wireValue == wireValue }
            ?: return PhoneOcrScript.entries
        return listOf(selected) + PhoneOcrScript.entries.filter { it != selected }
    }

    private fun handleRequest(envelopeId: String, payload: JSONObject) {
        if (closed.get()) return
        when (val parsed = LensTranslationRequestParser.parse(envelopeId, payload)) {
            is LensTranslationParseResult.Failure -> {
                postIfOpen {
                    parsed.replyId?.let { sendError(it, parsed.error) }
                    host.log("lens translate request rejected code=${parsed.error.wireValue}")
                }
            }
            is LensTranslationParseResult.Success -> startRequest(parsed.request)
        }
    }

    private fun startRequest(request: TranslationRequest) {
        if (request.strings.isEmpty()) {
            postIfOpen { sendTranslations(request.id, emptyList()) }
            return
        }

        var rejection: TranslationErrorCode? = null
        val active = synchronized(activeRequestsLock) {
            when {
                closed.get() -> {
                    rejection = TranslationErrorCode.PROVIDER_CLOSED
                    null
                }
                activeRequests.containsKey(request.id) -> {
                    rejection = TranslationErrorCode.DUPLICATE_REQUEST
                    null
                }
                activeRequests.size >= MAX_ACTIVE_REQUESTS -> {
                    rejection = TranslationErrorCode.BUSY
                    null
                }
                else -> ActiveRequest().also {
                    activeRequests[request.id] = it
                    scheduleDeadlineLocked(request.id, it, LensWireContract.PHONE_REQUEST_TIMEOUT_MS)
                }
            }
        }
        if (active == null) {
            postIfOpen {
                sendError(request.id, rejection ?: TranslationErrorCode.INTERNAL_ERROR)
                host.log("lens translate request rejected code=${rejection?.wireValue ?: TranslationErrorCode.INTERNAL_ERROR.wireValue}")
            }
            return
        }

        val call = try {
            provider.translate(
                request.copy(targetLang = selectedTargetLanguage(request.targetLang)),
                object : TranslationProvider.Callback {
                    override fun onDownloading(status: TranslationDownloadStatus) {
                        if (!extendDeadline(request.id, active, LensWireContract.PHONE_MODEL_DOWNLOAD_TIMEOUT_MS)) return
                        postIfOpen {
                            if (!isActive(request.id, active)) return@postIfOpen
                            host.send(
                                BusPaths.LENS_TRANSLATE_REPLY,
                                request.id,
                                JSONObject()
                                    .put("version", PROTOCOL_VERSION)
                                    .put("id", request.id)
                                    .put("status", "downloading")
                                    .put("lang", status.srcLang)
                                    .put("targetLang", status.targetLang),
                            )
                            host.log("lens model download started languagePair=${status.srcLang}->${status.targetLang}")
                        }
                    }

                    override fun onSuccess(translations: List<TranslationResult>) {
                        if (!finishActive(request.id, active)) return
                        postIfOpen {
                            sendTranslations(request.id, translations)
                        }
                    }

                    override fun onError(error: TranslationErrorCode) {
                        if (!finishActive(request.id, active)) return
                        postIfOpen {
                            sendError(request.id, error)
                            host.log("lens translate failed code=${error.wireValue}")
                        }
                    }
                },
            )
        } catch (failure: RuntimeException) {
            if (finishActive(request.id, active)) {
                postIfOpen {
                    sendError(request.id, TranslationErrorCode.INTERNAL_ERROR)
                    host.log("lens translate failed code=${TranslationErrorCode.INTERNAL_ERROR.wireValue} exception=${failure.javaClass.simpleName}")
                }
            }
            TranslationCall.NONE
        }

        val cancelNow = synchronized(activeRequestsLock) {
            if (activeRequests[request.id] === active) {
                active.call = call
                false
            } else {
                true
            }
        }
        if (cancelNow) call.cancel()
    }

    private fun selectedTargetLanguage(wireTargetLanguage: String): String =
        runCatching(targetLanguageSupplier)
            .getOrNull()
            ?.trim()
            ?.takeIf(String::isNotEmpty)
            ?: wireTargetLanguage

    private fun sendTranslations(requestId: String, translations: List<TranslationResult>) {
        val response = fitLensTranslationResponse(
            requestId = requestId,
            translations = translations,
            protocolVersion = PROTOCOL_VERSION,
            maxPayloadBytes = MAX_RESPONSE_PAYLOAD_BYTES,
        )
        if (response == null) {
            sendError(requestId, TranslationErrorCode.RESPONSE_TOO_LARGE)
            host.log("lens translate failed code=${TranslationErrorCode.RESPONSE_TOO_LARGE.wireValue}")
            return
        }
        host.send(BusPaths.LENS_TRANSLATE_REPLY, requestId, response.payload)
        host.log("lens translate reply stringCount=${translations.size} degradedCount=${response.degradedCount}")
    }

    private fun sendError(requestId: String, error: TranslationErrorCode) {
        host.send(
            BusPaths.LENS_TRANSLATE_REPLY,
            requestId,
            JSONObject()
                .put("version", PROTOCOL_VERSION)
                .put("id", requestId)
                .put("status", "error")
                .put("error", error.wireValue),
        )
    }

    private fun isActive(requestId: String, active: ActiveRequest): Boolean =
        synchronized(activeRequestsLock) { activeRequests[requestId] === active }

    private fun finishActive(requestId: String, active: ActiveRequest): Boolean {
        var deadline: ScheduledFuture<*>? = null
        val finished = synchronized(activeRequestsLock) {
            if (activeRequests[requestId] !== active) {
                false
            } else {
                activeRequests.remove(requestId)
                active.deadlineGeneration += 1
                deadline = active.deadline
                active.deadline = null
                true
            }
        }
        deadline?.cancel(false)
        return finished
    }

    private fun extendDeadline(
        requestId: String,
        active: ActiveRequest,
        delayMs: Long,
    ): Boolean {
        var previous: ScheduledFuture<*>? = null
        val extended = synchronized(activeRequestsLock) {
            if (activeRequests[requestId] !== active || closed.get()) {
                false
            } else {
                previous = active.deadline
                scheduleDeadlineLocked(requestId, active, delayMs)
                true
            }
        }
        previous?.cancel(false)
        return extended
    }

    private fun scheduleDeadlineLocked(
        requestId: String,
        active: ActiveRequest,
        delayMs: Long,
    ) {
        active.deadlineGeneration += 1
        val generation = active.deadlineGeneration
        active.deadline = deadlineExecutor.schedule(
            { expireRequest(requestId, active, generation) },
            delayMs,
            TimeUnit.MILLISECONDS,
        )
    }

    private fun expireRequest(
        requestId: String,
        active: ActiveRequest,
        generation: Long,
    ) {
        val call = synchronized(activeRequestsLock) {
            if (closed.get() ||
                activeRequests[requestId] !== active ||
                active.deadlineGeneration != generation
            ) {
                null
            } else {
                activeRequests.remove(requestId)
                active.deadlineGeneration += 1
                active.deadline = null
                active.call
            }
        } ?: return

        call.cancel()
        postIfOpen {
            sendError(requestId, TranslationErrorCode.TIMEOUT)
            host.log("lens translate failed code=${TranslationErrorCode.TIMEOUT.wireValue}")
        }
    }

    private fun postIfOpen(action: () -> Unit) {
        if (closed.get()) return
        host.post {
            if (!closed.get()) action()
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        subscription?.close()
        subscription = null
        linkSubscription?.close()
        linkSubscription = null
        imageLink?.close()
        imageLink = null
        frozenImageExecutor?.shutdownNow()
        frozenImageExecutor = null
        frozenOcr?.close()
        frozenOcr = null
        val active = synchronized(activeRequestsLock) {
            activeRequests.values.toList().also { requests ->
                activeRequests.clear()
                requests.forEach { it.deadlineGeneration += 1 }
            }
        }
        active.forEach { request ->
            request.deadline?.cancel(false)
            request.deadline = null
            request.call.cancel()
        }
        deadlineExecutor.shutdownNow()
        if (::provider.isInitialized) provider.close()
    }

    private companion object {
        private const val PROTOCOL_VERSION = 1
        private const val MAX_ACTIVE_REQUESTS = 8
        private const val MAX_RESPONSE_PAYLOAD_BYTES = 128 * 1024
        private const val MAX_FROZEN_IMAGE_EDGE = 4096
    }
}
