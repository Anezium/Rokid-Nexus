package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SpeechSessionState
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SpeechUtteranceListener
import com.anezium.rokidbus.phone.speech.SttError
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage
import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.ComposeDictateFinal
import com.anezium.rokidbus.shared.ComposeDictatePartial
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ComposeTypeDone
import com.anezium.rokidbus.shared.ComposeTypeStart
import com.anezium.rokidbus.shared.ComposeTypeText
import org.json.JSONObject
import java.util.Locale

internal fun interface ComposeTimer {
    fun cancel()
}

/** What the composer's capture engines need from the hub; [BusHubService] is the real one. */
internal interface PhoneComposeHost {
    /**
     * Starts one utterance of the hub speech engine. The composer always passes
     * [glassesAudioOnly] true: the glasses microphone is its only source, never the phone's.
     */
    fun startUtterance(
        listener: SpeechUtteranceListener,
        language: TranscriptionLanguage?,
        glassesAudioOnly: Boolean,
    ): SpeechStartResult

    fun finishUtterance(listener: SpeechUtteranceListener)

    fun cancelUtterance(listener: SpeechUtteranceListener)

    /** A plugin's speech session holds the hub's single speech session. */
    fun pluginSpeechActive(): Boolean

    /**
     * Opens the phone keyboard for [request]: directly when Android allows it, else a heads-up.
     * False when neither could come up.
     */
    fun openKeyboard(request: ComposeTypeStart): Boolean

    /** Closes the keyboard opened for [captureId], screen and heads-up alike. */
    fun closeKeyboard(captureId: String)

    fun sendToGlasses(path: String, payload: JSONObject): String?

    fun schedule(delayMs: Long, task: () -> Unit): ComposeTimer

    fun log(message: String)
}

/**
 * The phone half of the hub composer's capture engines (`/core/compose/...`), trusted and
 * hub-to-hub only: no plugin capability unlocks them and no plugin sees them.
 *
 * Dictation runs the hub speech engine on the glasses microphone alone, as a hub-internal
 * requester of the same single session plugins use, so a plugin holding it makes a dictation end
 * `busy`. Typing opens the phone keyboard and mirrors its field. One capture at a time, across
 * both engines: a new typing capture replaces whatever capture was running, and a dictation never
 * starts over another. A link loss cancels the capture without a word, since nothing can reach the
 * glasses then; they keep their previous draft on their own.
 */
internal class PhoneComposeRouter(private val host: PhoneComposeHost) {
    private val lock = Any()
    private var dictation: Dictation? = null
    private var typing: Typing? = null

    fun handleGlasses(path: String, payload: JSONObject) {
        when (path) {
            ComposeContract.DICTATE_START_PATH -> startDictation(payload)
            ComposeContract.DICTATE_STOP_PATH -> stopDictation(payload)
            ComposeContract.TYPE_START_PATH -> startTyping(payload)
            ComposeContract.TYPE_FINISH_PATH -> endTypingFromGlasses(payload, commit = true)
            ComposeContract.TYPE_CANCEL_PATH -> endTypingFromGlasses(payload, commit = false)
            else -> host.log("compose ignored path=$path")
        }
    }

    /** Both hubs lost each other: every capture ends here, and nothing is sent. */
    fun onLinkLost() {
        var watchdog: ComposeTimer? = null
        val (ended, closedTyping) = synchronized(lock) {
            val current = dictation?.also { capture ->
                capture.end()
                watchdog = capture.watchdog
                capture.watchdog = null
            }
            dictation = null
            val open = typing
            typing = null
            current to open
        }
        watchdog?.cancel()
        ended?.let {
            host.cancelUtterance(it)
            host.log("compose dictation cancelled capture=${it.captureId} reason=link_lost")
        }
        closedTyping?.let {
            host.closeKeyboard(it.captureId)
            host.log("compose typing dismissed capture=${it.captureId} reason=link_lost")
        }
    }

    // ---- Dictation ----

    private fun startDictation(payload: JSONObject) {
        val request = ComposeContract.parseDictateStart(payload)
        if (request == null) {
            host.log("compose dictation start ignored reason=invalid")
            return
        }
        val capture = Dictation(request.captureId)
        var accepted = false
        val replacedTyping = synchronized(lock) {
            val current = dictation
            when {
                current?.captureId == request.captureId -> return
                current != null -> null
                else -> {
                    dictation = capture
                    accepted = true
                    typing.also { typing = null }
                }
            }
        }
        if (!accepted) {
            sendFinal(request.captureId, ComposeDictationReason.BUSY)
            host.log("compose dictation refused capture=${request.captureId} reason=busy")
            return
        }
        replacedTyping?.let { closeTyping(it, committedText = null) }
        if (host.pluginSpeechActive()) {
            finishDictation(capture, ComposeDictationReason.BUSY)
            return
        }
        val result = runCatching {
            host.startUtterance(capture, composeLanguage(request.language), glassesAudioOnly = true)
        }.getOrDefault(SpeechStartResult.START_FAILED)
        val refusal = when (result) {
            SpeechStartResult.OK -> null
            SpeechStartResult.BUSY -> ComposeDictationReason.BUSY
            // The glasses microphone could not be opened: nothing was heard, so it is the mic.
            SpeechStartResult.NO_LINK, SpeechStartResult.START_FAILED -> ComposeDictationReason.MIC_UNAVAILABLE
            SpeechStartResult.NOT_READY -> ComposeDictationReason.ERROR
        }
        if (refusal != null) {
            finishDictation(capture, refusal)
            return
        }
        host.log("compose dictation started capture=${capture.captureId}")
        val watchdog = host.schedule(FIRST_AUDIO_TIMEOUT_MS) { onFirstAudioTimeout(capture) }
        val armed = synchronized(lock) {
            if (dictation === capture && !capture.ended && !capture.heardAudio) {
                capture.watchdog = watchdog
                true
            } else {
                false
            }
        }
        if (!armed) watchdog.cancel()
    }

    private fun stopDictation(payload: JSONObject) {
        val request = ComposeContract.parseDictateStop(payload)
        val capture = synchronized(lock) {
            dictation?.takeIf { request != null && it.captureId == request.captureId && !it.ended }
        }
        if (request == null || capture == null) {
            host.log("compose dictation stop ignored reason=${if (request == null) "invalid" else "stale"}")
            return
        }
        if (request.cancel) {
            // The wearer pressed Back: the result is known now, whatever the engine still does.
            finishDictation(capture, ComposeDictationReason.CANCELLED)
            host.cancelUtterance(capture)
        } else {
            host.finishUtterance(capture)
        }
    }

    private fun onFirstAudioTimeout(capture: Dictation) {
        val stale = synchronized(lock) { dictation !== capture || capture.ended || capture.heardAudio }
        if (stale) return
        finishDictation(capture, ComposeDictationReason.MIC_UNAVAILABLE)
        host.cancelUtterance(capture)
    }

    private fun onEngineEnded(capture: Dictation, reason: SpeechEndReason) {
        val (heard, finalText) = synchronized(lock) { capture.heardAudio to capture.finalText }
        val text = finalText?.let(ComposeContract::clip)?.takeIf { it.isNotBlank() }
        val outcome = when {
            reason == SpeechEndReason.COMPLETED && text != null -> ComposeDictationReason.COMPLETED
            reason == SpeechEndReason.CANCELLED -> ComposeDictationReason.CANCELLED
            // Not one byte came from the glasses: that is the microphone, not the wearer.
            !heard -> ComposeDictationReason.MIC_UNAVAILABLE
            reason == SpeechEndReason.COMPLETED || reason == SpeechEndReason.NO_SPEECH -> ComposeDictationReason.NO_SPEECH
            reason == SpeechEndReason.LINK_LOST -> ComposeDictationReason.LINK_LOST
            else -> ComposeDictationReason.ERROR
        }
        finishDictation(capture, outcome, text.takeIf { outcome == ComposeDictationReason.COMPLETED })
    }

    /** Sends the one final of [capture], if it is still the capture in flight. */
    private fun finishDictation(capture: Dictation, reason: ComposeDictationReason, text: String? = null) {
        val watchdog = synchronized(lock) {
            if (capture.ended) return
            capture.end()
            if (dictation === capture) dictation = null
            capture.watchdog.also { capture.watchdog = null }
        }
        watchdog?.cancel()
        sendFinal(capture.captureId, reason, text)
        host.log("compose dictation ended capture=${capture.captureId} reason=${reason.wireValue}")
    }

    private fun sendFinal(captureId: String, reason: ComposeDictationReason, text: String? = null) {
        host.sendToGlasses(
            ComposeContract.DICTATE_FINAL_PATH,
            ComposeContract.dictateFinal(ComposeDictateFinal(captureId, reason, text)),
        )
    }

    private inner class Dictation(val captureId: String) : SpeechUtteranceListener {
        var ended = false
            private set
        var heardAudio = false
        var finalText: String? = null
        var lastPartial: String? = null
        var watchdog: ComposeTimer? = null

        fun end() {
            ended = true
        }

        override fun onState(state: SpeechSessionState) = Unit

        override fun onFirstAudio() {
            val pending = synchronized(lock) {
                heardAudio = true
                this.watchdog.also { this.watchdog = null }
            }
            pending?.cancel()
        }

        override fun onPartial(text: String) {
            val clipped = ComposeContract.clip(text)
            val send = synchronized(lock) {
                if (ended || dictation !== this || clipped.isBlank() || clipped == lastPartial) {
                    false
                } else {
                    lastPartial = clipped
                    true
                }
            }
            if (send) {
                host.sendToGlasses(
                    ComposeContract.DICTATE_PARTIAL_PATH,
                    ComposeContract.dictatePartial(ComposeDictatePartial(captureId, clipped)),
                )
            }
        }

        override fun onFinal(text: String) {
            synchronized(lock) { finalText = text }
        }

        override fun onEnded(reason: SpeechEndReason, error: SttError?) {
            onEngineEnded(this, reason)
        }
    }

    // ---- Typing ----

    private fun startTyping(payload: JSONObject) {
        val request = ComposeContract.parseTypeStart(payload)
        if (request == null) {
            host.log("compose typing start ignored reason=invalid")
            return
        }
        val next = Typing(request.captureId, request.maxChars, request.initialText)
        val (replaced, replacedDictation) = synchronized(lock) {
            val current = typing
            if (current?.captureId == request.captureId) return
            typing = next
            val stoppedDictation = dictation
            current to stoppedDictation
        }
        replacedDictation?.let {
            finishDictation(it, ComposeDictationReason.CANCELLED)
            host.cancelUtterance(it)
        }
        // The screen moves to the new capture; the one it replaces ends uncommitted.
        replaced?.let { sendDone(it.captureId, committedText = null) }
        if (!host.openKeyboard(request)) {
            // Neither the keyboard screen nor its notification can come up: the capture ends
            // uncommitted now rather than leave the glasses waiting for text.
            val failed = synchronized(lock) { typing?.takeIf { it.captureId == request.captureId }?.also { typing = null } }
            failed?.let { sendDone(it.captureId, committedText = null) }
            host.log("compose typing could not open the phone keyboard capture=${request.captureId}")
            return
        }
        host.log("compose typing started capture=${request.captureId}")
    }

    private fun endTypingFromGlasses(payload: JSONObject, commit: Boolean) {
        val captureId = ComposeContract.parseTypeEnd(payload)
        val session = synchronized(lock) {
            typing?.takeIf { it.captureId == captureId }?.also { typing = null }
        }
        if (session == null) {
            host.log("compose typing ${if (commit) "finish" else "cancel"} ignored reason=stale")
            return
        }
        closeTyping(session, committedText = if (commit) session.text else null)
    }

    /** The phone field changed. Text past the capture's ceiling never leaves the phone. */
    fun onPhoneText(captureId: String, text: String) {
        val mirrored = synchronized(lock) {
            val session = typing?.takeIf { it.captureId == captureId } ?: return
            val clipped = text.take(session.maxChars)
            if (clipped == session.text) return
            session.text = clipped
            clipped
        }
        host.sendToGlasses(
            ComposeContract.TYPE_TEXT_PATH,
            ComposeContract.typeText(ComposeTypeText(captureId, mirrored)),
        )
    }

    /** The user ended typing on the phone: Done commits, Cancel or leaving the screen does not. */
    fun onPhoneDone(captureId: String, commit: Boolean, text: String? = null) {
        val session = synchronized(lock) {
            typing?.takeIf { it.captureId == captureId }?.also { session ->
                typing = null
                text?.let { session.text = it.take(session.maxChars) }
            }
        } ?: return
        closeTyping(session, committedText = if (commit) session.text else null)
    }

    private fun closeTyping(session: Typing, committedText: String?) {
        host.closeKeyboard(session.captureId)
        sendDone(session.captureId, committedText)
    }

    private fun sendDone(captureId: String, committedText: String?) {
        val done = if (committedText != null) {
            ComposeTypeDone(captureId, committed = true, text = committedText)
        } else {
            ComposeTypeDone(captureId, committed = false)
        }
        host.sendToGlasses(ComposeContract.TYPE_DONE_PATH, ComposeContract.typeDone(done))
        host.log("compose typing ended capture=$captureId committed=${done.committed}")
    }

    private class Typing(val captureId: String, val maxChars: Int, var text: String)

    companion object {
        /** The glasses microphone that has not sent a byte by then is not going to. */
        const val FIRST_AUDIO_TIMEOUT_MS = 3_000L

        /** The glasses' language tag as the hub speech engine knows it, or null for the user's choice. */
        fun composeLanguage(tag: String?): TranscriptionLanguage? {
            val normalized = tag?.trim()?.lowercase(Locale.US)?.takeIf { it.isNotEmpty() } ?: return null
            val languages = TranscriptionLanguage.entries.filter { it != TranscriptionLanguage.AUTO }
            return languages.firstOrNull { it.id == normalized }
                ?: languages.firstOrNull { it.androidTag?.lowercase(Locale.US) == normalized }
                ?: languages.firstOrNull { it.id == normalized.substringBefore('-') }
        }
    }
}
