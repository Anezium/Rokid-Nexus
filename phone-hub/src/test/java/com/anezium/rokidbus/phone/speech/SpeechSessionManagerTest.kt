package com.anezium.rokidbus.phone.speech

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
class SpeechSessionManagerTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private lateinit var settings: SpeechSettingsStore
    private lateinit var secrets: HubSecretStore

    @Before
    fun setUp() {
        context.getSharedPreferences(SpeechSettingsStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        settings = SpeechSettingsStore(context).apply {
            selectedEngineId = SpeechEngine.OPENAI_GPT_REALTIME_WHISPER.id
            selectedLanguageId = TranscriptionLanguage.AUTO.id
        }
        secrets = HubSecretStore(context)
    }

    @Test
    fun cancelWhileAcquireIsBlockedReleasesLeaseAfterAcquireCompletes() {
        val audio = BlockingAudioAccess()
        val engine = RecordingSession()
        val listener = RecordingUtteranceListener()
        val manager = manager(audio, engine, AtomicLong(1_000L))
        var result: SpeechStartResult? = null
        val startThread = Thread {
            result = manager.startUtterance(listener)
        }.apply { start() }

        assertTrue(audio.acquireEntered.await(2, TimeUnit.SECONDS))
        manager.cancel()
        assertTrue(listener.ended.await(2, TimeUnit.SECONDS))
        audio.allowAcquire.countDown()
        startThread.join(2_000L)

        assertEquals(SpeechStartResult.OK, result)
        assertTrue(audio.released.await(2, TimeUnit.SECONDS))
        assertEquals(1, audio.releaseCalls.get())
        manager.close()
    }

    @Test
    fun noSpeechSessionUploadsNeitherAudioNorCommit() {
        val audio = ImmediateAudioAccess()
        val engine = RecordingSession()
        val listener = RecordingUtteranceListener()
        val clock = AtomicLong(1_000L)
        val manager = manager(audio, engine, clock)

        assertEquals(SpeechStartResult.OK, manager.startUtterance(listener))
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        audio.consumer!!.onPcm(ByteArray(3_200), 0, 3_200, 0L, 1_010L)
        clock.set(10_000L)

        assertTrue(listener.ended.await(2, TimeUnit.SECONDS))
        assertEquals(SpeechEndReason.NO_SPEECH, listener.reason)
        assertEquals(0, engine.acceptCalls.get())
        assertEquals(0, engine.finishCalls.get())
        assertTrue(engine.cancelCalls.get() > 0)
        assertEquals(1, audio.releaseCalls.get())
        manager.close()
    }

    @Test
    fun endpointHandsTheMicrophoneBackBeforeTheProviderAnswers() {
        val audio = ImmediateAudioAccess()
        val engine = RecordingSession()
        val listener = RecordingUtteranceListener()
        val clock = AtomicLong(1_000L)
        val manager = manager(audio, engine, clock)

        assertEquals(SpeechStartResult.OK, manager.startUtterance(listener))
        assertTrue(engine.started.await(2, TimeUnit.SECONDS))
        audio.consumer!!.onPcm(voicePcm(1_600), 0, 3_200, 0L, 1_010L)
        // Let the speech chunk land before the clock jumps, so the detector times the
        // silence from the moment the voice was heard.
        assertTrue(engine.accepted.await(2, TimeUnit.SECONDS))
        clock.set(10_000L)

        assertTrue(engine.finished.await(2, TimeUnit.SECONDS))
        assertEquals(1, audio.releaseCalls.get())
        assertEquals(null, listener.reason)

        engine.listener.onFinal("bonjour")
        assertTrue(listener.ended.await(2, TimeUnit.SECONDS))
        assertEquals(SpeechEndReason.COMPLETED, listener.reason)
        assertEquals(1, audio.releaseCalls.get())
        manager.close()
        assertEquals(1, audio.releaseCalls.get())
    }

    @Test
    fun languageOverrideAppliesOnlyToRequestedSession() {
        val audio = ImmediateAudioAccess()
        val engine = RecordingSession()
        val languages = mutableListOf<TranscriptionLanguage>()
        val manager = manager(
            audio = audio,
            recordingSession = engine,
            clock = AtomicLong(1_000L),
            languages = languages,
        )

        assertEquals(
            SpeechStartResult.OK,
            manager.startUtterance(RecordingUtteranceListener(), TranscriptionLanguage.FRENCH),
        )
        assertEquals(listOf(TranscriptionLanguage.FRENCH), languages)
        manager.cancel()
        manager.close()
        assertEquals(TranscriptionLanguage.AUTO, settings.selectedLanguage())
    }

    @Test
    fun listenerScopedCancelCannotCancelAnotherOwner() {
        val audio = ImmediateAudioAccess()
        val engine = RecordingSession()
        val listener = RecordingUtteranceListener()
        val manager = manager(audio, engine, AtomicLong(1_000L))

        assertEquals(SpeechStartResult.OK, manager.startUtterance(listener))
        manager.cancel(RecordingUtteranceListener())
        assertTrue(manager.isActive)

        manager.cancel(listener)
        assertTrue(listener.ended.await(2, TimeUnit.SECONDS))
        assertEquals(SpeechEndReason.CANCELLED, listener.reason)
        manager.close()
    }

    /** Samples loud enough to clear the detector's peak threshold. */
    private fun voicePcm(samples: Int): ByteArray {
        val bytes = ByteArray(samples * 2)
        for (index in 0 until samples) {
            bytes[index * 2] = 0x00
            bytes[index * 2 + 1] = 0x20 // 8192, well above the 2800 peak threshold
        }
        return bytes
    }

    private fun manager(
        audio: InternalAudioAccess,
        recordingSession: RecordingSession,
        clock: AtomicLong,
        languages: MutableList<TranscriptionLanguage>? = null,
    ): SpeechSessionManager =
        SpeechSessionManager(
            context = context,
            settings = settings,
            secrets = secrets,
            internalAudio = audio,
            sessionFactory = object : SpeechSttSessionFactory {
                override fun create(
                    engine: SpeechEngine,
                    language: TranscriptionLanguage,
                    phoneLanguageTag: String,
                    patience: SpeechPatience,
                    listener: SttSessionListener,
                ): SttSession = recordingSession.apply {
                    languages?.add(language)
                    this.listener = listener
                }
            },
            mainPoster = MainThreadPoster { task -> task() },
            elapsedRealtime = clock::get,
            audioExecutor = ScheduledThreadPoolExecutor(1).apply {
                removeOnCancelPolicy = true
            },
            diagnostic = {},
            readinessProvider = { SpeechReadiness.READY },
        )

    private class RecordingSession : SttSession {
        lateinit var listener: SttSessionListener
        val started = CountDownLatch(1)
        val accepted = CountDownLatch(1)
        val finished = CountDownLatch(1)
        val acceptCalls = AtomicInteger()
        val finishCalls = AtomicInteger()
        val cancelCalls = AtomicInteger()

        override fun start(): Boolean {
            listener.onReady()
            started.countDown()
            return true
        }

        override fun acceptPcm(data: ByteArray, offset: Int, length: Int) {
            acceptCalls.incrementAndGet()
            accepted.countDown()
        }

        override fun finishAudio() {
            finishCalls.incrementAndGet()
            finished.countDown()
        }

        override fun cancel() {
            cancelCalls.incrementAndGet()
        }
    }

    private class RecordingUtteranceListener : SpeechUtteranceListener {
        val ended = CountDownLatch(1)
        @Volatile var reason: SpeechEndReason? = null

        override fun onState(state: SpeechSessionState) = Unit
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) = Unit

        override fun onEnded(reason: SpeechEndReason, error: SttError?) {
            this.reason = reason
            ended.countDown()
        }
    }

    private class BlockingAudioAccess : InternalAudioAccess {
        val acquireEntered = CountDownLatch(1)
        val allowAcquire = CountDownLatch(1)
        val released = CountDownLatch(1)
        val releaseCalls = AtomicInteger()

        override fun acquireInternalAudio(
            tag: String,
            consumer: InternalAudioConsumer,
        ): InternalAudioAcquireResult {
            acquireEntered.countDown()
            allowAcquire.await(2, TimeUnit.SECONDS)
            return InternalAudioAcquireResult.OK
        }

        override fun releaseInternalAudio(tag: String) {
            releaseCalls.incrementAndGet()
            released.countDown()
        }
    }

    private class ImmediateAudioAccess : InternalAudioAccess {
        var consumer: InternalAudioConsumer? = null
        val releaseCalls = AtomicInteger()

        override fun acquireInternalAudio(
            tag: String,
            consumer: InternalAudioConsumer,
        ): InternalAudioAcquireResult {
            this.consumer = consumer
            return InternalAudioAcquireResult.OK
        }

        override fun releaseInternalAudio(tag: String) {
            releaseCalls.incrementAndGet()
        }
    }
}
