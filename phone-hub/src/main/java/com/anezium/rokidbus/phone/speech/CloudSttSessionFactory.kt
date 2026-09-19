package com.anezium.rokidbus.phone.speech

import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

internal interface SpeechSttSessionFactory : AutoCloseable {
    fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession

    override fun close() = Unit
}

internal fun interface SttTimeoutHandle {
    fun cancel()
}

internal fun interface SttTimeoutScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): SttTimeoutHandle
}

internal class ExecutorSttTimeoutScheduler : SttTimeoutScheduler, AutoCloseable {
    private val executor = ScheduledThreadPoolExecutor(1).apply {
        removeOnCancelPolicy = true
    }

    override fun schedule(delayMs: Long, task: () -> Unit): SttTimeoutHandle {
        val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
        return SttTimeoutHandle { future.cancel(false) }
    }

    override fun close() {
        executor.shutdownNow()
    }
}

internal class PostCommitTimeoutSttSession(
    delegateFactory: (SttSessionListener) -> SttSession,
    private val listener: SttSessionListener,
    private val providerLabel: String,
    private val timeoutScheduler: SttTimeoutScheduler,
    private val timeoutMs: Long = FINAL_RESULT_TIMEOUT_MS,
) : SttSession, SttSessionListener {
    private val lock = Any()
    private val delegate = delegateFactory(this)
    private var timeoutHandle: SttTimeoutHandle? = null
    private var committed = false
    private var terminal = false
    private var cancelled = false

    override fun start(): Boolean =
        synchronized(lock) {
            if (cancelled || terminal) return false
            delegate.start()
        }

    override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        synchronized(lock) {
            if (cancelled || terminal || committed) return
        }
        delegate.acceptPcm(data, offset, length)
    }

    override fun finishAudio() {
        synchronized(lock) {
            if (cancelled || terminal || committed) return
            committed = true
        }
        delegate.finishAudio()
        synchronized(lock) {
            if (!cancelled && !terminal && timeoutHandle == null) {
                timeoutHandle = timeoutScheduler.schedule(timeoutMs, ::onTimeout)
            }
        }
    }

    override fun cancel() {
        val shouldCancel = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            terminal = true
            timeoutHandle?.cancel()
            timeoutHandle = null
            true
        }
        if (shouldCancel) delegate.cancel()
    }

    override fun onReady() {
        synchronized(lock) {
            if (cancelled || terminal) return
        }
        listener.onReady()
    }

    override fun onPartial(text: String) {
        synchronized(lock) {
            if (cancelled || terminal) return
        }
        listener.onPartial(text)
    }

    override fun onFinal(text: String) {
        synchronized(lock) {
            if (cancelled || terminal) return
            terminal = true
            timeoutHandle?.cancel()
            timeoutHandle = null
        }
        listener.onFinal(text)
    }

    override fun onError(error: SttError) {
        synchronized(lock) {
            if (cancelled || terminal) return
            terminal = true
            timeoutHandle?.cancel()
            timeoutHandle = null
        }
        listener.onError(error)
    }

    private fun onTimeout() {
        synchronized(lock) {
            if (cancelled || terminal) return
            terminal = true
            timeoutHandle = null
        }
        delegate.cancel()
        listener.onError(
            SttError(
                SttErrorKind.TIMEOUT,
                providerLabel,
                "Final speech result timed out",
            ),
        )
    }

    companion object {
        const val FINAL_RESULT_TIMEOUT_MS = 15_000L
    }
}

internal class CloudSttSessionFactory(
    private val secrets: HubSecretStore,
    private val timeoutScheduler: ExecutorSttTimeoutScheduler = ExecutorSttTimeoutScheduler(),
    private val networkExecutor: java.util.concurrent.ExecutorService =
        Executors.newSingleThreadExecutor(),
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .pingInterval(15, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build(),
) : SpeechSttSessionFactory {
    override fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession =
        PostCommitTimeoutSttSession(
            delegateFactory = { timedListener ->
                if (engine.usesRealtime) {
                    ApiRealtimeSpeechToText.create(
                        client = httpClient,
                        secrets = secrets,
                        engine = engine,
                        listener = timedListener,
                        forcedLanguage = language,
                        phoneLanguageTag = phoneLanguageTag,
                    )
                } else {
                    BufferedSttSession(
                        engine = engine,
                        language = language,
                        languageTag = phoneLanguageTag,
                        transcriber = ApiCompletedAudioSpeechToTextEngine(secrets, engine),
                        executor = networkExecutor,
                        listener = timedListener,
                    )
                }
            },
            listener = listener,
            providerLabel = engine.provider.displayName,
            timeoutScheduler = timeoutScheduler,
        )

    override fun close() {
        timeoutScheduler.close()
        networkExecutor.shutdownNow()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
