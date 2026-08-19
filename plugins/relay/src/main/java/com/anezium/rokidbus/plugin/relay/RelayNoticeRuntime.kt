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
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusTtsCallbacks
import com.anezium.rokidbus.client.plugin.NexusTtsDoneReason
import com.anezium.rokidbus.client.plugin.NexusTtsSession
import com.anezium.rokidbus.client.plugin.speechSession
import com.anezium.rokidbus.client.plugin.ttsSession
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

    fun show(reply: ReplyRepository.PendingReply) = onMain {
        // The inbox owns the bus while it is open, and it is already showing
        // this conversation — the capture reached the repository before us.
        if (NotificationControl.inboxOpen) {
            Log.i(TAG, "band suppressed: inbox has the bus")
            return@onMain
        }
        showGeneration += 1
        val generation = showGeneration
        val nowMs = SystemClock.elapsedRealtime()
        invalidateSpeech()
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
            ACTION_REPLY, ACTION_RETRY -> startListening()
            ACTION_SEND -> sendConfirmedReply()
            ACTION_DISMISS, ACTION_CANCEL -> dismissNotice()
        }
    }

    override fun onNoticeClosed(reason: NexusNoticeCloseReason) = onMain {
        closeClient()
    }

    override fun onMessage(path: String, id: String, payload: JSONObject) = Unit

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
        currentTranscript = null
        speechFinalReceived = false
        // Deliberately offers nothing while listening, and says so.
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

        val generation = speechGeneration
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
                queueSpeechFailure(speechReasonLabel(reason))
            }
        })
        speech = newSpeech
        val result = newSpeech.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            queueSpeechFailure(result.name)
        }
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
                main.postDelayed({ if (activeNotice) dismissNotice() }, SENT_LINGER_MS)
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
                NexusNoticeUpdate(actions = confirmActions(seconds), ttlMs = DECISION_TTL_MS),
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
        pendingPartial = null
        queueEssential(
            NexusNoticeUpdate(
                footer = fitFooter(cause),
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
        pendingPartial = null
        essentialUpdates.clear()
        client?.hideNotice()
        main.postDelayed({
            if (activeNotice) closeClient()
        }, HIDE_FALLBACK_MS)
    }

    private fun invalidateSpeech() {
        cancelSendCountdown()
        speechGeneration += 1
        speechFinalReceived = false
        speech?.stop()
        speech = null
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

        /** Long enough to read "Sent", short enough not to be in the way. */
        const val SENT_LINGER_MS = 1_500L

        const val ACTION_REPLY = "reply"
        const val ACTION_DISMISS = "dismiss"
        const val ACTION_SEND = "send"
        const val ACTION_RETRY = "retry"
        const val ACTION_CANCEL = "cancel"
        const val ACTION_SHOW = "show"

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
