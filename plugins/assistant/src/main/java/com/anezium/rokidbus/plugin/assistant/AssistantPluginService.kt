package com.anezium.rokidbus.plugin.assistant

import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusAudioCallbacks
import com.anezium.rokidbus.client.plugin.NexusAudioFormat
import com.anezium.rokidbus.client.plugin.NexusAudioSession
import com.anezium.rokidbus.client.plugin.NexusAudioStopReason
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusInkCloseReason
import com.anezium.rokidbus.client.plugin.NexusInkProblem
import com.anezium.rokidbus.client.plugin.NexusInkSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotCallbacks
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.client.plugin.NexusSnapshotSession
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.client.plugin.ttsSession
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.coroutines.resume

class AssistantPluginService : NexusPluginService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val authStore by lazy { CodexAuthStore(applicationContext) }
    private val accountContextSync by lazy { AccountContextSync(applicationContext) }
    private val threadStore by lazy { AssistantThreadStore(applicationContext) }
    private val noteStore by lazy { AssistantNoteStore(applicationContext) }
    private val reminderStore by lazy { AssistantReminderStore(applicationContext) }
    private val reminderScheduler by lazy { androidReminderScheduler(applicationContext) }
    private val calendarGateway by lazy { AndroidCalendarGateway(applicationContext) }
    private val hermesCapabilitiesClient by lazy { HermesCapabilitiesClient() }
    private val conversationThreading by lazy { AssistantConversationThreading(threadStore) }
    private val inkPageToolCapabilities by lazy { createInkPageToolCapabilities() }
    private val inkPageToolRuntime by lazy {
        InkPageToolRuntime(inkPageToolCapabilities, visualAnswers = authStore::visualAnswers)
    }
    private val inkTemplateLoader by lazy {
        InkTemplateLoader { template ->
            assets.open("$INK_TEMPLATE_ASSET_DIR/${template.wireValue}.ink")
                .bufferedReader()
                .use { it.readText() }
        }
    }
    private val assistantToolRegistry by lazy {
        AssistantToolRegistry(
            definitions = listOf(
                TakePhotoTool(createTakePhotoToolCapabilities()),
                RenderTemplateTool(inkPageToolRuntime, inkTemplateLoader),
                RenderInkPageTool(inkPageToolRuntime),
            ) +
                assistantProductivityTools(
                    noteStore = noteStore,
                    reminderStore = reminderStore,
                    reminderScheduler = reminderScheduler,
                ) + assistantCalendarTools(calendarGateway),
            sessionContext = ::assistantToolSessionContext,
            progressReporter = { label -> uiController.showTransient(label) },
        )
    }
    private val openAiCompatClients by lazy {
        ProviderCatalog.presets.associate { preset ->
            preset.id to OpenAiCompatApiClient(
                preset = preset,
                apiKeyProvider = { authStore.providerApiKey(preset.id) },
                baseUrlProvider = { authStore.providerBaseUrl(preset.id) },
                effortProvider = { authStore.providerEffort(preset.id) },
                backendProvider = { authStore.providerBackend(preset.id) },
            )
        }
    }
    private val openAiCompatProviders by lazy {
        ProviderCatalog.presets.map { preset ->
            OpenAiCompatProvider(
                preset = preset,
                apiClient = checkNotNull(openAiCompatClients[preset.id]),
                apiKeyConfigured = { !authStore.providerApiKey(preset.id).isNullOrBlank() },
                toolRegistry = assistantToolRegistry,
                modelProvider = { authStore.providerModel(preset.id) },
                supportsVision = { providerSupportsPhotos(preset.id) },
                backendProvider = { authStore.providerBackend(preset.id) },
            )
        }
    }
    private val chatGptCodexClient by lazy {
        ChatGptCodexApiClient(
            tokenProvider = authStore::oauthTokens,
            refreshTokens = {
                CodexChatGptOAuth.refreshStoredTokens(applicationContext)
            },
        )
    }
    private val chatGptCodexProvider by lazy {
        ChatGptCodexProvider(
            apiClient = chatGptCodexClient,
            oauthConfigured = { authStore.oauthTokens() != null },
            toolRegistry = assistantToolRegistry,
            modelProvider = authStore::chatGptModel,
            reasoningEffortProvider = authStore::chatGptReasoningEffort,
        )
    }
    private val providerRouter by lazy {
        ProviderRouter(
            providers = listOf(chatGptCodexProvider) + openAiCompatProviders,
            defaultProviderId = ProviderCatalog.openAi.id,
        )
    }
    private val transcriber by lazy { OpenAiTranscriber(authStore::apiKey) }

    private var surface: NexusSurfaceSession? = null
    private var pendingNoteEntry = false
    private var inkSurface: NexusInkSurfaceSession? = null
    private var pendingInkShow: PendingInkShow? = null
    private var inkSurfaceActive = false
    private var inkShownRequestId: String? = null
    private var speechSession: NexusSpeechSession? = null
    private var audioSession: NexusAudioSession? = null
    private var snapshotSession: NexusSnapshotSession? = null
    private var ttsSession: NexusTtsSession? = null
    private var activeTtsUtteranceId: String? = null
    private var captureGeneration = 0L
    private var customBackendProbeAttempted = false
    private var captureActive = false
    private var fallbackTranscribePending = false

    /** The recorded fallback audio is at the transcription service, not yet a question. */
    private var fallbackTranscriptionInFlight = false
    private var fallbackStopJob: Job? = null
    private var audioFormat: NexusAudioFormat? = null
    private var pcmBuffer = ByteArrayOutputStream()
    private var pipelineJob: Job? = null
    private var currentRequestId: String? = null
    private var currentAssistantGeneration: Long? = null
    private var photoCapturedRequestId: String? = null
    private var photoJpegForCompletedTurn: ByteArray? = null
    private var currentLinkState = 0
    private val captureTriggerGate = AssistantCaptureTriggerGate()
    private val optionsMenu = AssistantOptionsMenu()
    private val answerSpeaker = AssistantAnswerSpeaker(
        // A bound reference here would read authStore while the service is still
        // being constructed, before it has a base context to build one from.
        enabled = { authStore.speakAnswers() },
        speak = ::speakAnswer,
    )
    private val ttsCallbacks = object : NexusTtsCallbacks {
        // The band outlives the voice, never the other way round: only this utterance may hold it,
        // so a stale one finishing cannot extend the answer that replaced it.
        override fun onTtsStarted(utteranceId: String) {
            if (utteranceId != activeTtsUtteranceId) return
            uiController.onAnswerSpeechStarted()
        }

        override fun onTtsDone(utteranceId: String, reason: NexusTtsDoneReason) {
            if (utteranceId != activeTtsUtteranceId) return
            activeTtsUtteranceId = null
            uiController.onAnswerSpeechFinished()
        }
    }
    private val uiController = AssistantUiController(
        scope = serviceScope,
        renderer = object : AssistantUiRenderer {
            override val supportsNoticeSurface: Boolean
                get() = nexusClient?.supportsNoticeSurface == true

            override fun showNotice(notice: NexusNotice): NexusSdkResult =
                nexusClient?.showNotice(notice) ?: NexusSdkResult.NOT_REGISTERED

            override fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult =
                nexusClient?.updateNotice(update) ?: NexusSdkResult.NOT_REGISTERED

            override fun hideNotice(): NexusSdkResult =
                nexusClient?.hideNotice() ?: NexusSdkResult.NOT_REGISTERED

            override fun showCard(
                lines: List<String>,
                forceShow: Boolean,
                footer: String?,
                contentKey: String?,
            ): NexusSdkResult = renderCard(lines, forceShow, footer, contentKey)

            override fun showRichCard(
                subtitle: String?,
                lines: List<NexusCardLine>,
                footer: String?,
                forceShow: Boolean,
                contentKey: String?,
            ): NexusSdkResult = renderRichCard(subtitle, lines, footer, forceShow, contentKey)

            override val supportsQuestionField: Boolean
                get() = nexusClient?.supportsEditableSurface == true

            override val chosenInputMode: AssistantInputMode
                get() = authStore.inputMode()

            override fun showQuestionField(
                field: EditableSurfaceField,
                footer: String,
                onRejected: (code: String) -> Unit,
            ): NexusSdkResult = renderQuestionField(field, footer, onRejected)

            override fun hideCard(): NexusSdkResult =
                surface?.hide() ?: NexusSdkResult.NOT_REGISTERED
        },
        cancelPipeline = ::cancelPipeline,
        resetCapture = ::resetCapture,
        sessionBusy = { captureActive || pipelineJob?.isActive == true || snapshotSession != null },
        answerSpeaking = { activeTtsUtteranceId != null },
        stopSpeech = { stopAnswerSpeech() },
    )

    override fun onCreate() {
        super.onCreate()
        debugInstance = this
        scheduleAccountContextSyncIfStale()
    }

    override fun onNexusOpen() = onNexusOpen(PluginOpenTypes.OPEN)

    /**
     * A launcher pick listens at once: the wearer came here to ask, not to be told to press a
     * button — which, with the takeover paused, would open Rokid's assistant anyway. The assist
     * button path keeps its hint: its follow-up message carries the gesture and starts capture.
     */
    override fun onNexusOpen(openType: String) {
        surface = nexusSurfaceSession(SURFACE_ID)
        inkSurface = nexusInkSurfaceSession(INK_SURFACE_ID)
        optionsMenu.close()
        val anchored = openType == PluginOpenTypes.OPEN && uiController.onLauncherOpen()
        if (!anchored) uiController.onOpen()
        // A re-delivered open can land mid-question. The mic keeps what the wearer is saying —
        // stopping it would drop the utterance for nothing — so the band it had comes back,
        // instead of the reset leaving a stale one to expire over work still under way.
        uiController.resumeInFlight(
            capturing = captureActive,
            transcribing = fallbackTranscribePending || fallbackTranscriptionInFlight,
        )
        scheduleAccountContextSyncIfStale()
        if (anchored) startLauncherCapture()
    }

    override fun onNexusClose() {
        optionsMenu.close()
        uiController.onClose()
        captureTriggerGate.resetSession()
        resetCapture()
        cancelPipeline()
        clearInkSurface(hide = true)
        closeAnswerSpeechSession()
        inkSurface = null
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> when {
                // Only reaches here when no band of ours is up to take Back first.
                uiController.isTyping -> uiController.endTyping(AssistantTypingEnd.CANCELLED)
                optionsMenu.isOpen -> closeOptionsMenu()
                else -> closeSurface()
            }
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            -> if (!optionsMenu.isOpen && uiController.isAnchored) openOptionsMenu()
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            -> when {
                optionsMenu.isOpen -> confirmOptionsMenu()
                uiController.isAnchored && !captureActive && pipelineJob?.isActive != true ->
                    startLauncherCapture()
            }
        }
    }

    private fun closeSurface() {
        cancelPipeline()
        resetCapture()
        pendingNoteEntry = false
        surface?.hide()
        uiController.onSurfaceHidden()
    }

    /** A launcher pick, or a tap on the anchor card: one capture, ended by the voice detector. */
    private fun startLauncherCapture() {
        if (!captureTriggerGate.claimGestureOpen(UUID.randomUUID().toString())) return
        startCaptureOnce()
        captureTriggerGate.onButtonStop()
    }

    private fun openOptionsMenu() {
        cancelPipeline()
        resetCapture()
        val granted = nexusClient?.hasCapability(PluginCapability.ASSISTANT) == true
        val ask = optionsMenu.open(assistantGranted = granted)
        renderOptionsMenu()
        if (!ask) return
        val result = requestNexusAssistantTakeover()
        if (result != NexusSdkResult.SENT) {
            optionsMenu.onError(result.name)
            renderOptionsMenu()
        }
    }

    private fun confirmOptionsMenu() {
        val action = optionsMenu.onConfirm() as? AssistantOptionsMenu.Action.Set ?: return
        renderOptionsMenu()
        val result = setNexusAssistantTakeover(action.takeover)
        if (result != NexusSdkResult.SENT) {
            optionsMenu.onError(result.name)
            renderOptionsMenu()
        }
    }

    private fun closeOptionsMenu() {
        optionsMenu.close()
        uiController.restoreAnchor()
    }

    private fun renderOptionsMenu() {
        val view = optionsMenu.view() ?: return
        uiController.showOptions(view, forceShow = false)
    }

    override fun onNexusAssistantTakeover(enabled: Boolean) {
        if (!optionsMenu.isOpen) return
        optionsMenu.onStatus(enabled)
        renderOptionsMenu()
    }

    override fun onNexusAssistantTakeoverError(code: String) {
        if (!optionsMenu.isOpen) return
        optionsMenu.onError(code)
        renderOptionsMenu()
    }

    override fun onNexusLinkState(state: Int) {
        currentLinkState = state
    }

    /**
     * The wearer typed a note directly — no model call, no STT, works even
     * without an AI provider configured. Triggered from [requestNewNote];
     * [pendingNoteEntry] disambiguates this from the typed question, which
     * uses the same [SURFACE_ID] card.
     */
    override fun onNexusSurfaceTextCommitted(surfaceId: String, text: String, cancelled: Boolean) {
        if (uiController.isTyping) {
            // The wire id round-trips as "pluginId:localSurfaceId".
            if (surfaceId.substringAfter(':', surfaceId) != SURFACE_ID) return
            onQuestionTyped(text, cancelled)
            return
        }
        if (!pendingNoteEntry) return
        pendingNoteEntry = false
        surface?.hide()
        uiController.onSurfaceHidden()
        if (cancelled) return
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val result = runCatching { noteStore.save(text = trimmed) }.getOrNull()
        // Never log `result` itself: AssistantNoteSaveResult.Saved's generated toString()
        // serializes the wearer's private note title and full text into logcat.
        val status = when (result) {
            is AssistantNoteSaveResult.Saved -> if (result.textTruncated) "saved_truncated" else "saved"
            AssistantNoteSaveResult.Full -> "rejected_full"
            null -> "failed"
        }
        Log.i(TAG, "typed note save status=$status")
    }

    override fun onNexusNoticeClosed(reason: NexusNoticeCloseReason) {
        if (reason == NexusNoticeCloseReason.USER) stopAnswerSpeech()
        uiController.onNoticeClosed(reason)
    }

    /**
     * The Type chip the listening band offers once the wearer is past the tap that started it.
     * Ignored unless the band is still listening: a spoken question that finished a moment
     * before the tap landed has already gone to the model and wins.
     */
    override fun onNexusNoticeAction(id: String) {
        if (id != AssistantUiController.ACTION_TYPE) return
        if (!uiController.offersTyping || !captureActive) return
        // The generation bump inside drops a final transcript still in flight.
        resetCapture()
        uiController.beginTyping()
    }

    /** Lands where a final transcript does: the typed question takes the same pipeline. */
    private fun onQuestionTyped(text: String, cancelled: Boolean) {
        if (cancelled) {
            uiController.endTyping(AssistantTypingEnd.CANCELLED)
            return
        }
        val question = normalizeTranscript(text)
        // Enter on an empty field — the Type tap's own bounce landing in the field it just
        // opened, or a stray temple tap — sends nothing, and the field stays open for typing.
        if (question.isEmpty()) return
        uiController.endTyping(AssistantTypingEnd.SUBMITTED)
        launchAssistantPipeline(question)
    }

    override fun onNexusInkReady(surfaceId: String) {
        if (surfaceId != INK_SURFACE_ID) return
        val pending = pendingInkShow
        if (pending == null || !isRenderInkSessionActive(pending.session)) {
            clearInkSurface(hide = true)
            return
        }
        pendingInkShow = null
        if (pending.continuation.isActive) {
            pending.continuation.resume(InkPageShowResult.Shown)
        }
    }

    override fun onNexusInkAction(surfaceId: String, actionId: String, dataset: JSONObject) {
        if (surfaceId != INK_SURFACE_ID) return
        Log.i(TAG, "ink action id=$actionId datasetFields=${dataset.length()}")
    }

    override fun onNexusInkClosed(surfaceId: String, reason: NexusInkCloseReason) {
        if (surfaceId != INK_SURFACE_ID) return
        Log.i(TAG, "ink closed reason=$reason")
        clearInkSurface(hide = false)
    }

    override fun onNexusInkError(surfaceId: String, problems: List<NexusInkProblem>) {
        if (surfaceId != INK_SURFACE_ID) return
        Log.w(TAG, "ink rejected codes=${problems.joinToString { it.code }}")
        inkSurfaceActive = false
        inkShownRequestId = null
        uiController.onSurfaceHidden()
        val pending = pendingInkShow
        pendingInkShow = null
        if (pending?.continuation?.isActive == true) {
            pending.continuation.resume(InkPageShowResult.Rejected(problems))
        }
    }

    override fun onNexusGlassesAiButton(active: Boolean) {
        if (!isNexusSessionOpen) return
        if (active) {
            if (captureTriggerGate.claimButtonStart()) startCaptureOnce()
        } else {
            captureTriggerGate.onButtonStop()
        }
    }

    override fun onNexusMessage(path: String, id: String, payload: JSONObject) {
        if (path == BusPaths.ERROR) {
            val code = payload.optString("code")
            if (code == NoticeSurfaceContract.ERROR_NOTICE_RATE_LIMITED) {
                Log.w(TAG, "notice message rejected code=$code")
                uiController.onNoticeRejected()
            }
            return
        }
        if (path != AI_ASSIST_OPEN_PATH ||
            payload.optString("type") != AI_ASSIST_OPEN_TYPE ||
            !isNexusSessionOpen
        ) {
            return
        }
        val gestureId = payload.optString("gestureId")
        if (!captureTriggerGate.claimGestureOpen(gestureId)) return
        uiController.cancelLauncherHint()
        startCaptureOnce()
        if (!payload.optBoolean("buttonActive", true)) {
            captureTriggerGate.onButtonStop()
        }
    }

    override fun onDestroy() {
        if (debugInstance === this) debugInstance = null
        uiController.onClose()
        resetCapture()
        cancelPipeline()
        clearInkSurface(hide = true)
        closeAnswerSpeechSession()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startCaptureOnce() {
        stopAnswerSpeech()
        when (askAction(captureActive, uiController.inputMode)) {
            AssistantAskAction.KEEP_LISTENING -> return
            // Type first was chosen while this capture listened: this ask means type, now.
            AssistantAskAction.STOP_AND_TYPE -> resetCapture()
            AssistantAskAction.START -> Unit
        }
        beginCapture()
    }

    private fun beginCapture() {
        // The assist button can land while the options menu or a typed question is up; the
        // spoken question wins.
        if (optionsMenu.isOpen) closeOptionsMenu()
        uiController.endTyping(AssistantTypingEnd.SUPERSEDED)
        uiController.beginGestureFlow()
        clearInkSurface(hide = true)
        if (captureActive) return
        if (!authStore.hasUsableAuth()) {
            resetCapture()
            uiController.showError(
                body = "Connect your ChatGPT account in settings",
                legacyForceShow = true,
            )
            return
        }

        cancelPipeline()
        // Type first: the question opens as a field and the microphone is never asked for.
        if (uiController.startQuestion()) return
        captureGeneration += 1
        val generation = captureGeneration
        captureActive = true
        fallbackTranscribePending = false
        fallbackStopJob?.cancel()
        fallbackStopJob = null
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
        uiController.showListening(legacyForceShow = true)
        startSpeechCapture(generation)
    }

    private fun startSpeechCapture(generation: Long) {
        var createdSpeech: NexusSpeechSession? = null
        var finalDelivered = false
        val callbacks = object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                if (generation != captureGeneration || !captureActive) {
                    createdSpeech?.stop()
                    return
                }
                uiController.showListening()
            }

            override fun onSpeechState(state: NexusSpeechState) = Unit

            override fun onSpeechPartial(text: String) {
                if (generation != captureGeneration || !captureActive || finalDelivered) return
                uiController.showTranscript(text)
            }

            override fun onSpeechFinal(text: String) {
                if (generation != captureGeneration || !captureActive || finalDelivered) return
                val transcript = normalizeTranscript(text)
                if (transcript.isEmpty()) return
                finalDelivered = true
                launchAssistantPipeline(transcript)
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                if (speechSession === createdSpeech) speechSession = null
                if (generation != captureGeneration) return
                captureActive = false
                if (finalDelivered) return
                if (shouldUseRawCaptureFallback(reason)) {
                    startFallbackCapture(generation)
                    return
                }
                handleSpeechFailure(reason, error)
            }
        }
        val session = nexusSpeechSession(callbacks)
        createdSpeech = session
        speechSession = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            if (speechSession === session) speechSession = null
            if (generation != captureGeneration) return
            captureActive = false
            if (shouldUseRawCaptureFallback(result)) {
                startFallbackCapture(generation)
            } else {
                Log.w(TAG, "Hub speech start rejected: $result")
                uiController.showError("Speech unavailable. Try again.")
            }
        }
    }

    private fun startFallbackCapture(generation: Long) {
        if (generation != captureGeneration || captureActive) return
        captureActive = true
        fallbackTranscribePending = false
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
        uiController.showListening()

        var createdAudio: NexusAudioSession? = null
        val callbacks = object : NexusAudioCallbacks {
            override fun onAudioStarted(format: NexusAudioFormat) {
                if (generation != captureGeneration || !captureActive) {
                    createdAudio?.stop()
                    return
                }
                audioFormat = format
                fallbackStopJob?.cancel()
                fallbackStopJob = serviceScope.launch {
                    delay(FALLBACK_CAPTURE_DURATION_MS)
                    if (generation != captureGeneration ||
                        !captureActive ||
                        audioSession !== createdAudio
                    ) {
                        return@launch
                    }
                    fallbackTranscribePending = true
                    uiController.showTransient(AssistantUiController.TRANSCRIBING_BODY)
                    createdAudio?.stop()
                }
            }

            override fun onAudioFrame(
                pcm: ByteArray,
                seq: Long,
                elapsedRealtimeMs: Long,
            ) {
                if (generation != captureGeneration ||
                    !captureActive ||
                    fallbackTranscribePending
                ) {
                    return
                }
                pcmBuffer.write(pcm)
            }

            override fun onAudioStopped(reason: NexusAudioStopReason) {
                if (audioSession === createdAudio) audioSession = null
                if (generation != captureGeneration) return
                fallbackStopJob?.cancel()
                fallbackStopJob = null
                val shouldTranscribe =
                    fallbackTranscribePending && reason == NexusAudioStopReason.RELEASED
                fallbackTranscribePending = false
                captureActive = false
                if (shouldTranscribe) {
                    launchFallbackAssistantPipeline(
                        pcm = pcmBuffer.toByteArray(),
                        format = audioFormat,
                    )
                } else if (reason != NexusAudioStopReason.RELEASED) {
                    Log.w(TAG, "Fallback microphone stopped: $reason")
                    uiController.showError("Speech unavailable. Try again.")
                }
            }
        }
        val session = nexusAudioSession(callbacks)
        createdAudio = session
        audioSession = session
        val result = session?.start() ?: NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        if (result != NexusSdkResult.SENT) {
            if (audioSession === session) audioSession = null
            if (generation != captureGeneration) return
            captureActive = false
            Log.w(TAG, "Fallback microphone start rejected: $result")
            uiController.showError(
                body = if (result == NexusSdkResult.CAPABILITY_NOT_GRANTED) {
                    "Grant Speech to text in Nexus settings."
                } else {
                    "Speech unavailable. Try again."
                },
            )
        }
    }

    private fun launchFallbackAssistantPipeline(
        pcm: ByteArray,
        format: NexusAudioFormat?,
    ) {
        if (pcm.isEmpty() || format == null) {
            uiController.showError("Didn't catch that")
            return
        }
        launchPipeline {
            fallbackTranscriptionInFlight = true
            val transcript = try {
                transcriber.transcribe(pcm, format).trim()
            } finally {
                fallbackTranscriptionInFlight = false
            }
            if (transcript.isEmpty()) {
                uiController.showError("Didn't catch that")
                return@launchPipeline
            }
            streamAssistantAnswer(transcript)
        }
    }

    private fun launchAssistantPipeline(transcript: String) {
        val normalized = normalizeTranscript(transcript)
        if (normalized.isEmpty()) {
            uiController.showError("Didn't catch that")
            return
        }
        launchPipeline {
            streamAssistantAnswer(normalized)
        }
    }

    private fun launchPipeline(block: suspend () -> Unit) {
        pipelineJob?.cancel()
        val launched = serviceScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Assistant pipeline failed: ${error.javaClass.simpleName}")
                showError(error.conciseProviderMessage("Request failed. Try again."))
            } finally {
                if (pipelineJob === currentCoroutineContext()[Job]) {
                    pipelineJob = null
                }
                uiController.onPipelineFinished()
            }
        }
        pipelineJob = launched
    }

    private fun scheduleAccountContextSyncIfStale() {
        serviceScope.launch {
            try {
                when (val result = accountContextSync.syncIfStale()) {
                    is SyncResult.Success -> Log.i(
                        TAG,
                        "Account context sync succeeded: memories=${result.memoryCount}, " +
                            "chars=${result.chars}",
                    )
                    is SyncResult.Unavailable -> Log.w(
                        TAG,
                        "Account context sync unavailable: ${result.reason.name}",
                    )
                    SyncResult.Disabled -> Log.i(TAG, "Account context sync disabled")
                    SyncResult.NotSignedIn -> Log.i(TAG, "Account context sync not signed in")
                    null -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                Log.w(TAG, "Account context sync failed: ${error.javaClass.simpleName}")
            }
        }
    }

    private suspend fun streamAssistantAnswer(transcript: String) {
        val noticeBandMode = uiController.isNoticeBandMode
        val providerId = selectedProviderId()
        uiController.showTransient("Thinking…")
        ensureProviderBackendDetected(providerId)
        val keepConversation = authStore.keepConversation()
        val keepPhotosInConversations = authStore.keepPhotosInConversations()
        val conversationContext = withContext(Dispatchers.IO) {
            if (!keepPhotosInConversations && threadStore.hasStoredPhotos()) {
                threadStore.deleteAllPhotos()
            }
            conversationThreading.prepare(
                keepConversation = keepConversation,
                idleWindowMinutes = authStore.conversationIdleWindowMinutes(),
            )
        }
        // Hermes consumes structured calls server-side, so Nexus advertises its phone tools in text.
        val hermesTextToolBackend = providerId != ChatGptCodexProvider.ID &&
            authStore.providerBackend(providerId) == ProviderBackend.HERMES
        val availableToolDefinitions = assistantToolRegistry
            .availableDefinitions(assistantProviderFeatures(providerId))
        val promptToolDefinitions = if (hermesTextToolBackend) {
            availableToolDefinitions.filter { definition ->
                definition.name in HERMES_TEXT_TOOL_NAMES
            }
        } else {
            availableToolDefinitions
        }
        val request = ChatRequest(
            userText = transcript,
            systemPrompt = NexusAgentPolicy.buildSystemPrompt(
                customPrompt = authStore.customSystemPrompt(),
                noticeBand = noticeBandMode,
                memory = authStore.combinedAssistantContextForPrompt(),
                currentDateTime = ZonedDateTime.now(),
                availableToolNames = promptToolDefinitions.map(AssistantToolDefinition::name),
                textToolDefinitions = promptToolDefinitions,
                allowTextToolFallback = hermesTextToolBackend,
            ),
            history = conversationContext.history,
            model = when (providerId) {
                ChatGptCodexProvider.ID -> authStore.chatGptModel()
                else -> authStore.providerModel(providerId)
            },
            conversationId = conversationContext.threadId,
        )
        currentRequestId = request.requestId
        currentAssistantGeneration = captureGeneration
        photoCapturedRequestId = null
        photoJpegForCompletedTurn = null
        val answer = StringBuilder()
        var lastHudUpdateMs = 0L
        var completed = false
        var failed = false
        var finalAnswer: String? = null
        try {
            providerRouter.providerFor(providerId).streamEvents(request).collect { event ->
                when (event) {
                    is AiProviderEvent.Started -> Unit
                    is AiProviderEvent.Progress -> uiController.showTransient(event.message)
                    is AiProviderEvent.TextReset -> {
                        answer.clear()
                        lastHudUpdateMs = 0L
                    }
                    is AiProviderEvent.TextDelta -> {
                        answer.append(event.delta)
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastHudUpdateMs >= HUD_UPDATE_INTERVAL_MS) {
                            showAnswer(answer.toString())
                            lastHudUpdateMs = now
                        }
                    }
                    is AiProviderEvent.MessageDone -> {
                        completed = true
                        val finalText = event.message.content.ifBlank { answer.toString() }
                        if (finalText.isBlank()) {
                            uiController.showError("No answer received. Try again.")
                        } else {
                            finalAnswer = finalText
                            showAnswer(finalText)
                        }
                    }
                    is AiProviderEvent.Failed -> {
                        completed = true
                        failed = true
                        showError(event.message)
                    }
                }
            }
            if (!completed) {
                if (answer.isBlank()) {
                    uiController.showError("No answer received. Try again.")
                } else {
                    finalAnswer = answer.toString()
                    showAnswer(finalAnswer.orEmpty())
                }
            }
            if (!failed) {
                currentCoroutineContext().ensureActive()
                answerSpeaker.speakCompletedAnswer(stripHudMarkdown(finalAnswer.orEmpty()))
                try {
                    val keepPhotosAtCompletion = authStore.keepPhotosInConversations()
                    withContext(Dispatchers.IO) {
                        if (!keepPhotosAtCompletion && threadStore.hasStoredPhotos()) {
                            threadStore.deleteAllPhotos()
                        }
                        conversationThreading.recordCompletedTurn(
                            context = conversationContext,
                            userText = transcript,
                            assistantText = finalAnswer,
                            hadPhoto = photoCapturedRequestId == request.requestId,
                            photoJpeg = photoJpegForCompletedTurn
                                ?.takeIf { keepPhotosAtCompletion },
                        )
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Throwable) {
                    Log.w(TAG, "Conversation persistence failed: ${error.javaClass.simpleName}")
                }
            }
        } finally {
            if (currentRequestId == request.requestId) {
                currentRequestId = null
                currentAssistantGeneration = null
                photoJpegForCompletedTurn = null
            }
        }
    }

    private fun createTakePhotoToolCapabilities(): TakePhotoToolCapabilities =
        object : TakePhotoToolCapabilities {
            override fun currentSession(): TakePhotoToolSession? {
                val requestId = currentRequestId ?: return null
                val generation = currentAssistantGeneration ?: return null
                return TakePhotoToolSession(requestId, generation)
            }

            override fun isSessionActive(session: TakePhotoToolSession): Boolean =
                currentRequestId == session.requestId &&
                    currentAssistantGeneration == session.generation &&
                    captureGeneration == session.generation &&
                    pipelineJob?.isActive == true &&
                    isNexusSessionOpen

            override fun hasCameraGrant(): Boolean =
                nexusClient?.hasCapability(PluginCapability.CAMERA) == true

            override fun isGlassesConnected(): Boolean =
                currentLinkState and LinkStateBits.SPP_DATA_UP != 0

            override fun isCameraBusy(): Boolean = snapshotSession != null

            override fun showTransient(message: String) {
                uiController.showTransient(message)
            }

            override suspend fun captureSnapshotJpeg(): TakePhotoCaptureResult =
                this@AssistantPluginService.captureSnapshotJpeg()

            override fun markPhotoCaptured(session: TakePhotoToolSession) {
                if (
                    currentRequestId == session.requestId &&
                    currentAssistantGeneration == session.generation
                ) {
                    photoCapturedRequestId = session.requestId
                }
            }

            override fun retainPhoto(session: TakePhotoToolSession, jpeg: ByteArray) {
                if (
                    currentRequestId == session.requestId &&
                    currentAssistantGeneration == session.generation
                ) {
                    photoJpegForCompletedTurn = jpeg
                }
            }

            override fun logOutcome(outcome: TakePhotoToolOutcome) {
                logToolOutcome(
                    requestId = outcome.requestId,
                    toolName = outcome.toolName,
                    outcome = outcome.outcome,
                    byteCount = outcome.byteCount,
                    width = outcome.width,
                    height = outcome.height,
                    captureMs = outcome.captureMs,
                    processMs = outcome.processMs,
                    totalMs = outcome.totalMs,
                )
            }
        }

    private fun createInkPageToolCapabilities(): InkPageToolCapabilities =
        object : InkPageToolCapabilities {
            override fun currentSession(): InkPageToolSession? {
                val requestId = currentRequestId ?: return null
                val generation = currentAssistantGeneration ?: return null
                return InkPageToolSession(requestId, generation)
            }

            override fun isSessionActive(session: InkPageToolSession): Boolean =
                isRenderInkSessionActive(session)

            override fun supportsInkSurface(): Boolean =
                nexusClient?.supportsInkSurface == true

            override suspend fun showInkPage(
                session: InkPageToolSession,
                page: String,
                data: JSONObject?,
            ): InkPageShowResult = awaitInkPageShow(session, page, data)

            override fun markInkShown(session: InkPageToolSession): Boolean {
                if (!isRenderInkSessionActive(session) || !inkSurfaceActive) {
                    clearInkSurface(hide = true)
                    return false
                }
                inkShownRequestId = session.requestId
                uiController.onInkAnswerShown()
                Log.i(TAG, "Ink answer shown; redundant notice stream dismissed")
                return true
            }
        }

    private suspend fun awaitInkPageShow(
        session: InkPageToolSession,
        page: String,
        data: JSONObject?,
    ): InkPageShowResult = suspendCancellableCoroutine { continuation ->
        if (!isRenderInkSessionActive(session)) {
            continuation.resume(InkPageShowResult.Failed(TOOL_ERROR_CANCELLED))
            return@suspendCancellableCoroutine
        }
        val sessionSurface = inkSurface
            ?: nexusInkSurfaceSession(INK_SURFACE_ID)?.also { inkSurface = it }
        if (sessionSurface == null) {
            continuation.resume(InkPageShowResult.Failed(TOOL_ERROR_INK_RENDER_FAILED))
            return@suspendCancellableCoroutine
        }
        if (pendingInkShow != null) {
            continuation.resume(InkPageShowResult.Failed(TOOL_ERROR_SURFACE_BUSY))
            return@suspendCancellableCoroutine
        }

        val pending = PendingInkShow(session, continuation)
        pendingInkShow = pending
        continuation.invokeOnCancellation {
            if (pendingInkShow === pending) {
                pendingInkShow = null
                val shouldHide = inkSurfaceActive
                inkSurfaceActive = false
                inkShownRequestId = null
                if (shouldHide) inkSurface?.hide()
            }
        }

        val result = sessionSurface.show(page = page, data = data, handlesBack = false)
        if (result == NexusSdkResult.SENT) {
            inkSurfaceActive = true
        } else if (pendingInkShow === pending) {
            pendingInkShow = null
            if (continuation.isActive) {
                continuation.resume(
                    InkPageShowResult.Failed(inkShowStartToolErrorCode(result)),
                )
            }
        }
    }

    private fun isRenderInkSessionActive(session: InkPageToolSession): Boolean =
        currentRequestId == session.requestId &&
            currentAssistantGeneration == session.generation &&
            captureGeneration == session.generation &&
            pipelineJob?.isActive == true &&
            isNexusSessionOpen

    private fun clearInkSurface(hide: Boolean) {
        val hadInk = inkSurfaceActive || inkShownRequestId != null
        val shouldHide = hide && inkSurfaceActive
        inkSurfaceActive = false
        inkShownRequestId = null
        val pending = pendingInkShow
        pendingInkShow = null
        if (pending?.continuation?.isActive == true) {
            pending.continuation.resume(InkPageShowResult.Failed(TOOL_ERROR_CANCELLED))
        }
        if (shouldHide) inkSurface?.hide()
        if (hadInk || pending != null) uiController.onSurfaceHidden()
    }

    private suspend fun captureSnapshotJpeg(): TakePhotoCaptureResult =
        suspendCancellableCoroutine { continuation ->
            var createdSession: NexusSnapshotSession? = null
            val callbacks = object : NexusSnapshotCallbacks {
                override fun onSnapshotCaptured(jpeg: ByteArray) {
                    if (snapshotSession === createdSession) snapshotSession = null
                    if (!continuation.isActive) return
                    continuation.resume(TakePhotoCaptureResult.Captured(jpeg))
                }

                override fun onSnapshotError(error: NexusSnapshotError) {
                    if (snapshotSession === createdSession) snapshotSession = null
                    if (!continuation.isActive) return
                    continuation.resume(
                        TakePhotoCaptureResult.Failed(snapshotToolErrorCode(error)),
                    )
                }
            }
            val session = nexusSnapshotSession(callbacks)
            createdSession = session
            if (session == null) {
                continuation.resume(
                    TakePhotoCaptureResult.Failed(TOOL_ERROR_CAPTURE_FAILED),
                )
                return@suspendCancellableCoroutine
            }
            snapshotSession = session
            continuation.invokeOnCancellation {
                if (snapshotSession === session) snapshotSession = null
                session.cancel()
            }
            val result = session.capture()
            if (result != NexusSdkResult.SENT && continuation.isActive) {
                if (snapshotSession === session) snapshotSession = null
                session.cancel()
                continuation.resume(
                    TakePhotoCaptureResult.Failed(snapshotStartToolErrorCode(result)),
                )
            }
        }

    private fun logToolOutcome(
        requestId: String?,
        toolName: String,
        outcome: String,
        byteCount: Int,
        width: Int,
        height: Int,
        captureMs: Long,
        processMs: Long,
        totalMs: Long,
    ) {
        Log.i(
            TAG,
            "tool requestId=${requestId ?: "none"} name=$toolName outcome=$outcome " +
                "bytes=$byteCount dimensions=${width}x$height " +
                "captureMs=$captureMs processMs=$processMs totalMs=$totalMs",
        )
    }

    private fun handleSpeechFailure(
        reason: NexusSpeechStopReason,
        error: NexusSpeechError?,
    ) {
        when (reason) {
            NexusSpeechStopReason.COMPLETED,
            NexusSpeechStopReason.NO_SPEECH,
            -> uiController.showError("Didn't catch that")
            NexusSpeechStopReason.CANCELLED -> Unit
            NexusSpeechStopReason.DENIED_BUSY ->
                uiController.showError("Speech is busy. Try again.")
            NexusSpeechStopReason.DENIED_NO_LINK,
            NexusSpeechStopReason.LINK_LOST,
            -> uiController.showError("Glasses microphone unavailable.")
            NexusSpeechStopReason.REVOKED ->
                uiController.showError("Speech access was revoked.")
            NexusSpeechStopReason.DENIED_NOT_READY,
            NexusSpeechStopReason.DENIED_START_FAILED,
            NexusSpeechStopReason.DENIED_INVALID,
            NexusSpeechStopReason.ERROR,
            -> uiController.showError("Speech recognition failed. Try again.")
        }
        if (reason != NexusSpeechStopReason.COMPLETED &&
            reason != NexusSpeechStopReason.NO_SPEECH &&
            reason != NexusSpeechStopReason.CANCELLED
        ) {
            Log.w(TAG, "Hub speech stopped: $reason (${error?.kind ?: "no detail"})")
        }
    }

    private fun showAnswer(text: String) {
        val plain = stripHudMarkdown(text)
        if (inkAnswerOwnsPresentation(inkShownRequestId, currentRequestId)) return
        uiController.showAnswer(
            body = plain,
            legacyCardLines = wrapHudText(plain),
        )
    }

    private fun showError(message: String) {
        val concise = message
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(MAX_ERROR_CHARS)
            .ifBlank { "Request failed. Try again." }
        uiController.showError(
            body = concise,
            legacyCardLines = wrapHudText(concise, maxLines = 3),
        )
    }

    private fun renderCard(
        lines: List<String>,
        forceShow: Boolean,
        footer: String? = null,
        contentKey: String? = null,
    ): NexusSdkResult {
        val session = surface ?: return NexusSdkResult.NOT_REGISTERED
        val card = NexusCard(
            title = "Assistant",
            lines = lines.take(MAX_HUD_LINES).map { it.take(MAX_CARD_LINE_CHARS) },
            footer = footer,
            contentKey = contentKey,
            handlesBack = true,
        )
        return if (forceShow) {
            session.showCard(card)
        } else {
            session.updateCard(card)
        }
    }

    /**
     * The typed-question field, on the same session as the anchor so that swapping between them
     * never leaves the plugin without a surface — which the hub would read as a close. Its own
     * content key keeps the anchor's footer off it where the field renders as a card.
     */
    private fun renderQuestionField(
        field: EditableSurfaceField,
        footer: String,
        onRejected: (code: String) -> Unit,
    ): NexusSdkResult {
        val session = surface ?: return NexusSdkResult.NOT_REGISTERED
        session.onRejected = onRejected
        return try {
            session.showCard(
                NexusCard(
                    title = "Assistant",
                    lines = emptyList(),
                    footer = footer,
                    contentKey = QUESTION_CONTENT_KEY,
                    editable = field,
                    handlesBack = true,
                ),
            )
        } finally {
            session.onRejected = null
        }
    }

    private fun renderRichCard(
        subtitle: String?,
        lines: List<NexusCardLine>,
        footer: String?,
        forceShow: Boolean,
        contentKey: String?,
    ): NexusSdkResult {
        val session = surface ?: return NexusSdkResult.NOT_REGISTERED
        val card = NexusCard(
            title = "Assistant",
            lines = emptyList(),
            subtitle = subtitle,
            contentKey = contentKey,
            richLines = lines.take(MAX_HUD_LINES),
            footer = footer,
            handlesBack = true,
        )
        return if (forceShow) {
            session.showCard(card)
        } else {
            session.updateCard(card)
        }
    }

    private fun cancelPipeline() {
        stopAnswerSpeech()
        pipelineJob?.cancel()
        pipelineJob = null
        val activeSnapshot = snapshotSession
        snapshotSession = null
        activeSnapshot?.cancel()
        currentRequestId?.let { requestId ->
            openAiCompatClients.values.forEach { client -> client.cancel(requestId) }
            chatGptCodexClient.cancel(requestId)
        }
        currentRequestId = null
        currentAssistantGeneration = null
        photoJpegForCompletedTurn = null
    }

    private fun speakAnswer(text: String, utteranceId: String): NexusSdkResult {
        if (captureActive || speechSession != null || audioSession != null) {
            return NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        }
        val currentClient = nexusClient ?: return NexusSdkResult.NOT_REGISTERED
        val session = ttsSession ?: currentClient.ttsSession(ttsCallbacks).also { ttsSession = it }
        activeTtsUtteranceId = utteranceId
        val result = session.speak(text, utteranceId)
        Log.i(TAG, "tts speak utteranceId=$utteranceId result=$result textChars=${text.length}")
        if (result != NexusSdkResult.SENT && activeTtsUtteranceId == utteranceId) {
            activeTtsUtteranceId = null
        }
        return result
    }

    private fun stopAnswerSpeech() {
        val utteranceId = activeTtsUtteranceId ?: return
        activeTtsUtteranceId = null
        val result = ttsSession?.stop() ?: return
        Log.i(TAG, "tts stop utteranceId=$utteranceId result=$result")
    }

    private fun closeAnswerSpeechSession() {
        stopAnswerSpeech()
        ttsSession?.close()
        ttsSession = null
    }

    private fun resetCapture() {
        captureGeneration += 1
        captureActive = false
        fallbackTranscribePending = false
        fallbackStopJob?.cancel()
        fallbackStopJob = null
        val activeSpeech = speechSession
        speechSession = null
        activeSpeech?.stop()
        val activeAudio = audioSession
        audioSession = null
        activeAudio?.stop()
        audioFormat = null
        pcmBuffer = ByteArrayOutputStream()
    }

    private fun selectedProviderId(): String =
        authStore.selectedProviderId() ?: when (authStore.authMode()) {
            CodexAuthStore.AUTH_MODE_CHATGPT -> ChatGptCodexProvider.ID
            else -> ProviderCatalog.openAi.id
        }

    private fun assistantToolSessionContext(): AssistantToolSessionContext =
        AssistantToolSessionContext(
            active = isNexusSessionOpen,
            grantedCapabilities = buildSet {
                if (nexusClient?.hasCapability(PluginCapability.CAMERA) == true) {
                    add(PluginCapability.CAMERA.wireValue)
                }
                if (nexusClient?.hasCapability(PluginCapability.INK_SURFACE) == true) {
                    add(PluginCapability.INK_SURFACE.wireValue)
                }
            },
        )

    private fun assistantProviderFeatures(providerId: String): AssistantProviderFeatures =
        AssistantProviderFeatures(
            supportsTools = true,
            supportsVision = providerSupportsPhotos(providerId),
        )

    private fun providerSupportsPhotos(providerId: String): Boolean =
        providerId == ChatGptCodexProvider.ID || authStore.providerModelSupportsPhotos(providerId)

    private suspend fun ensureProviderBackendDetected(providerId: String) {
        if (
            providerId != ProviderCatalog.custom.id ||
            authStore.providerDetectedBackend(providerId) != null ||
            customBackendProbeAttempted
        ) {
            return
        }
        customBackendProbeAttempted = true
        val baseUrl = authStore.providerBaseUrl(providerId)
        val apiKey = authStore.providerApiKey(providerId).orEmpty()
        if (baseUrl.isBlank() || apiKey.isBlank()) return
        when (hermesCapabilitiesClient.discover(baseUrl, apiKey)) {
            is HermesDiscoveryResult.Detected ->
                authStore.setProviderDetectedBackend(providerId, ProviderBackend.HERMES)
            HermesDiscoveryResult.NotHermes ->
                authStore.setProviderDetectedBackend(providerId, ProviderBackend.OPENAI_COMPAT)
            HermesDiscoveryResult.Unavailable -> Unit
        }
    }

    companion object {
        private const val TAG = "NexusAssistant"
        private const val SURFACE_ID = "assistant"
        private const val QUESTION_CONTENT_KEY = "question"
        private const val INK_SURFACE_ID = "assistant-ink"
        private const val INK_TEMPLATE_ASSET_DIR = "ink_templates"
        private const val AI_ASSIST_OPEN_PATH = "/system/plugin/ai-assist"
        private const val AI_ASSIST_OPEN_TYPE = "ai_assist"
        private const val FALLBACK_CAPTURE_DURATION_MS = 6_000L
        private const val HUD_UPDATE_INTERVAL_MS = 250L
        private const val MAX_HUD_LINES = 6
        private const val MAX_HUD_LINE_CHARS = 42
        private const val MAX_CARD_LINE_CHARS = 240
        private const val MAX_ERROR_CHARS = 180

        @Volatile
        private var debugInstance: AssistantPluginService? = null

        /**
         * Test-only rendezvous for [AssistantDebugAskReceiver]: hands a typed
         * question to the live service as if speech had produced it. Returns
         * false when no service process is up; a closed session is reported in
         * logcat from the main thread, where the session flag is trustworthy.
         */
        internal fun debugAsk(question: String): Boolean {
            val service = debugInstance ?: return false
            service.serviceScope.launch {
                if (!service.isNexusSessionOpen) {
                    Log.i(TAG, "debug ask ignored: no open assistant session")
                    return@launch
                }
                service.stopAnswerSpeech()
                service.clearInkSurface(hide = true)
                service.launchAssistantPipeline(question)
            }
            return true
        }

        /**
         * Opens a bare editable field on the wearer's foreground card for a
         * typed note — the [AssistantProductivityActivity] "Add note" button's
         * rendezvous with the live service, same shape as [debugAsk]. Only
         * works while the wearer already has Assistant open on the glasses,
         * same constraint [debugAsk] has: there is no surface session before
         * `onNexusOpen` has run.
         */
        internal fun requestNewNote(): Boolean {
            val service = debugInstance ?: return false
            if (!service.isNexusSessionOpen) return false
            val currentSurface = service.surface ?: return false
            service.serviceScope.launch {
                service.cancelPipeline()
                service.resetCapture()
                service.uiController.endTyping(AssistantTypingEnd.REPLACED)
                service.pendingNoteEntry = true
                val result = currentSurface.showCard(
                    NexusCard(
                        title = "New note",
                        lines = emptyList(),
                        editable = EditableSurfaceField(
                            placeholder = "Type a note…",
                            submitLabel = "Save",
                        ),
                    ),
                )
                if (result != NexusSdkResult.SENT) service.pendingNoteEntry = false
            }
            return true
        }
    }
}

private data class PendingInkShow(
    val session: InkPageToolSession,
    val continuation: CancellableContinuation<InkPageShowResult>,
)

internal fun inkAnswerOwnsPresentation(
    inkShownRequestId: String?,
    currentRequestId: String?,
): Boolean = currentRequestId != null && inkShownRequestId == currentRequestId

internal fun inkShowStartToolErrorCode(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> TOOL_ERROR_NOT_AUTHORIZED
    NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> TOOL_ERROR_INK_SURFACE_UNAVAILABLE
    NexusSdkResult.SURFACE_BUSY -> TOOL_ERROR_SURFACE_BUSY
    NexusSdkResult.NOT_REGISTERED -> TOOL_ERROR_CANCELLED
    else -> TOOL_ERROR_INK_RENDER_FAILED
}

internal fun shouldUseRawCaptureFallback(result: NexusSdkResult): Boolean =
    result == NexusSdkResult.CAPABILITY_NOT_GRANTED ||
        result == NexusSdkResult.CAPABILITY_NOT_AVAILABLE

internal fun shouldUseRawCaptureFallback(reason: NexusSpeechStopReason): Boolean =
    reason == NexusSpeechStopReason.DENIED_NOT_READY

internal fun snapshotErrorMessage(error: NexusSnapshotError): String = when (error) {
    NexusSnapshotError.BUSY -> "Camera is busy. Close Lens and try again."
    NexusSnapshotError.LINK_DOWN -> "Glasses camera is unavailable."
    NexusSnapshotError.TIMEOUT -> "Camera timed out. Try again."
    NexusSnapshotError.CAPTURE_FAILED -> "Camera capture failed. Try again."
    NexusSnapshotError.CANCELLED -> "Camera request was cancelled."
    NexusSnapshotError.ERROR -> "Camera request failed. Try again."
}

internal fun snapshotStartErrorMessage(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Grant Camera access in Nexus settings."
    NexusSdkResult.NOT_REGISTERED -> "Assistant is not connected to Nexus."
    else -> "Glasses camera is unavailable."
}

private fun normalizeTranscript(text: String): String =
    text.replace(Regex("\\s+"), " ").trim()

/**
 * The HUD renders one green monospace face, so emphasis markers arrive as literal
 * asterisks on the wearer's glasses. Models reach for them anyway — especially when
 * confirming a time a tool just returned — so the markers come off here instead of
 * being begged away in the prompt. Bullets keep their "- " and code fences their
 * content; only the decoration goes.
 */
internal fun stripHudMarkdown(text: String): String {
    if (text.indexOf('*') < 0 && text.indexOf('_') < 0 && text.indexOf('`') < 0) return text
    return text
        .replace(Regex("(?<!\\*)\\*\\*(?!\\s)(.+?)(?<!\\s)\\*\\*(?!\\*)", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("__(?!\\s)(.+?)(?<!\\s)__", RegexOption.DOT_MATCHES_ALL), "$1")
        .replace(Regex("(?<![\\w*])\\*(?!\\s)([^*\\n]+?)(?<!\\s)\\*(?![\\w*])"), "$1")
        .replace(Regex("`{1,3}([^`]+)`{1,3}", RegexOption.DOT_MATCHES_ALL), "$1")
}

internal fun wrapHudText(
    text: String,
    maxLines: Int = 6,
    maxLineChars: Int = 42,
): List<String> {
    require(maxLines > 0)
    require(maxLineChars > 1)
    val wrapped = mutableListOf<String>()
    text.replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .forEach { paragraph ->
            val words = paragraph.trim().split(Regex("\\s+")).filter(String::isNotBlank)
            if (words.isEmpty()) return@forEach
            var line = ""
            words.forEach { word ->
                val pieces = if (word.length > maxLineChars) {
                    word.chunked(maxLineChars)
                } else {
                    listOf(word)
                }
                pieces.forEach { piece ->
                    val candidate = if (line.isEmpty()) piece else "$line $piece"
                    if (candidate.length <= maxLineChars) {
                        line = candidate
                    } else {
                        if (line.isNotEmpty()) wrapped += line
                        line = piece
                    }
                }
            }
            if (line.isNotEmpty()) wrapped += line
        }
    if (wrapped.isEmpty()) return listOf("…")
    if (wrapped.size <= maxLines) return wrapped
    return wrapped.take(maxLines).toMutableList().apply {
        val lastIndex = lastIndex
        this[lastIndex] = this[lastIndex].take(maxLineChars - 1).trimEnd() + "…"
    }
}
