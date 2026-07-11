package com.anezium.rokidbus.phone.lens

import android.util.Log
import com.anezium.rokidbus.shared.LensWireContract
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.json.JSONTokener

private const val TAG = "LensTranslation"
private const val CONNECT_TIMEOUT_MS = 2_000
private const val READ_TIMEOUT_MS = 4_000
private const val MAX_HTTP_RESPONSE_BYTES = 2 * 1024 * 1024

internal data class ParsedOnlineTranslation(
    val dst: String,
    val srcLang: String,
)

internal object OnlineTranslationResponseParser {
    fun parseGoogle(response: String): ParsedOnlineTranslation {
        val root = strictJsonArray(response)
        val rawSegments = root.opt(0)
        if (rawSegments !is JSONArray) throw TranslationResponseParseException()

        val translated = buildString {
            for (index in 0 until rawSegments.length()) {
                val rawSegment = rawSegments.opt(index)
                if (rawSegment !is JSONArray) throw TranslationResponseParseException()
                val rawText = rawSegment.opt(0)
                if (rawText !is String) throw TranslationResponseParseException()
                append(rawText)
            }
        }
        if (translated.isBlank()) throw TranslationResponseParseException()
        val detected = (root.opt(2) as? String).orEmpty().lowercase(Locale.ROOT)
        return ParsedOnlineTranslation(dst = translated, srcLang = detected)
    }

    fun parseDeepL(response: String, expectedCount: Int): List<ParsedOnlineTranslation> {
        val root = strictJsonObject(response)
        val rawTranslations = root.opt("translations")
        if (rawTranslations !is JSONArray || rawTranslations.length() != expectedCount) {
            throw TranslationResponseParseException()
        }
        return List(rawTranslations.length()) { index ->
            val rawTranslation = rawTranslations.opt(index)
            if (rawTranslation !is JSONObject) throw TranslationResponseParseException()
            val rawText = rawTranslation.opt("text")
            if (rawText !is String || rawText.isBlank()) throw TranslationResponseParseException()
            val detected = (rawTranslation.opt("detected_source_language") as? String)
                .orEmpty()
                .lowercase(Locale.ROOT)
            ParsedOnlineTranslation(dst = rawText, srcLang = detected)
        }
    }

    fun parseGemini(response: String, expectedCount: Int): List<ParsedOnlineTranslation> {
        val root = strictJsonObject(response)
        val candidates = root.opt("candidates")
        if (candidates !is JSONArray || candidates.length() == 0) {
            throw TranslationResponseParseException()
        }
        val candidate = candidates.opt(0)
        if (candidate !is JSONObject) throw TranslationResponseParseException()
        val content = candidate.opt("content")
        if (content !is JSONObject) throw TranslationResponseParseException()
        val parts = content.opt("parts")
        if (parts !is JSONArray || parts.length() != 1) throw TranslationResponseParseException()
        val part = parts.opt(0)
        if (part !is JSONObject) throw TranslationResponseParseException()
        val replyText = part.opt("text")
        if (replyText !is String) throw TranslationResponseParseException()

        // Deliberately do not strip markdown fences: the model was asked for strict JSON only.
        val translations = strictJsonArray(replyText)
        if (translations.length() != expectedCount) throw TranslationResponseParseException()
        return List(translations.length()) { index ->
            val rawTranslation = translations.opt(index)
            if (rawTranslation !is JSONObject) throw TranslationResponseParseException()
            val rawDst = rawTranslation.opt("dst")
            val rawSrcLang = rawTranslation.opt("srcLang")
            if (rawDst !is String || rawDst.isBlank() || rawSrcLang !is String) {
                throw TranslationResponseParseException()
            }
            ParsedOnlineTranslation(
                dst = rawDst,
                srcLang = rawSrcLang.lowercase(Locale.ROOT),
            )
        }
    }

    private fun strictJsonArray(json: String): JSONArray =
        strictJsonValue(json) as? JSONArray ?: throw TranslationResponseParseException()

    private fun strictJsonObject(json: String): JSONObject =
        strictJsonValue(json) as? JSONObject ?: throw TranslationResponseParseException()

    private fun strictJsonValue(json: String): Any {
        try {
            val tokener = JSONTokener(json)
            val value = tokener.nextValue()
            if (tokener.nextClean() != '\u0000') throw TranslationResponseParseException()
            return value
        } catch (failure: TranslationResponseParseException) {
            throw failure
        } catch (_: JSONException) {
            throw TranslationResponseParseException()
        }
    }
}

internal class TranslationResponseParseException : Exception()

private class TranslationAttemptException(
    val error: TranslationErrorCode,
) : Exception()

abstract class HttpTranslationProvider(
    threadName: String,
) : TranslationProvider {
    protected inner class Operation(
        private val callback: TranslationProvider.Callback,
    ) : TranslationCall {
        private val terminal = TranslationTerminal()
        private val connections = Collections.newSetFromMap(
            ConcurrentHashMap<HttpURLConnection, Boolean>(),
        )
        private val futures = Collections.newSetFromMap(
            ConcurrentHashMap<Future<*>, Boolean>(),
        )

        fun isActive(): Boolean = terminal.isActive() && !closed.get()

        fun track(connection: HttpURLConnection) {
            if (isActive()) {
                connections += connection
            } else {
                connection.disconnect()
                throw InterruptedException()
            }
        }

        fun untrack(connection: HttpURLConnection) {
            connections -= connection
        }

        fun track(future: Future<*>) {
            if (isActive()) {
                futures += future
            } else {
                future.cancel(true)
            }
        }

        fun succeed(translations: List<TranslationResult>) {
            if (terminal.complete { notifyCallback { callback.onSuccess(translations) } }) {
                cleanup()
            }
        }

        fun fail(error: TranslationErrorCode) {
            if (terminal.complete { notifyCallback { callback.onError(error) } }) {
                cleanup()
            }
        }

        override fun cancel() {
            if (!terminal.cancel()) return
            cleanup()
        }

        private fun cleanup() {
            operations -= this
            futures.toList().forEach { it.cancel(true) }
            futures.clear()
            connections.toList().forEach { connection ->
                runCatching { connection.disconnect() }
            }
            connections.clear()
        }
    }

    private val closed = AtomicBoolean(false)
    private val operations = Collections.newSetFromMap(ConcurrentHashMap<Operation, Boolean>())
    private val operationExecutor: ExecutorService = Executors.newCachedThreadPool(
        daemonThreadFactory(threadName),
    )

    final override fun translate(
        request: TranslationRequest,
        callback: TranslationProvider.Callback,
    ): TranslationCall {
        if (closed.get()) return failImmediately(callback, TranslationErrorCode.PROVIDER_CLOSED)
        if (request.strings.size > LensWireContract.MAX_STRING_COUNT) {
            return failImmediately(callback, TranslationErrorCode.REQUEST_TOO_LARGE)
        }
        if (request.strings.isEmpty()) {
            notifyCallback { callback.onSuccess(emptyList()) }
            return TranslationCall.NONE
        }

        val operation = Operation(callback)
        operations += operation
        if (closed.get()) {
            operation.cancel()
            return failImmediately(callback, TranslationErrorCode.PROVIDER_CLOSED)
        }
        val future = try {
            operationExecutor.submit {
                if (!operation.isActive()) return@submit
                try {
                    operation.succeed(execute(request, operation))
                } catch (failure: Throwable) {
                    if (operation.isActive()) operation.fail(mapFailure(failure))
                }
            }
        } catch (_: RuntimeException) {
            operation.cancel()
            return failImmediately(
                callback,
                if (closed.get()) {
                    TranslationErrorCode.PROVIDER_CLOSED
                } else {
                    TranslationErrorCode.INTERNAL_ERROR
                },
            )
        }
        operation.track(future)
        return operation
    }

    protected abstract fun execute(
        request: TranslationRequest,
        operation: Operation,
    ): List<TranslationResult>

    protected fun readResponse(
        operation: Operation,
        url: URL,
        method: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray? = null,
        mapHttpError: (Int) -> TranslationErrorCode = ::defaultHttpError,
    ): String {
        val connection = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        operation.track(connection)
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(body.size)
                connection.outputStream.use { it.write(body) }
            }
            val status = connection.responseCode
            if (status !in 200..299) throw TranslationAttemptException(mapHttpError(status))
            return connection.inputStream.use(::readLimitedUtf8)
        } finally {
            operation.untrack(connection)
            connection.disconnect()
        }
    }

    protected fun track(operation: Operation, future: Future<*>) {
        operation.track(future)
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        operations.toList().forEach(TranslationCall::cancel)
        operationExecutor.shutdownNow()
        closeAdditionalExecutors()
    }

    protected open fun closeAdditionalExecutors() = Unit

    private fun failImmediately(
        callback: TranslationProvider.Callback,
        error: TranslationErrorCode,
    ): TranslationCall {
        notifyCallback { callback.onError(error) }
        return TranslationCall.NONE
    }

    private fun mapFailure(failure: Throwable): TranslationErrorCode {
        val root = unwrapFailure(failure)
        return when (root) {
            is TranslationAttemptException -> root.error
            is SocketTimeoutException -> TranslationErrorCode.TIMEOUT
            is InterruptedException -> TranslationErrorCode.TIMEOUT
            is TranslationResponseParseException, is JSONException ->
                TranslationErrorCode.TRANSLATION_FAILED
            is IOException -> TranslationErrorCode.TRANSLATION_FAILED
            else -> TranslationErrorCode.INTERNAL_ERROR
        }
    }

    private fun notifyCallback(action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            Log.w(TAG, "callback failed exception=${failure.javaClass.simpleName}")
        }
    }

    private fun readLimitedUtf8(input: java.io.InputStream): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_HTTP_RESPONSE_BYTES) {
                throw TranslationAttemptException(TranslationErrorCode.RESPONSE_TOO_LARGE)
            }
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}

class GoogleWebTranslationProvider : HttpTranslationProvider("lens-google-web") {
    private data class IndexedTranslation(
        val index: Int,
        val parsed: ParsedOnlineTranslation,
    )

    private val httpExecutor = Executors.newFixedThreadPool(
        MAX_CONCURRENT_CALLS,
        daemonThreadFactory("lens-google-web-http"),
    )

    override fun execute(
        request: TranslationRequest,
        operation: Operation,
    ): List<TranslationResult> {
        val completion = ExecutorCompletionService<IndexedTranslation>(httpExecutor)
        val futures = request.strings.mapIndexed { index, source ->
            completion.submit(
                Callable {
                    val target = urlEncode(request.targetLang)
                    val query = urlEncode(source)
                    // This unofficial Google Web endpoint may change, break, or throttle at any
                    // time. It 302-redirects Dalvik user agents to google.com/sorry (verified
                    // on-device 2026-07-11), so present a browser UA.
                    val response = readResponse(
                        operation = operation,
                        url = URL(
                            "https://translate.googleapis.com/translate_a/single" +
                                "?client=gtx&sl=auto&tl=$target&dt=t&q=$query",
                        ),
                        method = "GET",
                        headers = mapOf("User-Agent" to GOOGLE_WEB_USER_AGENT),
                    )
                    val parsed = try {
                        OnlineTranslationResponseParser.parseGoogle(response)
                    } catch (failure: Exception) {
                        // Field 2026-07-11: every attempt died as TRANSLATION_FAILED with no
                        // clue whether Google served a captcha page or an unexpected shape.
                        Log.w(
                            "LensTranslation",
                            "googleWebParseFailed len=${response.length} " +
                                "head=${response.take(120).replace(Regex("\\s+"), " ")}",
                        )
                        throw failure
                    }
                    IndexedTranslation(index, parsed)
                },
            ).also { track(operation, it) }
        }

        val parsed = arrayOfNulls<ParsedOnlineTranslation>(request.strings.size)
        try {
            repeat(request.strings.size) {
                val completed = completion.take().get()
                parsed[completed.index] = completed.parsed
            }
        } catch (failure: Throwable) {
            futures.forEach { it.cancel(true) }
            throw unwrapFailure(failure)
        }

        return request.strings.indices.map { index ->
            val item = parsed[index] ?: throw TranslationResponseParseException()
            if (item.dst.length > MAX_TRANSLATED_TEXT_CHARS) {
                throw TranslationAttemptException(TranslationErrorCode.RESPONSE_TOO_LARGE)
            }
            TranslationResult(
                src = request.strings[index],
                dst = item.dst,
                srcLang = item.srcLang,
                fallback = false,
            )
        }
    }

    override fun closeAdditionalExecutors() {
        httpExecutor.shutdownNow()
    }

    private companion object {
        private const val MAX_CONCURRENT_CALLS = 4
        private const val GOOGLE_WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }
}

class DeepLTranslationProvider(
    private val apiKeySupplier: () -> String,
) : HttpTranslationProvider("lens-deepl") {
    constructor(apiKey: String) : this({ apiKey })

    override fun execute(
        request: TranslationRequest,
        operation: Operation,
    ): List<TranslationResult> {
        val apiKey = apiKeySupplier().trim()
        if (apiKey.isBlank()) throw TranslationAttemptException(TranslationErrorCode.MODEL_UNAVAILABLE)
        val body = JSONObject()
            .put("text", JSONArray(request.strings))
            .put("target_lang", request.targetLang.uppercase(Locale.ROOT))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = readResponse(
            operation = operation,
            url = URL("https://api-free.deepl.com/v2/translate"),
            method = "POST",
            headers = mapOf(
                "Authorization" to "DeepL-Auth-Key $apiKey",
                "Content-Type" to "application/json; charset=utf-8",
            ),
            body = body,
            mapHttpError = { status ->
                if (status == 456) TranslationErrorCode.MODEL_UNAVAILABLE else defaultHttpError(status)
            },
        )
        val parsed = OnlineTranslationResponseParser.parseDeepL(response, request.strings.size)
        return parsed.mapIndexed { index, item ->
            if (item.dst.length > MAX_TRANSLATED_TEXT_CHARS) {
                throw TranslationAttemptException(TranslationErrorCode.RESPONSE_TOO_LARGE)
            }
            TranslationResult(
                src = request.strings[index],
                dst = item.dst,
                srcLang = item.srcLang,
                fallback = false,
            )
        }
    }
}

class GeminiTranslationProvider(
    private val apiKeySupplier: () -> String,
    private val modelSupplier: () -> String = { LENS_GEMINI_MODEL_DEFAULT },
) : HttpTranslationProvider("lens-gemini") {
    constructor(apiKey: String) : this({ apiKey })

    override fun execute(
        request: TranslationRequest,
        operation: Operation,
    ): List<TranslationResult> {
        val apiKey = apiKeySupplier().trim()
        if (apiKey.isBlank()) throw TranslationAttemptException(TranslationErrorCode.MODEL_UNAVAILABLE)
        val model = modelSupplier().trim().takeIf { it in LENS_GEMINI_MODELS } ?: LENS_GEMINI_MODEL_DEFAULT
        val prompt = buildGeminiPrompt(request)
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put(
                            "parts",
                            JSONArray().put(JSONObject().put("text", prompt)),
                        ),
                ),
            )
            .put(
                "generationConfig",
                JSONObject()
                    .put("temperature", 0.1)
                    .put("response_mime_type", "application/json"),
            )
            .toString()
            .toByteArray(Charsets.UTF_8)
        val response = readResponse(
            operation = operation,
            url = URL(
                "https://generativelanguage.googleapis.com/v1beta/models/" +
                    "$model:generateContent?key=${urlEncode(apiKey)}",
            ),
            method = "POST",
            headers = mapOf("Content-Type" to "application/json; charset=utf-8"),
            body = body,
        )
        val parsed = OnlineTranslationResponseParser.parseGemini(response, request.strings.size)
        return parsed.mapIndexed { index, item ->
            val source = request.strings[index]
            if (item.dst.length > MAX_TRANSLATED_TEXT_CHARS) {
                TranslationResult(
                    src = source,
                    dst = source,
                    srcLang = item.srcLang,
                    fallback = true,
                    failure = TranslationErrorCode.TRANSLATION_FAILED,
                )
            } else {
                TranslationResult(
                    src = source,
                    dst = item.dst,
                    srcLang = item.srcLang,
                    fallback = false,
                )
            }
        }
    }

    private fun buildGeminiPrompt(request: TranslationRequest): String =
        "Translate each entry of the following JSON array of strings into " +
            "${request.targetLang}. The input is OCR output that may contain character-level " +
            "recognition errors; infer and fix the intended words before translating. Return ONLY " +
            "a JSON array of objects in the form [{\"dst\":\"...\",\"srcLang\":\"xx\"}], " +
            "with exactly the same length and order as the input array. Do not use markdown fences " +
            "or add commentary. Input: ${JSONArray(request.strings)}"
}

private fun defaultHttpError(status: Int): TranslationErrorCode =
    when (status) {
        408, 504 -> TranslationErrorCode.TIMEOUT
        413 -> TranslationErrorCode.REQUEST_TOO_LARGE
        401, 403 -> TranslationErrorCode.MODEL_UNAVAILABLE
        429 -> TranslationErrorCode.BUSY
        else -> TranslationErrorCode.TRANSLATION_FAILED
    }

private fun unwrapFailure(failure: Throwable): Throwable =
    if (failure is ExecutionException && failure.cause != null) failure.cause!! else failure

private fun urlEncode(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name())

private fun daemonThreadFactory(prefix: String): ThreadFactory {
    val counter = AtomicInteger(0)
    return ThreadFactory { runnable ->
        Thread(runnable, "$prefix-${counter.incrementAndGet()}").apply { isDaemon = true }
    }
}
