package com.anezium.rokidbus.phone.speech

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class SpeechStartResult {
    OK,
    BUSY,
    NO_LINK,
    NOT_READY,
    START_FAILED,
}

enum class SpeechSessionState {
    LISTENING,
    RECOGNIZING,
    PROCESSING,
}

enum class SpeechEndReason {
    COMPLETED,
    CANCELLED,
    NO_SPEECH,
    ERROR,
    LINK_LOST,
}

interface SpeechUtteranceListener {
    fun onState(state: SpeechSessionState)
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onEnded(reason: SpeechEndReason, error: SttError?)
}

enum class InternalAudioAcquireResult {
    OK,
    BUSY,
    NO_LINK,
    START_FAILED,
}

enum class InternalAudioStopReason {
    LINK_LOST,
    HUB_STOPPED,
}

interface InternalAudioConsumer {
    /**
     * Called directly on the CXR vendor callback thread. Implementations must copy before return.
     */
    fun onPcm(
        data: ByteArray,
        offset: Int,
        length: Int,
        seq: Long,
        elapsedRealtimeMs: Long,
    )

    fun onStopped(reason: InternalAudioStopReason)
}

interface InternalAudioAccess {
    fun acquireInternalAudio(
        tag: String,
        consumer: InternalAudioConsumer,
    ): InternalAudioAcquireResult

    fun releaseInternalAudio(tag: String)
}

internal fun interface MainThreadPoster {
    fun post(task: () -> Unit)
}

class SpeechSessionManager internal constructor(
    context: Context,
    private val settings: SpeechSettingsStore,
    private val secrets: HubSecretStore,
    private val internalAudio: InternalAudioAccess,
    private val sessionFactory: SpeechSttSessionFactory,
    private val mainPoster: MainThreadPoster,
    private val elapsedRealtime: () -> Long,
    private val audioExecutor: ScheduledThreadPoolExecutor,
    private val diagnostic: (String) -> Unit,
    private val readinessProvider: () -> SpeechReadiness,
) : AutoCloseable {
    constructor(
        context: Context,
        settings: SpeechSettingsStore,
        secrets: HubSecretStore,
        internalAudio: InternalAudioAccess,
    ) : this(
        context = context,
        settings = settings,
        secrets = secrets,
        internalAudio = internalAudio,
        sessionFactory = RoutingSttSessionFactory(
            cloud = CloudSttSessionFactory(secrets),
            android = AndroidSttSessionFactory(context),
        ),
        mainPoster = Handler(Looper.getMainLooper()).let { handler ->
            MainThreadPoster { task -> handler.post(task) }
        },
        elapsedRealtime = SystemClock::elapsedRealtime,
        audioExecutor = ScheduledThreadPoolExecutor(1).apply {
            removeOnCancelPolicy = true
        },
        diagnostic = {},
        readinessProvider = { settings.readiness(secrets) },
    )

    private val lock = Any()
    private var active: ActiveUtterance? = null
    private var closed = false

    val isActive: Boolean
        get() = synchronized(lock) { active?.ended?.get() == false }

    val activeRealtime: Boolean?
        get() = synchronized(lock) {
            active?.takeIf { !it.ended.get() }?.engine?.usesRealtime
        }

    fun startUtterance(
        listener: SpeechUtteranceListener,
        language: TranscriptionLanguage? = null,
    ): SpeechStartResult {
        val engine: SpeechEngine
        val sessionLanguage: TranscriptionLanguage
        synchronized(lock) {
            if (closed) return SpeechStartResult.START_FAILED
            if (active?.ended?.get() == false) return SpeechStartResult.BUSY
        }
        if (readinessProvider() != SpeechReadiness.READY) {
            return SpeechStartResult.NOT_READY
        }
        engine = settings.selectedEngine() ?: return SpeechStartResult.NOT_READY
        sessionLanguage = language ?: settings.selectedLanguageForEngine(engine)
        val patience = settings.patience()

        val run = ActiveUtterance(
            tag = "speech-${UUID.randomUUID()}",
            engine = engine,
            language = sessionLanguage,
            patience = patience,
            listener = listener,
        )
        run.vad.reset(elapsedRealtime())
        val stt = runCatching {
            sessionFactory.create(
                engine = engine,
                language = sessionLanguage,
                phoneLanguageTag = Locale.getDefault().toLanguageTag(),
                patience = patience,
                listener = EngineListener(run),
            )
        }.getOrNull() ?: return SpeechStartResult.START_FAILED
        run.sttSession = stt

        synchronized(lock) {
            if (closed) {
                stt.cancel()
                return SpeechStartResult.START_FAILED
            }
            if (active?.ended?.get() == false) {
                stt.cancel()
                return SpeechStartResult.BUSY
            }
            active = run
        }

        val acquireResult = internalAudio.acquireInternalAudio(
            tag = run.tag,
            consumer = AudioConsumer(run),
        )
        if (acquireResult != InternalAudioAcquireResult.OK) {
            run.ended.set(true)
            synchronized(lock) {
                if (active === run) active = null
            }
            stt.cancel()
            return when (acquireResult) {
                InternalAudioAcquireResult.BUSY -> SpeechStartResult.BUSY
                InternalAudioAcquireResult.NO_LINK -> SpeechStartResult.NO_LINK
                InternalAudioAcquireResult.START_FAILED -> SpeechStartResult.START_FAILED
                InternalAudioAcquireResult.OK -> SpeechStartResult.OK
            }
        }

        run.leaseAcquired.set(true)
        if (run.ended.get() || run.cancelRequested.get()) {
            releaseLease(run)
            return SpeechStartResult.OK
        }
        postState(run, SpeechSessionState.LISTENING)
        diagnostic(
            "start engine=${engine.id} language=${sessionLanguage.id}" +
                if (engine.startsOnSpeech) " deferred=speech" else "",
        )
        executeAudio(run) {
            if (run.cancelRequested.get()) {
                end(run, SpeechEndReason.CANCELLED, null)
                return@executeAudio
            }
            // An engine that gives up on silence is not started until there is speech to
            // give it; until then the detector alone keeps time (see SpeechEngine.startsOnSpeech).
            if (!engine.startsOnSpeech && !startEngine(run)) return@executeAudio
            run.vadTick = audioExecutor.scheduleAtFixedRate(
                { checkVadEndpoint(run) },
                VAD_CHECK_INTERVAL_MS,
                VAD_CHECK_INTERVAL_MS,
                TimeUnit.MILLISECONDS,
            )
        }
        return SpeechStartResult.OK
    }

    fun cancel() {
        cancelActive(expectedListener = null)
    }

    internal fun cancel(listener: SpeechUtteranceListener) {
        cancelActive(expectedListener = listener)
    }

    private fun cancelActive(expectedListener: SpeechUtteranceListener?) {
        val run = synchronized(lock) {
            active?.takeIf { expectedListener == null || it.listener === expectedListener }
        } ?: return
        if (!run.cancelRequested.compareAndSet(false, true)) return
        executeAudio(run) {
            end(run, SpeechEndReason.CANCELLED, null)
        }
    }

    override fun close() {
        val run = synchronized(lock) {
            if (closed) return
            closed = true
            active.also { active = null }
        }
        if (run != null && run.ended.compareAndSet(false, true)) {
            run.cancelRequested.set(true)
            run.vadTick?.cancel(false)
            run.sttSession?.cancel()
            releaseLease(run)
            postEnded(run, SpeechEndReason.CANCELLED, null)
        }
        audioExecutor.shutdownNow()
        sessionFactory.close()
    }

    private inner class AudioConsumer(
        private val run: ActiveUtterance,
    ) : InternalAudioConsumer {
        override fun onPcm(
            data: ByteArray,
            offset: Int,
            length: Int,
            seq: Long,
            elapsedRealtimeMs: Long,
        ) {
            if (run.ended.get() || run.cancelRequested.get() || length <= 0) return
            val safeOffset = offset.coerceIn(0, data.size)
            val safeLength = length.coerceAtMost(data.size - safeOffset)
            if (safeLength <= 0) return
            val copy = data.copyOfRange(safeOffset, safeOffset + safeLength)
            executeAudio(run) {
                acceptAudio(run, copy, seq, elapsedRealtimeMs)
            }
        }

        override fun onStopped(reason: InternalAudioStopReason) {
            executeAudio(run) {
                if (run.cancelRequested.get()) {
                    end(run, SpeechEndReason.CANCELLED, null)
                } else {
                    end(
                        run,
                        SpeechEndReason.LINK_LOST,
                        SttError(
                            SttErrorKind.SOURCE_UNAVAILABLE,
                            null,
                            when (reason) {
                                InternalAudioStopReason.LINK_LOST -> "Glasses audio link was lost"
                                InternalAudioStopReason.HUB_STOPPED -> "Phone hub stopped"
                            },
                        ),
                    )
                }
            }
        }
    }

    private inner class EngineListener(
        private val run: ActiveUtterance,
    ) : SttSessionListener {
        override fun onReady() {
            executeAudio(run) {
                if (run.ended.get() || run.cancelRequested.get()) return@executeAudio
            }
        }

        override fun onPartial(text: String) {
            executeAudio(run) {
                if (run.ended.get() || run.cancelRequested.get() || text.isBlank()) {
                    return@executeAudio
                }
                mainPoster.post {
                    run.listener.onPartial(text)
                }
            }
        }

        override fun onFinal(text: String) {
            executeAudio(run) {
                if (run.ended.get() || run.cancelRequested.get()) return@executeAudio
                if (text.isBlank()) {
                    end(
                        run,
                        SpeechEndReason.NO_SPEECH,
                        SttError(
                            SttErrorKind.NO_SPEECH,
                            run.engine.provider.displayName,
                            "Provider did not recognize speech",
                        ),
                    )
                    return@executeAudio
                }
                end(run, SpeechEndReason.COMPLETED, null, finalText = text)
            }
        }

        override fun onError(error: SttError) {
            executeAudio(run) {
                if (run.ended.get()) return@executeAudio
                when (error.kind) {
                    SttErrorKind.CANCELLED ->
                        end(run, SpeechEndReason.CANCELLED, null)
                    SttErrorKind.NO_SPEECH ->
                        end(run, SpeechEndReason.NO_SPEECH, error)
                    SttErrorKind.SOURCE_UNAVAILABLE ->
                        end(run, SpeechEndReason.LINK_LOST, error)
                    else ->
                        end(run, SpeechEndReason.ERROR, error)
                }
            }
        }
    }

    /**
     * Starts the engine on the audio executor and hands it whatever audio queued up meanwhile.
     * Returns false when the run is over because the engine would not start.
     */
    private fun startEngine(run: ActiveUtterance): Boolean {
        val stt = run.sttSession ?: return false
        val started = runCatching { stt.start() }.getOrDefault(false)
        if (!started) {
            if (!run.ended.get()) {
                val startFailure = (stt as? SttStartFailureSource)?.startFailure
                end(
                    run,
                    SpeechEndReason.ERROR,
                    startFailure ?: SttError(
                        SttErrorKind.INTERNAL,
                        run.engine.provider.displayName,
                        "Speech engine failed to start",
                    ),
                )
            }
            return false
        }
        run.engineStarted = true
        val pending = run.pendingAudio.toByteArray()
        run.pendingAudio.reset()
        if (pending.isNotEmpty()) {
            run.sttSession?.acceptPcm(pending, 0, pending.size)
        }
        return true
    }

    private fun acceptAudio(
        run: ActiveUtterance,
        pcm: ByteArray,
        @Suppress("UNUSED_PARAMETER") seq: Long,
        @Suppress("UNUSED_PARAMETER") elapsedRealtimeMs: Long,
    ) {
        if (run.ended.get() || run.cancelRequested.get() || run.endpointReached) return
        val hadSpeech = run.vad.speechDetected
        run.vad.acceptPcm16Le(pcm, 0, pcm.size, elapsedRealtime())
        run.pcmBytes += pcm.size
        if (!hadSpeech && run.vad.speechDetected) {
            postState(run, SpeechSessionState.RECOGNIZING)
        }
        if (!run.speechGateOpened) {
            if (run.preSpeechAudio.size() + pcm.size > MAX_PENDING_AUDIO_BYTES) {
                failAudioQueue(run)
                return
            }
            run.preSpeechAudio.write(pcm)
            if (!run.vad.speechDetected) return
            run.speechGateOpened = true
            val captured = run.preSpeechAudio.toByteArray()
            run.preSpeechAudio.reset()
            queueEngineAudio(run, captured)
            if (run.engine.startsOnSpeech && !run.engineStarted) {
                diagnostic("start engine=${run.engine.id} trigger=speech")
                startEngine(run)
            }
            return
        }
        queueEngineAudio(run, pcm)
    }

    private fun queueEngineAudio(run: ActiveUtterance, pcm: ByteArray) {
        if (run.engineStarted) {
            run.sttSession?.acceptPcm(pcm, 0, pcm.size)
        } else if (run.pendingAudio.size() + pcm.size <= MAX_PENDING_AUDIO_BYTES) {
            run.pendingAudio.write(pcm)
        } else {
            failAudioQueue(run)
        }
    }

    private fun failAudioQueue(run: ActiveUtterance) {
        if (!run.ended.get()) {
            end(
                run,
                SpeechEndReason.ERROR,
                SttError(
                    SttErrorKind.INTERNAL,
                    run.engine.provider.displayName,
                    "Speech audio queue limit reached",
                ),
            )
        }
    }

    private fun checkVadEndpoint(run: ActiveUtterance) {
        if (run.ended.get() || run.cancelRequested.get() || run.endpointReached) return
        val closeReason = run.vad.closeReason(elapsedRealtime()) ?: return
        run.endpointReached = true
        run.vadTick?.cancel(false)
        run.vadTick = null
        diagnostic(
            "endpoint engine=${run.engine.id} language=${run.language.id} " +
                "bytes=${run.pcmBytes} speech=${run.vad.speechDetected} reason=${closeReason.substringBefore(' ')}",
        )
        // The utterance is complete, so hand the microphone back before the provider
        // round-trip: the glasses mic stops the moment you stop speaking instead of
        // staying open while the transcript is in flight. A link drop from here on no
        // longer matters either — every byte the engine needs has already been sent.
        releaseLease(run)
        if (!run.vad.speechDetected) {
            run.sttSession?.cancel()
            end(run, SpeechEndReason.NO_SPEECH, null)
            return
        }
        postState(run, SpeechSessionState.PROCESSING)
        finishProviderAudio(run)
    }

    private fun finishProviderAudio(run: ActiveUtterance) {
        if (run.providerFinished || run.ended.get()) return
        run.providerFinished = true
        run.sttSession?.finishAudio()
    }

    private fun postState(run: ActiveUtterance, state: SpeechSessionState) {
        if (run.lastState == state || run.ended.get()) return
        run.lastState = state
        diagnostic("state=${state.name.lowercase(Locale.US)} engine=${run.engine.id}")
        mainPoster.post {
            run.listener.onState(state)
        }
    }

    private fun end(
        run: ActiveUtterance,
        reason: SpeechEndReason,
        error: SttError?,
        finalText: String? = null,
    ) {
        if (!run.ended.compareAndSet(false, true)) return
        run.vadTick?.cancel(false)
        run.vadTick = null
        run.sttSession?.cancel()
        run.pendingAudio.reset()
        run.preSpeechAudio.reset()
        releaseLease(run)
        synchronized(lock) {
            if (active === run) active = null
        }
        diagnostic(
            "ended reason=${reason.name.lowercase(Locale.US)} engine=${run.engine.id} " +
                "bytes=${run.pcmBytes} error=${error?.kind?.name ?: "none"}",
        )
        postEnded(run, reason, error, finalText)
    }

    private fun postEnded(
        run: ActiveUtterance,
        reason: SpeechEndReason,
        error: SttError?,
        finalText: String? = null,
    ) {
        mainPoster.post {
            finalText?.let(run.listener::onFinal)
            run.listener.onEnded(reason, error)
        }
    }

    private fun releaseLease(run: ActiveUtterance) {
        if (run.leaseAcquired.get() && run.leaseReleased.compareAndSet(false, true)) {
            internalAudio.releaseInternalAudio(run.tag)
        }
    }

    private fun executeAudio(run: ActiveUtterance, task: () -> Unit) {
        if (run.ended.get() && !run.cancelRequested.get()) return
        try {
            audioExecutor.execute(task)
        } catch (_: RejectedExecutionException) {
            // Manager teardown owns the physical lease cleanup.
        }
    }

    private class ActiveUtterance(
        val tag: String,
        val engine: SpeechEngine,
        val language: TranscriptionLanguage,
        val patience: SpeechPatience,
        val listener: SpeechUtteranceListener,
    ) {
        val vad = VoiceActivityDetector(patience.voiceActivityConfig())
        val pendingAudio = ByteArrayOutputStream()
        val preSpeechAudio = ByteArrayOutputStream()
        val cancelRequested = AtomicBoolean(false)
        val leaseAcquired = AtomicBoolean(false)
        val leaseReleased = AtomicBoolean(false)
        val ended = AtomicBoolean(false)
        var sttSession: SttSession? = null
        var vadTick: ScheduledFuture<*>? = null
        var engineStarted = false
        var speechGateOpened = false
        var endpointReached = false
        var providerFinished = false
        var pcmBytes = 0L
        var lastState: SpeechSessionState? = null
    }

    companion object {
        private const val VAD_CHECK_INTERVAL_MS = 120L
        private const val MAX_PENDING_AUDIO_BYTES = 1_024 * 1_024
    }
}
