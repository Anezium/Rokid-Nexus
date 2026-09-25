package com.anezium.rokidbus.plugin.relay

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusNotice
import com.anezium.rokidbus.client.plugin.NexusNoticeAction
import com.anezium.rokidbus.client.plugin.NexusNoticeCloseReason
import com.anezium.rokidbus.client.plugin.NexusNoticeImage
import com.anezium.rokidbus.client.plugin.NexusNoticeUpdate
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.client.plugin.speechSession
import com.anezium.rokidbus.client.plugin.surfaceSession
import com.anezium.rokidbus.client.plugin.ttsSession
import com.anezium.rokidbus.shared.EditableSurfaceField
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import java.util.ArrayDeque

/** One bus connection per live notice/reply exchange; it closes when that band closes. */
internal class RelayNoticeRuntime(context: Context) : NexusPluginCallbacks {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val essentialUpdates = ArrayDeque<NexusNoticeUpdate>()
    private val settings = RelaySettings(appContext)

    private var client: NexusPluginClient? = null
    private var typingSurface: NexusSurfaceSession? = null
    private var pendingShow: ReplyRepository.PendingReply? = null
    private var pendingShowStartedAtMs = 0L
    private var pendingShowWasBlocked = false
    private var currentReply: ReplyRepository.PendingReply? = null
    private var currentTranscript: String? = null
    private var activeNotice = false
    private var showGeneration = 0
    private var speechGeneration = 0
    private var speechFinalReceived = false
    private var speech: NexusSpeechSession? = null
    private var tts: NexusTtsSession? = null
    private var activeTtsUtteranceId: String? = null
    private var pendingPartial: NexusNoticeUpdate? = null
    private var updateDrainScheduled = false
    private var lastNoticeMessageAtMs = Long.MIN_VALUE
    private var sendDeadlineMs: Long? = null
    private var inputKeepaliveRunnable: Runnable? = null
    private var typeChipRunnable: Runnable? = null
    private var typingOpenedAtShowGeneration: Int? = null
    // Identifies one open attempt distinctly from the next even when both share
    // a showGeneration (cancel, then Reply again on the same still-active
    // notice) — see onTypingSurfaceRejected. currentTypingAttempt is null
    // whenever no field is open, same lifecycle as typingOpenedAtShowGeneration.
    private var typingAttemptCounter = 0
    private var currentTypingAttempt: Int? = null
    private var deferredShow: ReplyRepository.PendingReply? = null

    fun show(reply: ReplyRepository.PendingReply) = onMain {
        // The inbox owns the bus while it is open, and it is already showing
        // this conversation — the capture reached the repository before us.
        if (NotificationControl.inboxOpen) {
            Log.i(TAG, "band suppressed: inbox has the bus")
            return@onMain
        }
        if (isComposingReply()) {
            // Replacing the band now would discard a reply the wearer is still dictating,
            // typing, or has already captured and is about to send — the same in-flight work
            // isTypingCommitStale protects from being misdelivered, here protected from being
            // silently thrown away instead. Hold the newest one and show it the moment the
            // current exchange resolves (sent, cancelled, or dismissed); see
            // applyDeferredShowOrElse.
            deferredShow = reply
            Log.i(TAG, "showDeferred: composing in progress")
            return@onMain
        }
        // Any reply held back for an earlier compose is superseded by this one
        // going up now through the ordinary path — applying it later (the Sent
        // linger, or a future onNoticeClosed) would replace what's about to show
        // with something older than it.
        deferredShow = null
        showGeneration += 1
        val generation = showGeneration
        val nowMs = SystemClock.elapsedRealtime()
        invalidateSpeech()
        // A typing field left open from the reply this is about to replace must not go on
        // accepting keystrokes for a conversation that is no longer current — see
        // isTypingCommitStale. Hiding it here, not just rejecting its eventual commit, keeps the
        // wearer from typing into a field that has already been silently discarded.
        hideTypingSurface()
        stopReadAloud()
        essentialUpdates.clear()
        pendingPartial = null
        currentReply = reply
        currentTranscript = null
        pendingShow = reply
        pendingShowStartedAtMs = nowMs
        pendingShowWasBlocked = false
        val captureAgeMs = (System.currentTimeMillis() - reply.capturedAtMs).coerceAtLeast(0L)
        Log.i(TAG, "showRequested generation=$generation captureAgeMs=$captureAgeMs")

        if (client == null) {
            client = NexusPluginClient.create(appContext, PLUGIN_ID, this).also(NexusPluginClient::connect)
        }
        tryShowPending()
        main.postDelayed({
            val elapsedNowMs = SystemClock.elapsedRealtime()
            if (pendingShow != null && shouldAbandonPendingShow(
                    timerGeneration = generation,
                    activeGeneration = showGeneration,
                    startedAtMs = pendingShowStartedAtMs,
                    nowMs = elapsedNowMs,
                )
            ) {
                abandonPendingShow(elapsedNowMs)
            }
        }, REPLAY_WINDOW_MS)
    }

    fun shutdown() = onMain {
        client?.hideNotice()
        closeClient()
    }

    override fun onOpen() = Unit

    override fun onClose() = onMain {
        // The client object may register again after a hub reconnect. Its TTS
        // session belongs to the registration that created it, never the next one.
        closeReadAloudSession()
    }

    override fun onInput(event: NexusInputEvent) = Unit

    override fun onLinkState(state: Int) = onMain { tryShowPending() }

    override fun onRegistrationState(result: Int) = onMain {
        when {
            result == PluginRegistrationResult.APPROVED -> tryShowPending()
            isTerminalRegistrationResult(result) -> {
                Log.w(TAG, "registration terminal result=$result")
                closeClient()
            }
            else -> {
                Log.i(TAG, "registration retryable result=$result")
                tryShowPending()
            }
        }
    }

    override fun onNoticeAction(id: String) = onMain {
        // The wearer decided, so the clock stops rather than firing behind them.
        cancelSendCountdown()
        stopReadAloud()
        when (id) {
            ACTION_SHOW -> revealNotice()
            ACTION_REPLY, ACTION_RETRY -> if (settings.replyByTyping()) startTyping() else startListening()
            ACTION_TYPE -> startTyping()
            ACTION_SEND -> sendConfirmedReply()
            ACTION_DISMISS, ACTION_CANCEL -> dismissNotice()
        }
    }

    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {
        // Back or the band's own TTL isn't the end of the story if a reply
        // arrived while the wearer was composing and got held back for exactly
        // this moment — that notification still deserves its own band, not to
        // be discarded along with the one that just closed.
        //
        // closeClient() runs unconditionally first, not just when nothing is
        // deferred: this notice is gone either way, and closing it is what
        // resets the composing state (typing field, speech, transcript) that
        // caused the deferral in the first place. Calling show(deferred)
        // without that reset first hit isComposingReply() still true — Back
        // pressed while typing closes the notice but leaves the field open —
        // so show() just deferred it again and returned, skipping closeClient()
        // entirely: notice gone, card still up, keepalive still posting to it.
        val deferred = deferredShow
        deferredShow = null
        closeClient()
        if (deferred != null) show(deferred)
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) = Unit

    /**
     * The wearer submitted or cancelled the typed-reply field opened by
     * [startTyping]. Lands at exactly the point [onSpeechFinal] does once a
     * transcript is ready — everything downstream (confirm chips, the send
     * countdown, [sendConfirmedReply]) doesn't care which input method got
     * [currentTranscript] there.
     */
    override fun onSurfaceTextCommitted(surfaceId: String, text: String, cancelled: Boolean) = onMain {
        // The wire id round-trips as "pluginId:localSurfaceId" (confirmed on
        // device: "relay:reply"), not the bare local id this plugin shows the
        // card under — same "$pluginId:$local" shape notice/ink events use.
        if (surfaceId.substringAfter(':', surfaceId) != TYPING_SURFACE_ID || !activeNotice) return@onMain
        val openedAtGeneration = typingOpenedAtShowGeneration
        if (openedAtGeneration == null || isTypingCommitStale(openedAtGeneration, showGeneration)) {
            // A new notification replaced the reply this field was opened for while the wearer
            // was still typing (show() already hid the field for this). Applying this text to
            // whatever notification is current now would send it to the wrong conversation.
            Log.i(TAG, "typed reply commit ignored: stale generation")
            return@onMain
        }
        stopInputKeepalive()
        hideTypingSurface()
        if (cancelled) {
            applyDeferredShowOrElse {
                queueEssential(
                    NexusNoticeUpdate(
                        footer = "",
                        interactive = true,
                        actions = INITIAL_ACTIONS,
                        ttlMs = DECISION_TTL_MS,
                    ),
                    dropPartial = true,
                )
            }
            return@onMain
        }
        val reviewed = NotificationTextExtractor.trimFromTop(text, NoticeSurfaceContract.MAX_BODY_CHARS)
        if (reviewed.isBlank()) {
            applyDeferredShowOrElse { queueSpeechFailure("Empty reply") }
            return@onMain
        }
        currentTranscript = reviewed
        pendingPartial = null
        queueEssential(
            NexusNoticeUpdate(
                body = reviewed,
                footer = "",
                interactive = true,
                actions = confirmActions(startSendCountdown(reviewed)),
                ttlMs = DECISION_TTL_MS,
            ),
            dropPartial = true,
        )
    }

    private fun tryShowPending() {
        val reply = pendingShow ?: return
        val nowMs = SystemClock.elapsedRealtime()
        if (isReplayWindowExpired(pendingShowStartedAtMs, nowMs)) {
            abandonPendingShow(nowMs)
            return
        }
        val currentClient = client
        if (currentClient == null || !currentClient.isApproved) {
            markShowBlocked("registration", nowMs)
            return
        }
        if (!currentClient.hasCapability(PluginCapability.SURFACES)) {
            markShowBlocked("grant", nowMs)
            return
        }
        if (!currentClient.supportsNoticeSurface) {
            markShowBlocked("capability", nowMs)
            return
        }

        val hidden = settings.hideNoticeText()
        val lines = if (hidden) {
            listOf(RelayPrivacy.HIDDEN_BODY)
        } else {
            messageLines(reply.content.renderedText)
        }
        // An image is message content; while the text is hidden, do not send its
        // preview bytes — the no-image overload keeps the band to the sender.
        val preview = if (hidden) null else reply.imagePreview
        val image = preview?.let {
            NexusNoticeImage(
                contentKey = it.id,
                mimeType = it.mimeType,
                pixelWidth = it.width,
                pixelHeight = it.height,
            )
        }
        val shownCharacterCount = lines.sumOf { it.length }
        val notice = NexusNotice(
            title = reply.content.title,
            // The extractor already separates messages with newlines; sending them
            // as lines is what stops the band flattening a conversation into one
            // paragraph. Newest win when a thread runs longer than the tier allows,
            // for the same reason the character trim drops from the top.
            lines = lines,
            footer = reply.footer.takeIf(String::isNotBlank),
            actions = if (hidden) HIDDEN_ACTIONS else INITIAL_ACTIONS,
            image = image?.takeIf { currentClient.supportsImageSurface },
            wakeDisplay = true,
            backdrop = settings.noticeBackdrop(),
            ttlMs = noticeTtlMs(settings.noticeDisplaySeconds(), settings.noticeScalesWithLength(), shownCharacterCount),
        )
        val result = if (notice.image != null && preview != null) {
            currentClient.showNotice(notice, preview.bytes)
        } else {
            currentClient.showNotice(notice)
        }
        Log.i(
            TAG,
            "notice show generation=$showGeneration result=$result textChars=$shownCharacterCount " +
                "imageBytes=${preview?.bytes?.size ?: 0}",
        )
        if (result == NexusSdkResult.SENT) {
            val delayMs = pendingShowAgeMs(pendingShowStartedAtMs, SystemClock.elapsedRealtime())
            val wasBlocked = pendingShowWasBlocked
            pendingShow = null
            pendingShowStartedAtMs = 0L
            pendingShowWasBlocked = false
            activeNotice = true
            lastNoticeMessageAtMs = SystemClock.uptimeMillis()
            if (wasBlocked) Log.i(TAG, "showReplayed generation=$showGeneration delayMs=$delayMs")
            readNoticeAloud(reply)
        } else {
            val blockReason = RETRYABLE_SHOW_BLOCK_REASONS[result]
            if (blockReason != null) {
                markShowBlocked(blockReason, SystemClock.elapsedRealtime())
            } else {
                closeClient()
            }
        }
    }

    private fun markShowBlocked(reason: String, nowMs: Long) {
        pendingShowWasBlocked = true
        Log.i(
            TAG,
            "showBlocked generation=$showGeneration reason=$reason " +
                "pendingAgeMs=${pendingShowAgeMs(pendingShowStartedAtMs, nowMs)}",
        )
    }

    private fun abandonPendingShow(nowMs: Long) {
        if (pendingShow == null) return
        Log.w(
            TAG,
            "showAbandoned generation=$showGeneration " +
                "pendingAgeMs=${pendingShowAgeMs(pendingShowStartedAtMs, nowMs)}",
        )
        closeClient()
    }

    private fun readNoticeAloud(reply: ReplyRepository.PendingReply) {
        val text = RelayReadAloud.textFor(
            enabled = settings.readAloud(),
            senderOnly = settings.hideNoticeText(),
            sender = reply.content.title,
            renderedThread = reply.content.renderedText,
        ) ?: return
        val currentClient = client ?: return
        val utteranceId = "notice-$showGeneration"
        val session = tts ?: currentClient.ttsSession(readAloudCallbacks).also { tts = it }
        activeTtsUtteranceId = utteranceId
        val result = session.speak(text, utteranceId)
        Log.i(TAG, "tts speak utteranceId=$utteranceId result=$result textChars=${text.length}")
        if (result != NexusSdkResult.SENT && activeTtsUtteranceId == utteranceId) {
            activeTtsUtteranceId = null
        }
    }

    private val readAloudCallbacks = object : NexusTtsCallbacks {
        override fun onTtsStarted(utteranceId: String) = onMain {
            Log.i(TAG, "tts started utteranceId=$utteranceId")
            if (utteranceId != activeTtsUtteranceId || !activeNotice) return@onMain
            queueEssential(
                NexusNoticeUpdate(ttlMs = NoticeSurfaceContract.MAX_TTL_MS),
                dropPartial = false,
            )
        }

        override fun onTtsDone(
            utteranceId: String,
            reason: NexusTtsDoneReason,
        ) = onMain {
            Log.i(TAG, "tts done utteranceId=$utteranceId reason=$reason")
            if (utteranceId != activeTtsUtteranceId) return@onMain
            activeTtsUtteranceId = null
            if (reason == NexusTtsDoneReason.CANCELLED || !activeNotice) return@onMain
            queueEssential(
                NexusNoticeUpdate(ttlMs = DECISION_TTL_MS),
                dropPartial = false,
            )
        }
    }

    private fun stopReadAloud() {
        val utteranceId = activeTtsUtteranceId ?: return
        activeTtsUtteranceId = null
        val result = tts?.stop() ?: return
        Log.i(TAG, "tts stop utteranceId=$utteranceId result=$result")
    }

    private fun closeReadAloudSession() {
        stopReadAloud()
        tts?.close()
        tts = null
    }

    private fun startListening() {
        val currentClient = client ?: return
        if (!activeNotice || currentReply == null) return
        invalidateSpeech()
        hideTypingSurface()
        currentTranscript = null
        speechFinalReceived = false
        // Deliberately offers nothing as listening starts, and says so; the one
        // chip dictation ever shows is armed later, by armTypeChip.
        //
        // Confirming spent the band's one answer, so the row is gone. Putting a
        // Cancel chip back re-arms the band — and a temple pad that does not
        // always send one press per touch then answers the new question with the
        // bounce from the old one: measured on hardware, a tap on Reply was
        // followed 433 ms later by a second action that closed the band. Guarding
        // against that inside the plugin is worse still, because the glasses hub
        // has already spent the answer by the time we see it, leaving a band that
        // claims nothing and a wearer whose taps fall through to the launcher.
        //
        // Back needs none of this: it dismisses whenever a band is visible,
        // answered or not. So the way out of dictation is Back, and the footer
        // says it. The explicit TTL is what stops a footer this short from being
        // handed the four-second floor.
        queueEssential(
            NexusNoticeUpdate(footer = "Listening… · Back to cancel", ttlMs = DECISION_TTL_MS),
            dropPartial = true,
        )
        startInputKeepalive {
            NexusNoticeUpdate(footer = "Listening… · Back to cancel", ttlMs = DECISION_TTL_MS)
        }

        val generation = speechGeneration
        if (currentClient.supportsEditableSurface) armTypeChip(generation)
        val newSpeech = currentClient.speechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) = Unit

            override fun onSpeechState(state: NexusSpeechState) = Unit

            override fun onSpeechPartial(text: String) = onMain {
                if (generation != speechGeneration || text.isBlank()) return@onMain
                queuePartial(
                    NexusNoticeUpdate(
                        footer = NotificationTextExtractor.trimFromTop(
                            text,
                            NoticeSurfaceContract.MAX_FOOTER_CHARS,
                        ),
                    ),
                )
            }

            override fun onSpeechFinal(text: String) = onMain {
                if (generation != speechGeneration) return@onMain
                stopInputKeepalive()
                cancelTypeChip()
                if (text.isBlank()) {
                    queueSpeechFailure("Didn't catch that")
                    return@onMain
                }
                speechFinalReceived = true
                // Trim once, then show and send the same string. Keeping the
                // full transcript while displaying a trimmed one meant the
                // recipient got opening words the wearer had never seen —
                // approving the end of a long dictation quietly sent the start
                // of it too, corrections and all.
                val reviewed = NotificationTextExtractor.trimFromTop(
                    text,
                    NoticeSurfaceContract.MAX_BODY_CHARS,
                )
                currentTranscript = reviewed
                pendingPartial = null
                queueEssential(
                    NexusNoticeUpdate(
                        body = reviewed,
                        // Cleared, not relabelled. The chips already say Send,
                        // Retry and Cancel; a footer repeating "Review, then
                        // send" spends a line of the band telling the wearer
                        // what they are already looking at, on the one screen
                        // where the transcript itself is what they need to read.
                        footer = "",
                        actions = confirmActions(startSendCountdown(reviewed)),
                        ttlMs = DECISION_TTL_MS,
                    ),
                    dropPartial = true,
                )
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) = onMain {
                if (generation != speechGeneration) return@onMain
                speech = null
                if (speechFinalReceived && reason == NexusSpeechStopReason.COMPLETED) return@onMain
                // The label, never error.kind: the kind is an enum name meant
                // for a bug report, and the band is not a bug report.
                applyDeferredShowOrElse { queueSpeechFailure(speechReasonLabel(reason)) }
            }
        })
        speech = newSpeech
        val result = newSpeech.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            applyDeferredShowOrElse { queueSpeechFailure(result.name) }
        }
    }

    /**
     * The typed alternative to [startListening], gated behind
     * [RelaySettings.replyByTyping]. Opens one bounded editable field on the
     * foreground surface tier — the notice band itself can never host a
     * focusable field (see plan notes) — and waits for
     * [onSurfaceTextCommitted]. The band's footer changes the same way it
     * would while listening; the underlying message keeps whatever paging it
     * already had.
     */
    private fun startTyping() {
        val currentClient = client ?: return
        if (!activeNotice || currentReply == null) return
        if (!currentClient.supportsEditableSurface) {
            // An older glasses hub shows the card fine but silently ignores the
            // `editable` field on it — there would be nothing wrong-looking to
            // recover from, just a field that can never be typed into or
            // committed. Falling back here, before that card ever opens, is the
            // only point this can still be caught.
            startListening()
            return
        }
        invalidateSpeech()
        currentTranscript = null
        // The foreground editable card is about to own confirm/direction keys
        // (Enter submits, arrows move the caret). An interactive notice claims
        // those first, at the accessibility layer, ahead of the card ever
        // seeing them — so this band steps back from that claim for as long
        // as the field is open.
        queueEssential(
            NexusNoticeUpdate(
                footer = TYPING_FOOTER,
                interactive = false,
                ttlMs = DECISION_TTL_MS,
            ),
            dropPartial = true,
        )
        startInputKeepalive {
            NexusNoticeUpdate(
                footer = TYPING_FOOTER,
                interactive = false,
                ttlMs = DECISION_TTL_MS,
            )
        }
        // Bound to the reply this field is being opened for: see isTypingCommitStale.
        typingOpenedAtShowGeneration = showGeneration
        val attempt = ++typingAttemptCounter
        currentTypingAttempt = attempt
        val session = typingSurface ?: currentClient.surfaceSession(TYPING_SURFACE_ID).also {
            typingSurface = it
        }
        // showCard() returning SENT only means the hub accepted the Binder call —
        // it can still reject the card itself (e.g. SURFACE_BUSY when another
        // plugin owns the foreground surface) in a reply that arrives after this
        // call already returned. Without this, the band was left stuck on
        // non-interactive "Typing…" with every new notification deferred behind
        // a field that never actually opened, with nothing left to resolve it
        // short of the wearer pressing Back on a field they can't see.
        session.onRejected = { code -> onTypingSurfaceRejected(attempt, code) }
        val result = session.showCard(
            NexusCard(
                title = "Reply",
                lines = emptyList(),
                editable = EditableSurfaceField(
                    placeholder = "Type your reply…",
                    submitLabel = "Send",
                    // Typed into the band itself, under the message it answers.
                    // A hub that cannot still opens the card, so nothing is lost.
                    inNotice = true,
                ),
            ),
        )
        Log.i(TAG, "typing show result=$result")
        if (result != NexusSdkResult.SENT) {
            // The field never actually opened: undo the composing state claimed above so a
            // reply held back by show() isn't stuck deferred forever with nothing left to
            // resolve it.
            hideTypingSurface()
            applyDeferredShowOrElse { queueSpeechFailure(result.name) }
        }
    }

    /**
     * A field this typing session opened was rejected after the fact. Ignored
     * once that attempt is no longer the open one — cancelled, sent, or
     * superseded by a fresh Reply on the same still-active notice.
     */
    private fun onTypingSurfaceRejected(attempt: Int, code: String) {
        if (currentTypingAttempt != attempt) return
        hideTypingSurface()
        applyDeferredShowOrElse { queueSpeechFailure(rejectionMessage(code)) }
    }

    /** The wire code is meant for logs, not the band — seen live as a bare "SURFACE_BUSY". */
    private fun rejectionMessage(code: String): String = when (code) {
        "SURFACE_BUSY" -> "Screen busy — try again"
        else -> "Couldn't open reply"
    }

    private fun hideTypingSurface() {
        typingSurface?.hide()
        typingOpenedAtShowGeneration = null
        currentTypingAttempt = null
    }

    /** See [isComposingReplyState]; [show] holds off replacing the band while this is true. */
    private fun isComposingReply(): Boolean = isComposingReplyState(
        speechActive = speech != null,
        typingFieldOpen = typingOpenedAtShowGeneration != null,
        hasUnsentTranscript = !currentTranscript.isNullOrBlank(),
    )

    /** Applies a reply that arrived while [isComposingReply] was true, or runs [fallback]. */
    private fun applyDeferredShowOrElse(fallback: () -> Unit) {
        val deferred = deferredShow
        if (deferred == null) {
            fallback()
            return
        }
        deferredShow = null
        show(deferred)
    }

    private fun sendConfirmedReply() {
        val reply = currentReply ?: return
        val transcript = currentTranscript
        if (transcript.isNullOrBlank()) {
            queueSpeechFailure("Didn't catch that")
            return
        }
        when (val result = ReplyRepository.sendReply(appContext, reply.id, transcript)) {
            ReplySendResult.Sent -> {
                currentTranscript = null
                // Confirm it, then take it away. The exchange is over: the band has
                // nothing left to say and nothing left to ask, and an answered band
                // that lingers claims no input while it sits there, so every tap the
                // wearer aims at it falls through to whatever is behind. Waiting for
                // a TTL to notice that would leave exactly that gap.
                queueEssential(
                    NexusNoticeUpdate(footer = "", actions = SENT_ACTIONS),
                    dropPartial = true,
                )
                // Bound to this exchange's generation, not just activeNotice: a
                // fresh notification arriving during the linger already went
                // through show() above and is the current band by the time this
                // fires — applying whatever deferredShow held then would replace
                // it with something older, even though show() cleared any stale
                // one already. Only fire this exchange's own dismiss-or-apply.
                val sentGeneration = showGeneration
                main.postDelayed(
                    {
                        if (activeNotice && showGeneration == sentGeneration) {
                            applyDeferredShowOrElse { dismissNotice() }
                        }
                    },
                    SENT_LINGER_MS,
                )
            }
            ReplySendResult.Missing -> queueSendFailure("Notification gone")
            ReplySendResult.Blank -> queueSendFailure("Empty reply")
            ReplySendResult.NoFreeFormInput -> queueSendFailure("Reply unavailable")
            is ReplySendResult.Failed -> queueSendFailure(result.causeClass)
        }
    }

    /**
     * Sends the transcript on its own unless the wearer steps in.
     *
     * Same shape and same numbers as the inbox: the wearer has just spoken and
     * is looking at their words, so asking them to confirm what they can read
     * buys nothing. Visible on the chip and cancellable for its whole length,
     * which is what keeps it from being a blind send. Returns the seconds the
     * first chip should show.
     */
    private fun startSendCountdown(text: String): Int {
        cancelSendCountdown()
        val words = text.trim().split(WHITESPACE).count(String::isNotBlank)
        val span = (SEND_BASE_MS + words * SEND_MS_PER_WORD).coerceIn(SEND_MIN_MS, SEND_MAX_MS)
        sendDeadlineMs = SystemClock.uptimeMillis() + span
        main.postDelayed(sendTick, SEND_TICK_MS)
        return ((span + 999L) / 1000L).toInt()
    }

    private fun cancelSendCountdown() {
        sendDeadlineMs = null
        main.removeCallbacks(sendTick)
    }

    private val sendTick = object : Runnable {
        override fun run() {
            val deadline = sendDeadlineMs ?: return
            if (!activeNotice || currentTranscript.isNullOrBlank()) return cancelSendCountdown()
            val remaining = deadline - SystemClock.uptimeMillis()
            if (remaining <= 0L) {
                cancelSendCountdown()
                sendConfirmedReply()
                return
            }
            val seconds = ((remaining + 999L) / 1000L).toInt()
            queueEssential(
                NexusNoticeUpdate(
                    actions = confirmActions(seconds),
                    ttlMs = DECISION_TTL_MS,
                    rearm = false,
                ),
                dropPartial = false,
            )
            main.postDelayed(this, SEND_TICK_MS)
        }
    }

    private fun queueSendFailure(cause: String) {
        queueEssential(
            NexusNoticeUpdate(
                footer = fitFooter("Reply failed: $cause"),
                actions = confirmActions(null),
                ttlMs = DECISION_TTL_MS,
            ),
            dropPartial = true,
        )
    }

    private fun queueSpeechFailure(cause: String) {
        stopInputKeepalive()
        pendingPartial = null
        queueEssential(
            NexusNoticeUpdate(
                footer = fitFooter(cause),
                // Reclaims confirm/direction keys from a card that may have just
                // been showing them to the typed-reply field instead (see
                // startTyping). A no-op when they were never released.
                interactive = true,
                actions = SPEECH_FAILURE_ACTIONS,
                ttlMs = DECISION_TTL_MS,
            ),
            dropPartial = true,
        )
    }

    private fun revealNotice() {
        val reply = currentReply ?: return
        if (!activeNotice) return
        // An update restarts the band's TTL, so the wearer gets the full reading
        // time after tapping Show. Images are not revealed by Show — updates
        // cannot carry image bytes — so a hidden image stays hidden.
        queueEssential(
            NexusNoticeUpdate(
                lines = messageLines(reply.content.renderedText),
                actions = INITIAL_ACTIONS,
                ttlMs = noticeTtlMs(
                    settings.noticeDisplaySeconds(),
                    settings.noticeScalesWithLength(),
                    reply.content.renderedText.length,
                ),
            ),
            dropPartial = false,
        )
    }

    private fun dismissNotice() {
        invalidateSpeech()
        hideTypingSurface()
        currentTranscript = null
        pendingPartial = null
        essentialUpdates.clear()
        applyDeferredShowOrElse {
            client?.hideNotice()
            main.postDelayed({
                if (activeNotice) closeClient()
            }, HIDE_FALLBACK_MS)
        }
    }

    /**
     * Offers typing instead, once the bounce window of the Reply tap is over.
     *
     * This is the one chip dictation shows, and it waits for the reason
     * [startListening] shows none at first: the band's row was just spent, and
     * re-arming it straight away hands the temple pad's bounce (measured at
     * 433 ms after a real tap) a question to answer. Arriving well after that,
     * a tap on it is the wearer's own. A single chip leaves the directions free,
     * so the message still pages behind it.
     */
    private fun armTypeChip(generation: Int) {
        cancelTypeChip()
        val runnable = Runnable {
            typeChipRunnable = null
            if (!activeNotice || generation != speechGeneration || speech == null) return@Runnable
            if (speechFinalReceived) return@Runnable
            queueEssential(NexusNoticeUpdate(actions = LISTENING_ACTIONS), dropPartial = false)
        }
        typeChipRunnable = runnable
        main.postDelayed(runnable, TYPE_CHIP_ARM_DELAY_MS)
    }

    private fun cancelTypeChip() {
        typeChipRunnable?.let(main::removeCallbacks)
        typeChipRunnable = null
    }

    private fun invalidateSpeech() {
        cancelSendCountdown()
        stopInputKeepalive()
        cancelTypeChip()
        speechGeneration += 1
        speechFinalReceived = false
        speech?.stop()
        speech = null
    }

    /**
     * Neither dictation nor typing is bounded by anything the band's own
     * [DECISION_TTL_MS] knows about — a slow talker or a hunt-and-peck typist
     * on a small keyboard can easily outrun 30 seconds. Resending the same
     * in-flight update on a short clock keeps the band's TTL from ever
     * reaching zero mid-input, the same way [onTtsStarted] holds it open for
     * a single known duration, just repeated for an open-ended one.
     */
    private fun startInputKeepalive(update: () -> NexusNoticeUpdate) {
        stopInputKeepalive()
        val runnable = object : Runnable {
            override fun run() {
                if (!activeNotice) return
                queueEssential(update(), dropPartial = false)
                main.postDelayed(this, INPUT_KEEPALIVE_INTERVAL_MS)
            }
        }
        inputKeepaliveRunnable = runnable
        main.postDelayed(runnable, INPUT_KEEPALIVE_INTERVAL_MS)
    }

    private fun stopInputKeepalive() {
        inputKeepaliveRunnable?.let(main::removeCallbacks)
        inputKeepaliveRunnable = null
    }

    private fun queuePartial(update: NexusNoticeUpdate) {
        pendingPartial = update
        scheduleUpdateDrain()
    }

    private fun queueEssential(update: NexusNoticeUpdate, dropPartial: Boolean) {
        if (dropPartial) pendingPartial = null
        essentialUpdates.addLast(update)
        scheduleUpdateDrain()
    }

    private fun scheduleUpdateDrain() {
        if (updateDrainScheduled || !activeNotice) return
        val now = SystemClock.uptimeMillis()
        val earliest = if (lastNoticeMessageAtMs == Long.MIN_VALUE) now else {
            lastNoticeMessageAtMs + MIN_NOTICE_MESSAGE_INTERVAL_MS
        }
        updateDrainScheduled = true
        main.postDelayed(::drainOneUpdate, (earliest - now).coerceAtLeast(0L))
    }

    private fun drainOneUpdate() {
        updateDrainScheduled = false
        if (!activeNotice) return
        val update = if (essentialUpdates.isNotEmpty()) {
            essentialUpdates.removeFirst()
        } else {
            pendingPartial.also { pendingPartial = null }
        } ?: return
        val result = client?.updateNotice(update)
        lastNoticeMessageAtMs = SystemClock.uptimeMillis()
        if (result != NexusSdkResult.SENT) Log.i(TAG, "notice update result=$result")
        if (essentialUpdates.isNotEmpty() || pendingPartial != null) scheduleUpdateDrain()
    }

    private fun closeClient() {
        cancelSendCountdown()
        showGeneration += 1
        invalidateSpeech()
        hideTypingSurface()
        typingSurface = null
        deferredShow = null
        closeReadAloudSession()
        essentialUpdates.clear()
        pendingPartial = null
        updateDrainScheduled = false
        pendingShow = null
        pendingShowStartedAtMs = 0L
        pendingShowWasBlocked = false
        currentReply = null
        currentTranscript = null
        activeNotice = false
        client?.close()
        client = null
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    /**
     * Lines that fit the contract's budget, separators included.
     *
     * The budget charges each line its length plus one, so a body already
     * trimmed to exactly MAX_BODY_CHARS overflows the instant it is split, and
     * `NexusNotice` throws while being constructed — a crash on the one path
     * that has to survive whatever an app decides to send. Newest lines win,
     * for the same reason the character trim drops from the top.
     */
    private fun messageLines(rendered: String): List<String> {
        val candidates = rendered.split('\n')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .takeLast(NoticeSurfaceContract.MAX_LINES)
        val kept = ArrayDeque<String>()
        var budget = NoticeSurfaceContract.MAX_BODY_CHARS
        for (line in candidates.asReversed()) {
            val cost = line.length + 1
            if (cost > budget) {
                // One line can outrun what is left on its own; keep its newest
                // words rather than dropping the message the wearer was sent.
                if (kept.isEmpty() && budget > 1) kept.addFirst(line.takeLast(budget - 1))
                break
            }
            kept.addFirst(line)
            budget -= cost
        }
        return kept.toList()
    }

    private fun fitFooter(value: String): String =
        value.trim().take(NoticeSurfaceContract.MAX_FOOTER_CHARS)

    private fun speechReasonLabel(reason: NexusSpeechStopReason): String = when (reason) {
        NexusSpeechStopReason.COMPLETED -> "Didn't catch that"
        NexusSpeechStopReason.CANCELLED -> "Cancelled"
        NexusSpeechStopReason.NO_SPEECH -> "Didn't catch that"
        NexusSpeechStopReason.ERROR -> "Speech failed"
        NexusSpeechStopReason.LINK_LOST -> "Glasses disconnected"
        NexusSpeechStopReason.REVOKED -> "Speech access revoked"
        NexusSpeechStopReason.DENIED_BUSY -> "Speech is busy"
        NexusSpeechStopReason.DENIED_NO_LINK -> "Glasses not connected"
        // The hub knows exactly why — no engine, no key, no microphone permission —
        // but the SDK flattens all of it to NOT_READY. Point at the screen that
        // does know rather than repeating a word the wearer cannot act on.
        NexusSpeechStopReason.DENIED_NOT_READY -> "Set up speech in Nexus"
        NexusSpeechStopReason.DENIED_START_FAILED -> "Start failed"
        NexusSpeechStopReason.DENIED_INVALID -> "Invalid request"
    }

    companion object {
        const val TAG = "NexusRelayNotice"
        const val PLUGIN_ID = "relay"
        const val HIDE_FALLBACK_MS = 500L
        const val MIN_NOTICE_MESSAGE_INTERVAL_MS = 210L

        /**
         * The time the band stays, given the wearer's base seconds, whether it
         * scales with length, and how much text is on it.
         *
         * The same per-character rate the hub uses when a plugin sends no TTL,
         * on top of a base the wearer chose — so "scale with length" still means
         * exactly what Auto always meant, and a fixed value still holds for its
         * whole duration. Clamped to the contract's floors and ceiling.
         */
        fun noticeTtlMs(displaySeconds: Int, scalesWithLength: Boolean, characterCount: Int): Long =
            (RelaySettings.coerceNoticeDisplaySeconds(displaySeconds) * 1_000L +
                (if (scalesWithLength) characterCount.coerceAtLeast(0) * NoticeSurfaceContract.DERIVED_TTL_PER_CHAR_MS else 0L))
                .coerceIn(NoticeSurfaceContract.MIN_TTL_MS, NoticeSurfaceContract.MAX_TTL_MS)

        /**
         * None of these is a refusal — each means "not yet", and each is
         * resolved by an event that is already on its way.
         *
         * `NOT_REGISTERED` includes a synchronous transport rejection after the
         * SDK's registration view went stale; re-registration brings it back.
         * `CAPABILITY_NOT_AVAILABLE` is the glasses being out of reach; the hub
         * holds the band and `onLinkState` brings us back.
         * `CAPABILITY_NOT_GRANTED` is subtler and cost an afternoon on hardware:
         * `registerPlugin` answers APPROVED synchronously, while the grant list
         * follows as a separate `/plugin/registration` message ~16 ms later. A
         * notice pushed the instant approval lands therefore asks about a grant
         * set that is still empty. APPROVED arrives a second time with the
         * grants on it, so the only correct move is to keep the pending show and
         * let the retry happen. Closing here threw away a notice the wearer was
         * entitled to see. The replay window is what stops us waiting forever.
         */
        private val RETRYABLE_SHOW_BLOCK_REASONS = mapOf(
            NexusSdkResult.NOT_REGISTERED to "registration",
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE to "capability",
            NexusSdkResult.CAPABILITY_NOT_GRANTED to "grant",
        )

        /**
         * Every state that is waiting on the wearer says so explicitly.
         *
         * Left to the platform's default the TTL is derived from the text, which
         * is right for a message and wrong for a question: an update carrying
         * only "Voice failed: Speech not ready" is a 30-character footer and
         * would be handed the four-second floor — gone before it has been read,
         * let alone answered, and the wearer's next press falls through to the
         * ROM launcher behind the band.
         */
        const val DECISION_TTL_MS = 30_000L

        /** Comfortably under [DECISION_TTL_MS], so a resend always lands before the old TTL could. */
        const val INPUT_KEEPALIVE_INTERVAL_MS = 12_000L

        /** Long enough to read "Sent", short enough not to be in the way. */
        const val SENT_LINGER_MS = 1_500L

        /** The one foreground-surface slot Relay ever opens, for the typed-reply field. */
        const val TYPING_SURFACE_ID = "reply"

        const val ACTION_REPLY = "reply"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SEND = "send"
        const val ACTION_RETRY = "retry"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_SHOW = "show"
        const val ACTION_TYPE = "type"

        /** The field is on the band itself; the footer only says how to finish. */
        const val TYPING_FOOTER = "Enter to send · Back to cancel"

        /** Deliberately handled by nothing: the chip says a thing, it is not one. */
        const val ACTION_SENT = "sent"

        /**
         * The countdown chip, once there is nothing left to count.
         *
         * It keeps the same mark and the same slot the wearer was already
         * watching, and only the word changes — the answer lands where the
         * eye is instead of asking it to go looking. Leaving the old label up
         * was the real fault: a chip promising to send in three seconds, after
         * it had been sent, is the band lying about the one thing the wearer
         * cared about.
         */
        val SENT_ACTIONS = listOf(NexusNoticeAction(ACTION_SENT, "send", "Sent"))

        /**
         * One answer, so the arriving band can be paged.
         *
         * Dismiss was a chip until wearing it showed what that cost: a row of
         * two takes the directions to choose along, and a band whose directions
         * are taken cannot turn pages, so a three-message thread was ellipsized
         * at eight lines with the rest unreachable. Back already dismisses any
         * visible band, answered or not, so the chip was buying nothing and
         * spending the one thing the wearer actually needed — the ability to
         * read the message they were interrupted about.
         */
        val INITIAL_ACTIONS = listOf(
            NexusNoticeAction(ACTION_REPLY, "reply", "Reply"),
        )
        // Hiding the text must not turn the band into a dead end: Show is the
        // wearer saying "now is fine", and Reply stays one tap away either way.
        val HIDDEN_ACTIONS = listOf(
            NexusNoticeAction(ACTION_SHOW, "show", "Show"),
            NexusNoticeAction(ACTION_REPLY, "reply", "Reply"),
        )
        /**
         * The same two answers the inbox offers, for the same reasons.
         *
         * Send carries the countdown as its label, so the seconds are on the
         * chip the wearer is deciding about. Cancel is gone: Back dismisses a
         * band from anywhere, and a chip that duplicates it costs a slot and
         * teaches a second way to do one thing. The two paths must agree —
         * dictating from a band and dictating from the inbox are the same act.
         */
        fun confirmActions(secondsLeft: Int?): List<NexusNoticeAction> = listOf(
            NexusNoticeAction(
                ACTION_SEND,
                "send",
                when {
                    secondsLeft == null -> "Send"
                    secondsLeft > 0 -> "Sending ${secondsLeft}s"
                    else -> "Sending…"
                },
            ),
            NexusNoticeAction(ACTION_RETRY, "retry", "Retry"),
        )

        /** Switches a dictation already under way to the typed field; see armTypeChip. */
        val LISTENING_ACTIONS = listOf(
            NexusNoticeAction(ACTION_TYPE, "keyboard", "Type"),
        )

        /** Comfortably past the 433 ms temple-pad bounce measured after a Reply tap. */
        const val TYPE_CHIP_ARM_DELAY_MS = 1_200L

        val SPEECH_FAILURE_ACTIONS = listOf(
            NexusNoticeAction(ACTION_RETRY, "mic", "Speak again"),
        )

        // Time to re-read what you just said, scaled to how much there is.
        // Kept identical to the inbox: one behaviour, two doors into it.
        const val SEND_BASE_MS = 2_200L
        const val SEND_MS_PER_WORD = 180L
        const val SEND_MIN_MS = 3_000L
        const val SEND_MAX_MS = 6_000L
        const val SEND_TICK_MS = 1_000L

        val WHITESPACE = Regex("""\s+""")
    }
}
