package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.CameraOverlayContract
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Base64
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

/** Opt-in: the frozen photo itself is sent to Google, so it is off until the user enables it. */
const val LENS_PREF_GEMINI_VISION_FROZEN = "gemini_vision_frozen"

internal const val GEMINI_VISION_MAX_PARAGRAPHS = 48
internal const val GEMINI_VISION_MAX_IMAGE_EDGE = 1_536
internal const val GEMINI_VISION_JPEG_QUALITY = 85
private const val GEMINI_VISION_BOX_SCALE = 1_000
private const val GEMINI_VISION_CONNECT_TIMEOUT_MS = 5_000
// One call reads and translates a whole photo; field-measured text-only Gemini calls already
// take ~3s, so the text path's 4s read timeout would cut most vision replies short. The phone
// never hears that the user left the frozen view, so this also bounds how long live OCR stays
// paused behind an abandoned photo.
private const val GEMINI_VISION_READ_TIMEOUT_MS = 20_000
private const val GEMINI_VISION_MAX_RESPONSE_BYTES = 2 * 1024 * 1024

internal data class GeminiVisionParagraph(
    val source: String,
    val translation: String,
    val srcLang: String,
    val bounds: FrozenLayoutRect,
    val lineCount: Int,
)

internal class GeminiVisionException(val reason: String) : Exception(reason)

internal const val GEMINI_VISION_SKIPPED = "skipped"

internal object GeminiVisionResponseParser {
    /**
     * Maps Gemini's `box_2d` ([ymin, xmin, ymax, xmax] on a 0-1000 grid) onto the frozen bitmap.
     * A malformed paragraph is dropped rather than failing the photo: one bad box should not cost
     * the user every other line Gemini read correctly.
     */
    fun parse(response: String, width: Int, height: Int): List<GeminiVisionParagraph> {
        require(width > 0 && height > 0)
        val root = strictJsonValue(response) as? JSONObject ?: throw GeminiVisionException("malformed")
        val candidate = (root.opt("candidates") as? JSONArray)?.opt(0) as? JSONObject
            ?: throw GeminiVisionException(blockedReason(root))
        val finishReason = candidate.optString("finishReason").lowercase(Locale.ROOT)
        val failureReason = finishReason.takeIf { it.isNotBlank() && it != "stop" } ?: "malformed"
        val parts = ((candidate.opt("content") as? JSONObject)?.opt("parts") as? JSONArray)
            ?: throw GeminiVisionException(failureReason)
        val replyText = buildString {
            for (index in 0 until parts.length()) {
                val part = parts.opt(index) as? JSONObject ?: continue
                if (part.optBoolean("thought", false)) continue
                (part.opt("text") as? String)?.let(::append)
            }
        }
        // A MAX_TOKENS cutoff still carries thought or partial JSON parts; name the cutoff.
        val entries = runCatching { strictJsonValue(replyText) }.getOrNull() as? JSONArray
            ?: throw GeminiVisionException(failureReason)
        return (0 until entries.length())
            .mapNotNull { index -> paragraph(entries.opt(index), width, height) }
            .take(GEMINI_VISION_MAX_PARAGRAPHS)
    }

    private fun paragraph(raw: Any?, width: Int, height: Int): GeminiVisionParagraph? {
        val entry = raw as? JSONObject ?: return null
        val source = (entry.opt("text") as? String)?.trim()?.replace(WHITESPACE, " ")
            ?.takeIf(String::isNotEmpty) ?: return null
        val translation = (entry.opt("translation") as? String)?.trim().orEmpty()
        val box = entry.opt("box_2d") as? JSONArray ?: return null
        if (box.length() != 4) return null
        val values = IntArray(4)
        for (index in 0 until 4) {
            val value = (box.opt(index) as? Number)?.toDouble()?.takeIf(Double::isFinite) ?: return null
            values[index] = value.toInt().coerceIn(0, GEMINI_VISION_BOX_SCALE)
        }
        val (yMin, xMin, yMax, xMax) = values
        val bounds = FrozenLayoutRect(
            left = scale(xMin, width),
            top = scale(yMin, height),
            right = scale(xMax, width),
            bottom = scale(yMax, height),
        )
        if (bounds.width <= 0 || bounds.height <= 0) return null
        val lineCount = (entry.opt("lines") as? Number)?.toInt()?.coerceIn(1, FROZEN_PARAGRAPH_MAX_LINES * 4) ?: 1
        return GeminiVisionParagraph(
            source = source,
            translation = translation,
            srcLang = (entry.opt("srcLang") as? String).orEmpty().trim().lowercase(Locale.ROOT),
            bounds = bounds,
            lineCount = lineCount,
        )
    }

    private fun scale(value: Int, extent: Int): Int =
        (value.toLong() * extent / GEMINI_VISION_BOX_SCALE).toInt().coerceIn(0, extent)

    private fun blockedReason(root: JSONObject): String =
        (root.opt("promptFeedback") as? JSONObject)?.optString("blockReason")
            ?.lowercase(Locale.ROOT)?.takeIf(String::isNotBlank)
            ?.let { "blocked_$it" }
            ?: "no_candidate"

    private fun strictJsonValue(json: String): Any {
        try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') throw GeminiVisionException("malformed")
            return value
        } catch (_: JSONException) {
            throw GeminiVisionException("malformed")
        }
    }

    private val WHITESPACE = Regex("\\s+")
}

internal fun buildGeminiVisionPrompt(targetLanguage: String): String =
    "You are the OCR and translation engine of camera translation glasses. Read every piece of " +
        "legible text in this photo, in any language or script (for example Thai, Arabic, " +
        "Cyrillic, Greek, Hebrew, Latin, CJK). Group the text into paragraphs: a sign, a menu " +
        "item, a label, a heading, or consecutive lines that read together. For each paragraph " +
        "return: \"text\", the original text exactly as written with its lines joined by a " +
        "space; \"translation\", that text translated into the language with code " +
        "\"$targetLanguage\" (copy the text unchanged if it is already in that language); " +
        "\"srcLang\", the ISO 639-1 code of the source language; \"lines\", the number of " +
        "visual lines; \"box_2d\", the paragraph's bounding box as [ymin, xmin, ymax, xmax] " +
        "normalized to 0-1000. Skip text too small or blurry to read. Order paragraphs top to " +
        "bottom, then left to right, and return at most $GEMINI_VISION_MAX_PARAGRAPHS. Return " +
        "an empty array when the photo has no legible text."

internal fun buildGeminiVisionRequestBody(jpeg: ByteArray, targetLanguage: String): ByteArray {
    val stringType = JSONObject().put("type", "STRING")
    val schema = JSONObject()
        .put("type", "ARRAY")
        .put(
            "items",
            JSONObject()
                .put("type", "OBJECT")
                .put(
                    "properties",
                    JSONObject()
                        .put("text", stringType)
                        .put("translation", stringType)
                        .put("srcLang", stringType)
                        .put("lines", JSONObject().put("type", "INTEGER"))
                        .put(
                            "box_2d",
                            JSONObject().put("type", "ARRAY").put("items", JSONObject().put("type", "INTEGER")),
                        ),
                )
                .put("required", JSONArray(listOf("text", "translation", "lines", "box_2d"))),
        )
    return JSONObject()
        .put(
            "contents",
            JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put(
                        "parts",
                        JSONArray()
                            .put(
                                JSONObject().put(
                                    "inline_data",
                                    JSONObject()
                                        .put("mime_type", "image/jpeg")
                                        .put("data", Base64.getEncoder().encodeToString(jpeg)),
                                ),
                            )
                            .put(JSONObject().put("text", buildGeminiVisionPrompt(targetLanguage))),
                    ),
            ),
        )
        .put(
            "generationConfig",
            JSONObject()
                .put("temperature", 0.1)
                .put("response_mime_type", "application/json")
                .put("response_schema", schema),
        )
        .toString()
        .toByteArray(Charsets.UTF_8)
}

internal fun geminiVisionHttpReason(status: Int): String = when (status) {
    400 -> "bad_request"
    401, 403 -> "key_rejected"
    404 -> "model_unavailable"
    429 -> "quota"
    in 500..599 -> "server_$status"
    else -> "http_$status"
}

internal fun geminiVisionRetriesOnRescue(failure: Throwable): Boolean =
    failure is SocketTimeoutException ||
        (failure is GeminiVisionException &&
            (failure.reason == "model_unavailable" || failure.reason.startsWith("server_")))

/**
 * Reads and translates a frozen photo in a single Gemini call. Only the frozen path uses it: a
 * network round trip per frame and the free tier's per-minute quota rule out the live overlay.
 */
internal class GeminiVisionReader(
    private val apiKeySupplier: () -> String,
    private val modelSupplier: () -> String,
) : AutoCloseable {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "lens-gemini-vision").apply { isDaemon = true }
    }

    inner class Request(
        private val jpeg: ByteArray,
        private val width: Int,
        private val height: Int,
        private val targetLanguage: String,
        private val shouldSend: () -> Boolean,
        private val callback: (Result<List<GeminiVisionParagraph>>) -> Unit,
    ) : TranslationCall {
        private val terminal = TranslationTerminal()
        private val connection = AtomicReference<HttpURLConnection?>(null)

        fun start() {
            runCatching { executor.execute(::run) }
                .onFailure { complete(Result.failure(GeminiVisionException("closed"))) }
        }

        override fun cancel() {
            if (terminal.cancel()) connection.getAndSet(null)?.let { runCatching { it.disconnect() } }
        }

        private fun run() {
            if (!terminal.isActive()) return
            val result = try {
                Result.success(execute())
            } catch (failure: GeminiVisionException) {
                Result.failure(failure)
            } catch (failure: Throwable) {
                // An Error (OOM while encoding a photo) must still reach the callback, or the
                // frozen lease and bitmap it owns would never be released.
                Result.failure(GeminiVisionException(failure.javaClass.simpleName.ifBlank { "failure" }))
            }
            complete(result)
        }

        private fun complete(result: Result<List<GeminiVisionParagraph>>) {
            terminal.complete { runCatching { callback(result) } }
        }

        private fun execute(): List<GeminiVisionParagraph> {
            val apiKey = apiKeySupplier().trim()
            if (apiKey.isEmpty()) throw GeminiVisionException("no_key")
            val model = modelSupplier().trim().takeIf { it in LENS_GEMINI_MODELS } ?: LENS_GEMINI_MODEL_DEFAULT
            val body = buildGeminiVisionRequestBody(jpeg, targetLanguage)
            return try {
                post(apiKey, model, body)
            } catch (failure: Exception) {
                if (model == LENS_GEMINI_RESCUE_MODEL || !geminiVisionRetriesOnRescue(failure)) throw failure
                post(apiKey, LENS_GEMINI_RESCUE_MODEL, body)
            }
        }

        private fun post(apiKey: String, model: String, body: ByteArray): List<GeminiVisionParagraph> {
            // Consent is re-read at the last moment: a photo queued behind another request
            // must not leave the phone after the user turned the setting off.
            if (!shouldSend()) throw GeminiVisionException(GEMINI_VISION_SKIPPED)
            val http = (
                URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
                    .openConnection() as HttpURLConnection
                ).apply {
                requestMethod = "POST"
                connectTimeout = GEMINI_VISION_CONNECT_TIMEOUT_MS
                readTimeout = GEMINI_VISION_READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                doOutput = true
                setFixedLengthStreamingMode(body.size)
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                // A header keeps the key out of the URL, which HTTP stacks are prone to logging.
                setRequestProperty("x-goog-api-key", apiKey)
            }
            connection.set(http)
            if (!terminal.isActive()) {
                http.disconnect()
                throw GeminiVisionException("canceled")
            }
            try {
                http.outputStream.use { it.write(body) }
                val status = http.responseCode
                if (status !in 200..299) throw GeminiVisionException(geminiVisionHttpReason(status))
                val response = http.inputStream.use(::readLimitedUtf8)
                return GeminiVisionResponseParser.parse(response, width, height)
            } finally {
                connection.compareAndSet(http, null)
                http.disconnect()
            }
        }
    }

    fun newRequest(
        jpeg: ByteArray,
        width: Int,
        height: Int,
        targetLanguage: String,
        shouldSend: () -> Boolean,
        callback: (Result<List<GeminiVisionParagraph>>) -> Unit,
    ): Request = Request(jpeg, width, height, targetLanguage, shouldSend, callback)

    override fun close() {
        executor.shutdownNow()
    }

    private fun readLimitedUtf8(input: InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > GEMINI_VISION_MAX_RESPONSE_BYTES) throw GeminiVisionException("response_too_large")
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

/** Gemini paragraphs as frozen overlay geometry plus the translations keyed by source text. */
internal data class GeminiVisionOverlay(
    val paragraphs: List<GeminiVisionParagraph>,
    val translations: Map<String, TranslationResult>,
)

internal fun geminiVisionOverlay(paragraphs: List<GeminiVisionParagraph>): GeminiVisionOverlay {
    val kept = paragraphs.take(CameraOverlayContract.MAX_ITEMS)
    val translations = linkedMapOf<String, TranslationResult>()
    kept.forEach { paragraph ->
        if (paragraph.source in translations) return@forEach
        val translated = paragraph.translation
        translations[paragraph.source] = if (translated.isBlank() || translated.length > MAX_TRANSLATED_TEXT_CHARS) {
            TranslationResult(
                src = paragraph.source,
                dst = paragraph.source,
                srcLang = paragraph.srcLang,
                fallback = true,
                engine = TranslationEngine.GEMINI,
            )
        } else {
            TranslationResult(
                src = paragraph.source,
                dst = translated,
                srcLang = paragraph.srcLang,
                engine = TranslationEngine.GEMINI,
            )
        }
    }
    return GeminiVisionOverlay(kept, translations)
}
