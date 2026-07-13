package com.anezium.rokidbus.plugin.lens

import com.anezium.rokidbus.shared.LensWireContract
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

internal data class LiveTranslationCandidate(
    val sessionGeneration: Long,
    val composerGeneration: Long,
    val script: OcrScript,
    val targetLanguage: String,
    val stableId: Long,
    val sourceText: String,
)

internal data class LiveTranslationUpdate(
    val candidate: LiveTranslationCandidate,
    val result: TranslationResult,
)

/**
 * Keeps one translation request in flight while preserving FIFO age for visible cache misses.
 * Identical source strings share one provider item and fan back out to every matching stable track.
 */
internal class LiveTranslationScheduler(
    private val translator: TranslationProvider,
    private val log: (String) -> Unit,
    private val onUpdates: (List<LiveTranslationUpdate>) -> Unit,
) : AutoCloseable {
    private data class Context(
        val sessionGeneration: Long,
        val composerGeneration: Long,
        val script: OcrScript,
        val targetLanguage: String,
    )

    private data class ActiveBatch(
        val serial: Long,
        val context: Context,
        val candidatesAtDispatch: Int,
        val bySource: LinkedHashMap<String, List<LiveTranslationCandidate>>,
        var call: TranslationCall = TranslationCall.NONE,
    )

    private val lock = Any()
    private val pending = linkedMapOf<String, List<LiveTranslationCandidate>>()
    private var context: Context? = null
    private var active: ActiveBatch? = null
    private var nextSerial = 1L
    private var closed = false

    fun submit(candidates: List<LiveTranslationCandidate>) {
        var canceled: TranslationCall? = null
        var dispatch: ActiveBatch? = null
        synchronized(lock) {
            if (closed) return
            val nextContext = candidates.firstOrNull()?.context()
            if (nextContext != null && nextContext != context) {
                canceled = active?.call
                active = null
                pending.clear()
                context = nextContext
            }

            val grouped = candidates
                .filter { nextContext == null || it.context() == nextContext }
                .groupByTo(linkedMapOf(), LiveTranslationCandidate::sourceText)
            pending.keys.retainAll(grouped.keys)
            grouped.forEach { (source, tracks) ->
                if (active?.bySource?.containsKey(source) == true) {
                    Unit // Preserve the exact stable IDs captured when this request was dispatched.
                } else if (pending.containsKey(source)) {
                    pending[source] = tracks
                } else {
                    pending[source] = tracks
                }
            }
            if (grouped.isEmpty()) {
                canceled = active?.call
                active = null
                pending.clear()
            } else if (active == null) {
                dispatch = prepareBatchLocked()
            }
        }
        canceled?.cancel()
        dispatch?.let(::dispatch)
    }

    fun reset() {
        val call = synchronized(lock) {
            pending.clear()
            context = null
            active?.call.also { active = null }
        }
        call?.cancel()
    }

    private fun prepareBatchLocked(): ActiveBatch? {
        val activeContext = context ?: return null
        val selected = linkedMapOf<String, List<LiveTranslationCandidate>>()
        var sourceBytes = 0
        val invalid = mutableListOf<LiveTranslationUpdate>()
        val iterator = pending.entries.iterator()
        while (iterator.hasNext() && selected.size < LensWireContract.MAX_STRING_COUNT) {
            val entry = iterator.next()
            val bytes = entry.key.toByteArray(Charsets.UTF_8).size
            if (entry.key.isBlank() || entry.key.length > LensWireContract.MAX_STRING_CHARS ||
                bytes > LensWireContract.MAX_TOTAL_SOURCE_BYTES
            ) {
                entry.value.forEach { candidate ->
                    invalid += LiveTranslationUpdate(
                        candidate,
                        failureResult(candidate.sourceText, TranslationErrorCode.REQUEST_TOO_LARGE),
                    )
                }
                iterator.remove()
                continue
            }
            if (sourceBytes + bytes > LensWireContract.MAX_TOTAL_SOURCE_BYTES) break
            selected[entry.key] = entry.value
            sourceBytes += bytes
            iterator.remove()
        }
        if (invalid.isNotEmpty()) onUpdates(invalid)
        if (selected.isEmpty()) return null
        return ActiveBatch(
            serial = nextSerial++,
            context = activeContext,
            candidatesAtDispatch = selected.values.sumOf(List<LiveTranslationCandidate>::size) +
                pending.values.sumOf(List<LiveTranslationCandidate>::size),
            bySource = selected,
        ).also { active = it }
    }

    private fun dispatch(batch: ActiveBatch) {
        val sources = batch.bySource.keys.toList()
        val request = TranslationRequest(
            id = "live-${batch.context.sessionGeneration}-${batch.serial}",
            targetLang = batch.context.targetLanguage,
            mode = batch.context.script.toRecognizerMode(),
            strings = sources,
        )
        val requestBytes = translationRequestJsonBytes(request)
        val callback = object : TranslationProvider.Callback {
            override fun onDownloading(status: TranslationDownloadStatus) = Unit

            override fun onSuccess(translations: List<TranslationResult>) {
                finish(batch.serial, translations.associateBy(TranslationResult::src), null, requestBytes)
            }

            override fun onError(error: TranslationErrorCode) {
                finish(batch.serial, emptyMap(), error, requestBytes)
            }
        }
        synchronized(lock) {
            if (active?.serial != batch.serial || closed) return
            active?.call = PENDING_TRANSLATION_CALL
        }
        val call = runCatching { translator.translate(request, callback) }
            .getOrElse {
                finish(batch.serial, emptyMap(), TranslationErrorCode.INTERNAL_ERROR, requestBytes)
                return
            }
        val cancelReturned = synchronized(lock) {
            val current = active
            if (current?.serial == batch.serial && current.call === PENDING_TRANSLATION_CALL) {
                current.call = call
                false
            } else {
                true
            }
        }
        if (cancelReturned) call.cancel()
    }

    private fun finish(
        serial: Long,
        translations: Map<String, TranslationResult>,
        error: TranslationErrorCode?,
        requestBytes: Int,
    ) {
        var next: ActiveBatch? = null
        val updates: List<LiveTranslationUpdate>
        val accounting: String
        synchronized(lock) {
            val batch = active?.takeIf { it.serial == serial } ?: return
            val currentTracks = batch.bySource
            updates = currentTracks.flatMap { (source, tracks) ->
                val result = translations[source]
                    ?: failureResult(source, error ?: TranslationErrorCode.TRANSLATION_FAILED)
                tracks.map { LiveTranslationUpdate(it, result) }
            }
            val covered = currentTracks.keys.count { source ->
                translations[source]?.let { !it.fallback && it.failure == null && it.dst.isNotBlank() } == true
            }
            val fallback = currentTracks.size - covered
            accounting = "live translation candidates=${batch.candidatesAtDispatch} " +
                "requested=${currentTracks.size} covered=$covered fallback=$fallback " +
                "itemCount=${currentTracks.size} jsonUtf8Bytes=$requestBytes " +
                "warningOver3KiB=${requestBytes > THREE_KIB}"
            active = null
            if (!closed) next = prepareBatchLocked()
        }
        log(accounting)
        if (updates.isNotEmpty()) onUpdates(updates)
        next?.let(::dispatch)
    }

    override fun close() {
        val call = synchronized(lock) {
            if (closed) return
            closed = true
            pending.clear()
            context = null
            active?.call.also { active = null }
        }
        call?.cancel()
    }

    private fun LiveTranslationCandidate.context(): Context = Context(
        sessionGeneration,
        composerGeneration,
        script,
        targetLanguage,
    )

    private fun OcrScript.toRecognizerMode(): LensRecognizerMode = when (this) {
        OcrScript.LATIN -> LensRecognizerMode.LATIN
        OcrScript.JAPANESE -> LensRecognizerMode.JAPANESE
        OcrScript.CHINESE -> LensRecognizerMode.CHINESE
        OcrScript.KOREAN -> LensRecognizerMode.KOREAN
        OcrScript.DEVANAGARI -> LensRecognizerMode.DEVANAGARI
    }
}

/** Runs every distinct frozen source through deterministic sequential bounded requests. */
internal class FrozenTranslationBatch(
    private val translator: TranslationProvider,
    private val requestPrefix: String,
    private val targetLanguage: String,
    private val mode: LensRecognizerMode,
    sources: List<String>,
    private val log: (String) -> Unit,
    private val onComplete: (Map<String, TranslationResult>) -> Unit,
) : TranslationCall {
    private val lock = Any()
    private val canceled = AtomicBoolean(false)
    private val remaining = ArrayDeque(sources.distinct())
    private val results = linkedMapOf<String, TranslationResult>()
    private var activeCall: TranslationCall = TranslationCall.NONE
    private var batchIndex = 0

    fun start(): TranslationCall {
        dispatchNext()
        return this
    }

    private fun dispatchNext() {
        val batch = synchronized(lock) {
            if (canceled.get()) return
            boundedBatch(remaining).also { selected ->
                selected.rejected.forEach { source ->
                    results[source] = failureResult(source, TranslationErrorCode.REQUEST_TOO_LARGE)
                }
            }
        }
        if (batch.sources.isEmpty()) {
            completeIfDone()
            return
        }
        val request = TranslationRequest(
            id = "$requestPrefix-${batchIndex++}",
            targetLang = targetLanguage,
            mode = mode,
            strings = batch.sources,
        )
        val jsonBytes = translationRequestJsonBytes(request)
        val callback = object : TranslationProvider.Callback {
            override fun onDownloading(status: TranslationDownloadStatus) = Unit
            override fun onSuccess(translations: List<TranslationResult>) =
                finishBatch(batch.sources, translations.associateBy(TranslationResult::src), null, jsonBytes)
            override fun onError(error: TranslationErrorCode) =
                finishBatch(batch.sources, emptyMap(), error, jsonBytes)
        }
        synchronized(lock) {
            if (canceled.get()) return
            activeCall = PENDING_TRANSLATION_CALL
        }
        val call = runCatching { translator.translate(request, callback) }
            .getOrElse {
                finishBatch(batch.sources, emptyMap(), TranslationErrorCode.INTERNAL_ERROR, jsonBytes)
                return
            }
        synchronized(lock) {
            if (canceled.get() || activeCall !== PENDING_TRANSLATION_CALL) {
                call.cancel()
            } else {
                activeCall = call
            }
        }
    }

    private fun finishBatch(
        sources: List<String>,
        translations: Map<String, TranslationResult>,
        error: TranslationErrorCode?,
        jsonBytes: Int,
    ) {
        val accounting = synchronized(lock) {
            if (canceled.get()) return
            sources.forEach { source ->
                results[source] = translations[source]
                    ?: failureResult(source, error ?: TranslationErrorCode.TRANSLATION_FAILED)
            }
            val covered = sources.count { source ->
                translations[source]?.let { !it.fallback && it.failure == null && it.dst.isNotBlank() } == true
            }
            "frozen translation candidates=${sources.size + remaining.size} requested=${sources.size} " +
                "covered=$covered fallback=${sources.size - covered} itemCount=${sources.size} " +
                "jsonUtf8Bytes=$jsonBytes warningOver3KiB=${jsonBytes > THREE_KIB}"
        }
        log(accounting)
        dispatchNext()
    }

    private fun completeIfDone() {
        val completed = synchronized(lock) {
            if (canceled.get() || remaining.isNotEmpty()) return
            results.toMap()
        }
        onComplete(completed)
    }

    override fun cancel() {
        if (!canceled.compareAndSet(false, true)) return
        synchronized(lock) {
            remaining.clear()
            activeCall.cancel()
        }
    }
}

private data class BoundedBatch(val sources: List<String>, val rejected: List<String>)

private fun boundedBatch(remaining: ArrayDeque<String>): BoundedBatch {
    val selected = mutableListOf<String>()
    val rejected = mutableListOf<String>()
    var bytes = 0
    while (remaining.isNotEmpty() && selected.size < LensWireContract.MAX_STRING_COUNT) {
        val source = remaining.first()
        val sourceBytes = source.toByteArray(Charsets.UTF_8).size
        if (source.isBlank() || source.length > LensWireContract.MAX_STRING_CHARS ||
            sourceBytes > LensWireContract.MAX_TOTAL_SOURCE_BYTES
        ) {
            rejected += remaining.removeFirst()
            continue
        }
        if (bytes + sourceBytes > LensWireContract.MAX_TOTAL_SOURCE_BYTES) break
        selected += remaining.removeFirst()
        bytes += sourceBytes
    }
    return BoundedBatch(selected, rejected)
}

internal fun translationRequestJsonBytes(request: TranslationRequest): Int =
    JSONObject()
        .put("id", request.id)
        .put("targetLang", request.targetLang)
        .put("mode", request.mode.wireValue)
        .put("strings", JSONArray(request.strings))
        .toString()
        .toByteArray(Charsets.UTF_8)
        .size

internal fun failureResult(source: String, error: TranslationErrorCode): TranslationResult =
    TranslationResult(
        src = source,
        dst = source,
        srcLang = "",
        fallback = true,
        failure = error,
    )

private val PENDING_TRANSLATION_CALL = TranslationCall { }
private const val THREE_KIB = 3 * 1024
