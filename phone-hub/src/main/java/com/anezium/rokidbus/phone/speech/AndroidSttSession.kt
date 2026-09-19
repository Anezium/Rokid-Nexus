package com.anezium.rokidbus.phone.speech

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.anezium.rokidbus.phone.BusHubService
import java.io.FileOutputStream
import java.io.IOException
import java.util.Locale
import java.util.concurrent.FutureTask

internal interface AndroidSpeechRecognizer {
    fun setRecognitionListener(listener: RecognitionListener)
    fun startListening(intent: Intent)
    fun stopListening()
    fun cancel()
    fun destroy()
}

internal data class AndroidRecognizerTarget(
    val id: String,
    val reportName: String,
    val segmentedSession: Boolean = true,
    val stopListeningOnInputClose: Boolean = false,
    val create: () -> AndroidSpeechRecognizer,
)

internal interface AndroidRecognizerEnvironment {
    val sdkInt: Int
    fun hasRecordAudioPermission(): Boolean
    fun isRecognitionAvailable(): Boolean
    fun recognitionTargets(): List<AndroidRecognizerTarget>
}

internal interface AndroidAudioPipe {
    val readEnd: ParcelFileDescriptor
    fun write(data: ByteArray, offset: Int, length: Int)
    fun closeWrite()
    fun close()
}

internal fun interface AndroidAudioPipeFactory {
    fun create(): AndroidAudioPipe
}

internal fun interface AndroidScheduledTask {
    fun cancel()
}

internal interface AndroidMainThread {
    fun <T> call(task: () -> T): T
    fun schedule(delayMs: Long, task: () -> Unit): AndroidScheduledTask
}

internal interface AndroidSttForegroundController {
    fun acquire(): SttError?
    fun release()
}

internal class AndroidSttSession(
    context: Context,
    private val language: TranscriptionLanguage,
    private val patience: SpeechPatience = SpeechPatience.NORMAL,
    private val listener: SttSessionListener,
    private val environment: AndroidRecognizerEnvironment =
        PlatformAndroidRecognizerEnvironment(context.applicationContext),
    private val pipeFactory: AndroidAudioPipeFactory =
        AndroidAudioPipeFactory { ParcelDescriptorAudioPipe.create() },
    private val mainThread: AndroidMainThread = HandlerAndroidMainThread(),
    private val foregroundController: AndroidSttForegroundController =
        HubAndroidSttForegroundController,
) : SttSession, SttStartFailureSource {
    private val lock = Any()
    private val segmentTranscripts = mutableListOf<String>()

    private var startAttempted = false
    private var cancelled = false
    private var terminal = false
    private var foregroundHeld = false
    private var audioFinished = false
    private var currentInputClosed = false
    private var targetIndex = 0
    private var languageTagIndex = 0
    private var transientRetryAttempt = 0
    private var currentPipe: AndroidAudioPipe? = null
    private var currentRecognizer: AndroidSpeechRecognizer? = null
    private var currentTarget: AndroidRecognizerTarget? = null
    private var retryTask: AndroidScheduledTask? = null
    private var finalResultTask: AndroidScheduledTask? = null
    private var bestPartialTranscript = ""
    private var totalBytes = 0L

    override var startFailure: SttError? = null
        private set

    override fun start(): Boolean {
        synchronized(lock) {
            if (startAttempted || cancelled || terminal) return false
            startAttempted = true
        }
        preflightError()?.let { error ->
            return failStart(error)
        }

        val foregroundError = runCatching {
            mainThread.call { foregroundController.acquire() }
        }.getOrElse { failure ->
            SttError(
                SttErrorKind.SOURCE_UNAVAILABLE,
                ANDROID_PROVIDER_LABEL,
                "Microphone foreground service could not start (${failure.safeType()})",
            )
        }
        if (foregroundError != null) return failStart(foregroundError)
        synchronized(lock) {
            if (cancelled || terminal) {
                mainThread.call { foregroundController.release() }
                return false
            }
            foregroundHeld = true
        }

        val attemptError = startCurrentAttempt()
        if (attemptError != null) return failStart(attemptError)
        return true
    }

    override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        val safeOffset = offset.coerceIn(0, data.size)
        val safeLength = length.coerceAtMost(data.size - safeOffset)
        if (safeLength <= 0) return

        var writeFailure: Throwable? = null
        synchronized(lock) {
            if (cancelled || terminal || audioFinished || currentInputClosed) return
            val pipe = currentPipe ?: return
            try {
                pipe.write(data, safeOffset, safeLength)
                totalBytes += safeLength
            } catch (failure: Throwable) {
                writeFailure = failure
            }
        }
        writeFailure?.let { failure ->
            Log.w(TAG, "Injected recognizer audio pipe write failed bytes=$safeLength", failure)
            finishWithError(
                SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Injected glasses audio source failed",
                ),
            )
        }
    }

    override fun finishAudio() {
        val hasCurrentInput = synchronized(lock) {
            if (cancelled || terminal || audioFinished) return
            audioFinished = true
            currentPipe != null && !currentInputClosed
        }
        if (hasCurrentInput) closeCurrentInput()
    }

    override fun cancel() {
        val terminalResources = synchronized(lock) {
            if (cancelled) return
            cancelled = true
            terminal = true
            takeTerminalResourcesLocked()
        }
        terminalResources.retryTask?.cancel()
        terminalResources.finalResultTask?.cancel()
        cleanupRecognizer(terminalResources.pipe, terminalResources.recognizer)
        if (terminalResources.releaseForeground) {
            runCatching { mainThread.call { foregroundController.release() } }
        }
    }

    private fun preflightError(): SttError? =
        when {
            environment.sdkInt < Build.VERSION_CODES.TIRAMISU ->
                SttError(
                    SttErrorKind.PROVIDER,
                    ANDROID_PROVIDER_LABEL,
                    "Injected audio recognition requires Android 13 or later",
                )
            !environment.hasRecordAudioPermission() ->
                SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Microphone permission is not granted",
                )
            !environment.isRecognitionAvailable() ->
                SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Android speech recognition is unavailable",
                )
            environment.recognitionTargets().isEmpty() ->
                SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Android speech recognition is unavailable",
                )
            else -> null
        }

    private fun startCurrentAttempt(): SttError? {
        val target: AndroidRecognizerTarget
        val languageTag: String?
        synchronized(lock) {
            if (cancelled || terminal) return null
            target = environment.recognitionTargets().getOrNull(targetIndex)
                ?: return SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Android speech recognition is unavailable",
                )
            languageTag = androidLanguageTags().getOrNull(languageTagIndex)
        }

        val pipe = runCatching { pipeFactory.create() }.getOrElse { failure ->
            Log.w(TAG, "Could not create injected recognizer audio pipe", failure)
            return SttError(
                SttErrorKind.SOURCE_UNAVAILABLE,
                ANDROID_PROVIDER_LABEL,
                "Injected glasses audio source could not start",
            )
        }
        var createdRecognizer: AndroidSpeechRecognizer? = null
        val started = runCatching {
            mainThread.call {
                val recognizer = target.create()
                createdRecognizer = recognizer
                synchronized(lock) {
                    if (cancelled || terminal) return@call false
                    currentPipe = pipe
                    currentRecognizer = recognizer
                    currentTarget = target
                    currentInputClosed = false
                }
                recognizer.setRecognitionListener(recognitionListener(recognizer))
                recognizer.startListening(recognizerIntent(pipe.readEnd, target, languageTag))
                Log.i(
                    TAG,
                    "recognizer start target=${target.reportName} " +
                        "language=${languageTag ?: "auto"} segmented=${target.segmentedSession}",
                )
                if (isActive(recognizer)) listener.onReady()
                true
            }
        }.getOrElse { failure ->
            Log.w(
                TAG,
                "Android speech recognizer failed to start target=${target.reportName}",
                failure,
            )
            false
        }
        if (!started) {
            synchronized(lock) {
                if (currentPipe === pipe) currentPipe = null
                if (currentRecognizer === createdRecognizer) currentRecognizer = null
                if (currentTarget === target) currentTarget = null
            }
            cleanupRecognizer(pipe, createdRecognizer)
            val nextAttempt = synchronized(lock) {
                if (terminal || cancelled) return null
                nextRecognizerTargetAttemptLocked()?.also { attempt ->
                    languageTagIndex = attempt.languageTagIndex
                    targetIndex = attempt.targetIndex
                }
            }
            if (nextAttempt == null) {
                return SttError(
                    SttErrorKind.PROVIDER,
                    ANDROID_PROVIDER_LABEL,
                    "Android speech recognizer failed to start",
                )
            }
            Log.i(
                TAG,
                "recognizer fallback reason=start-failure " +
                    "target=${recognizerTarget(nextAttempt).reportName}",
            )
            return startCurrentAttempt()
        }

        val shouldClose = synchronized(lock) {
            audioFinished && !terminal && currentPipe === pipe && !currentInputClosed
        }
        if (shouldClose) closeCurrentInput()
        return null
    }

    private fun recognizerIntent(
        readEnd: ParcelFileDescriptor,
        target: AndroidRecognizerTarget,
        languageTag: String?,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            languageTag?.takeIf { it.isNotBlank() }?.let { tag ->
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            }
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                SPEECH_INPUT_MINIMUM_LENGTH_MS,
            )
            val silenceMs = patience.androidSilenceMs.toInt()
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMs,
            )
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                silenceMs,
            )
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            if (target.segmentedSession) {
                putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            }
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE_HZ)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }

    private fun recognitionListener(owner: AndroidSpeechRecognizer): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) = Unit
            override fun onBeginningOfSpeech() = Unit
            override fun onRmsChanged(rmsdB: Float) = Unit
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!isActive(owner)) return
                val inputWasClosed = synchronized(lock) { currentInputClosed }
                val transcript = bestAvailableTranscript()
                Log.w(
                    TAG,
                    "recognizer error code=$error inputClosed=$inputWasClosed bytes=$totalBytes " +
                        "partialAvailable=${transcript.isNotBlank()}",
                )
                when {
                    transcript.isNotBlank() && error.allowsPartialFallback(inputWasClosed) ->
                        finishWithFinal(transcript)
                    error.isLanguageSupportError() ->
                        retryAfterLanguageError(owner, error)
                    error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                        retryAfterTransientDisconnect(owner, error)
                    error == SpeechRecognizer.ERROR_CLIENT ->
                        retryAfterClientError(owner)
                    else ->
                        finishWithError(androidRecognizerError(error, inputWasClosed))
                }
            }

            override fun onResults(results: Bundle?) {
                if (!isActive(owner)) return
                val finalText = results.bestRecognizerText()
                val text = bestCompleteTranscript(finalText, bestAvailableTranscript())
                if (text.isBlank()) {
                    finishWithError(noSpeechError())
                } else {
                    finishWithFinal(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                if (!isActive(owner)) return
                val partial = partialResults.bestRecognizerText()
                if (partial.isBlank()) return
                val merged = synchronized(lock) {
                    if (!isActiveLocked(owner)) return
                    bestPartialTranscript = mergeTranscriptWindow(bestPartialTranscript, partial)
                    bestPartialTranscript
                }
                listener.onPartial(merged)
            }

            override fun onSegmentResults(segmentResults: Bundle) {
                if (!isActive(owner)) return
                val segment = segmentResults.bestRecognizerText()
                if (segment.isBlank()) return
                val merged = synchronized(lock) {
                    if (!isActiveLocked(owner)) return
                    segmentTranscripts += segment
                    bestAvailableTranscriptLocked()
                }
                listener.onPartial(merged)
            }

            override fun onEndOfSegmentedSession() {
                if (!isActive(owner)) return
                val transcript = bestAvailableTranscript()
                if (transcript.isBlank()) {
                    finishWithError(noSpeechError())
                } else {
                    finishWithFinal(transcript)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun retryAfterLanguageError(owner: AndroidSpeechRecognizer, errorCode: Int) {
        val nextAttempt = synchronized(lock) {
            if (!isActiveLocked(owner)) return
            nextRecognizerWalkAttemptLocked()
        }
        if (nextAttempt == null) {
            finishWithError(androidRecognizerError(errorCode, synchronized(lock) { currentInputClosed }))
            return
        }
        replaceRecognizer(owner, nextAttempt, delayMs = 0L)
    }

    private fun retryAfterTransientDisconnect(owner: AndroidSpeechRecognizer, errorCode: Int) {
        val retry = synchronized(lock) {
            if (!isActiveLocked(owner) || transientRetryAttempt >= MAX_TRANSIENT_RETRIES) {
                false
            } else {
                transientRetryAttempt += 1
                true
            }
        }
        if (!retry) {
            finishWithError(androidRecognizerError(errorCode, synchronized(lock) { currentInputClosed }))
            return
        }
        replaceRecognizer(
            owner = owner,
            nextAttempt = RecognizerWalkAttempt(languageTagIndex, targetIndex),
            delayMs = TRANSIENT_RETRY_DELAY_MS,
        )
    }

    private fun retryAfterClientError(owner: AndroidSpeechRecognizer) {
        val nextAttempt = synchronized(lock) {
            if (!isActiveLocked(owner)) return
            nextRecognizerTargetAttemptLocked()
        }
        if (nextAttempt == null) {
            val inputWasClosed = synchronized(lock) { currentInputClosed }
            finishWithError(terminalClientError(inputWasClosed))
            return
        }
        Log.i(
            TAG,
            "recognizer fallback reason=client-error " +
                "target=${recognizerTarget(nextAttempt).reportName}",
        )
        replaceRecognizer(owner, nextAttempt, delayMs = 0L)
    }

    private fun replaceRecognizer(
        owner: AndroidSpeechRecognizer,
        nextAttempt: RecognizerWalkAttempt,
        delayMs: Long,
    ) {
        val resources = synchronized(lock) {
            if (!isActiveLocked(owner)) return
            finalResultTask?.cancel()
            finalResultTask = null
            val detached = CurrentResources(currentPipe, currentRecognizer)
            currentPipe = null
            currentRecognizer = null
            currentTarget = null
            currentInputClosed = false
            languageTagIndex = nextAttempt.languageTagIndex
            targetIndex = nextAttempt.targetIndex
            detached
        }
        cleanupRecognizer(resources.pipe, resources.recognizer)

        val restart = {
            val canRestart = synchronized(lock) {
                retryTask = null
                !cancelled && !terminal
            }
            if (canRestart) startCurrentAttempt()?.let(::finishWithError)
        }
        if (delayMs <= 0L) {
            restart()
        } else {
            val scheduled = mainThread.schedule(delayMs, restart)
            synchronized(lock) {
                if (cancelled || terminal) {
                    scheduled.cancel()
                } else {
                    retryTask = scheduled
                }
            }
        }
    }

    private fun closeCurrentInput() {
        var closeFailure: Throwable? = null
        val recognizer: AndroidSpeechRecognizer?
        val target: AndroidRecognizerTarget?
        synchronized(lock) {
            if (cancelled || terminal || currentInputClosed) return
            val pipe = currentPipe ?: return
            currentInputClosed = true
            try {
                pipe.closeWrite()
            } catch (failure: Throwable) {
                closeFailure = failure
            }
            recognizer = currentRecognizer
            target = currentTarget
        }
        if (closeFailure != null) {
            finishWithError(
                SttError(
                    SttErrorKind.SOURCE_UNAVAILABLE,
                    ANDROID_PROVIDER_LABEL,
                    "Injected glasses audio source failed",
                ),
            )
            return
        }
        if (target?.stopListeningOnInputClose == true) {
            runCatching { mainThread.call { recognizer?.stopListening() } }
                .onFailure { Log.w(TAG, "Speech recognizer stopListening failed", it) }
        }
        scheduleFinalResultTimeout()
    }

    private fun scheduleFinalResultTimeout() {
        val scheduled = mainThread.schedule(FINAL_RESULT_TIMEOUT_MS) {
            val transcript = bestAvailableTranscript()
            if (transcript.isBlank()) {
                finishWithError(noSpeechError())
            } else {
                finishWithFinal(transcript)
            }
        }
        synchronized(lock) {
            if (cancelled || terminal) {
                scheduled.cancel()
            } else {
                finalResultTask?.cancel()
                finalResultTask = scheduled
            }
        }
    }

    private fun failStart(error: SttError): Boolean {
        startFailure = error
        finishWithError(error)
        return false
    }

    private fun finishWithFinal(transcript: String) {
        val clean = transcript.trim()
        if (clean.isBlank()) {
            finishWithError(noSpeechError())
            return
        }
        val resources = terminate() ?: return
        releaseResources(resources)
        mainThread.call { listener.onFinal(clean) }
    }

    private fun finishWithError(error: SttError) {
        val resources = terminate() ?: return
        releaseResources(resources)
        mainThread.call { listener.onError(error) }
    }

    private fun terminate(): TerminalResources? =
        synchronized(lock) {
            if (cancelled || terminal) return null
            terminal = true
            takeTerminalResourcesLocked()
        }

    private fun takeTerminalResourcesLocked(): TerminalResources {
        val resources = TerminalResources(
            pipe = currentPipe,
            recognizer = currentRecognizer,
            retryTask = retryTask,
            finalResultTask = finalResultTask,
            releaseForeground = foregroundHeld,
        )
        currentPipe = null
        currentRecognizer = null
        currentTarget = null
        retryTask = null
        finalResultTask = null
        foregroundHeld = false
        return resources
    }

    private fun releaseResources(resources: TerminalResources) {
        resources.retryTask?.cancel()
        resources.finalResultTask?.cancel()
        cleanupRecognizer(resources.pipe, resources.recognizer)
        if (resources.releaseForeground) {
            runCatching { mainThread.call { foregroundController.release() } }
        }
    }

    private fun cleanupRecognizer(
        pipe: AndroidAudioPipe?,
        recognizer: AndroidSpeechRecognizer?,
    ) {
        runCatching { pipe?.close() }
        runCatching {
            mainThread.call {
                runCatching { recognizer?.cancel() }
                runCatching { recognizer?.destroy() }
            }
        }
    }

    private fun isActive(owner: AndroidSpeechRecognizer): Boolean =
        synchronized(lock) { isActiveLocked(owner) }

    private fun isActiveLocked(owner: AndroidSpeechRecognizer): Boolean =
        !cancelled && !terminal && currentRecognizer === owner

    private fun nextRecognizerWalkAttemptLocked(): RecognizerWalkAttempt? {
        val tags = androidLanguageTags()
        val nextLanguageIndex = languageTagIndex + 1
        if (nextLanguageIndex < tags.size) {
            return RecognizerWalkAttempt(nextLanguageIndex, targetIndex)
        }
        val nextTargetIndex = targetIndex + 1
        if (nextTargetIndex >= environment.recognitionTargets().size) return null
        return RecognizerWalkAttempt(0, nextTargetIndex)
    }

    private fun nextRecognizerTargetAttemptLocked(): RecognizerWalkAttempt? {
        val nextTargetIndex = targetIndex + 1
        if (nextTargetIndex >= environment.recognitionTargets().size) return null
        return RecognizerWalkAttempt(0, nextTargetIndex)
    }

    private fun recognizerTarget(attempt: RecognizerWalkAttempt): AndroidRecognizerTarget =
        environment.recognitionTargets()[attempt.targetIndex]

    private fun terminalClientError(inputWasClosed: Boolean): SttError {
        val error = androidRecognizerError(SpeechRecognizer.ERROR_CLIENT, inputWasClosed)
        val targets = environment.recognitionTargets()
        val onlyNonGoogleDefault = targets.size == 1 &&
            targets.single().id == DEFAULT_TARGET_ID &&
            !targets.single().reportName.contains(GOOGLE_PACKAGE_PREFIX)
        return if (onlyNonGoogleDefault) {
            error.copy(detail = NO_EXTERNAL_AUDIO_SUPPORT_DETAIL)
        } else {
            error
        }
    }

    private fun androidLanguageTags(): List<String?> =
        if (language == TranscriptionLanguage.AUTO) {
            listOf(null)
        } else {
            language.androidTagChain().map { tag -> tag as String? }.ifEmpty { listOf(null) }
        }

    private fun bestAvailableTranscript(): String =
        synchronized(lock) { bestAvailableTranscriptLocked() }

    private fun bestAvailableTranscriptLocked(): String {
        val segments = segmentTranscripts.joinToString(" ").trim()
        return bestCompleteTranscript(segments, bestPartialTranscript)
    }

    private fun noSpeechError(): SttError =
        SttError(
            SttErrorKind.NO_SPEECH,
            ANDROID_PROVIDER_LABEL,
            "Android speech recognizer returned no text",
        )

    private data class RecognizerWalkAttempt(
        val languageTagIndex: Int,
        val targetIndex: Int,
    )

    private data class CurrentResources(
        val pipe: AndroidAudioPipe?,
        val recognizer: AndroidSpeechRecognizer?,
    )

    private data class TerminalResources(
        val pipe: AndroidAudioPipe?,
        val recognizer: AndroidSpeechRecognizer?,
        val retryTask: AndroidScheduledTask?,
        val finalResultTask: AndroidScheduledTask?,
        val releaseForeground: Boolean,
    )

    companion object {
        private const val TAG = "NexusAndroidStt"
        private const val ANDROID_PROVIDER_LABEL = "Android"
        private const val SAMPLE_RATE_HZ = 16_000
        // Int, not Long: RecognizerIntent reads these extras with getInt, and a Long silently
        // falls back to 0 (Relay ships them as Long and has been losing them all along).
        private const val SPEECH_INPUT_MINIMUM_LENGTH_MS = 2_500
        private const val FINAL_RESULT_TIMEOUT_MS = 2_500L
        private const val TRANSIENT_RETRY_DELAY_MS = 250L
        private const val MAX_TRANSIENT_RETRIES = 1
        private const val DEFAULT_TARGET_ID = "default"
        private const val GOOGLE_PACKAGE_PREFIX = "com.google.android"
        private const val NO_EXTERNAL_AUDIO_SUPPORT_DETAIL =
            "Your phone's speech service does not accept external audio - choose a cloud engine " +
                "(OpenAI, ElevenLabs or Azure) in Speech settings"
    }
}

internal fun androidRecognizerError(
    errorCode: Int,
    inputWasClosed: Boolean = false,
): SttError {
    val kind = when (errorCode) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SttErrorKind.TIMEOUT
        SpeechRecognizer.ERROR_NETWORK -> SttErrorKind.NETWORK
        SpeechRecognizer.ERROR_AUDIO,
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS,
        -> SttErrorKind.SOURCE_UNAVAILABLE
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
        SpeechRecognizer.ERROR_NO_MATCH,
        -> SttErrorKind.NO_SPEECH
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS,
        -> SttErrorKind.QUOTA_RATE
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE,
        -> SttErrorKind.UNSUPPORTED_LANGUAGE
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED,
        -> SttErrorKind.PROVIDER
        SpeechRecognizer.ERROR_CLIENT -> SttErrorKind.INTERNAL
        else -> SttErrorKind.PROVIDER
    }
    val detail = when (kind) {
        SttErrorKind.SOURCE_UNAVAILABLE ->
            if (errorCode == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                "Microphone permission is not granted"
            } else {
                "Injected glasses audio source failed"
            }
        SttErrorKind.NO_SPEECH ->
            if (inputWasClosed) {
                "Android speech recognizer returned no text"
            } else {
                "Android speech recognizer did not detect speech"
            }
        SttErrorKind.NETWORK -> "Android speech recognition network request failed"
        SttErrorKind.TIMEOUT -> "Android speech recognition timed out"
        SttErrorKind.QUOTA_RATE -> "Android speech recognizer is busy or rate limited"
        SttErrorKind.UNSUPPORTED_LANGUAGE -> "Android speech language is unavailable"
        SttErrorKind.INTERNAL -> "Android speech recognizer client failed"
        else -> "Android speech recognizer failed (code $errorCode)"
    }
    return SttError(kind, "Android", detail)
}

private fun Int.isLanguageSupportError(): Boolean =
    this == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
        this == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

private fun Int.allowsPartialFallback(inputWasClosed: Boolean): Boolean =
    inputWasClosed ||
        this == SpeechRecognizer.ERROR_NO_MATCH ||
        this == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
        this == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
        this == SpeechRecognizer.ERROR_SERVER ||
        this == SpeechRecognizer.ERROR_NETWORK ||
        this == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
        this == SpeechRecognizer.ERROR_CLIENT

private fun Bundle?.bestRecognizerText(): String {
    val values = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
    return values.firstOrNull { it.isNotBlank() }?.trim().orEmpty()
}

private fun bestCompleteTranscript(finalText: String, partialText: String): String {
    val finalClean = finalText.trim()
    val partialClean = partialText.trim()
    if (finalClean.isBlank()) return partialClean
    if (partialClean.isBlank()) return finalClean
    val finalWords = normalizedWords(finalClean)
    val partialWords = normalizedWords(partialClean)
    return if (
        partialWords.size > finalWords.size &&
        containsTokenSequence(partialWords, finalWords)
    ) {
        partialClean
    } else {
        mergeTranscriptWindow(partialClean, finalClean)
    }
}

private fun mergeTranscriptWindow(current: String, incoming: String): String {
    val base = current.trim()
    val next = incoming.trim()
    if (base.isBlank()) return next
    if (next.isBlank()) return base

    val baseWords = normalizedWords(base)
    val nextWords = normalizedWords(next)
    if (baseWords.isEmpty() || nextWords.isEmpty()) return "$base $next".trim()
    if (baseWords == nextWords) return if (next.length > base.length) next else base
    if (containsTokenSequence(baseWords, nextWords)) return base
    if (containsTokenSequence(nextWords, baseWords)) return next

    val overlap = longestSuffixPrefixOverlap(baseWords, nextWords)
    val incomingWords = next.split(Regex("\\s+")).filter { it.isNotBlank() }
    return if (overlap > 0 && overlap < incomingWords.size) {
        "$base ${incomingWords.drop(overlap).joinToString(" ")}".trim()
    } else {
        "$base $next".trim()
    }
}

private fun normalizedWords(text: String): List<String> =
    text.split(Regex("\\s+"))
        .map { word ->
            word.lowercase(Locale.ROOT)
                .trim { char -> !char.isLetterOrDigit() }
        }
        .filter { it.isNotBlank() }

private fun containsTokenSequence(haystack: List<String>, needle: List<String>): Boolean {
    if (needle.isEmpty()) return true
    if (needle.size > haystack.size) return false
    for (start in 0..(haystack.size - needle.size)) {
        if (haystack.subList(start, start + needle.size) == needle) return true
    }
    return false
}

private fun longestSuffixPrefixOverlap(left: List<String>, right: List<String>): Int {
    val max = minOf(left.size, right.size)
    for (size in max downTo 1) {
        if (left.takeLast(size) == right.take(size)) return size
    }
    return 0
}

private class PlatformAndroidRecognizerEnvironment(
    private val context: Context,
) : AndroidRecognizerEnvironment {
    override val sdkInt: Int
        get() = Build.VERSION.SDK_INT

    override fun hasRecordAudioPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    override fun isRecognitionAvailable(): Boolean =
        runCatching { SpeechRecognizer.isRecognitionAvailable(context) }.getOrDefault(false)

    override fun recognitionTargets(): List<AndroidRecognizerTarget> {
        val defaultComponent = defaultRecognitionServiceComponent(context)
        val targets = mutableListOf<AndroidRecognizerTarget>()
        val ids = mutableSetOf<String>()

        fun add(target: AndroidRecognizerTarget) {
            if (ids.add(target.id)) targets += target
        }

        add(
            AndroidRecognizerTarget(
                id = DEFAULT_TARGET_ID,
                reportName =
                    "default:${defaultComponent?.flattenToShortString() ?: DEFAULT_COMPONENT_LABEL}",
                segmentedSession = false,
                stopListeningOnInputClose = true,
                create = {
                    if (defaultComponent?.packageName?.startsWith(GOOGLE_PACKAGE_PREFIX) != true) {
                        Log.w(TAG, "System-default recognizer may ignore injected audio")
                    }
                    PlatformAndroidSpeechRecognizer(
                        SpeechRecognizer.createSpeechRecognizer(context),
                    )
                },
            ),
        )

        googleRecognitionComponents(context)
            .filter { component -> component != defaultComponent }
            .forEach { component ->
                add(
                    AndroidRecognizerTarget(
                        id = "component:${component.flattenToShortString()}",
                        reportName = "google:${component.flattenToShortString()}",
                        create = {
                            PlatformAndroidSpeechRecognizer(
                                SpeechRecognizer.createSpeechRecognizer(context, component),
                            )
                        },
                    ),
                )
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            runCatching { SpeechRecognizer.isOnDeviceRecognitionAvailable(context) }
                .getOrDefault(false)
        ) {
            add(
                AndroidRecognizerTarget(
                    id = ON_DEVICE_TARGET_ID,
                    reportName = "on-device:$ON_DEVICE_COMPONENT_LABEL",
                    create = {
                        PlatformAndroidSpeechRecognizer(
                            SpeechRecognizer.createOnDeviceSpeechRecognizer(context),
                        )
                    },
                ),
            )
        }
        return targets
    }

    private fun googleRecognitionComponents(context: Context): List<ComponentName> {
        val services = runCatching {
            context.packageManager.queryIntentServices(
                Intent(RecognitionService.SERVICE_INTERFACE),
                0,
            )
        }.getOrDefault(emptyList())
        return services.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            if (!service.packageName.startsWith(GOOGLE_PACKAGE_PREFIX)) return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }
    }

    private fun defaultRecognitionServiceComponent(context: Context): ComponentName? =
        runCatching {
            Settings.Secure.getString(
                context.contentResolver,
                VOICE_RECOGNITION_SERVICE_SETTING,
            )?.let(ComponentName::unflattenFromString)
        }.getOrNull()

    companion object {
        private const val TAG = "NexusAndroidStt"
        private const val GOOGLE_PACKAGE_PREFIX = "com.google.android"
        private const val VOICE_RECOGNITION_SERVICE_SETTING = "voice_recognition_service"
        private const val DEFAULT_TARGET_ID = "default"
        private const val ON_DEVICE_TARGET_ID = "on-device"
        private const val ON_DEVICE_COMPONENT_LABEL =
            "SpeechRecognizer.createOnDeviceSpeechRecognizer"
        private const val DEFAULT_COMPONENT_LABEL =
            "SpeechRecognizer.createSpeechRecognizer(default)"
    }
}

private class PlatformAndroidSpeechRecognizer(
    private val delegate: SpeechRecognizer,
) : AndroidSpeechRecognizer {
    override fun setRecognitionListener(listener: RecognitionListener) {
        delegate.setRecognitionListener(listener)
    }

    override fun startListening(intent: Intent) = delegate.startListening(intent)
    override fun stopListening() = delegate.stopListening()
    override fun cancel() = delegate.cancel()
    override fun destroy() = delegate.destroy()
}

private class ParcelDescriptorAudioPipe(
    override val readEnd: ParcelFileDescriptor,
    private var writeEnd: ParcelFileDescriptor?,
    private var output: FileOutputStream?,
) : AndroidAudioPipe {
    override fun write(data: ByteArray, offset: Int, length: Int) {
        val stream = output ?: throw IOException("Audio pipe is closed")
        stream.write(data, offset, length)
    }

    override fun closeWrite() {
        val stream = output
        val descriptor = writeEnd
        output = null
        writeEnd = null
        stream?.close()
        runCatching { descriptor?.close() }
    }

    override fun close() {
        runCatching { closeWrite() }
        runCatching { readEnd.close() }
    }

    companion object {
        fun create(): ParcelDescriptorAudioPipe {
            val pipe = ParcelFileDescriptor.createPipe()
            return ParcelDescriptorAudioPipe(
                readEnd = pipe[0],
                writeEnd = pipe[1],
                output = FileOutputStream(pipe[1].fileDescriptor),
            )
        }
    }
}

private class HandlerAndroidMainThread : AndroidMainThread {
    private val handler = Handler(Looper.getMainLooper())

    override fun <T> call(task: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return task()
        val future = FutureTask(task)
        check(handler.post(future)) { "Android main thread is unavailable" }
        return future.get()
    }

    override fun schedule(delayMs: Long, task: () -> Unit): AndroidScheduledTask {
        val runnable = Runnable(task)
        check(handler.postDelayed(runnable, delayMs)) { "Android main thread is unavailable" }
        return AndroidScheduledTask { handler.removeCallbacks(runnable) }
    }
}

private object HubAndroidSttForegroundController : AndroidSttForegroundController {
    override fun acquire(): SttError? =
        BusHubService.requestSpeechMicrophoneForeground()

    override fun release() {
        BusHubService.releaseSpeechMicrophoneForeground()
    }
}

private fun Throwable.safeType(): String =
    this::class.java.simpleName.ifBlank { "ForegroundServiceException" }
