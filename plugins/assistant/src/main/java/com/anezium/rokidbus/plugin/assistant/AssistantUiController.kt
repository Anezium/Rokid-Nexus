package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal interface AssistantUiRenderer {
    val supportsNoticeSurface: Boolean

    fun showNotice(notice: NexusNotice): NexusSdkResult

    fun updateNotice(update: NexusNoticeUpdate): NexusSdkResult

    fun hideNotice(): NexusSdkResult

    /**
     * [contentKey] decides what the glasses carry over: a card that omits a field inherits the
     * previous card's when both share a key (or the new one has none). The anchor and the
     * options menu use distinct keys so neither wears the other's subtitle or footer.
     */
    fun showCard(
        lines: List<String>,
        forceShow: Boolean,
        footer: String? = null,
        contentKey: String? = null,
    ): NexusSdkResult

    /** One card of list rows — the options menu — with its own subtitle and footer. */
    fun showRichCard(
        subtitle: String?,
        lines: List<NexusCardLine>,
        footer: String?,
        forceShow: Boolean,
        contentKey: String?,
    ): NexusSdkResult

    /** Whether the glasses can take a typed answer at all (the `EDITABLE_SURFACE` bit). */
    val supportsQuestionField: Boolean

    /** The Input setting, as the wearer left it. */
    val chosenInputMode: AssistantInputMode

    /**
     * Replaces the card with the typed-question field. [onRejected] hears a rejection that
     * lands after `SENT`, for this show only.
     */
    fun showQuestionField(
        field: EditableSurfaceField,
        footer: String,
        onRejected: (code: String) -> Unit,
    ): NexusSdkResult

    /** Hides the card. When it was the plugin's last surface, the hub reads this as a close. */
    fun hideCard(): NexusSdkResult
}

internal enum class AssistantNoticeMode {
    NONE,
    ENGAGED,
    PASSIVE,
}

/** How a typed question ended; decides what takes the field's place on the card tier. */
internal enum class AssistantTypingEnd {
    /** Enter with text: the question goes through the pipeline. */
    SUBMITTED,

    /** Back, or a cancelled commit: nothing is sent. */
    CANCELLED,

    /** A new spoken question takes over the session while the field was open. */
    SUPERSEDED,

    /** The caller is about to put its own card over the field. */
    REPLACED,
}

internal class AssistantUiController(
    private val scope: CoroutineScope,
    private val renderer: AssistantUiRenderer,
    private val cancelPipeline: () -> Unit,
    private val resetCapture: () -> Unit,
    private val launcherHintDelayMs: Long = LAUNCHER_HINT_DELAY_MS,
    private val errorNoticeDurationMs: Long = ERROR_NOTICE_DURATION_MS,
    private val transcriptUpdateIntervalMs: Long = TRANSCRIPT_UPDATE_INTERVAL_MS,
    private val keepaliveIntervalMs: Long = NOTICE_KEEPALIVE_INTERVAL_MS,
    private val typeChipArmDelayMs: Long = TYPE_CHIP_ARM_DELAY_MS,
    private val typingKeepaliveIntervalMs: Long = TYPING_KEEPALIVE_INTERVAL_MS,
    noticeIntervalMs: Long = AssistantNoticePacer.MIN_INTERVAL_MS,
    /** A capture, a model call or a camera snapshot is still under way. */
    private val sessionBusy: () -> Boolean = { false },
    /** The answer is still being read out, or is about to be. */
    private val answerSpeaking: () -> Boolean = { false },
    private val holderRecheckMs: Long = HOLDER_RECHECK_MS,
) {
    private var launcherHintJob: Job? = null
    private var holderReleaseJob: Job? = null
    private var noticeHideJob: Job? = null
    private var transcriptUpdateJob: Job? = null
    private var keepaliveJob: Job? = null
    private var typeChipJob: Job? = null
    private var lastInFlightBody: String? = null
    private var lastInFlightUsesLines = false

    /** True from the first "Listening…" of a capture until anything else takes the band. */
    private var listening = false
    private var typeChipLive = false

    /**
     * What the visible band carries beyond its text. An update cannot take either away — the
     * SDK never sends an empty action row — so leaving a state that had them is a fresh show.
     */
    private var bandActions: List<NexusNoticeAction> = emptyList()
    private var bandFooter: String? = null

    /** A Type tap is being served: from the quiet band's show until the field is retired. */
    private var typingOpen = false
    private var anchoredBeforeTyping = false

    /**
     * The bare card that keeps an assist-button session open while its answer arrives. That
     * session never had an anchor, and must not grow one: all it shows is the band. The holder
     * carries nothing a band does not cover, and goes as soon as nothing is left to wait for.
     */
    private var holderShown = false

    /** The field has actually replaced the card; it opens only once its quiet band has left. */
    private var fieldShown = false
    private var typingAttempt = 0

    /** The hub may have turned a message away, so the glasses' band is not what we think. */
    private var bandUncertain = false

    private val notices = AssistantNoticePacer(
        scope = scope,
        sendShow = renderer::showNotice,
        sendUpdate = renderer::updateNotice,
        sendHide = renderer::hideNotice,
        intervalMs = noticeIntervalMs,
        onDeferredFailure = { onNoticeSendFailed() },
    )

    /** The answer the voice is about to read, kept so speech can hold its band open. */
    private var spokenAnswerBody: String? = null
    private var pendingTranscriptBody: String? = null
    private var noticeStateVersion = 0L
    private var surfaceShown = false
    private var noticeShown = false
    private var noticeMode = AssistantNoticeMode.NONE
    private var answerCardStarted = false

    /**
     * True while an Ink page owns this answer's visual presentation. Later
     * Progress ("Thinking…") and spoken-reply [showAnswer] calls must not
     * resurrect the notice band over that page.
     */
    private var inkOwnsAnswer = false

    /**
     * True while the launcher card anchors the session. Unlike a card the wearer opened
     * into, it is not the conversation's render target: the band keeps drawing over it.
     * It is there so the plugin owns the screen — which is what makes a swipe reach it.
     */
    private var anchorShown = false

    val isAnchored: Boolean
        get() = anchorShown

    /**
     * True while the band is the render target. A surface card the wearer already
     * has open keeps that interaction on the card: hiding it from here would read
     * as a self-close to the hub, which tears the whole plugin session down. The
     * launcher anchor is the one card that does not claim the interaction.
     */
    val isNoticeBandMode: Boolean
        get() = renderer.supportsNoticeSurface &&
            (!surfaceShown || anchorShown || typingOpen || holderShown)

    internal val isEngagedNoticeEpisode: Boolean
        get() = noticeShown && noticeMode == AssistantNoticeMode.ENGAGED

    /** The typed-question field is open on the card tier. */
    val isTyping: Boolean
        get() = typingOpen

    /**
     * The band is listening and its Type chip is up, so a Type action is still current — and
     * still wanted: a switch to another Input mode mid-question retires the chip's tap even
     * though the chip itself stays drawn until the band moves on.
     */
    val offersTyping: Boolean
        get() = listening && typeChipLive && noticeShown && bandActions == TYPE_ACTIONS &&
            inputMode == AssistantInputMode.VOICE_AND_TYPE

    /** The Input setting as these glasses can honour it right now; see [effectiveInputMode]. */
    val inputMode: AssistantInputMode
        get() = effectiveInputMode(
            chosen = renderer.chosenInputMode,
            canType = renderer.supportsQuestionField && renderer.supportsNoticeSurface,
        )

    fun onOpen() {
        // A re-delivered open while an assist-button answer is still on its way: resetting would
        // drop the holder, and the hint queued below would replace it and turn the rest of the
        // answer into card lines. The session carries on as it was, band and all.
        if (holderShown || sessionBusy() || answerSpeaking()) {
            cancelLauncherHint()
            reshowInFlight(lastInFlightBody, lastInFlightUsesLines, spokenAnswerBody)
            return
        }
        resetForOpen()
        launcherHintJob = scope.launch {
            delay(launcherHintDelayMs)
            launcherHintJob = null
            startNewState()
            hideNoticeIfShown()
            showCard(
                lines = listOf(launcherHint()),
                forceShow = true,
            )
        }
    }

    /**
     * A launcher pick: no hint and no waiting. The anchor card goes up at once as the thing
     * the wearer is holding, and the session listens right away. Returns false when the card
     * could not be shown, in which case the caller falls back to the plain [onOpen].
     */
    fun onLauncherOpen(): Boolean {
        resetForOpen()
        val shown = showCard(
            anchorLines(),
            forceShow = true,
            footer = ANCHOR_FOOTER,
            contentKey = ANCHOR_CONTENT_KEY,
        ) == NexusSdkResult.SENT
        anchorShown = shown
        return shown
    }

    /** The options menu replaces whatever band was up; its card is the render target while open. */
    fun showOptions(view: AssistantOptionsMenu.View, forceShow: Boolean) {
        cancelLauncherHint()
        stopKeepalive()
        // Whatever was being transcribed belongs to the capture the menu just cancelled.
        startNewState(flushTranscript = false)
        hideNoticeIfShown()
        val result = renderer.showRichCard(
            subtitle = OPTIONS_SUBTITLE,
            lines = listOf(NexusCardLine(text = view.text, sub = view.sub, selected = true)),
            footer = view.footer,
            forceShow = forceShow || !surfaceShown,
            contentKey = OPTIONS_CONTENT_KEY,
        )
        if (result == NexusSdkResult.SENT) surfaceShown = true
    }

    /**
     * Back from the options menu: the anchor card returns and the band is the target again.
     * A fresh show under the anchor's own key: an update, or a show under the menu's key,
     * would inherit the menu's subtitle on the glasses and leave "Options" over a card that
     * has none.
     */
    fun restoreAnchor() {
        val result = showCard(
            anchorLines(),
            forceShow = true,
            footer = ANCHOR_FOOTER,
            contentKey = ANCHOR_CONTENT_KEY,
        )
        anchorShown = result == NexusSdkResult.SENT
        if (anchorShown) forgetHolder()
    }

    fun onClose() {
        cancelLauncherHint()
        startNewState(flushTranscript = false)
        hideNoticeIfShown()
        surfaceShown = false
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        notices.dropWaiting()
        answerCardStarted = false
        anchorShown = false
        inkOwnsAnswer = false
        typingOpen = false
        fieldShown = false
        forgetHolder()
    }

    private fun resetForOpen() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        surfaceShown = false
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        notices.dropWaiting()
        answerCardStarted = false
        anchorShown = false
        inkOwnsAnswer = false
        typingOpen = false
        fieldShown = false
        forgetHolder()
    }

    fun cancelLauncherHint() {
        launcherHintJob?.cancel()
        launcherHintJob = null
    }

    fun beginGestureFlow() {
        cancelLauncherHint()
        discardPendingTranscript()
        // Every capture is its own listening episode, with its own wait for the Type chip.
        endListening()
        inkOwnsAnswer = false
    }

    /**
     * "Listening…" for a capture. The first call of a capture starts its listening episode and
     * arms the Type chip; later ones (the speech engine confirming it started, the raw-audio
     * fallback taking over) only redraw, so the chip neither restarts its wait nor flickers.
     */
    fun showListening(legacyForceShow: Boolean = false) {
        if (inkOwnsAnswer) return
        if (!listening || !useNoticeBand()) {
            showTransient(LISTENING_BODY, legacyForceShow)
            if (!useNoticeBand()) return
            listening = true
            armTypeChip()
            return
        }
        cancelLauncherHint()
        lastInFlightBody = LISTENING_BODY
        lastInFlightUsesLines = false
        showOrUpdateNotice(LISTENING_BODY)
        startKeepalive()
    }

    /**
     * A question is starting, by whatever way the wearer asked. With Type first in effect it
     * opens here, straight into the typed field, and true tells the caller to leave the
     * microphone off; otherwise false, and the caller listens as usual.
     */
    fun startQuestion(): Boolean {
        if (inputMode != AssistantInputMode.TYPE_FIRST) return false
        beginTyping()
        return true
    }

    /**
     * A re-delivered open reset the band while work it was showing is still under way. The
     * reset stopped that band's keepalive, and a non-anchor open has its launcher hint queued
     * on top, so the band comes back as it was: "Transcribing…" while recorded audio is being
     * turned into text, otherwise "Listening…" with a fresh wait for its Type chip.
     */
    fun resumeInFlight(capturing: Boolean, transcribing: Boolean) {
        when {
            transcribing -> showTransient(TRANSCRIBING_BODY)
            capturing -> showListening(legacyForceShow = true)
        }
    }

    /**
     * Swaps the listening band for the typed-question field, drawn inside the band itself. The
     * caller has already stopped the microphone. The band stops asking for a gesture while the
     * field is open: an interactive band claims confirm ahead of the field, and Enter is the
     * field's. The field opens only once that quiet band has actually left — never over the
     * listening band and its chip, which a ring tap would still answer. On a glasses hub
     * without in-band fields the same field opens as a card instead.
     */
    fun beginTyping() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        typingAttempt += 1
        val attempt = typingAttempt
        typingOpen = true
        fieldShown = false
        anchoredBeforeTyping = anchorShown
        showTypingBand { result -> onTypingBandSent(attempt, result) }
    }

    /**
     * Retires the field. Whatever the wearer had before takes the card tier back: the anchor,
     * or — for a session the assist button opened with nothing on screen — nothing, which the
     * hub reads as the plugin closing, exactly like Back on the anchor. That holds even when
     * the cancel beats the field onto the glasses: the microphone is already off, so a session
     * left open there would have nothing on screen and nothing left to do. A submitted or
     * superseded question keeps a card up, because its answer still has to arrive in this
     * session; without an anchor that card is the bare holder, so the session stays a band.
     */
    fun endTyping(end: AssistantTypingEnd): Boolean {
        if (!typingOpen) return false
        typingOpen = false
        val replacedCard = fieldShown
        fieldShown = false
        stopKeepalive()
        when {
            // The caller's card is about to take the tier, whatever was there.
            end == AssistantTypingEnd.REPLACED -> Unit
            end == AssistantTypingEnd.CANCELLED && !anchoredBeforeTyping -> {
                renderer.hideCard()
                onSurfaceHidden()
            }
            // Nothing took the anchor's place yet, so it is still up.
            !replacedCard -> Unit
            !anchoredBeforeTyping -> showHolder()
            else -> restoreAnchor()
        }
        // A submitted question's Thinking, or a superseding capture's Listening, replaces the
        // quiet band in place: hiding it first would leave the card below uncovered meanwhile.
        if (end == AssistantTypingEnd.CANCELLED || end == AssistantTypingEnd.REPLACED) {
            // A quiet band still waiting to leave would come back after the wearer left it.
            notices.dropWaiting()
            hideNoticeIfShown()
        }
        return true
    }

    /**
     * The hub turned one of this band's messages away — its five-a-second limit — after the SDK
     * had already said `SENT`. Which one is unknown, so the band is put back whole: the quiet
     * band while typing (a lost one leaves the listening band and its chip under the field),
     * otherwise the state in flight, and in any case a fresh show next time.
     */
    fun onNoticeRejected() {
        bandUncertain = true
        if (typingOpen) {
            val attempt = typingAttempt
            showTypingBand { result -> onTypingBandSent(attempt, result) }
            return
        }
        val body = lastInFlightBody ?: return
        if (!noticeShown || !useNoticeBand()) return
        if (lastInFlightUsesLines) showOrUpdateAnswerNotice(body) else showOrUpdateNotice(body)
    }

    private fun onTypingBandSent(attempt: Int, result: NexusSdkResult) {
        if (!typingOpen || attempt != typingAttempt) return
        if (result != NexusSdkResult.SENT) {
            // A band re-shown under an open field changes nothing for the field: without it,
            // the glasses bring the field back as a card.
            if (fieldShown) onNoticeSendFailed() else abortTyping()
            return
        }
        if (fieldShown) return
        val fieldResult = renderer.showQuestionField(
            field = QUESTION_FIELD,
            footer = TYPING_FOOTER,
            onRejected = ::onQuestionFieldRejected,
        )
        if (fieldResult != NexusSdkResult.SENT) {
            abortTyping()
            return
        }
        fieldShown = true
        surfaceShown = true
        anchorShown = false
        forgetHolder()
        startTypingKeepalive()
    }

    /**
     * The quiet band or the field never made it. The band on the glasses may still be the
     * listening one, Type chip and all, whatever our own flags say — so it is hidden outright
     * rather than left offering a question nothing will answer.
     */
    private fun abortTyping() {
        typingOpen = false
        fieldShown = false
        stopKeepalive()
        notices.hide()
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        showError(QUESTION_FIELD_FAILED)
    }

    private fun onQuestionFieldRejected(code: String) {
        if (!typingOpen || !fieldShown) return
        typingOpen = false
        fieldShown = false
        stopKeepalive()
        // The field never replaced the card, so whatever was there still is.
        surfaceShown = anchoredBeforeTyping
        anchorShown = anchoredBeforeTyping
        showError(if (code == SURFACE_BUSY) SCREEN_BUSY else QUESTION_FIELD_FAILED)
    }

    /** A message that had waited for its turn failed when it left: the band is not up. */
    private fun onNoticeSendFailed() {
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
    }

    /**
     * The flags are claimed before the show because [onSent] may run inside it — and may
     * already have moved the band on (an error) by the time it returns. A failure is settled
     * there too, in [onTypingBandSent].
     */
    private fun showTypingBand(onSent: (NexusSdkResult) -> Unit): Boolean {
        noticeShown = true
        noticeMode = AssistantNoticeMode.PASSIVE
        bandActions = emptyList()
        bandFooter = TYPING_FOOTER
        bandUncertain = false
        // A fresh show, never an update: it drops the Type chip the wearer just used, and it
        // starts a new band lifetime for however long the typing takes.
        return notices.show(
            NexusNotice(
                title = NOTICE_TITLE,
                footer = TYPING_FOOTER,
                interactive = false,
                ttlMs = TYPING_TTL_MS,
            ),
            onSent,
        ) == NexusSdkResult.SENT
    }

    /**
     * The typing band has no stream of updates to restart its TTL, and a wearer picking out a
     * question on a phone keyboard easily outlasts one. A footer-only update carries neither
     * actions nor `interactive`, so it keeps the band alive without asking anything again.
     */
    private fun startTypingKeepalive() {
        if (keepaliveJob != null) return
        keepaliveJob = scope.launch {
            while (true) {
                delay(typingKeepaliveIntervalMs)
                if (!typingOpen || !noticeShown) break
                notices.update(NexusNoticeUpdate(footer = TYPING_FOOTER, ttlMs = TYPING_TTL_MS))
            }
            keepaliveJob = null
        }
    }

    /**
     * Offers typing instead, once the bounce window of the tap that started listening is over.
     * Re-arming the band straight away hands the temple pad's bounce (433 ms measured after a
     * real tap) a question to answer; arriving well after it, a tap on the chip is the wearer's.
     * One chip leaves the directions free, so a long transcript still pages behind it.
     */
    private fun armTypeChip() {
        cancelTypeChip()
        // Voice only keeps the band exactly as it was before the chip existed, so a temple tap
        // while listening does what it always did instead of opening a keyboard.
        if (inputMode != AssistantInputMode.VOICE_AND_TYPE) return
        typeChipJob = scope.launch {
            delay(typeChipArmDelayMs)
            typeChipJob = null
            if (!listening || !noticeShown || !useNoticeBand()) return@launch
            // The setting may have changed while this capture was listening.
            if (inputMode != AssistantInputMode.VOICE_AND_TYPE) return@launch
            if (notices.update(NexusNoticeUpdate(actions = TYPE_ACTIONS)) == NexusSdkResult.SENT) {
                typeChipLive = true
                bandActions = TYPE_ACTIONS
            }
        }
    }

    /** "Ask out loud." would be wrong where asking opens a keyboard and the mic stays off. */
    private fun anchorLines(): List<String> =
        if (inputMode == AssistantInputMode.TYPE_FIRST) TYPE_FIRST_ANCHOR_LINES else ANCHOR_LINES

    private fun launcherHint(): String =
        if (inputMode == AssistantInputMode.TYPE_FIRST) TYPE_FIRST_LAUNCHER_HINT else LAUNCHER_HINT

    private fun cancelTypeChip() {
        typeChipJob?.cancel()
        typeChipJob = null
    }

    private fun endListening() {
        listening = false
        typeChipLive = false
        cancelTypeChip()
    }

    private fun clearBandDecorations() {
        bandActions = emptyList()
        bandFooter = null
        bandUncertain = false
    }

    /** Whatever the band should carry now: the Type chip while it is live, nothing otherwise. */
    private fun wantedActions(): List<NexusNoticeAction> =
        if (listening && typeChipLive) TYPE_ACTIONS else emptyList()

    /** True when [wanted] would leave something behind that only a fresh show can remove. */
    private fun needsFreshShow(wanted: List<NexusNoticeAction>): Boolean =
        noticeShown &&
            (bandUncertain || (bandActions.isNotEmpty() && wanted.isEmpty()) || bandFooter != null)

    fun showTransient(
        body: String,
        legacyForceShow: Boolean = false,
    ) {
        if (inkOwnsAnswer) return
        cancelLauncherHint()
        startNewState()
        answerCardStarted = false
        if (useNoticeBand()) {
            // In-flight states (Listening, Thinking, Searching) have no natural
            // stream of updates to keep restarting the band's TTL — a wearer
            // who takes five seconds to start speaking would watch the band
            // vanish under them. The keepalive resends the latest in-flight
            // body until a terminal state (answer, error, hide) takes over.
            lastInFlightBody = body
            lastInFlightUsesLines = false
            showOrUpdateNotice(body)
            startKeepalive()
        } else {
            showCard(listOf(body), forceShow = legacyForceShow || !surfaceShown)
        }
    }

    fun showTranscript(text: String) {
        if (inkOwnsAnswer) return
        if (!useNoticeBand()) return
        val body = truncateTranscriptTail(text)
        if (body.isBlank()) return
        pendingTranscriptBody = body
        if (transcriptUpdateJob != null) return

        renderPendingTranscript()
        transcriptUpdateJob = scope.launch {
            while (true) {
                delay(transcriptUpdateIntervalMs)
                if (pendingTranscriptBody == null) break
                renderPendingTranscript()
            }
            transcriptUpdateJob = null
        }
    }

    fun showError(
        body: String,
        legacyCardLines: List<String> = listOf(body),
        legacyForceShow: Boolean = false,
    ) {
        cancelLauncherHint()
        stopKeepalive()
        val stateVersion = startNewState()
        answerCardStarted = false
        if (useNoticeBand()) {
            if (showOrUpdateNotice(body, AssistantNoticeMode.PASSIVE)) {
                noticeHideJob = scope.launch {
                    delay(errorNoticeDurationMs)
                    noticeHideJob = null
                    if (noticeStateVersion == stateVersion) {
                        hideNoticeIfShown()
                        maybeReleaseHolder()
                    }
                }
            }
        } else {
            showCard(
                lines = legacyCardLines,
                forceShow = legacyForceShow || !surfaceShown,
            )
        }
    }

    fun showAnswer(
        body: String,
        legacyCardLines: List<String>,
    ) {
        if (inkOwnsAnswer) return
        cancelLauncherHint()
        stopKeepalive()
        startNewState()
        if (useNoticeBand()) {
            spokenAnswerBody = body
            showOrUpdateAnswerNotice(body, ttlMs = answerTtlMs(body))
            return
        }

        hideNoticeIfShown()
        val result = showCard(
            lines = legacyCardLines,
            forceShow = !answerCardStarted,
        )
        if (result == NexusSdkResult.SENT) {
            answerCardStarted = true
        }
    }

    /**
     * Speech is the honest clock for how long an answer needs to stay up. [answerTtlMs] can only
     * guess it from the text, and the guess starts running the moment the answer renders — while
     * the voice is still waking its engine and the audio link. A cold start therefore ate the
     * band's life before the first word, and the wearer watched the answer vanish mid-sentence.
     * So while it is actually being spoken the band is held open, and it gets its readable
     * remainder once the voice stops.
     */
    fun onAnswerSpeechStarted() {
        val body = spokenAnswerBody ?: return
        if (!useNoticeBand() || !noticeShown) return
        lastInFlightBody = body
        lastInFlightUsesLines = true
        startKeepalive()
    }

    fun onAnswerSpeechFinished() {
        val body = spokenAnswerBody
        spokenAnswerBody = null
        if (body != null) {
            stopKeepalive()
            if (useNoticeBand() && noticeShown) {
                // A glance, not a second reading: the wearer just heard the whole thing, so the
                // band owes them only long enough to catch the tail. Handing back the
                // length-based TTL here would pin a long answer on the display for another
                // twenty seconds after the voice had moved on, which reads as the band being
                // stuck.
                showOrUpdateAnswerNotice(body, ttlMs = ANSWER_SPOKEN_GRACE_MS)
            }
        }
        maybeReleaseHolder()
    }

    /** The model call ended, answered, failed or cancelled. */
    fun onPipelineFinished() {
        maybeReleaseHolder()
    }

    fun onSurfaceHidden() {
        if (typingOpen) stopKeepalive()
        surfaceShown = false
        answerCardStarted = false
        anchorShown = false
        inkOwnsAnswer = false
        typingOpen = false
    }

    /**
     * An Ink page produced for this answer now owns its visual presentation.
     * Retire the in-flight notice without marking the card tier active: later
     * errors are still discrete notices and must not replace the Ink surface.
     * Idempotent: a second call keeps ownership and does not resurrect the band.
     */
    fun onInkAnswerShown() {
        inkOwnsAnswer = true
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        hideNoticeIfShown()
        if (holderShown) {
            // The page now holds the session, so the holder can go without closing it: the
            // hub only closes a plugin whose last surface is gone, and dismissing the page is
            // then the end of this answer.
            forgetHolder()
            renderer.hideCard()
            surfaceShown = false
        }
    }

    /**
     * Does not clear [inkOwnsAnswer]: a notice close is not a new interaction.
     * OWNER is the hide already requested after the Ink page appeared; USER
     * dismissing an error overlay still leaves that page as the answer until
     * the next capture.
     */
    fun onNoticeClosed(reason: NexusNoticeCloseReason) {
        // Read before the reset below clears them: see the holder case at the end.
        val inFlight = lastInFlightBody
        val inFlightUsesLines = lastInFlightUsesLines
        val spoken = spokenAnswerBody
        // Whatever was still waiting to leave belonged to the band that is gone; a show among
        // it would bring back a band the wearer just dismissed.
        notices.dropWaiting()
        stopKeepalive()
        startNewState(flushTranscript = false)
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        if (typingOpen) {
            when (reason) {
                // Back reaches the band before the field underneath it ever sees the key.
                NexusNoticeCloseReason.USER -> endTyping(AssistantTypingEnd.CANCELLED)
                // Only the band's hard lifetime can get here while the keepalive runs: a
                // fresh band carries on with the field rather than dropping it to a card.
                NexusNoticeCloseReason.TIMEOUT -> {
                    val attempt = typingAttempt
                    if (showTypingBand { result -> onTypingBandSent(attempt, result) } && fieldShown) {
                        startTypingKeepalive()
                    }
                }
                // Another plugin's band, or our own hide: the glasses bring the field back
                // as a card on their own, and the commit still arrives as usual. A lost
                // connection takes the field down with the session.
                NexusNoticeCloseReason.OWNER,
                NexusNoticeCloseReason.REPLACED,
                NexusNoticeCloseReason.DISCONNECT,
                -> Unit
            }
        }
        if (reason == NexusNoticeCloseReason.USER) {
            cancelPipeline()
            resetCapture()
        }
        // A band's life is capped at 90 s from its show, and updates never move that cap, so a
        // long tool turn or a long readout outlives it. Under the holder that would leave the
        // bare card as the screen: a fresh show of what was up starts the clock again.
        if (reason == NexusNoticeCloseReason.TIMEOUT && holderShown && !typingOpen &&
            (sessionBusy() || answerSpeaking()) &&
            reshowInFlight(inFlight, inFlightUsesLines, spoken)
        ) {
            return
        }
        maybeReleaseHolder()
    }

    /**
     * Puts the band that was up back as a fresh show — "Thinking…" or a progress label with its
     * keepalive, the answer while it is read out, or an answer still waiting for its voice.
     * False when nothing was in flight.
     */
    private fun reshowInFlight(inFlight: String?, inFlightUsesLines: Boolean, spoken: String?): Boolean {
        val body = inFlight ?: spoken ?: return false
        if (!useNoticeBand()) return false
        bandUncertain = true
        spokenAnswerBody = spoken
        if (inFlight == null) {
            showOrUpdateAnswerNotice(body, ttlMs = answerTtlMs(body))
            return true
        }
        if (inFlightUsesLines) showOrUpdateAnswerNotice(body) else showOrUpdateNotice(body)
        lastInFlightBody = inFlight
        lastInFlightUsesLines = inFlightUsesLines
        startKeepalive()
        return true
    }

    private fun showHolder() {
        val result = showCard(
            lines = emptyList(),
            forceShow = true,
            contentKey = HOLDER_CONTENT_KEY,
        )
        if (result != NexusSdkResult.SENT) return
        anchorShown = false
        holderShown = true
    }

    /**
     * Lets the holder go once nothing is left for it to wait on: no field, no band, no capture
     * or model call, and no voice still reading. Hiding it is the plugin's last surface going,
     * which the hub reads as the session closing — the same end as Back on the anchor.
     */
    private fun maybeReleaseHolder() {
        if (!holderShown || typingOpen || noticeShown || sessionBusy()) return
        if (answerSpeaking()) {
            armHolderRecheck()
            return
        }
        releaseHolder()
    }

    /**
     * Speech normally reports its end, and that is what releases the holder. This looks again
     * now and then in case an end slipped by unreported, and never cuts a voice still reading:
     * letting the holder go closes the session, and the readout with it.
     */
    private fun armHolderRecheck() {
        if (holderReleaseJob != null) return
        holderReleaseJob = scope.launch {
            delay(holderRecheckMs)
            holderReleaseJob = null
            maybeReleaseHolder()
        }
    }

    private fun releaseHolder() {
        forgetHolder()
        renderer.hideCard()
        onSurfaceHidden()
    }

    private fun forgetHolder() {
        holderShown = false
        holderReleaseJob?.cancel()
        holderReleaseJob = null
    }

    private fun useNoticeBand(): Boolean = isNoticeBandMode

    private fun showOrUpdateNotice(
        body: String,
        mode: AssistantNoticeMode = AssistantNoticeMode.ENGAGED,
        ttlMs: Long? = null,
    ): Boolean {
        val safeBody = truncateNoticeHead(body)
        val actions = wantedActions()
        val modeUpdate = mode.takeIf { !noticeShown || it != noticeMode }
        val result = if (noticeShown && !needsFreshShow(actions)) {
            notices.update(
                NexusNoticeUpdate(
                    body = safeBody,
                    interactive = modeUpdate?.let { it == AssistantNoticeMode.ENGAGED },
                    actions = actions.takeIf { it != bandActions }.orEmpty(),
                    ttlMs = ttlMs,
                ),
            )
        } else {
            notices.show(
                NexusNotice(
                    title = NOTICE_TITLE,
                    body = safeBody,
                    interactive = mode == AssistantNoticeMode.ENGAGED,
                    actions = actions,
                    ttlMs = ttlMs,
                ),
            )
        }
        if (result == NexusSdkResult.SENT) {
            noticeShown = true
            noticeMode = mode
            bandActions = actions
            bandFooter = null
            bandUncertain = false
            return true
        }
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        return false
    }

    private fun showOrUpdateAnswerNotice(
        body: String,
        ttlMs: Long? = null,
    ): Boolean {
        val truncatedBody = truncateAnswerBody(body)
        val modeUpdate = AssistantNoticeMode.ENGAGED.takeIf {
            !noticeShown || noticeMode != AssistantNoticeMode.ENGAGED
        }
        val result = if (noticeShown && !needsFreshShow(emptyList())) {
            notices.update(
                NexusNoticeUpdate(
                    interactive = modeUpdate?.let { true },
                    body = truncatedBody,
                    ttlMs = ttlMs,
                ),
            )
        } else {
            notices.show(
                NexusNotice(
                    title = NOTICE_TITLE,
                    interactive = true,
                    body = truncatedBody,
                    ttlMs = ttlMs,
                ),
            )
        }
        if (result == NexusSdkResult.SENT) {
            noticeShown = true
            noticeMode = AssistantNoticeMode.ENGAGED
            clearBandDecorations()
            return true
        }
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        return false
    }

    private fun hideNoticeIfShown() {
        stopKeepalive()
        if (!noticeShown) {
            noticeMode = AssistantNoticeMode.NONE
            return
        }
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        clearBandDecorations()
        notices.hide()
    }

    private fun startKeepalive() {
        if (keepaliveJob != null) return
        keepaliveJob = scope.launch {
            while (true) {
                delay(keepaliveIntervalMs)
                val body = lastInFlightBody
                if (body == null || !noticeShown || !useNoticeBand()) break
                if (lastInFlightUsesLines) {
                    showOrUpdateAnswerNotice(body)
                } else {
                    showOrUpdateNotice(body)
                }
            }
            keepaliveJob = null
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
        lastInFlightBody = null
        lastInFlightUsesLines = false
    }

    private fun renderPendingTranscript() {
        val body = pendingTranscriptBody ?: return
        pendingTranscriptBody = null
        if (useNoticeBand()) {
            lastInFlightBody = body
            lastInFlightUsesLines = false
            showOrUpdateNotice(body)
            startKeepalive()
        }
    }

    private fun flushPendingTranscript() {
        transcriptUpdateJob?.cancel()
        transcriptUpdateJob = null
        renderPendingTranscript()
    }

    private fun discardPendingTranscript() {
        transcriptUpdateJob?.cancel()
        transcriptUpdateJob = null
        pendingTranscriptBody = null
    }

    private fun showCard(
        lines: List<String>,
        forceShow: Boolean,
        footer: String? = null,
        contentKey: String? = null,
    ): NexusSdkResult {
        val result = renderer.showCard(lines, forceShow, footer, contentKey)
        if (result == NexusSdkResult.SENT) {
            surfaceShown = true
        }
        return result
    }

    private fun startNewState(flushTranscript: Boolean = true): Long {
        if (flushTranscript) {
            flushPendingTranscript()
        } else {
            discardPendingTranscript()
        }
        // After the flush: the transcript still belongs to the listening band, chip and all.
        endListening()
        noticeStateVersion += 1
        noticeHideJob?.cancel()
        noticeHideJob = null
        // Whatever the voice was reading belongs to the state we are leaving. Callers that own an
        // answer claim it again right after; everyone else gets a clean slate, so a late utterance
        // cannot hold open a band that has moved on.
        spokenAnswerBody = null
        return noticeStateVersion
    }

    private fun truncateTranscriptTail(text: String): String {
        val normalized = normalizeNoticeText(text)
        if (normalized.length <= TRANSCRIPT_TAIL_CHARS + ELLIPSIS.length + 1) return normalized
        return "$ELLIPSIS ${normalized.takeLast(TRANSCRIPT_TAIL_CHARS).trimStart()}"
    }

    /**
     * Paragraph breaks are preserved as `\n` inside one `body` string rather than a
     * `lines` array: `lines` caps out at [NoticeSurfaceContract.MAX_LINES] entries on
     * top of the shared character budget, which a Hermes-style answer of many short
     * paragraphs/bullets hits well before the budget is used. `body` only enforces
     * the character budget, so the glasses' own notice pagination gets to page
     * through everything the budget allows instead of the tail being dropped early.
     */
    private fun truncateAnswerBody(text: String): String {
        val normalizedLines = text
            .split(Regex("\\n+"))
            .map(::normalizeNoticeText)
            .filter(String::isNotEmpty)
            .ifEmpty { return ELLIPSIS }
        val joined = normalizedLines.joinToString("\n")
        if (joined.length <= MAX_NOTICE_BODY_CHARS) return joined
        return joined
            .take(MAX_NOTICE_BODY_CHARS - ELLIPSIS.length)
            .trimEnd() + ELLIPSIS
    }

    private fun truncateNoticeHead(text: String): String {
        val normalized = normalizeNoticeText(text).ifBlank { ELLIPSIS }
        if (normalized.length <= MAX_NOTICE_BODY_CHARS) return normalized
        return normalized
            .take(MAX_NOTICE_BODY_CHARS - ELLIPSIS.length)
            .trimEnd() + ELLIPSIS
    }

    private fun normalizeNoticeText(text: String): String =
        text.replace(Regex("\\s+"), " ").trim()

    /**
     * A long answer earns its reading time: roughly a character's worth of
     * milliseconds each, within the band's contract clamp. The TTL restarts on
     * every accepted update, so streaming keeps the band alive on its own.
     */
    private fun answerTtlMs(body: String): Long =
        (body.length * ANSWER_TTL_PER_CHAR_MS).coerceIn(ANSWER_TTL_MIN_MS, ANSWER_TTL_MAX_MS)

    internal companion object {
        const val LAUNCHER_HINT_DELAY_MS = 400L
        const val ERROR_NOTICE_DURATION_MS = 2_500L
        const val TRANSCRIPT_UPDATE_INTERVAL_MS = 300L
        const val NOTICE_KEEPALIVE_INTERVAL_MS = 3_000L
        const val ANSWER_TTL_PER_CHAR_MS = 75L
        const val ANSWER_TTL_MIN_MS = 8_000L
        const val ANSWER_TTL_MAX_MS = 20_000L

        /** What an already-heard answer is worth on screen: a look at the tail, then gone. */
        const val ANSWER_SPOKEN_GRACE_MS = 4_000L
        const val MAX_NOTICE_BODY_CHARS = NoticeSurfaceContract.MAX_BODY_CHARS
        const val TRANSCRIPT_TAIL_CHARS = 200
        const val LAUNCHER_HINT = "Press the assist button, then speak."
        val ANCHOR_LINES = listOf("Ask out loud.")
        val TYPE_FIRST_ANCHOR_LINES = listOf("Tap to type a question.")
        const val TYPE_FIRST_LAUNCHER_HINT = "Press the assist button to type a question."
        const val ANCHOR_FOOTER = "tap to ask again · swipe for options"
        const val OPTIONS_SUBTITLE = "Options"
        const val ANCHOR_CONTENT_KEY = "anchor"
        const val HOLDER_CONTENT_KEY = "holder"

        const val HOLDER_RECHECK_MS = 10_000L
        const val OPTIONS_CONTENT_KEY = "options"
        const val NOTICE_TITLE = "Assistant"
        const val ELLIPSIS = "…"
        const val LISTENING_BODY = "Listening…"
        const val TRANSCRIBING_BODY = "Transcribing…"

        const val ACTION_TYPE = "type"

        /** Switches a question being spoken to the typed field; see armTypeChip. */
        val TYPE_ACTIONS = listOf(NexusNoticeAction(ACTION_TYPE, "keyboard", "Type"))

        /** Comfortably past the 433 ms temple-pad bounce measured after a real tap. */
        const val TYPE_CHIP_ARM_DELAY_MS = 1_200L

        /** The field is on the band itself; the footer only says how to finish. */
        const val TYPING_FOOTER = "Enter to send · Back to cancel"
        const val TYPING_TTL_MS = 30_000L
        const val TYPING_KEEPALIVE_INTERVAL_MS = 12_000L

        // Typed into the band, under its title. A glasses hub that cannot still opens the
        // field as a card, so nothing is lost on an older one.
        val QUESTION_FIELD = EditableSurfaceField(
            placeholder = "Type your question…",
            submitLabel = "Ask",
            inNotice = true,
        )
        const val QUESTION_FIELD_FAILED = "Couldn't open the keyboard"
        const val SCREEN_BUSY = "Screen busy — try again"
        const val SURFACE_BUSY = "SURFACE_BUSY"
    }
}
