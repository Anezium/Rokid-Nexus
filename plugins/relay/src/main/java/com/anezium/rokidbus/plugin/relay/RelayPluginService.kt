package com.anezium.rokidbus.plugin.relay

import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import com.anezium.rokidbus.client.plugin.NexusPluginService
import com.anezium.rokidbus.client.plugin.NexusReader
import com.anezium.rokidbus.client.plugin.NexusReaderSegment
import com.anezium.rokidbus.client.plugin.NexusReaderSegmentKind
import com.anezium.rokidbus.client.plugin.NexusRowTone
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSpeechCallbacks
import com.anezium.rokidbus.client.plugin.NexusSpeechError
import com.anezium.rokidbus.client.plugin.NexusSpeechSession
import com.anezium.rokidbus.client.plugin.NexusSpeechState
import com.anezium.rokidbus.client.plugin.NexusSpeechStopReason
import com.anezium.rokidbus.client.plugin.NexusSurfaceSession
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent

/** Menu-launched inbox. The notification-band runtime remains a separate, untouched flow. */
class RelayPluginService : NexusPluginService() {
    private enum class ThreadMode {
        READING,
        LISTENING,
        REVIEW,
        VOICE_FAILURE,
        SENT,
    }

    /**
     * Two answers, because the third and fourth were already there.
     *
     * Send is the countdown itself — tapping it only means "go now" — so it
     * needs no separate button, and its row is where the seconds are counted
     * rather than in a footer nobody looks at while reading their own words.
     * Cancel is Back, which dismisses from anywhere and always has.
     */
    private enum class ReplyChoice(val label: String) {
        SEND("Send"),
        RETRY("Retry"),
    }

    private val main = android.os.Handler(android.os.Looper.getMainLooper())
    private val settings by lazy { RelaySettings(this) }
    private var sendDeadlineMs: Long? = null
    private var surface: NexusSurfaceSession? = null
    private var entries: List<RelayInboxEntry> = emptyList()
    private val selection = RelayInboxSelection()

    private var threadMode = ThreadMode.READING
    private var threadStatus: String? = null
    private var selectedChoice = 0
    private var transcript: String? = null
    private var speechPreview = ""
    private var speechStatus = "Starting speech..."
    private var speechFinalReceived = false
    private var speechGeneration = 0
    private var speech: NexusSpeechSession? = null

    override fun onNexusOpen() {
        // One client per plugin id on the bus: the band steps aside while the
        // wearer is in here. See NotificationControl.inboxOpen.
        NotificationControl.inboxOpened(this)
        invalidateSpeech()
        entries = ReplyRepository.inboxEntries()
        selection.reset(entries.map(RelayInboxEntry::id))
        resetThreadMode()
        surface = nexusSurfaceSession(SURFACE_ID)
        renderList(show = true)
    }

    override fun onNexusClose() {
        NotificationControl.inboxClosed(this)
        invalidateSpeech()
        entries = emptyList()
        selection.reset(emptyList())
        resetThreadMode()
        surface = null
    }

    override fun onNexusInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            -> move(1)

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            -> move(-1)

            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            -> confirm()

            KeyEvent.KEYCODE_BACK -> back()
        }
    }

    /** A capture redraws the list or the thread being read without interrupting a reply in progress. */
    internal fun onCaptureChanged(notificationId: String) {
        when (selection.liveRefreshTarget(notificationId, threadMode == ThreadMode.READING)) {
            RelayInboxRefreshTarget.LIST -> renderList(show = false)
            RelayInboxRefreshTarget.THREAD -> renderThread(show = false)
            RelayInboxRefreshTarget.NONE -> Unit
        }
    }

    private fun move(delta: Int) {
        // Choosing among the answers means the wearer is deciding themselves,
        // so the clock stops rather than firing under their hand.
        if (selection.view == RelayInboxView.THREAD) cancelSendCountdown()
        if (selection.view == RelayInboxView.LIST) {
            refreshEntries()
            if (selection.move(delta)) renderList(show = false)
            return
        }
        val choices = visibleChoices()
        if (choices.isEmpty()) return
        selectedChoice = Math.floorMod(selectedChoice + delta, choices.size)
        renderThread(show = false)
    }

    private fun confirm() {
        if (selection.view == RelayInboxView.LIST) {
            refreshEntries()
            if (selection.openSelected() != null) {
                resetThreadMode()
                renderThread(show = false)
            }
            return
        }

        when (threadMode) {
            ThreadMode.READING -> startListening()
            ThreadMode.LISTENING,
            ThreadMode.SENT,
            -> Unit
            ThreadMode.REVIEW,
            ThreadMode.VOICE_FAILURE,
            -> activateChoice()
        }
    }

    /**
     * Reads the transcript back, then sends it unless the wearer steps in.
     *
     * Requiring a tap was the right call before wearing it and the wrong one
     * after: the wearer has just spoken their reply and is looking straight at
     * it, and asking them to confirm what they can already read turns one
     * gesture into two for no new information. The countdown is the compromise
     * Relay landed on — visible, proportional to how much there is to re-read,
     * and cancellable for its whole length, which is what separates it from
     * sending blind.
     */
    private fun startSendCountdown(text: String) {
        cancelSendCountdown()
        val words = text.trim().split(WHITESPACE).count(String::isNotBlank)
        sendDeadlineMs = android.os.SystemClock.uptimeMillis() +
            (SEND_BASE_MS + words * SEND_MS_PER_WORD).coerceIn(SEND_MIN_MS, SEND_MAX_MS)
        main.post(sendTick)
    }

    private fun cancelSendCountdown() {
        sendDeadlineMs = null
        main.removeCallbacks(sendTick)
    }

    /** One second of the countdown: redraw the footer, or send when it runs out. */
    private val sendTick = object : Runnable {
        override fun run() {
            val deadline = sendDeadlineMs ?: return
            if (threadMode != ThreadMode.REVIEW) return cancelSendCountdown()
            if (android.os.SystemClock.uptimeMillis() >= deadline) {
                cancelSendCountdown()
                sendConfirmedReply()
                return
            }
            if (!renderThread(show = false)) {
                // The wearer stopped being able to see the countdown, so it
                // stops counting. Send stays available by hand.
                cancelSendCountdown()
                return
            }
            main.postDelayed(this, SEND_TICK_MS)
        }
    }

    /** Whole seconds left, for the footer. Zero once it is on its way. */
    private fun sendSecondsLeft(): Int? = sendDeadlineMs?.let { deadline ->
        val remaining = deadline - android.os.SystemClock.uptimeMillis()
        if (remaining <= 0L) 0 else ((remaining + 999L) / 1000L).toInt()
    }

    /** Returns to the list once "Sent" has been read, if nothing else moved since. */
    private fun leaveSentThread() {
        if (threadMode != ThreadMode.SENT || selection.view != RelayInboxView.THREAD) return
        resetThreadMode()
        selection.back()
        renderList(show = false)
    }

    private fun back() {
        if (selection.view == RelayInboxView.THREAD && threadMode != ThreadMode.READING) {
            invalidateSpeech()
            resetThreadMode()
            renderThread(show = false)
            return
        }
        when (selection.back()) {
            RelayInboxBackResult.SHOW_LIST -> {
                resetThreadMode()
                renderList(show = false)
            }
            RelayInboxBackResult.CLOSE_SURFACE -> surface?.hide()
        }
    }

    private fun renderList(show: Boolean) {
        refreshEntries()
        val rows = entries.mapIndexed { index, entry ->
            val selected = index == selection.selectedIndex
            NexusCardLine(
                text = RelayInboxCatalog.lineFor(entry, selected),
                // Tapping a conversation open is the wearer's deliberate act, the
                // equivalent of the band's Show — so the preview can hide while
                // the opened thread still reads in full.
                sub = RelayInboxCatalog.previewFor(entry, hidden = settings.hideInboxPreviews()).takeIf(String::isNotBlank),
                // A thread that can no longer be answered is present but is not
                // competing for the wearer's attention.
                tone = if (entry.availability == RelayReplyAvailability.REPLIABLE) {
                    NexusRowTone.NORMAL
                } else {
                    NexusRowTone.DIM
                },
                selected = selected,
            )
        }
        val footer = if (entries.isEmpty()) {
            "back to close"
        } else {
            "${selection.selectedIndex + 1}/${entries.size} · scroll · tap to open"
        }
        sendCard(
            NexusCard(
                title = "Messages",
                lines = if (entries.isEmpty()) listOf("Nothing yet.") else emptyList(),
                subtitle = if (entries.isEmpty()) null else "${entries.size} waiting",
                footer = footer,
                contentKey = LIST_CONTENT_KEY,
                richLines = rows.takeIf { it.isNotEmpty() },
                handlesBack = false,
            ),
            show = show,
        )
    }

    /** Returns whether the glasses took the surface; the countdown depends on it. */
    private fun renderThread(show: Boolean): Boolean {
        // Not while confirming a send: the repository has already dropped the
        // conversation, so refreshing here would lose the entry mid-render and
        // bounce the wearer to the list before they could read "Sent".
        if (threadMode != ThreadMode.SENT) refreshEntries()
        val entry = currentEntry()
        if (entry == null) {
            resetThreadMode()
            renderList(show = false)
            return false
        }
        val snapshot = entry.snapshot
        if (threadMode == ThreadMode.READING) {
            val document = RelayReaderDocument.from(
                snapshot = snapshot,
                threadStatus = threadStatus,
                canReply = ReplyRepository.contains(entry.id),
            )
            return sendReader(
                NexusReader(
                    title = document.title,
                    footer = document.footer,
                    contentKey = document.contentKey,
                    handlesBack = document.handlesBack,
                    segments = document.segments.map { segment ->
                        NexusReaderSegment(
                            kind = when (segment.kind) {
                                RelayReaderSegmentKind.HEADER -> NexusReaderSegmentKind.HEADER
                                RelayReaderSegmentKind.PROSE -> NexusReaderSegmentKind.PROSE
                                RelayReaderSegmentKind.ASIDE -> NexusReaderSegmentKind.ASIDE
                            },
                            text = segment.text,
                            emphasis = segment.emphasis,
                        )
                    },
                ),
                show = show,
            )
        }
        val rows = when (threadMode) {
            ThreadMode.READING -> emptyList()
            // Mid-dictation the wearer's own words are the only thing moving, so
            // they get the emphasis and the speaker column says whose they are.
            ThreadMode.LISTENING -> buildList {
                addAll(messageRows(snapshot.renderedText, snapshot.sender).takeLast(LISTENING_CONTEXT_MESSAGES))
                add(
                    NexusCardLine(
                        text = speechPreview.ifBlank { speechStatus },
                        badge = "You",
                        tone = NexusRowTone.BODY,
                        selected = speechPreview.isNotBlank(),
                    ),
                )
            }
            // The transcript reads as the message it is about to become, and the
            // answers sit under it as rows the HUD draws a rail against.
            ThreadMode.REVIEW -> buildList {
                add(
                    NexusCardLine(
                        text = transcript.orEmpty(),
                        badge = "You",
                        tone = NexusRowTone.BODY,
                        selected = true,
                    ),
                )
                addAll(choiceRows())
            }
            ThreadMode.VOICE_FAILURE -> buildList {
                add(noteRow(threadStatus ?: "That did not work."))
                addAll(choiceRows())
            }
            ThreadMode.SENT -> buildList {
                addAll(messageRows(snapshot.renderedText, snapshot.sender).takeLast(LISTENING_CONTEXT_MESSAGES))
                add(
                    NexusCardLine(
                        text = transcript.orEmpty().ifBlank { "Sent." },
                        badge = "You",
                        tone = NexusRowTone.BODY,
                        selected = true,
                    ),
                )
            }
        }.takeLast(RelayInboxCatalog.MAX_CARD_LINES)

        val instruction = when (threadMode) {
            ThreadMode.READING -> error("Reading threads render as reader surfaces")
            ThreadMode.LISTENING -> "speak now · back cancels"
            ThreadMode.REVIEW -> "scroll · tap · back cancels"
            ThreadMode.VOICE_FAILURE -> "tap to try again · back cancels"
            ThreadMode.SENT -> "back"
        }
        val footer = listOf(snapshot.appLabel.trim().take(MAX_FOOTER_SOURCE_CHARS), instruction)
            .filter(String::isNotBlank)
            .joinToString(" · ")
            .take(MAX_CARD_LINE_CHARS)
        return sendCard(
            NexusCard(
                title = cardTitle(snapshot.sender.ifBlank { snapshot.appLabel }),
                lines = emptyList(),
                footer = footer,
                contentKey = "$THREAD_CONTENT_PREFIX${entry.id}",
                richLines = rows.takeIf { it.isNotEmpty() },
                handlesBack = true,
            ),
            show = show,
        )
    }

    /** The thread as rows: speaker in the label column, message beside it. */
    private fun messageRows(rendered: String, title: String = ""): List<NexusCardLine> {
        val messages = RelayInboxCatalog.threadMessages(rendered)
        // One-to-one: the title is already that person's name, so labelling
        // every line repeats it down the whole screen. In a group the title is
        // the group and the labels are the only thing saying who is speaking —
        // which is exactly when they earn their column.
        val soloVoice = title.isNotBlank() && messages.all {
            it.speaker.isBlank() || it.speaker.equals(title.trim(), ignoreCase = true)
        }
        return messages.map { message ->
            NexusCardLine(
                text = message.text.take(MAX_CARD_LINE_CHARS),
                badge = message.speaker.takeIf { it.isNotBlank() && !soloVoice },
                tone = NexusRowTone.BODY,
            )
        }
    }

    /** A line about the conversation rather than in it: dim, and unlabelled. */
    private fun noteRow(text: String): NexusCardLine =
        NexusCardLine(text = text.take(MAX_CARD_LINE_CHARS), tone = NexusRowTone.DIM)

    /**
     * The answers, as rows the HUD draws its own selection rail against.
     *
     * They used to be plain lines with a "> " typed into them, which put the
     * caret in a different place from the one the list uses and left the wearer
     * two selection languages to learn on the same screen.
     */
    private fun choiceRows(): List<NexusCardLine> =
        visibleChoices().mapIndexed { index, choice ->
            val seconds = if (choice == ReplyChoice.SEND) sendSecondsLeft() else null
            NexusCardLine(
                text = when {
                    seconds == null -> choice.label
                    seconds > 0 -> "Sending in ${seconds}s"
                    else -> "Sending…"
                },
                selected = index == selectedChoice,
                tone = if (index == selectedChoice) NexusRowTone.ALERT else NexusRowTone.NORMAL,
            )
        }

    private fun startListening() {
        val entry = currentEntry() ?: return
        if (!ReplyRepository.contains(entry.id)) {
            threadStatus = "Reply is no longer available."
            threadMode = ThreadMode.READING
            renderThread(show = false)
            return
        }

        invalidateSpeech()
        threadMode = ThreadMode.LISTENING
        threadStatus = null
        transcript = null
        speechPreview = ""
        speechStatus = "Starting speech..."
        speechFinalReceived = false
        selectedChoice = 0
        renderThread(show = false)

        val generation = speechGeneration
        val newSpeech = nexusSpeechSession(object : NexusSpeechCallbacks {
            override fun onSpeechStarted(realtime: Boolean) {
                if (!acceptSpeechCallback(generation)) return
                speechStatus = "Listening..."
                renderThread(show = false)
            }

            override fun onSpeechState(state: NexusSpeechState) {
                if (!acceptSpeechCallback(generation)) return
                speechStatus = when (state) {
                    NexusSpeechState.LISTENING -> "Listening..."
                    NexusSpeechState.RECOGNIZING -> "Recognizing..."
                    NexusSpeechState.PROCESSING -> "Transcribing..."
                }
                renderThread(show = false)
            }

            override fun onSpeechPartial(text: String) {
                if (!acceptSpeechCallback(generation) || text.isBlank()) return
                speechPreview = fitTranscript(text)
                renderThread(show = false)
            }

            override fun onSpeechFinal(text: String) {
                if (!acceptSpeechCallback(generation)) return
                val finalText = fitTranscript(text)
                if (finalText.isBlank()) {
                    showVoiceFailure("Didn't catch that.")
                    return
                }
                speechFinalReceived = true
                transcript = finalText
                speechPreview = ""
                threadStatus = null
                threadMode = ThreadMode.REVIEW
                selectedChoice = 0
                // Render first, and only start the clock if the review reached
                // the glasses. Starting it blind meant a dark display or a
                // dropped link still sent the transcript three seconds later —
                // the wearer had approved nothing because they had seen nothing.
                if (renderThread(show = false)) {
                    startSendCountdown(finalText)
                } else {
                    threadStatus = "Reply is ready. Tap to send."
                    renderThread(show = false)
                }
            }

            override fun onSpeechStopped(
                reason: NexusSpeechStopReason,
                error: NexusSpeechError?,
            ) {
                if (generation != speechGeneration) return
                speech = null
                if (speechFinalReceived && reason == NexusSpeechStopReason.COMPLETED) return
                // speechReasonLabel, never error.kind: the kind is an enum name
                // for a bug report ("NO_SPEECH"), and the band is not a bug report.
                showVoiceFailure(speechReasonLabel(reason))
            }
        }) ?: run {
            showVoiceFailure("Speech is unavailable.")
            return
        }
        speech = newSpeech
        val result = newSpeech.start()
        if (result != NexusSdkResult.SENT) {
            speech = null
            showVoiceFailure(speechStartFailure(result))
        }
    }

    private fun activateChoice() {
        cancelSendCountdown()
        when (visibleChoices().getOrNull(selectedChoice)) {
            ReplyChoice.SEND -> sendConfirmedReply()
            ReplyChoice.RETRY -> startListening()
            null -> Unit
        }
    }

    private fun sendConfirmedReply() {
        val entry = currentEntry() ?: return
        val replyText = transcript
        if (replyText.isNullOrBlank()) {
            showVoiceFailure("Didn't catch that.")
            return
        }
        when (val result = ReplyRepository.sendReply(applicationContext, entry.id, replyText)) {
            ReplySendResult.Sent -> {
                transcript = null
                threadStatus = null
                threadMode = ThreadMode.SENT
                // Confirm it, then hand the wearer back their list. The
                // conversation is finished and the repository has already
                // dropped it, so staying in a thread that no longer exists only
                // makes them press Back to be told the same thing.
                main.postDelayed(::leaveSentThread, SENT_LINGER_MS)
            }
            ReplySendResult.Missing -> {
                transcript = null
                threadStatus = "Reply is no longer available."
                threadMode = ThreadMode.READING
            }
            ReplySendResult.Blank -> threadStatus = "Reply failed: empty reply"
            ReplySendResult.NoFreeFormInput -> threadStatus = "Reply failed: reply unavailable"
            is ReplySendResult.Failed -> threadStatus = "Reply failed: ${result.causeClass}"
        }
        renderThread(show = false)
    }

    private fun showVoiceFailure(reason: String) {
        transcript = null
        speechPreview = ""
        threadStatus = reason.trim().take(MAX_CARD_LINE_CHARS)
        threadMode = ThreadMode.VOICE_FAILURE
        selectedChoice = 0
        renderThread(show = false)
    }

    private fun visibleChoices(): List<ReplyChoice> = when (threadMode) {
        ThreadMode.REVIEW -> REVIEW_CHOICES
        ThreadMode.VOICE_FAILURE -> FAILURE_CHOICES
        else -> emptyList()
    }

    private fun currentEntry(): RelayInboxEntry? {
        val id = selection.openedThreadId ?: return null
        return entries.firstOrNull { it.id == id }
    }

    private fun refreshEntries() {
        entries = ReplyRepository.inboxEntries()
        selection.replaceItems(entries.map(RelayInboxEntry::id))
    }

    private fun resetThreadMode() {
        cancelSendCountdown()
        threadMode = ThreadMode.READING
        threadStatus = null
        selectedChoice = 0
        transcript = null
        speechPreview = ""
        speechStatus = "Starting speech..."
        speechFinalReceived = false
    }

    private fun invalidateSpeech() {
        speechGeneration += 1
        speechFinalReceived = false
        val previous = speech
        speech = null
        previous?.stop()
    }

    private fun acceptSpeechCallback(generation: Int): Boolean =
        generation == speechGeneration && threadMode == ThreadMode.LISTENING

    /**
     * What the wearer will actually see — which is also exactly what gets sent.
     *
     * A card line holds 240 characters, not the notice tier's 1024. Fitting to
     * the larger number and then handing the result to one `NexusCardLine` threw
     * while rendering, so a paragraph of dictation took the inbox down instead
     * of showing the review. Trimming from the top keeps the end of what was
     * said, which is the part still in the speaker's head.
     */
    private fun fitTranscript(value: String): String = NotificationTextExtractor.trimFromTop(
        value,
        MAX_CARD_LINE_CHARS,
    )

    private fun cardTitle(value: String): String = value.trim().ifBlank { "Relay" }.take(MAX_CARD_TITLE_CHARS)

    /** Returns whether the glasses actually took it. */
    private fun sendCard(card: NexusCard, show: Boolean): Boolean {
        val session = surface ?: return false
        val result = if (show) session.showCard(card) else session.updateCard(card)
        return result == NexusSdkResult.SENT
    }

    /** Returns whether the glasses actually took it. */
    private fun sendReader(reader: NexusReader, show: Boolean): Boolean {
        val session = surface ?: return false
        val result = if (show) session.showReader(reader) else session.updateReader(reader)
        return result == NexusSdkResult.SENT
    }

    private fun speechStartFailure(result: NexusSdkResult): String = when (result) {
        NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Grant Speech to text in Nexus settings"
        NexusSdkResult.CAPABILITY_NOT_AVAILABLE -> "Speech unavailable"
        NexusSdkResult.NOT_REGISTERED -> "Nexus connection unavailable"
        else -> result.name
    }

    private fun speechReasonLabel(reason: NexusSpeechStopReason): String = when (reason) {
        NexusSpeechStopReason.COMPLETED -> "Didn't catch that."
        NexusSpeechStopReason.CANCELLED -> "Cancelled."
        NexusSpeechStopReason.NO_SPEECH -> "Didn't catch that."
        NexusSpeechStopReason.ERROR -> "Speech failed."
        NexusSpeechStopReason.LINK_LOST -> "Glasses disconnected."
        NexusSpeechStopReason.REVOKED -> "Speech access was revoked."
        NexusSpeechStopReason.DENIED_BUSY -> "Speech is busy elsewhere."
        NexusSpeechStopReason.DENIED_NO_LINK -> "Glasses not connected."
        NexusSpeechStopReason.DENIED_NOT_READY -> "Set up speech in Nexus."
        NexusSpeechStopReason.DENIED_START_FAILED -> "Start failed"
        NexusSpeechStopReason.DENIED_INVALID -> "Invalid request"
    }

    private companion object {
        const val SURFACE_ID = "relay-inbox"
        const val LIST_CONTENT_KEY = "relay-inbox-v1"
        const val THREAD_CONTENT_PREFIX = "relay-thread-"
        const val MAX_CARD_TITLE_CHARS = 120
        const val MAX_CARD_LINE_CHARS = 240
        const val MAX_FOOTER_SOURCE_CHARS = 80

        /**
         * How much of the conversation stays on screen while the wearer speaks.
         *
         * Enough to remember what is being answered, few enough that their own
         * words are where the eye lands — the whole thread above a live
         * transcript buries the one line that is moving.
         */
        const val LISTENING_CONTEXT_MESSAGES = 2

        /**
         * Long enough to read "Sent", short enough that the hand-back to the
         * list happens before the wearer's own Back does. At 1.2s the switch
         * landed exactly where most people tap — their Back then closed a list
         * they had not seen yet (field report, 2026-08).
         */
        const val SENT_LINGER_MS = 850L

        // Time to re-read what you just said, scaled to how much of it there is.
        const val SEND_BASE_MS = 2_200L
        const val SEND_MS_PER_WORD = 180L
        const val SEND_MIN_MS = 3_000L
        const val SEND_MAX_MS = 6_000L
        const val SEND_TICK_MS = 250L

        val WHITESPACE = Regex("""\s+""")

        val REVIEW_CHOICES = listOf(ReplyChoice.SEND, ReplyChoice.RETRY)
        val FAILURE_CHOICES = listOf(ReplyChoice.RETRY)
    }
}
