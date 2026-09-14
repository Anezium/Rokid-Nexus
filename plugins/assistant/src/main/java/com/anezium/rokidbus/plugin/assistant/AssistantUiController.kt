package com.anezium.rokidbus.plugin.assistant

import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusSdkResult
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

    fun showCard(
        lines: List<String>,
        forceShow: Boolean,
        footer: String? = null,
    ): NexusSdkResult

    /** One card of list rows — the options menu — with its own subtitle and footer. */
    fun showRichCard(
        subtitle: String?,
        lines: List<NexusCardLine>,
        footer: String?,
        forceShow: Boolean,
    ): NexusSdkResult
}

internal enum class AssistantNoticeMode {
    NONE,
    ENGAGED,
    PASSIVE,
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
) {
    private var launcherHintJob: Job? = null
    private var noticeHideJob: Job? = null
    private var transcriptUpdateJob: Job? = null
    private var keepaliveJob: Job? = null
    private var lastInFlightBody: String? = null
    private var lastInFlightUsesLines = false

    /** The answer the voice is about to read, kept so speech can hold its band open. */
    private var spokenAnswerBody: String? = null
    private var pendingTranscriptBody: String? = null
    private var noticeStateVersion = 0L
    private var surfaceShown = false
    private var noticeShown = false
    private var noticeMode = AssistantNoticeMode.NONE
    private var answerCardStarted = false

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
        get() = renderer.supportsNoticeSurface && (!surfaceShown || anchorShown)

    internal val isEngagedNoticeEpisode: Boolean
        get() = noticeShown && noticeMode == AssistantNoticeMode.ENGAGED

    fun onOpen() {
        resetForOpen()
        launcherHintJob = scope.launch {
            delay(launcherHintDelayMs)
            launcherHintJob = null
            startNewState()
            hideNoticeIfShown()
            showCard(
                lines = listOf(LAUNCHER_HINT),
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
        val shown = showCard(ANCHOR_LINES, forceShow = true, footer = ANCHOR_FOOTER) == NexusSdkResult.SENT
        anchorShown = shown
        return shown
    }

    /** The options menu replaces whatever band was up; its card is the render target while open. */
    fun showOptions(view: AssistantOptionsMenu.View, forceShow: Boolean) {
        cancelLauncherHint()
        stopKeepalive()
        startNewState()
        hideNoticeIfShown()
        val result = renderer.showRichCard(
            subtitle = OPTIONS_SUBTITLE,
            lines = listOf(NexusCardLine(text = view.text, sub = view.sub, selected = true)),
            footer = view.footer,
            forceShow = forceShow || !surfaceShown,
        )
        if (result == NexusSdkResult.SENT) surfaceShown = true
    }

    /** Back from the options menu: the anchor card returns and the band is the target again. */
    fun restoreAnchor() {
        val result = showCard(ANCHOR_LINES, forceShow = !surfaceShown, footer = ANCHOR_FOOTER)
        anchorShown = result == NexusSdkResult.SENT
    }

    fun onClose() {
        cancelLauncherHint()
        startNewState(flushTranscript = false)
        hideNoticeIfShown()
        surfaceShown = false
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        answerCardStarted = false
        anchorShown = false
    }

    private fun resetForOpen() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        surfaceShown = false
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        answerCardStarted = false
        anchorShown = false
    }

    fun cancelLauncherHint() {
        launcherHintJob?.cancel()
        launcherHintJob = null
    }

    fun beginGestureFlow() {
        cancelLauncherHint()
        discardPendingTranscript()
    }

    fun showTransient(
        body: String,
        legacyForceShow: Boolean = false,
    ) {
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
        val body = spokenAnswerBody ?: return
        spokenAnswerBody = null
        stopKeepalive()
        if (!useNoticeBand() || !noticeShown) return
        // A glance, not a second reading: the wearer just heard the whole thing, so the band owes
        // them only long enough to catch the tail. Handing back the length-based TTL here would
        // pin a long answer on the display for another twenty seconds after the voice had moved
        // on, which reads as the band being stuck.
        showOrUpdateAnswerNotice(body, ttlMs = ANSWER_SPOKEN_GRACE_MS)
    }

    fun onSurfaceHidden() {
        surfaceShown = false
        answerCardStarted = false
        anchorShown = false
    }

    /**
     * An Ink page produced for this answer now owns its visual presentation.
     * Retire the in-flight notice without marking the card tier active: later
     * errors are still discrete notices and must not replace the Ink surface.
     */
    fun onInkAnswerShown() {
        cancelLauncherHint()
        stopKeepalive()
        startNewState(flushTranscript = false)
        hideNoticeIfShown()
    }

    fun onNoticeClosed(reason: NexusNoticeCloseReason) {
        stopKeepalive()
        startNewState(flushTranscript = false)
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
        if (reason == NexusNoticeCloseReason.USER) {
            cancelPipeline()
            resetCapture()
        }
    }

    private fun useNoticeBand(): Boolean = isNoticeBandMode

    private fun showOrUpdateNotice(
        body: String,
        mode: AssistantNoticeMode = AssistantNoticeMode.ENGAGED,
        ttlMs: Long? = null,
    ): Boolean {
        val safeBody = truncateNoticeHead(body)
        val modeUpdate = mode.takeIf { !noticeShown || it != noticeMode }
        val result = if (noticeShown) {
            renderer.updateNotice(
                NexusNoticeUpdate(
                    body = safeBody,
                    interactive = modeUpdate?.let { it == AssistantNoticeMode.ENGAGED },
                    ttlMs = ttlMs,
                ),
            )
        } else {
            renderer.showNotice(
                NexusNotice(
                    title = NOTICE_TITLE,
                    body = safeBody,
                    interactive = mode == AssistantNoticeMode.ENGAGED,
                    ttlMs = ttlMs,
                ),
            )
        }
        if (result == NexusSdkResult.SENT) {
            noticeShown = true
            noticeMode = mode
            return true
        }
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
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
        val result = if (noticeShown) {
            renderer.updateNotice(
                NexusNoticeUpdate(
                    interactive = modeUpdate?.let { true },
                    body = truncatedBody,
                    ttlMs = ttlMs,
                ),
            )
        } else {
            renderer.showNotice(
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
            return true
        }
        noticeShown = false
        noticeMode = AssistantNoticeMode.NONE
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
        renderer.hideNotice()
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
    ): NexusSdkResult {
        val result = renderer.showCard(lines, forceShow, footer)
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
        const val ANCHOR_FOOTER = "tap to ask again · swipe for options"
        const val OPTIONS_SUBTITLE = "Options"
        const val NOTICE_TITLE = "Assistant"
        const val ELLIPSIS = "…"
    }
}
