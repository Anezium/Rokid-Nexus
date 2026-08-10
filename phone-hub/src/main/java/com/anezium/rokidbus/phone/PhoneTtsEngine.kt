package com.anezium.rokidbus.phone

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

data class PhoneTtsVoiceOption(
    val name: String,
    val locale: Locale,
    val quality: Int,
    val needsNetwork: Boolean,
)

internal fun phoneTtsAudioAttributes(): AudioAttributes {
    val usage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        AudioAttributes.USAGE_ASSISTANT
    } else {
        AudioAttributes.USAGE_MEDIA
    }
    return AudioAttributes.Builder()
        .setUsage(usage)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}

internal enum class PhoneTtsSpeakResult {
    ACCEPTED,
    ENGINE_UNAVAILABLE,
    LANGUAGE_UNAVAILABLE,
}

internal interface PhoneTtsOutput {
    interface Listener {
        fun onStart(utteranceId: String)
        fun onDone(utteranceId: String)
        fun onUnavailable(utteranceId: String)
        fun onStopped(utteranceId: String)
    }

    val isReady: Boolean

    fun setListener(listener: Listener)
    fun initialize()
    fun speak(utteranceId: String, text: String, locale: Locale): PhoneTtsSpeakResult
    fun prewarm(): Boolean
    fun availableVoices(locale: Locale? = null): List<PhoneTtsVoiceOption>
    fun speakSample(text: String, locale: Locale): Boolean
    fun stop()
    fun shutdown()
}

internal interface PhoneTtsBackend {
    val voices: Set<Voice>?
    val defaultVoice: Voice?

    fun setLanguage(locale: Locale): Int
    fun setSpeechRate(rate: Float): Int
    fun setVoice(voice: Voice): Int
    fun setAudioAttributes(attributes: AudioAttributes): Int
    fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int
    fun speak(text: String, queueMode: Int, utteranceId: String): Int
    fun playSilentUtterance(durationInMs: Long, queueMode: Int, utteranceId: String): Int
    fun stop(): Int
    fun shutdown()
}

private class AndroidPhoneTtsBackend(
    private val delegate: TextToSpeech,
) : PhoneTtsBackend {
    override val voices: Set<Voice>?
        get() = delegate.voices
    override val defaultVoice: Voice?
        get() = delegate.defaultVoice

    override fun setLanguage(locale: Locale): Int = delegate.setLanguage(locale)
    override fun setSpeechRate(rate: Float): Int = delegate.setSpeechRate(rate)
    override fun setVoice(voice: Voice): Int = delegate.setVoice(voice)
    override fun setAudioAttributes(attributes: AudioAttributes): Int =
        delegate.setAudioAttributes(attributes)

    override fun setOnUtteranceProgressListener(listener: UtteranceProgressListener): Int =
        delegate.setOnUtteranceProgressListener(listener)

    override fun speak(text: String, queueMode: Int, utteranceId: String): Int =
        delegate.speak(text, queueMode, null, utteranceId)

    override fun playSilentUtterance(
        durationInMs: Long,
        queueMode: Int,
        utteranceId: String,
    ): Int = delegate.playSilentUtterance(durationInMs, queueMode, utteranceId)

    override fun stop(): Int = delegate.stop()
    override fun shutdown() = delegate.shutdown()
}

/** Lazy wrapper around the phone's configured Android TTS engine. */
internal class PhoneTtsEngine private constructor(
    private val appContext: Context,
    private val logger: (String) -> Unit,
    private val settings: PhoneTtsSettingsStore,
    private val mainHandler: Handler?,
    private val nowMs: () -> Long,
    initialEngine: PhoneTtsBackend?,
) : PhoneTtsOutput {
    private enum class State { NEW, INITIALIZING, READY, UNAVAILABLE, SHUTDOWN }

    private val lock = Any()
    @Volatile private var state = if (initialEngine == null) State.NEW else State.READY
    private var engine: PhoneTtsBackend? = initialEngine
    @Volatile private var listener: PhoneTtsOutput.Listener? = null
    private var activePluginUtteranceId: String? = null
    private var sampleActive = false
    private var prewarmActive = false
    private var lastPrewarmAtMs: Long? = null
    private val submittedAtMs = mutableMapOf<String, Long>()

    constructor(
        context: Context,
        logger: (String) -> Unit,
    ) : this(
        appContext = context.applicationContext,
        logger = logger,
        settings = PhoneTtsSettingsStore(context),
        mainHandler = Handler(Looper.getMainLooper()),
        nowMs = SystemClock::elapsedRealtime,
        initialEngine = null,
    )

    internal constructor(
        context: Context,
        backend: PhoneTtsBackend,
        logger: (String) -> Unit = {},
        nowMs: () -> Long = SystemClock::elapsedRealtime,
    ) : this(
        appContext = context.applicationContext,
        logger = logger,
        settings = PhoneTtsSettingsStore(context),
        mainHandler = null,
        nowMs = nowMs,
        initialEngine = backend,
    ) {
        check(backend.setOnUtteranceProgressListener(progressListener) != TextToSpeech.ERROR)
    }

    override val isReady: Boolean
        get() = state == State.READY

    private val progressListener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            val id = utteranceId ?: return
            logElapsed("start", id, remove = false)
            listener?.onStart(id)
        }

        override fun onDone(utteranceId: String?) {
            val id = utteranceId ?: return
            logElapsed("done", id, remove = true)
            retire(id)
            listener?.onDone(id)
        }

        @Deprecated("Android still calls this overload on older engines")
        override fun onError(utteranceId: String?) {
            handleUnavailable(utteranceId)
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            handleUnavailable(utteranceId)
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            val id = utteranceId ?: return
            retire(id)
            listener?.onStopped(id)
        }
    }

    override fun setListener(listener: PhoneTtsOutput.Listener) {
        this.listener = listener
    }

    override fun initialize() {
        synchronized(lock) {
            if (state != State.NEW) return
            state = State.INITIALIZING
        }
        mainHandler?.post(::createEngine)
    }

    override fun speak(
        utteranceId: String,
        text: String,
        locale: Locale,
    ): PhoneTtsSpeakResult = synchronized(lock) {
        val current = engine.takeIf { state == State.READY }
            ?: return@synchronized PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
        val prepared = prepareForSpeech(current, utteranceId, text.length, locale)
        if (prepared != PhoneTtsSpeakResult.ACCEPTED) return@synchronized prepared

        activePluginUtteranceId = utteranceId
        submittedAtMs[utteranceId] = nowMs()
        val result = runCatching {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, utteranceId)
        }.getOrElse {
            logger("phone TTS speak id=$utteranceId result=SPEAK_ERROR chars=${text.length}")
            TextToSpeech.ERROR
        }
        logger("phone TTS speak id=$utteranceId result=$result chars=${text.length}")
        if (result == TextToSpeech.SUCCESS) {
            PhoneTtsSpeakResult.ACCEPTED
        } else {
            if (activePluginUtteranceId == utteranceId) activePluginUtteranceId = null
            submittedAtMs.remove(utteranceId)
            PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
        }
    }

    override fun prewarm(): Boolean = synchronized(lock) {
        val current = engine.takeIf { state == State.READY } ?: return@synchronized false
        if (hasActiveUtterance()) return@synchronized false
        val now = nowMs()
        if (lastPrewarmAtMs?.let { now - it < PREWARM_INTERVAL_MS } == true) {
            return@synchronized false
        }

        prewarmActive = true
        submittedAtMs[PREWARM_UTTERANCE_ID] = now
        val result = runCatching {
            current.playSilentUtterance(
                PREWARM_DURATION_MS,
                TextToSpeech.QUEUE_ADD,
                PREWARM_UTTERANCE_ID,
            )
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            prewarmActive = false
            submittedAtMs.remove(PREWARM_UTTERANCE_ID)
            return@synchronized false
        }
        lastPrewarmAtMs = now
        true
    }

    override fun availableVoices(locale: Locale?): List<PhoneTtsVoiceOption> {
        val current = synchronized(lock) {
            engine.takeIf { state == State.READY }
        } ?: return emptyList()
        return runCatching {
            current.voices.orEmpty()
                .asSequence()
                .filter { voice -> locale == null || voice.locale.matchesLanguageTag(locale) }
                .map {
                    PhoneTtsVoiceOption(
                        name = it.name,
                        locale = it.locale,
                        quality = it.quality,
                        needsNetwork = it.isNetworkConnectionRequired,
                    )
                }
                .toList()
        }.getOrDefault(emptyList())
    }

    override fun speakSample(text: String, locale: Locale): Boolean = synchronized(lock) {
        val current = engine.takeIf { state == State.READY } ?: return@synchronized false
        if (activePluginUtteranceId != null || sampleActive) return@synchronized false
        if (prepareForSpeech(current, SAMPLE_UTTERANCE_ID, text.length, locale) !=
            PhoneTtsSpeakResult.ACCEPTED
        ) {
            return@synchronized false
        }

        sampleActive = true
        prewarmActive = false
        submittedAtMs.remove(PREWARM_UTTERANCE_ID)
        submittedAtMs[SAMPLE_UTTERANCE_ID] = nowMs()
        val result = runCatching {
            current.speak(text, TextToSpeech.QUEUE_FLUSH, SAMPLE_UTTERANCE_ID)
        }.getOrDefault(TextToSpeech.ERROR)
        if (result != TextToSpeech.SUCCESS) {
            sampleActive = false
            submittedAtMs.remove(SAMPLE_UTTERANCE_ID)
            return@synchronized false
        }
        true
    }

    override fun stop() {
        val current = synchronized(lock) {
            activePluginUtteranceId = null
            sampleActive = false
            prewarmActive = false
            engine
        }
        val result = runCatching { current?.stop() }.getOrNull()
        logger("phone TTS stop result=${result ?: TextToSpeech.ERROR}")
    }

    override fun shutdown() {
        val current = synchronized(lock) {
            if (state == State.SHUTDOWN) return
            state = State.SHUTDOWN
            activePluginUtteranceId = null
            sampleActive = false
            prewarmActive = false
            submittedAtMs.clear()
            engine.also { engine = null }
        }
        runCatching { current?.shutdown() }
        listener = null
        logger("phone TTS shutdown")
    }

    private fun prepareForSpeech(
        current: PhoneTtsBackend,
        utteranceId: String,
        textLength: Int,
        locale: Locale,
    ): PhoneTtsSpeakResult {
        val languageResult = runCatching { current.setLanguage(locale) }
            .getOrElse {
                logger("phone TTS speak id=$utteranceId result=LANGUAGE_ERROR chars=$textLength")
                return PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
            }
        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            logger("phone TTS speak id=$utteranceId result=LANGUAGE_UNAVAILABLE chars=$textLength")
            return PhoneTtsSpeakResult.LANGUAGE_UNAVAILABLE
        }

        val storedVoiceName = runCatching { settings.voiceName(locale) }.getOrNull()
        val selectedVoice = storedVoiceName?.let { name ->
            runCatching {
                current.voices.orEmpty().firstOrNull { voice ->
                    voice.name == name && voice.locale.matchesLanguageTag(locale)
                }
            }.getOrNull()
        }

        val rate = runCatching(settings::speechRate)
            .getOrDefault(PhoneTtsSettingsStore.DEFAULT_SPEECH_RATE)
        val rateResult = runCatching { current.setSpeechRate(rate) }.getOrDefault(TextToSpeech.ERROR)
        if (rateResult == TextToSpeech.ERROR) {
            logger("phone TTS speak id=$utteranceId result=RATE_ERROR chars=$textLength")
            return PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
        }
        if (selectedVoice != null) {
            val voiceResult = runCatching { current.setVoice(selectedVoice) }
                .getOrDefault(TextToSpeech.ERROR)
            if (voiceResult == TextToSpeech.ERROR) {
                logger("phone TTS speak id=$utteranceId result=VOICE_ERROR chars=$textLength")
                return PhoneTtsSpeakResult.ENGINE_UNAVAILABLE
            }
        }
        return PhoneTtsSpeakResult.ACCEPTED
    }

    private fun createEngine() {
        synchronized(lock) {
            if (state != State.INITIALIZING || engine != null) return
        }
        var candidate: PhoneTtsBackend? = null
        val textToSpeech = try {
            TextToSpeech(appContext) { status ->
                val initialized = candidate
                mainHandler?.post { completeInitialization(initialized, status) }
            }
        } catch (exception: RuntimeException) {
            logger("phone TTS ready=false reason=init_${exception.javaClass.simpleName}")
            synchronized(lock) {
                if (state == State.INITIALIZING) state = State.UNAVAILABLE
            }
            null
        }
        val initializedEngine = textToSpeech?.let(::AndroidPhoneTtsBackend) ?: return
        candidate = initializedEngine
        synchronized(lock) {
            if (state == State.INITIALIZING) {
                engine = initializedEngine
            } else {
                initializedEngine.shutdown()
            }
        }
    }

    private fun completeInitialization(candidate: PhoneTtsBackend?, status: Int) {
        val current = synchronized(lock) {
            if (state != State.INITIALIZING || engine !== candidate) return
            engine
        } ?: return markUnavailable("missing_engine")
        if (status != TextToSpeech.SUCCESS) {
            markUnavailable("init_status_$status")
            return
        }
        val configured = runCatching {
            current.setAudioAttributes(phoneTtsAudioAttributes()) != TextToSpeech.ERROR &&
                current.setOnUtteranceProgressListener(progressListener) != TextToSpeech.ERROR
        }.getOrDefault(false)
        if (!configured) {
            markUnavailable("configuration")
            return
        }
        val ready = synchronized(lock) {
            if (state == State.INITIALIZING && engine === current) {
                state = State.READY
                true
            } else {
                false
            }
        }
        if (ready) logger("phone TTS ready=true")
    }

    private fun markUnavailable(reason: String) {
        val current = synchronized(lock) {
            if (state == State.SHUTDOWN) return
            state = State.UNAVAILABLE
            engine.also { engine = null }
        }
        runCatching { current?.shutdown() }
        logger("phone TTS ready=false reason=$reason")
    }

    private fun handleUnavailable(utteranceId: String?) {
        val id = utteranceId ?: return
        retire(id)
        listener?.onUnavailable(id)
    }

    private fun retire(utteranceId: String) = synchronized(lock) {
        submittedAtMs.remove(utteranceId)
        if (activePluginUtteranceId == utteranceId) activePluginUtteranceId = null
        if (utteranceId == SAMPLE_UTTERANCE_ID) sampleActive = false
        if (utteranceId == PREWARM_UTTERANCE_ID) prewarmActive = false
    }

    private fun hasActiveUtterance(): Boolean =
        activePluginUtteranceId != null || sampleActive || prewarmActive

    private fun logElapsed(event: String, utteranceId: String, remove: Boolean) {
        val startedAt = synchronized(lock) {
            if (remove) submittedAtMs.remove(utteranceId) else submittedAtMs[utteranceId]
        } ?: return
        val elapsed = (nowMs() - startedAt).coerceAtLeast(0L)
        logger("phone TTS audio $event id=$utteranceId afterMs=$elapsed")
    }

    internal companion object {
        const val PREWARM_UTTERANCE_ID = "nexus-phone-tts-prewarm"
        const val SAMPLE_UTTERANCE_ID = "nexus-phone-tts-sample"
        const val PREWARM_DURATION_MS = 150L
        const val PREWARM_INTERVAL_MS = 10_000L
    }
}

private fun Locale.matchesLanguageTag(other: Locale): Boolean =
    toLanguageTag().equals(other.toLanguageTag(), ignoreCase = true)
