package com.anezium.rokidbus.phone.lens

import android.content.Context
import android.util.Log
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

const val LENS_TRANSLATION_PREFS_NAME = "lens_translation"
const val LENS_TRANSLATION_PREF_ENGINE = "engine"
const val LENS_TRANSLATION_PREF_DEEPL_API_KEY = "deepl_api_key"
const val LENS_TRANSLATION_PREF_GEMINI_API_KEY = "gemini_api_key"
const val LENS_TRANSLATION_PREF_GEMINI_MODEL = "gemini_model"
const val LENS_TRANSLATION_PREF_TARGET_LANG = "target_lang"
const val LENS_TRANSLATION_TARGET_LANG_DEFAULT = "fr"

const val LENS_GEMINI_MODEL_DEFAULT = "gemini-2.5-flash"

// Free-tier models verified against the Gemini API on 2026-07-10; "pro" models 429 with
// a zero free-tier quota, and retired models (gemini-2.0-flash) do too. Unknown stored
// values fall back to the default so a removed model can never brick translation again.
val LENS_GEMINI_MODELS: List<String> = listOf(
    LENS_GEMINI_MODEL_DEFAULT,
    "gemini-3-flash-preview",
    "gemini-flash-latest",
)

enum class TranslationEngine {
    AUTO,
    GOOGLE_WEB,
    DEEPL,
    GEMINI,
    MLKIT_OFFLINE,
}

data class TranslationEngineConfig(
    val engine: TranslationEngine = TranslationEngine.AUTO,
    val deepLApiKey: String = "",
    val geminiApiKey: String = "",
    val geminiModel: String = LENS_GEMINI_MODEL_DEFAULT,
)

fun sharedPreferencesTranslationEngineConfigSupplier(
    context: Context,
): () -> TranslationEngineConfig {
    val preferences = context.applicationContext.getSharedPreferences(
        LENS_TRANSLATION_PREFS_NAME,
        Context.MODE_PRIVATE,
    )
    return {
        TranslationEngineConfig(
            engine = preferences.getString(LENS_TRANSLATION_PREF_ENGINE, null)
                ?.let { stored ->
                    TranslationEngine.entries.firstOrNull {
                        it.name.equals(stored, ignoreCase = true)
                    }
                }
                ?: TranslationEngine.AUTO,
            deepLApiKey = preferences.getString(
                LENS_TRANSLATION_PREF_DEEPL_API_KEY,
                "",
            ).orEmpty(),
            geminiApiKey = preferences.getString(
                LENS_TRANSLATION_PREF_GEMINI_API_KEY,
                "",
            ).orEmpty(),
            geminiModel = preferences.getString(LENS_TRANSLATION_PREF_GEMINI_MODEL, null)
                ?.takeIf { it in LENS_GEMINI_MODELS }
                ?: LENS_GEMINI_MODEL_DEFAULT,
        )
    }
}

class TranslationEngineRouter internal constructor(
    private val configSupplier: () -> TranslationEngineConfig,
    googleWebProvider: TranslationProvider,
    deepLProvider: TranslationProvider,
    geminiProvider: TranslationProvider,
    mlKitProvider: TranslationProvider,
    private val nowMs: () -> Long,
    private val logger: (String) -> Unit,
) : TranslationProvider {
    constructor(
        context: Context,
        configSupplier: () -> TranslationEngineConfig =
            sharedPreferencesTranslationEngineConfigSupplier(context),
        googleWebProvider: TranslationProvider = GoogleWebTranslationProvider(),
        deepLProvider: TranslationProvider = DeepLTranslationProvider {
            configSupplier().deepLApiKey
        },
        geminiProvider: TranslationProvider = GeminiTranslationProvider(
            apiKeySupplier = { configSupplier().geminiApiKey },
            modelSupplier = { configSupplier().geminiModel },
        ),
        mlKitProvider: TranslationProvider = MlKitTranslationProvider(context),
    ) : this(
        configSupplier = configSupplier,
        googleWebProvider = googleWebProvider,
        deepLProvider = deepLProvider,
        geminiProvider = geminiProvider,
        mlKitProvider = mlKitProvider,
        nowMs = System::currentTimeMillis,
        logger = { message -> Log.w(TAG, message) },
    )

    private inner class RouterOperation(
        private val request: TranslationRequest,
        private val callback: TranslationProvider.Callback,
        private val candidates: List<TranslationEngine>,
    ) : TranslationCall {
        private val lock = Any()
        private val terminal = TranslationTerminal()
        private val onlineStartedAtMs = nowMs()
        private var nextCandidateIndex = 0
        private var currentEngine: TranslationEngine? = null
        private var currentAttemptStartedAtMs = 0L
        private var generation = 0L
        private var activeCall: TranslationCall = TranslationCall.NONE
        private var budgetFuture: ScheduledFuture<*>? = null
        private var finishing = false

        fun start() {
            if (candidates.any { it.isOnline() }) {
                val delayMs = (ONLINE_BUDGET_MS - elapsedOnlineMs()).coerceAtLeast(1L)
                budgetFuture = budgetExecutor.schedule(
                    ::expireOnlineBudget,
                    delayMs,
                    TimeUnit.MILLISECONDS,
                )
            }
            advance()
        }

        private fun advance() {
            val selection = synchronized(lock) {
                if (!terminal.isActive() || finishing || currentEngine != null || closed.get()) {
                    return
                }
                val selected = if (elapsedOnlineMs() >= ONLINE_BUDGET_MS) {
                    nextCandidateIndex = candidates.size
                    TranslationEngine.MLKIT_OFFLINE
                } else {
                    candidates.getOrNull(nextCandidateIndex++) ?: TranslationEngine.MLKIT_OFFLINE
                }
                currentEngine = selected
                currentAttemptStartedAtMs = nowMs()
                generation += 1
                Attempt(selected, generation, currentAttemptStartedAtMs)
            }
            if (selection.engine == TranslationEngine.MLKIT_OFFLINE) {
                budgetFuture?.cancel(false)
            }
            startAttempt(selection)
        }

        private fun startAttempt(attempt: Attempt) {
            val provider = providers.getValue(attempt.engine)
            val call = try {
                provider.translate(
                    request,
                    object : TranslationProvider.Callback {
                        override fun onDownloading(status: TranslationDownloadStatus) {
                            if (attempt.engine != TranslationEngine.MLKIT_OFFLINE) return
                            val active = synchronized(lock) {
                                isCurrentAttempt(attempt) && !finishing
                            }
                            if (active) notifyCallback { callback.onDownloading(status) }
                        }

                        override fun onSuccess(translations: List<TranslationResult>) {
                            completeSuccess(attempt, translations)
                        }

                        override fun onError(error: TranslationErrorCode) {
                            failAttempt(attempt, error)
                        }
                    },
                )
            } catch (failure: RuntimeException) {
                failAttempt(attempt, TranslationErrorCode.INTERNAL_ERROR)
                TranslationCall.NONE
            }

            val cancelNow = synchronized(lock) {
                if (isCurrentAttempt(attempt) && !finishing) {
                    activeCall = call
                    false
                } else {
                    true
                }
            }
            if (cancelNow) call.cancel()
        }

        private fun completeSuccess(
            attempt: Attempt,
            translations: List<TranslationResult>,
        ) {
            val accepted = synchronized(lock) {
                if (!isCurrentAttempt(attempt) || finishing) {
                    false
                } else {
                    finishing = true
                    currentEngine = null
                    generation += 1
                    true
                }
            }
            if (!accepted) return
            budgetFuture?.cancel(false)
            if (terminal.complete { notifyCallback { callback.onSuccess(translations) } }) {
                operations -= this
            }
        }

        private fun failAttempt(
            attempt: Attempt,
            error: TranslationErrorCode,
        ) {
            val accepted = synchronized(lock) {
                if (!isCurrentAttempt(attempt) || finishing) {
                    false
                } else {
                    currentEngine = null
                    activeCall = TranslationCall.NONE
                    generation += 1
                    if (attempt.engine == TranslationEngine.MLKIT_OFFLINE) finishing = true
                    true
                }
            }
            if (!accepted) return

            if (attempt.engine.isOnline()) {
                logAttemptFailure(attempt.engine, error, attempt.startedAtMs)
                advance()
            } else {
                budgetFuture?.cancel(false)
                if (terminal.complete { notifyCallback { callback.onError(error) } }) {
                    operations -= this
                }
            }
        }

        private fun expireOnlineBudget() {
            var callToCancel: TranslationCall? = null
            var expiredAttempt: Attempt? = null
            val shouldAdvance = synchronized(lock) {
                if (!terminal.isActive() || finishing || closed.get()) {
                    false
                } else {
                    val engine = currentEngine
                    if (engine != null && engine.isOnline()) {
                        expiredAttempt = Attempt(engine, generation, currentAttemptStartedAtMs)
                        callToCancel = activeCall
                        activeCall = TranslationCall.NONE
                        currentEngine = null
                        generation += 1
                    }
                    nextCandidateIndex = candidates.size
                    true
                }
            }
            if (!shouldAdvance) return
            callToCancel?.cancel()
            expiredAttempt?.let {
                logAttemptFailure(it.engine, TranslationErrorCode.TIMEOUT, it.startedAtMs)
            }
            advance()
        }

        override fun cancel() {
            var callToCancel: TranslationCall? = null
            var timeoutToCancel: ScheduledFuture<*>? = null
            val cancel = synchronized(lock) {
                if (finishing || !terminal.isActive()) {
                    false
                } else {
                    finishing = true
                    generation += 1
                    currentEngine = null
                    callToCancel = activeCall
                    activeCall = TranslationCall.NONE
                    timeoutToCancel = budgetFuture
                    budgetFuture = null
                    true
                }
            }
            if (!cancel) {
                if (terminal.cancel()) {
                    budgetFuture?.cancel(false)
                    operations -= this
                }
                return
            }
            timeoutToCancel?.cancel(false)
            callToCancel?.cancel()
            terminal.cancel()
            operations -= this
        }

        private fun isCurrentAttempt(attempt: Attempt): Boolean =
            terminal.isActive() &&
                currentEngine == attempt.engine &&
                generation == attempt.generation

        private fun elapsedOnlineMs(): Long =
            (nowMs() - onlineStartedAtMs).coerceAtLeast(0L)
    }

    private data class Attempt(
        val engine: TranslationEngine,
        val generation: Long,
        val startedAtMs: Long,
    )

    private val closed = AtomicBoolean(false)
    private val providers = mapOf(
        TranslationEngine.GOOGLE_WEB to googleWebProvider,
        TranslationEngine.DEEPL to deepLProvider,
        TranslationEngine.GEMINI to geminiProvider,
        TranslationEngine.MLKIT_OFFLINE to mlKitProvider,
    )
    private val operations = Collections.newSetFromMap(
        ConcurrentHashMap<RouterOperation, Boolean>(),
    )
    private val budgetExecutor = ScheduledThreadPoolExecutor(1) { runnable ->
        Thread(runnable, "lens-online-budget").apply { isDaemon = true }
    }.apply {
        setRemoveOnCancelPolicy(true)
    }

    override fun translate(
        request: TranslationRequest,
        callback: TranslationProvider.Callback,
    ): TranslationCall {
        if (closed.get()) {
            notifyCallback { callback.onError(TranslationErrorCode.PROVIDER_CLOSED) }
            return TranslationCall.NONE
        }

        val config = runCatching(configSupplier).getOrElse {
            TranslationEngineConfig(engine = TranslationEngine.MLKIT_OFFLINE)
        }
        val operation = RouterOperation(request, callback, candidates(config))
        operations += operation
        if (closed.get()) {
            operation.cancel()
            notifyCallback { callback.onError(TranslationErrorCode.PROVIDER_CLOSED) }
            return TranslationCall.NONE
        }
        operation.start()
        return operation
    }

    private fun candidates(config: TranslationEngineConfig): List<TranslationEngine> =
        when (config.engine) {
            TranslationEngine.AUTO -> buildList {
                if (config.geminiApiKey.isNotBlank()) add(TranslationEngine.GEMINI)
                if (config.deepLApiKey.isNotBlank()) add(TranslationEngine.DEEPL)
                // Product contract: AUTO intentionally tries keyless Google Web before offline ML Kit.
                add(TranslationEngine.GOOGLE_WEB)
                add(TranslationEngine.MLKIT_OFFLINE)
            }
            TranslationEngine.GOOGLE_WEB -> listOf(
                TranslationEngine.GOOGLE_WEB,
                TranslationEngine.MLKIT_OFFLINE,
            )
            TranslationEngine.DEEPL -> buildList {
                if (config.deepLApiKey.isNotBlank()) add(TranslationEngine.DEEPL)
                add(TranslationEngine.MLKIT_OFFLINE)
            }
            TranslationEngine.GEMINI -> buildList {
                if (config.geminiApiKey.isNotBlank()) add(TranslationEngine.GEMINI)
                add(TranslationEngine.MLKIT_OFFLINE)
            }
            TranslationEngine.MLKIT_OFFLINE -> listOf(TranslationEngine.MLKIT_OFFLINE)
        }

    private fun logAttemptFailure(
        engine: TranslationEngine,
        error: TranslationErrorCode,
        startedAtMs: Long,
    ) {
        val latencyMs = (nowMs() - startedAtMs).coerceAtLeast(0L)
        runCatching {
            logger(
                "engineAttemptFailed engine=${engine.name} " +
                    "code=${error.wireValue} latencyMs=$latencyMs",
            )
        }
    }

    private fun notifyCallback(action: () -> Unit) {
        runCatching(action).onFailure { failure ->
            runCatching {
                logger("callbackFailed exception=${failure.javaClass.simpleName}")
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        operations.toList().forEach(TranslationCall::cancel)
        budgetExecutor.shutdownNow()
        providers.values.distinct().forEach { provider ->
            runCatching { provider.close() }.onFailure { failure ->
                runCatching {
                    logger("providerCloseFailed exception=${failure.javaClass.simpleName}")
                }
            }
        }
    }

    private fun TranslationEngine.isOnline(): Boolean =
        this != TranslationEngine.MLKIT_OFFLINE && this != TranslationEngine.AUTO

    private companion object {
        private const val TAG = "LensTranslation"
        private const val ONLINE_BUDGET_MS = 5_500L
    }
}
