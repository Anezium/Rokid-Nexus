package com.anezium.rokidbus.phone

import com.anezium.rokidbus.phone.speech.SpeechEndReason
import com.anezium.rokidbus.phone.speech.SpeechStartResult
import com.anezium.rokidbus.phone.speech.SpeechUtteranceListener
import com.anezium.rokidbus.phone.speech.TranscriptionLanguage
import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.ComposeDictateStart
import com.anezium.rokidbus.shared.ComposeDictateStop
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ComposeTypeStart
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneComposeRouterTest {
    private val host = FakeComposeHost()
    private val router = PhoneComposeRouter(host)

    // ---- Dictation ----

    @Test
    fun `dictation streams partials and ends completed with the recognized text`() {
        startDictation(CAPTURE_A, "fr-FR")
        val listener = host.listeners.single()
        listener.onFirstAudio()
        listener.onPartial("on my")
        listener.onPartial("on my")
        listener.onFinal("on my way")
        listener.onEnded(SpeechEndReason.COMPLETED, null)

        assertEquals(listOf(TranscriptionLanguage.FRENCH), host.languages)
        val partial = ComposeContract.parseDictatePartial(host.sent(ComposeContract.DICTATE_PARTIAL_PATH).single())!!
        assertEquals("on my", partial.text)
        val final = finals().single()
        assertEquals(ComposeDictationReason.COMPLETED, final.reason)
        assertEquals("on my way", final.text)
        assertEquals(CAPTURE_A, final.captureId)
    }

    @Test
    fun `the composer only ever asks for the glasses microphone`() {
        startDictation(CAPTURE_A)
        host.listeners.single().onEnded(SpeechEndReason.CANCELLED, null)
        host.startResult = SpeechStartResult.NO_LINK
        startDictation(CAPTURE_B)

        assertEquals(listOf(true, true), host.glassesAudioOnly)
    }

    @Test
    fun `a plugin holding the hub speech session makes the dictation busy`() {
        host.pluginSpeech = true
        startDictation(CAPTURE_A)

        assertTrue(host.listeners.isEmpty())
        assertEquals(ComposeDictationReason.BUSY, finals().single().reason)
    }

    @Test
    fun `an engine already in use makes the dictation busy`() {
        host.startResult = SpeechStartResult.BUSY
        startDictation(CAPTURE_A)

        assertEquals(ComposeDictationReason.BUSY, finals().single().reason)
    }

    @Test
    fun `a second dictation while one runs is busy and leaves the first alone`() {
        startDictation(CAPTURE_A)
        startDictation(CAPTURE_B)

        val final = finals().single()
        assertEquals(CAPTURE_B, final.captureId)
        assertEquals(ComposeDictationReason.BUSY, final.reason)
        assertEquals(1, host.listeners.size)
        assertTrue(host.cancelled.isEmpty())
    }

    @Test
    fun `a repeated start for the same capture is ignored`() {
        startDictation(CAPTURE_A)
        startDictation(CAPTURE_A)

        assertEquals(1, host.listeners.size)
        assertTrue(finals().isEmpty())
    }

    @Test
    fun `a glasses mic that cannot open ends mic_unavailable, an unconfigured engine ends error`() {
        host.startResult = SpeechStartResult.NO_LINK
        startDictation(CAPTURE_A)
        host.startResult = SpeechStartResult.START_FAILED
        startDictation(CAPTURE_B)
        host.startResult = SpeechStartResult.NOT_READY
        startDictation(CAPTURE_C)

        assertEquals(
            listOf(
                ComposeDictationReason.MIC_UNAVAILABLE,
                ComposeDictationReason.MIC_UNAVAILABLE,
                ComposeDictationReason.ERROR,
            ),
            finals().map { it.reason },
        )
    }

    @Test
    fun `no glasses audio before the first-byte deadline ends mic_unavailable`() {
        startDictation(CAPTURE_A)
        assertEquals(PhoneComposeRouter.FIRST_AUDIO_TIMEOUT_MS, host.timers.single().delayMs)

        host.timers.single().task()

        assertEquals(ComposeDictationReason.MIC_UNAVAILABLE, finals().single().reason)
        assertEquals(host.listeners.single(), host.cancelled.single())
        host.listeners.single().onEnded(SpeechEndReason.CANCELLED, null)
        assertEquals(1, finals().size)
    }

    @Test
    fun `the first glasses audio disarms the first-byte deadline`() {
        startDictation(CAPTURE_A)
        host.listeners.single().onFirstAudio()

        assertTrue(host.timers.single().cancelled)
        host.timers.single().task()
        assertTrue(finals().isEmpty())
    }

    @Test
    fun `stop without cancel finishes the utterance and silence ends no_speech`() {
        startDictation(CAPTURE_A)
        val listener = host.listeners.single()
        listener.onFirstAudio()
        router.handleGlasses(ComposeContract.DICTATE_STOP_PATH, ComposeContract.dictateStop(ComposeDictateStop(CAPTURE_A, cancel = false)))

        assertEquals(listener, host.finished.single())
        assertTrue(finals().isEmpty())
        listener.onEnded(SpeechEndReason.NO_SPEECH, null)
        assertEquals(ComposeDictationReason.NO_SPEECH, finals().single().reason)
    }

    @Test
    fun `stop with cancel answers cancelled at once and cancels the engine`() {
        startDictation(CAPTURE_A)
        val listener = host.listeners.single()
        router.handleGlasses(ComposeContract.DICTATE_STOP_PATH, ComposeContract.dictateStop(ComposeDictateStop(CAPTURE_A, cancel = true)))

        assertEquals(ComposeDictationReason.CANCELLED, finals().single().reason)
        assertEquals(listener, host.cancelled.single())
        listener.onFinal("too late")
        listener.onEnded(SpeechEndReason.COMPLETED, null)
        assertEquals(1, finals().size)
    }

    @Test
    fun `a stop for another capture is stale and ignored`() {
        startDictation(CAPTURE_A)
        router.handleGlasses(ComposeContract.DICTATE_STOP_PATH, ComposeContract.dictateStop(ComposeDictateStop(CAPTURE_B, cancel = true)))

        assertTrue(host.cancelled.isEmpty())
        assertTrue(finals().isEmpty())
    }

    @Test
    fun `losing the glasses audio mid-utterance ends link_lost, before any byte mic_unavailable`() {
        startDictation(CAPTURE_A)
        host.listeners[0].onFirstAudio()
        host.listeners[0].onEnded(SpeechEndReason.LINK_LOST, null)
        startDictation(CAPTURE_B)
        host.listeners[1].onEnded(SpeechEndReason.LINK_LOST, null)

        assertEquals(listOf(ComposeDictationReason.LINK_LOST, ComposeDictationReason.MIC_UNAVAILABLE), finals().map { it.reason })
    }

    @Test
    fun `a completed utterance with no text ends no_speech`() {
        startDictation(CAPTURE_A)
        host.listeners.single().onFirstAudio()
        host.listeners.single().onFinal("   ")
        host.listeners.single().onEnded(SpeechEndReason.COMPLETED, null)

        assertEquals(ComposeDictationReason.NO_SPEECH, finals().single().reason)
        assertNull(finals().single().text)
    }

    @Test
    fun `link loss cancels the dictation without sending anything`() {
        startDictation(CAPTURE_A)
        val listener = host.listeners.single()
        router.onLinkLost()

        assertEquals(listener, host.cancelled.single())
        assertTrue(host.timers.single().cancelled)
        listener.onPartial("late")
        listener.onEnded(SpeechEndReason.CANCELLED, null)
        assertTrue(host.messages.isEmpty())
        startDictation(CAPTURE_B)
        assertEquals(2, host.listeners.size)
    }

    @Test
    fun `malformed starts are ignored`() {
        router.handleGlasses(ComposeContract.DICTATE_START_PATH, JSONObject().put("captureId", "short"))
        router.handleGlasses(ComposeContract.TYPE_START_PATH, JSONObject().put("captureId", CAPTURE_A))

        assertTrue(host.listeners.isEmpty())
        assertTrue(host.opened.isEmpty())
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun `glasses language tags map onto the hub speech languages`() {
        assertEquals(TranscriptionLanguage.FRENCH, PhoneComposeRouter.composeLanguage("fr-FR"))
        assertEquals(TranscriptionLanguage.FRENCH, PhoneComposeRouter.composeLanguage("fr"))
        assertEquals(TranscriptionLanguage.ENGLISH, PhoneComposeRouter.composeLanguage("EN-us"))
        assertNull(PhoneComposeRouter.composeLanguage("auto"))
        assertNull(PhoneComposeRouter.composeLanguage(null))
    }

    // ---- Typing ----

    @Test
    fun `typing opens the phone keyboard and mirrors the field within maxChars`() {
        startTyping(CAPTURE_A, "Hi", maxChars = 10)
        router.onPhoneText(CAPTURE_A, "Hi there")
        router.onPhoneText(CAPTURE_A, "Hi there")
        router.onPhoneText(CAPTURE_A, "Hi there, see you soon")

        assertEquals(ComposeTypeStart(CAPTURE_A, "Hi", 10), host.opened.single())
        val texts = host.sent(ComposeContract.TYPE_TEXT_PATH).map { ComposeContract.parseTypeText(it)!!.text }
        assertEquals(listOf("Hi there", "Hi there, "), texts)
        assertTrue(host.listeners.isEmpty())
    }

    @Test
    fun `finish from the glasses commits the current text and closes the keyboard`() {
        startTyping(CAPTURE_A, "Hi")
        router.onPhoneText(CAPTURE_A, "Hi Marie")
        router.handleGlasses(ComposeContract.TYPE_FINISH_PATH, ComposeContract.typeEnd(CAPTURE_A))

        val done = dones().single()
        assertTrue(done.committed)
        assertEquals("Hi Marie", done.text)
        assertEquals(listOf(CAPTURE_A), host.closed)
    }

    @Test
    fun `finish before any typing commits the initial text`() {
        startTyping(CAPTURE_A, "Draft")
        router.handleGlasses(ComposeContract.TYPE_FINISH_PATH, ComposeContract.typeEnd(CAPTURE_A))

        assertEquals("Draft", dones().single().text)
    }

    @Test
    fun `cancel from the glasses ends uncommitted and closes the keyboard`() {
        startTyping(CAPTURE_A, "Hi")
        router.onPhoneText(CAPTURE_A, "Hi Marie")
        router.handleGlasses(ComposeContract.TYPE_CANCEL_PATH, ComposeContract.typeEnd(CAPTURE_A))

        val done = dones().single()
        assertFalse(done.committed)
        assertNull(done.text)
        assertEquals(listOf(CAPTURE_A), host.closed)
    }

    @Test
    fun `done and cancel on the phone end the capture the same way`() {
        startTyping(CAPTURE_A, "")
        router.onPhoneDone(CAPTURE_A, commit = true, text = "")
        startTyping(CAPTURE_B, "x")
        router.onPhoneDone(CAPTURE_B, commit = false, text = "xyz")

        val done = dones()
        assertTrue(done[0].committed)
        assertEquals("", done[0].text)
        assertFalse(done[1].committed)
    }

    @Test
    fun `one typing capture at a time - a new one ends the old uncommitted`() {
        startTyping(CAPTURE_A, "old")
        startTyping(CAPTURE_B, "new")
        router.onPhoneText(CAPTURE_A, "ignored")

        val done = dones().single()
        assertEquals(CAPTURE_A, done.captureId)
        assertFalse(done.committed)
        assertEquals(listOf(CAPTURE_A, CAPTURE_B), host.opened.map { it.captureId })
        assertTrue(host.sent(ComposeContract.TYPE_TEXT_PATH).isEmpty())
    }

    @Test
    fun `typing replaces a running dictation and dictation replaces typing`() {
        startDictation(CAPTURE_A)
        startTyping(CAPTURE_B, "")
        assertEquals(ComposeDictationReason.CANCELLED, finals().single().reason)
        assertEquals(host.listeners.single(), host.cancelled.single())

        startDictation(CAPTURE_C)
        val done = dones().single()
        assertEquals(CAPTURE_B, done.captureId)
        assertFalse(done.committed)
        assertEquals(listOf(CAPTURE_B), host.closed)
    }

    @Test
    fun `link loss dismisses the keyboard without sending and later phone input is dropped`() {
        startTyping(CAPTURE_A, "Hi")
        router.onLinkLost()
        router.onPhoneText(CAPTURE_A, "Hi again")
        router.onPhoneDone(CAPTURE_A, commit = true, text = "Hi again")

        assertEquals(listOf(CAPTURE_A), host.closed)
        assertTrue(host.messages.isEmpty())
    }

    @Test
    fun `a finish for another capture is stale`() {
        startTyping(CAPTURE_A, "Hi")
        router.handleGlasses(ComposeContract.TYPE_FINISH_PATH, ComposeContract.typeEnd(CAPTURE_B))

        assertTrue(dones().isEmpty())
        assertTrue(host.closed.isEmpty())
    }

    private fun startDictation(captureId: String, language: String? = null) =
        router.handleGlasses(
            ComposeContract.DICTATE_START_PATH,
            ComposeContract.dictateStart(ComposeDictateStart(captureId, language)),
        )

    private fun startTyping(captureId: String, initialText: String, maxChars: Int = 500) =
        router.handleGlasses(
            ComposeContract.TYPE_START_PATH,
            ComposeContract.typeStart(ComposeTypeStart(captureId, initialText, maxChars)),
        )

    private fun finals() = host.sent(ComposeContract.DICTATE_FINAL_PATH).map { ComposeContract.parseDictateFinal(it)!! }

    private fun dones() = host.sent(ComposeContract.TYPE_DONE_PATH).map { ComposeContract.parseTypeDone(it)!! }

    private class FakeComposeHost : PhoneComposeHost {
        class Timer(val delayMs: Long, val task: () -> Unit) {
            var cancelled = false
        }

        var startResult = SpeechStartResult.OK
        var pluginSpeech = false
        val listeners = mutableListOf<SpeechUtteranceListener>()
        val languages = mutableListOf<TranscriptionLanguage?>()
        val glassesAudioOnly = mutableListOf<Boolean>()
        val finished = mutableListOf<SpeechUtteranceListener>()
        val cancelled = mutableListOf<SpeechUtteranceListener>()
        val opened = mutableListOf<ComposeTypeStart>()
        val closed = mutableListOf<String>()
        val messages = mutableListOf<Pair<String, JSONObject>>()
        val timers = mutableListOf<Timer>()

        fun sent(path: String): List<JSONObject> = messages.filter { it.first == path }.map { it.second }

        override fun startUtterance(
            listener: SpeechUtteranceListener,
            language: TranscriptionLanguage?,
            glassesAudioOnly: Boolean,
        ): SpeechStartResult {
            this.glassesAudioOnly += glassesAudioOnly
            languages += language
            if (startResult == SpeechStartResult.OK) listeners += listener
            return startResult
        }

        override fun finishUtterance(listener: SpeechUtteranceListener) {
            finished += listener
        }

        override fun cancelUtterance(listener: SpeechUtteranceListener) {
            cancelled += listener
        }

        override fun pluginSpeechActive(): Boolean = pluginSpeech

        override fun openKeyboard(request: ComposeTypeStart) {
            opened += request
        }

        override fun closeKeyboard(captureId: String) {
            closed += captureId
        }

        override fun sendToGlasses(path: String, payload: JSONObject): String? {
            messages += path to payload
            return null
        }

        override fun schedule(delayMs: Long, task: () -> Unit): ComposeTimer {
            val timer = Timer(delayMs, task)
            timers += timer
            return ComposeTimer { timer.cancelled = true }
        }

        override fun log(message: String) = Unit
    }

    private companion object {
        const val CAPTURE_A = "capture-aaaa-0001"
        const val CAPTURE_B = "capture-bbbb-0002"
        const val CAPTURE_C = "capture-cccc-0003"
    }
}
