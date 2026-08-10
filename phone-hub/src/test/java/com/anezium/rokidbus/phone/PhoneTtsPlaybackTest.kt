package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.TtsContract
import com.anezium.rokidbus.shared.TtsDoneEvent
import com.anezium.rokidbus.shared.TtsDoneReason
import com.anezium.rokidbus.shared.TtsSpeakRequest
import com.anezium.rokidbus.shared.TtsStopRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class PhoneTtsPlaybackTest {
    @Test
    fun `prewarm is skipped when the engine is not ready`() {
        val output = FakePhoneTtsOutput(ready = false)

        assertFalse(playback(output, mutableListOf()).prewarm())

        assertEquals(0, output.prewarmCount)
    }

    @Test
    fun `prewarm is skipped while a plugin utterance is active`() {
        val output = FakePhoneTtsOutput(ready = true)
        val playback = playback(output, mutableListOf())
        playback.speak(speak("alpha", "u1", "hello"))

        assertFalse(playback.prewarm())

        assertEquals(0, output.prewarmCount)
    }

    @Test
    fun `reserved prewarm and sample ids emit no plugin events`() {
        val output = FakePhoneTtsOutput(ready = true)
        val started = mutableListOf<Pair<String, String>>()
        val done = mutableListOf<TtsDoneEvent>()
        PhoneTtsPlayback(
            output = output,
            emitStarted = { owner, id -> started += owner to id },
            emitDone = done::add,
        )

        output.start(PhoneTtsEngine.PREWARM_UTTERANCE_ID)
        output.done(PhoneTtsEngine.PREWARM_UTTERANCE_ID)
        output.start(PhoneTtsEngine.SAMPLE_UTTERANCE_ID)
        output.done(PhoneTtsEngine.SAMPLE_UTTERANCE_ID)

        assertTrue(started.isEmpty())
        assertTrue(done.isEmpty())
    }

    @Test
    fun `sample refuses while a plugin utterance is active`() {
        val output = FakePhoneTtsOutput(ready = true)
        val playback = playback(output, mutableListOf())
        playback.speak(speak("alpha", "u1", "hello"))

        assertFalse(playback.speakSample("sample", Locale.US))

        assertEquals(0, output.sampleCount)
    }

    @Test
    fun `preemption and completion each emit one terminal event`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val playback = playback(output, done)

        assertTrue(playback.speak(speak("alpha", "u1", "one")))
        output.start()
        assertTrue(playback.speak(speak("beta", "u2", "two")))
        output.done()
        output.done()

        assertEquals(
            listOf(
                TtsDoneEvent("alpha", "u1", TtsDoneReason.PREEMPTED),
                TtsDoneEvent("beta", "u2", TtsDoneReason.COMPLETED),
            ),
            done,
        )
    }

    @Test
    fun `explicit stop emits STOPPED exactly once`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val playback = playback(output, done)
        playback.speak(speak("alpha", "u1", "hello"))

        assertTrue(playback.stop(TtsStopRequest("u1", "alpha")))
        output.stopped()

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.STOPPED)), done)
        assertEquals(1, output.stopCount)
    }

    @Test
    fun `synchronous engine failure emits UNAVAILABLE exactly once`() {
        val output = FakePhoneTtsOutput(
            ready = true,
            speakResult = PhoneTtsSpeakResult.ENGINE_UNAVAILABLE,
        )
        val done = mutableListOf<TtsDoneEvent>()
        val playback = playback(output, done)

        assertTrue(playback.speak(speak("alpha", "u1", "hello")))
        output.unavailable()

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE)), done)
    }

    @Test
    fun `locale resolution uses system then setting while wire language wins`() {
        val output = FakePhoneTtsOutput(ready = true)
        var storedLanguageTag: String? = null
        val playback = PhoneTtsPlayback(
            output = output,
            storedLanguageTag = { storedLanguageTag },
            systemLocale = { Locale.CANADA_FRENCH },
            engineId = sequenceIds(),
            emitStarted = { _, _ -> },
            emitDone = {},
        )

        playback.speak(speak("alpha", "u1", "bonjour"))
        storedLanguageTag = "pt-BR"
        playback.speak(speak("alpha", "u2", "olá"))
        playback.speak(speak("alpha", "u3", "bonjour", "fr-FR"))

        assertEquals(
            listOf(
                Locale.CANADA_FRENCH,
                Locale.forLanguageTag("pt-BR"),
                Locale.forLanguageTag("fr-FR"),
            ),
            output.locales,
        )
    }

    @Test
    fun `external sink speaks immediately with the phone engine`() {
        val output = FakePhoneTtsOutput(ready = true)
        val logs = mutableListOf<String>()
        val dispatcher = dispatcher(
            output = output,
            phoneRoute = { PhoneTtsRoute.EXTERNAL_SINK },
            logger = logs::add,
        )

        assertEquals(
            PhoneTtsDispatchResult.PhoneHandled,
            dispatcher.dispatch(routedSpeak("alpha", "u1", "hello")),
        )

        assertEquals(listOf("engine-1"), output.spokenIds)
        assertEquals(0, output.prewarmCount)
        assertEquals(
            listOf("phone TTS dispatch classification=EXTERNAL_SINK route=phone"),
            logs,
        )
    }

    @Test
    fun `glasses link prewarms and speaks with the phone engine`() {
        val output = FakePhoneTtsOutput(ready = true)
        val dispatcher = dispatcher(
            output = output,
            phoneRoute = { PhoneTtsRoute.GLASSES_LINK },
        )

        assertEquals(
            PhoneTtsDispatchResult.PhoneHandled,
            dispatcher.dispatch(routedSpeak("alpha", "u1", "hello")),
        )

        assertEquals(1, output.prewarmCount)
        assertEquals(listOf("engine-1"), output.spokenIds)
    }

    @Test
    fun `phone speaker waits and speaks when the probe finds an ear before the budget`() {
        val output = FakePhoneTtsOutput(ready = true)
        val scheduler = ManualScheduler()
        var route = PhoneTtsRoute.PHONE_SPEAKER
        val dispatcher = dispatcher(
            output = output,
            phoneRoute = { route },
            scheduler = scheduler,
        )

        assertEquals(
            PhoneTtsDispatchResult.PhoneHandled,
            dispatcher.dispatch(routedSpeak("alpha", "u1", "hello")),
        )
        assertEquals(1, output.prewarmCount)
        assertTrue(output.spokenIds.isEmpty())

        scheduler.advanceBy(1_000L)
        assertTrue(output.spokenIds.isEmpty())
        route = PhoneTtsRoute.EXTERNAL_SINK
        scheduler.advanceBy(PhoneTtsDispatcher.ROUTE_REPROBE_INTERVAL_MS)

        assertEquals(listOf("engine-1"), output.spokenIds)
    }

    @Test
    fun `phone speaker drops with a terminal event when the wait budget expires`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val logs = mutableListOf<String>()
        val scheduler = ManualScheduler()
        val dispatcher = dispatcher(
            output = output,
            done = done,
            phoneRoute = { PhoneTtsRoute.PHONE_SPEAKER },
            logger = logs::add,
            scheduler = scheduler,
        )

        dispatcher.dispatch(routedSpeak("alpha", "u1", "hello"))
        scheduler.advanceBy(PhoneTtsDispatcher.ROUTE_WAIT_BUDGET_MS - 1L)
        assertTrue(done.isEmpty())
        scheduler.advanceBy(1L)

        assertTrue(output.spokenIds.isEmpty())
        assertEquals(
            listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE)),
            done,
        )
        assertTrue(
            logs.contains(
                "phone TTS dispatch classification=PHONE_SPEAKER " +
                    "route=dropped reason=no_ear id=u1",
            ),
        )
    }

    @Test
    fun `unready phone engine initializes and speaks if ready within the budget`() {
        val output = FakePhoneTtsOutput(ready = false)
        val scheduler = ManualScheduler()
        val dispatcher = dispatcher(
            output = output,
            phoneRoute = { PhoneTtsRoute.EXTERNAL_SINK },
            scheduler = scheduler,
        )

        dispatcher.dispatch(routedSpeak("alpha", "u1", "hello"))
        assertEquals(1, output.initializeCount)
        output.ready = true
        scheduler.advanceBy(PhoneTtsDispatcher.ROUTE_REPROBE_INTERVAL_MS)

        assertEquals(listOf("engine-1"), output.spokenIds)
    }

    @Test
    fun `phone engine async failure drops with one terminal event`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val logs = mutableListOf<String>()
        val dispatcher = dispatcher(
            output = output,
            done = done,
            phoneRoute = { PhoneTtsRoute.EXTERNAL_SINK },
            logger = logs::add,
        )

        dispatcher.dispatch(routedSpeak("alpha", "u1", "hello"))
        output.unavailable()
        output.unavailable()

        assertEquals(
            listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.UNAVAILABLE)),
            done,
        )
        assertTrue(
            logs.contains(
                "phone TTS dispatch classification=ASYNC_PHONE_FAILURE " +
                    "route=dropped reason=phone_unavailable id=u1",
            ),
        )
    }

    @Test
    fun `utterances queued during route wait reach playback in arrival order`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val scheduler = ManualScheduler()
        var route = PhoneTtsRoute.PHONE_SPEAKER
        val dispatcher = dispatcher(
            output = output,
            done = done,
            phoneRoute = { route },
            scheduler = scheduler,
        )

        dispatcher.dispatch(routedSpeak("alpha", "u1", "one"))
        scheduler.advanceBy(250L)
        dispatcher.dispatch(routedSpeak("beta", "u2", "two"))
        route = PhoneTtsRoute.EXTERNAL_SINK
        scheduler.advanceBy(250L)

        assertEquals(listOf("engine-1", "engine-2"), output.spokenIds)
        assertEquals(
            listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.PREEMPTED)),
            done,
        )
    }

    @Test
    fun `stop retires a waiting utterance and no scheduled probe speaks it`() {
        val output = FakePhoneTtsOutput(ready = true)
        val done = mutableListOf<TtsDoneEvent>()
        val scheduler = ManualScheduler()
        var route = PhoneTtsRoute.PHONE_SPEAKER
        val dispatcher = dispatcher(
            output = output,
            done = done,
            phoneRoute = { route },
            scheduler = scheduler,
        )
        dispatcher.dispatch(routedSpeak("alpha", "u1", "hello"))

        assertEquals(
            PhoneTtsDispatchResult.PhoneHandled,
            dispatcher.dispatch(routedStop("alpha", "u1")),
        )
        route = PhoneTtsRoute.EXTERNAL_SINK
        scheduler.advanceBy(PhoneTtsDispatcher.ROUTE_WAIT_BUDGET_MS)

        assertEquals(listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.STOPPED)), done)
        assertTrue(output.spokenIds.isEmpty())
    }

    @Test
    fun `microphone interlock cancels active and waiting phone speech`() {
        val activeOutput = FakePhoneTtsOutput(ready = true)
        val activeDone = mutableListOf<TtsDoneEvent>()
        val activeDispatcher = dispatcher(
            output = activeOutput,
            done = activeDone,
            phoneRoute = { PhoneTtsRoute.EXTERNAL_SINK },
        )
        activeDispatcher.dispatch(routedSpeak("alpha", "u1", "active"))

        activeDispatcher.cancelForMicrophone()
        activeOutput.stopped()

        assertEquals(
            listOf(TtsDoneEvent("alpha", "u1", TtsDoneReason.CANCELLED)),
            activeDone,
        )

        val waitingOutput = FakePhoneTtsOutput(ready = true)
        val waitingDone = mutableListOf<TtsDoneEvent>()
        val scheduler = ManualScheduler()
        val waitingDispatcher = dispatcher(
            output = waitingOutput,
            done = waitingDone,
            phoneRoute = { PhoneTtsRoute.PHONE_SPEAKER },
            scheduler = scheduler,
        )
        waitingDispatcher.dispatch(routedSpeak("beta", "u2", "waiting"))

        waitingDispatcher.cancelForMicrophone()
        scheduler.advanceBy(PhoneTtsDispatcher.ROUTE_WAIT_BUDGET_MS)

        assertEquals(
            listOf(TtsDoneEvent("beta", "u2", TtsDoneReason.CANCELLED)),
            waitingDone,
        )
        assertTrue(waitingOutput.spokenIds.isEmpty())
    }

    @Test
    fun `voice sample never uses the phone speaker`() {
        val output = FakePhoneTtsOutput(ready = true)
        var route = PhoneTtsRoute.PHONE_SPEAKER
        val dispatcher = dispatcher(output = output, phoneRoute = { route })

        assertFalse(dispatcher.speakSample("sample", Locale.US))
        assertEquals(1, output.prewarmCount)
        assertEquals(0, output.sampleCount)

        route = PhoneTtsRoute.GLASSES_LINK
        assertTrue(dispatcher.speakSample("sample", Locale.US))
        assertEquals(2, output.prewarmCount)
        assertEquals(1, output.sampleCount)
    }

    private fun dispatcher(
        output: FakePhoneTtsOutput,
        done: MutableList<TtsDoneEvent> = mutableListOf(),
        phoneRoute: () -> PhoneTtsRoute,
        logger: (String) -> Unit = {},
        scheduler: ManualScheduler? = null,
    ) = PhoneTtsDispatcher(
        playback = playback(output, done),
        emitDone = done::add,
        phoneRoute = phoneRoute,
        logger = logger,
        nowMs = scheduler?.let { clock -> { clock.nowMs } } ?: { 0L },
        scheduleAfter = scheduler?.let { clock -> clock::schedule } ?: { _, _ -> true },
    )

    private fun playback(
        output: FakePhoneTtsOutput,
        done: MutableList<TtsDoneEvent>,
    ) = PhoneTtsPlayback(
        output = output,
        engineId = sequenceIds(),
        emitStarted = { _, _ -> },
        emitDone = done::add,
    )

    private fun speak(
        owner: String,
        utteranceId: String,
        text: String,
        lang: String? = null,
    ) = TtsSpeakRequest(utteranceId, text, owner, lang)

    private fun routedSpeak(
        owner: String,
        utteranceId: String,
        text: String,
        lang: String? = null,
    ) = BusEnvelope(
        path = BusPaths.TTS_SPEAK,
        payload = TtsContract.withOwner(
            TtsContract.speakPayload(utteranceId, text, lang),
            owner,
        ),
    )

    private fun routedStop(owner: String, utteranceId: String) = BusEnvelope(
        path = BusPaths.TTS_STOP,
        payload = TtsContract.withOwner(TtsContract.stopPayload(utteranceId), owner),
    )

    private fun sequenceIds(): () -> String {
        var next = 0
        return { "engine-${++next}" }
    }

    private class ManualScheduler {
        private data class Scheduled(
            val atMs: Long,
            val sequence: Long,
            val action: () -> Unit,
        )

        var nowMs = 0L
            private set
        private var nextSequence = 0L
        private val scheduled = mutableListOf<Scheduled>()

        fun schedule(delayMs: Long, action: () -> Unit): Boolean {
            scheduled += Scheduled(nowMs + delayMs, nextSequence++, action)
            return true
        }

        fun advanceBy(durationMs: Long) {
            val targetMs = nowMs + durationMs
            while (true) {
                val next = scheduled
                    .filter { it.atMs <= targetMs }
                    .minWithOrNull(compareBy<Scheduled> { it.atMs }.thenBy { it.sequence })
                    ?: break
                scheduled.remove(next)
                nowMs = next.atMs
                next.action()
            }
            nowMs = targetMs
        }
    }

    private class FakePhoneTtsOutput(
        var ready: Boolean,
        private val speakResult: PhoneTtsSpeakResult = PhoneTtsSpeakResult.ACCEPTED,
    ) : PhoneTtsOutput {
        private lateinit var listener: PhoneTtsOutput.Listener
        var initializeCount = 0
        var stopCount = 0
        var prewarmCount = 0
        var sampleCount = 0
        val spokenIds = mutableListOf<String>()
        val locales = mutableListOf<Locale>()

        override val isReady: Boolean
            get() = ready

        override fun setListener(listener: PhoneTtsOutput.Listener) {
            this.listener = listener
        }

        override fun initialize() {
            initializeCount += 1
        }

        override fun speak(
            utteranceId: String,
            text: String,
            locale: Locale,
        ): PhoneTtsSpeakResult {
            spokenIds += utteranceId
            locales += locale
            return speakResult
        }

        override fun prewarm(): Boolean {
            prewarmCount += 1
            return ready
        }

        override fun availableVoices(locale: Locale?): List<PhoneTtsVoiceOption> = emptyList()

        override fun speakSample(text: String, locale: Locale): Boolean {
            sampleCount += 1
            return ready
        }

        override fun stop() {
            stopCount += 1
        }

        override fun shutdown() = Unit

        fun start(utteranceId: String = spokenIds.last()) = listener.onStart(utteranceId)
        fun done(utteranceId: String = spokenIds.last()) = listener.onDone(utteranceId)
        fun stopped() = listener.onStopped(spokenIds.last())
        fun unavailable() = listener.onUnavailable(spokenIds.last())
    }
}
