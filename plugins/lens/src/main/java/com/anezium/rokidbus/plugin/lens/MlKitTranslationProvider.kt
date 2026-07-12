package com.anezium.rokidbus.plugin.lens

import android.content.Context
import com.anezium.rokidbus.shared.LensWireContract
import android.util.Log
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.languageid.LanguageIdentifier
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

private const val TAG = "LensTranslation"
private const val MAX_TRANSLATOR_CACHE_SIZE = 3
private const val MAX_ACTIVE_TEXT_PIPELINES = 4
private const val MAX_QUEUED_TEXT_PIPELINES = 72

internal fun mlKitSourceLanguage(detectedLang: String?, mode: LensRecognizerMode): String {
    val detected = detectedLang
        ?.takeUnless { it == "und" }
        ?.let(TranslateLanguage::fromLanguageTag)
    if (detected != null) return detected
    return when (mode) {
        LensRecognizerMode.LATIN -> TranslateLanguage.ENGLISH
        LensRecognizerMode.JAPANESE -> TranslateLanguage.JAPANESE
        LensRecognizerMode.CHINESE -> TranslateLanguage.CHINESE
        LensRecognizerMode.KOREAN -> TranslateLanguage.KOREAN
        LensRecognizerMode.DEVANAGARI -> TranslateLanguage.HINDI
    }
}

class MlKitTranslationProvider(
    @Suppress("UNUSED_PARAMETER") context: Context,
) : TranslationProvider {
    private data class PairKey(
        val sourceLang: String,
        val targetLang: String,
    )

    private data class IndexedResult(
        val index: Int,
        val result: TranslationResult,
    )

    private class TranslatorHolder(
        val translator: Translator,
        val ready: AtomicBoolean = AtomicBoolean(false),
        var cached: Boolean = false,
        var inUse: Int = 0,
        val closed: AtomicBoolean = AtomicBoolean(false),
    )

    private data class TranslatorLease(
        val holder: TranslatorHolder,
    )

    private inner class TextWork(
        val operation: Operation,
        val index: Int,
        val sourceText: String,
        val finished: AtomicBoolean = AtomicBoolean(false),
    )

    private inner class Operation(
        val request: TranslationRequest,
        val callback: TranslationProvider.Callback,
        workCount: Int,
    ) : TranslationCall {
        private val remaining = AtomicInteger(workCount)
        private val terminal = TranslationTerminal()
        val results = Collections.synchronizedList(mutableListOf<IndexedResult>())
        val failures = Collections.synchronizedList(mutableListOf<TranslationErrorCode>())
        val statusSent = ConcurrentHashMap<PairKey, Boolean>()

        fun isActive(): Boolean = terminal.isActive() && !closed.get()

        fun complete(
            index: Int,
            result: TranslationResult?,
            failure: TranslationErrorCode?,
        ) {
            if (terminal.isActive()) {
                if (result != null) results += IndexedResult(index, result)
                if (failure != null) failures += failure
            }
            if (remaining.decrementAndGet() != 0) return
            if (closed.get()) {
                terminal.cancel()
            } else {
                val ordered = synchronized(results) {
                    results.sortedBy { it.index }.map { it.result }
                }
                val requestFailures = synchronized(failures) { failures.toList() }
                terminal.complete {
                    if (ordered.none { !it.fallback } && requestFailures.isNotEmpty()) {
                        notifyCallback { callback.onError(selectFailure(requestFailures)) }
                    } else {
                        notifyCallback { callback.onSuccess(ordered) }
                    }
                }
            }
            operations.remove(this)
        }

        fun skipQueued(count: Int) {
            if (count <= 0) return
            if (remaining.addAndGet(-count) == 0) {
                terminal.cancel()
                operations.remove(this)
            }
        }

        override fun cancel() {
            if (!terminal.cancel()) return
            operations.remove(this)
            skipQueued(removeQueuedWork(this))
        }
    }

    private val languageIdentifier: LanguageIdentifier = LanguageIdentification.getClient()
    private val downloadConditions = DownloadConditions.Builder().build()
    private val closed = AtomicBoolean(false)
    private val languageIdentifierClosed = AtomicBoolean(false)
    private val operations = Collections.newSetFromMap(ConcurrentHashMap<Operation, Boolean>())

    private val schedulerLock = Any()
    private val pendingWork = ArrayDeque<TextWork>()
    private var activeTextPipelines = 0

    private val translatorsLock = Any()
    private val translators = LinkedHashMap<PairKey, TranslatorHolder>(MAX_TRANSLATOR_CACHE_SIZE, 0.75f, true)

    override fun translate(
        request: TranslationRequest,
        callback: TranslationProvider.Callback,
    ): TranslationCall {
        if (closed.get()) return failImmediately(callback, TranslationErrorCode.PROVIDER_CLOSED)
        val targetLang = supportedLanguage(request.targetLang)
            ?: return failImmediately(callback, TranslationErrorCode.UNSUPPORTED_TARGET_LANGUAGE)
        val uniqueStrings = request.strings
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
        if (uniqueStrings.size > LensWireContract.MAX_STRING_COUNT) {
            return failImmediately(callback, TranslationErrorCode.REQUEST_TOO_LARGE)
        }
        if (uniqueStrings.isEmpty()) {
            notifyCallback { callback.onSuccess(emptyList()) }
            return TranslationCall.NONE
        }

        val normalizedRequest = request.copy(targetLang = targetLang, strings = uniqueStrings)
        val operation = Operation(normalizedRequest, callback, uniqueStrings.size)
        val works = uniqueStrings.mapIndexed { index, sourceText ->
            TextWork(operation = operation, index = index, sourceText = sourceText)
        }

        var rejection: TranslationErrorCode? = null
        val toStart = synchronized(schedulerLock) {
            when {
                closed.get() -> rejection = TranslationErrorCode.PROVIDER_CLOSED
                pendingWork.size + works.size > MAX_QUEUED_TEXT_PIPELINES -> rejection = TranslationErrorCode.BUSY
                else -> {
                    operations += operation
                    pendingWork.addAll(works)
                }
            }
            if (rejection == null) takeWorkLocked() else emptyList()
        }
        if (rejection != null) return failImmediately(callback, rejection!!)
        startWorks(toStart)
        return operation
    }

    private fun startWorks(works: List<TextWork>) {
        works.forEach(::startLanguageIdentification)
    }

    private fun startLanguageIdentification(work: TextWork) {
        if (!work.operation.isActive()) {
            finishSkipped(work)
            return
        }
        try {
            languageIdentifier.identifyLanguage(work.sourceText)
                .addOnSuccessListener { detected ->
                    startTranslation(work, detected)
                }
                .addOnFailureListener { failure ->
                    logFailure("language_id", failure)
                    startTranslation(work, null)
                }
        } catch (failure: RuntimeException) {
            logFailure("language_id", failure)
            startTranslation(work, null)
        }
    }

    private fun startTranslation(work: TextWork, detectedLang: String?) {
        if (!work.operation.isActive()) {
            finishSkipped(work)
            return
        }
        val sourceLang = mlKitSourceLanguage(detectedLang, work.operation.request.mode)
        val targetLang = work.operation.request.targetLang
        if (sourceLang == targetLang) {
            finishSuccess(
                work,
                TranslationResult(
                    src = work.sourceText,
                    dst = work.sourceText,
                    srcLang = sourceLang,
                ),
            )
            return
        }

        val key = PairKey(sourceLang, targetLang)
        val lease = acquireTranslator(key)
        if (lease == null) {
            finishFailure(work, sourceLang, TranslationErrorCode.PROVIDER_CLOSED)
            return
        }
        val holder = lease.holder
        if (!holder.ready.get() && work.operation.statusSent.putIfAbsent(key, true) == null) {
            notifyCallback {
                if (work.operation.isActive()) {
                    work.operation.callback.onDownloading(
                        TranslationDownloadStatus(
                            srcLang = sourceLang,
                            targetLang = targetLang,
                        ),
                    )
                }
            }
        }

        try {
            holder.translator.downloadModelIfNeeded(downloadConditions)
                .addOnSuccessListener {
                    holder.ready.set(true)
                    if (!work.operation.isActive()) {
                        releaseTranslator(lease)
                        finishSkipped(work)
                        return@addOnSuccessListener
                    }
                    translateWithReadyModel(work, sourceLang, lease)
                }
                .addOnFailureListener { failure ->
                    logFailure("model_download", failure)
                    releaseTranslator(lease)
                    finishFailure(work, sourceLang, TranslationErrorCode.MODEL_UNAVAILABLE)
                }
        } catch (failure: RuntimeException) {
            logFailure("model_download", failure)
            releaseTranslator(lease)
            finishFailure(work, sourceLang, TranslationErrorCode.MODEL_UNAVAILABLE)
        }
    }

    private fun translateWithReadyModel(
        work: TextWork,
        sourceLang: String,
        lease: TranslatorLease,
    ) {
        try {
            lease.holder.translator.translate(work.sourceText)
                .addOnSuccessListener { translated ->
                    releaseTranslator(lease)
                    if (translated.isBlank() || translated.length > MAX_TRANSLATED_TEXT_CHARS) {
                        finishFailure(work, sourceLang, TranslationErrorCode.TRANSLATION_FAILED)
                    } else {
                        finishSuccess(
                            work,
                            TranslationResult(
                                src = work.sourceText,
                                dst = translated,
                                srcLang = sourceLang,
                            ),
                        )
                    }
                }
                .addOnFailureListener { failure ->
                    logFailure("translate", failure)
                    releaseTranslator(lease)
                    finishFailure(work, sourceLang, TranslationErrorCode.TRANSLATION_FAILED)
                }
        } catch (failure: RuntimeException) {
            logFailure("translate", failure)
            releaseTranslator(lease)
            finishFailure(work, sourceLang, TranslationErrorCode.TRANSLATION_FAILED)
        }
    }

    private fun finishSuccess(work: TextWork, result: TranslationResult) {
        finishWork(work, result = result, failure = null)
    }

    private fun finishFailure(
        work: TextWork,
        sourceLang: String,
        error: TranslationErrorCode,
    ) {
        finishWork(
            work = work,
            result = TranslationResult(
                src = work.sourceText,
                dst = work.sourceText,
                srcLang = sourceLang,
                fallback = true,
                failure = error,
            ),
            failure = error,
        )
    }

    private fun finishSkipped(work: TextWork) {
        finishWork(work, result = null, failure = null)
    }

    private fun finishWork(
        work: TextWork,
        result: TranslationResult?,
        failure: TranslationErrorCode?,
    ) {
        if (!work.finished.compareAndSet(false, true)) return
        work.operation.complete(work.index, result, failure)
        val toStart = synchronized(schedulerLock) {
            activeTextPipelines = (activeTextPipelines - 1).coerceAtLeast(0)
            takeWorkLocked()
        }
        startWorks(toStart)
        closeLanguageIdentifierIfIdle()
    }

    private fun takeWorkLocked(): List<TextWork> {
        val result = ArrayList<TextWork>(MAX_ACTIVE_TEXT_PIPELINES)
        while (activeTextPipelines < MAX_ACTIVE_TEXT_PIPELINES && pendingWork.isNotEmpty()) {
            val work = pendingWork.removeFirst()
            if (!work.operation.isActive()) {
                work.operation.skipQueued(1)
                continue
            }
            activeTextPipelines += 1
            result += work
        }
        return result
    }

    private fun removeQueuedWork(operation: Operation): Int = synchronized(schedulerLock) {
        var removed = 0
        val iterator = pendingWork.iterator()
        while (iterator.hasNext()) {
            if (iterator.next().operation === operation) {
                iterator.remove()
                removed += 1
            }
        }
        removed
    }

    private fun acquireTranslator(key: PairKey): TranslatorLease? {
        var evicted: TranslatorHolder? = null
        val lease = synchronized(translatorsLock) {
            if (closed.get()) return@synchronized null
            val existing = translators[key]
            if (existing != null) {
                existing.inUse += 1
                return@synchronized TranslatorLease(existing)
            }

            if (translators.size >= MAX_TRANSLATOR_CACHE_SIZE) {
                val idleEntry = translators.entries.firstOrNull { it.value.inUse == 0 }
                if (idleEntry != null) {
                    translators.remove(idleEntry.key)
                    idleEntry.value.cached = false
                    evicted = idleEntry.value
                }
            }

            val holder = try {
                TranslatorHolder(
                    translator = Translation.getClient(
                        TranslatorOptions.Builder()
                            .setSourceLanguage(key.sourceLang)
                            .setTargetLanguage(key.targetLang)
                            .build(),
                    ),
                )
            } catch (failure: RuntimeException) {
                logFailure("translator_create", failure)
                return@synchronized null
            }
            holder.inUse = 1
            if (translators.size < MAX_TRANSLATOR_CACHE_SIZE) {
                holder.cached = true
                translators[key] = holder
            }
            TranslatorLease(holder)
        }
        evicted?.let(::closeTranslator)
        return lease
    }

    private fun releaseTranslator(lease: TranslatorLease) {
        val shouldClose = synchronized(translatorsLock) {
            val holder = lease.holder
            holder.inUse = (holder.inUse - 1).coerceAtLeast(0)
            holder.inUse == 0 && (!holder.cached || closed.get())
        }
        if (shouldClose) closeTranslator(lease.holder)
    }

    private fun closeTranslator(holder: TranslatorHolder) {
        if (!holder.closed.compareAndSet(false, true)) return
        runCatching { holder.translator.close() }
            .onFailure { logFailure("translator_close", it) }
    }

    private fun supportedLanguage(languageTag: String): String? =
        TranslateLanguage.fromLanguageTag(languageTag)

    private fun selectFailure(failures: List<TranslationErrorCode>): TranslationErrorCode =
        when {
            TranslationErrorCode.MODEL_UNAVAILABLE in failures -> TranslationErrorCode.MODEL_UNAVAILABLE
            TranslationErrorCode.PROVIDER_CLOSED in failures -> TranslationErrorCode.PROVIDER_CLOSED
            else -> TranslationErrorCode.TRANSLATION_FAILED
        }

    private fun failImmediately(
        callback: TranslationProvider.Callback,
        error: TranslationErrorCode,
    ): TranslationCall {
        notifyCallback { callback.onError(error) }
        return TranslationCall.NONE
    }

    private fun notifyCallback(action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            Log.w(TAG, "callback failed exception=${failure.javaClass.simpleName}")
        }
    }

    private fun logFailure(stage: String, failure: Throwable) {
        Log.w(TAG, "stage=$stage failed exception=${failure.javaClass.simpleName}")
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        operations.toList().forEach(TranslationCall::cancel)

        val idleHolders = synchronized(translatorsLock) {
            translators.values.forEach { it.cached = false }
            translators.values.filter { it.inUse == 0 }.also { translators.clear() }
        }
        idleHolders.forEach(::closeTranslator)
        closeLanguageIdentifierIfIdle()
    }

    private fun closeLanguageIdentifierIfIdle() {
        if (!closed.get() || languageIdentifierClosed.get()) return
        val idle = synchronized(schedulerLock) {
            activeTextPipelines == 0 && pendingWork.isEmpty()
        }
        if (idle && languageIdentifierClosed.compareAndSet(false, true)) {
            runCatching { languageIdentifier.close() }
                .onFailure { logFailure("language_identifier_close", it) }
        }
    }
}

