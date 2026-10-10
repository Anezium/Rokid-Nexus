package com.anezium.rokidbus.phone.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class RoutingSttSessionFactoryTest {
    @Test
    fun routesAndroidOnlyToAndroidDelegateAndCloudOnlyToCloudDelegate() {
        val cloud = RecordingFactory()
        val android = RecordingFactory()
        val routing = RoutingSttSessionFactory(cloud, android)
        val listener = RecordingListener()

        val androidSession = routing.create(
            SpeechEngine.ANDROID_RECOGNIZER,
            TranscriptionLanguage.AUTO,
            "fr-FR",
            SpeechPatience.NORMAL,
            listener,
        )
        val cloudSession = routing.create(
            SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE,
            TranscriptionLanguage.FRENCH,
            "fr-FR",
            SpeechPatience.NORMAL,
            listener,
        )

        assertSame(android.session, androidSession)
        assertSame(cloud.session, cloudSession)
        assertEquals(listOf(SpeechEngine.ANDROID_RECOGNIZER), android.engines)
        assertEquals(listOf(SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE), cloud.engines)
    }

    @Test
    fun glassesOnlyRequestsReachTheDelegatesGlassesOnlyPath() {
        val cloud = RecordingFactory()
        val android = RecordingFactory()
        val routing = RoutingSttSessionFactory(cloud, android)

        routing.createGlassesAudioOnly(
            SpeechEngine.ANDROID_RECOGNIZER,
            TranscriptionLanguage.AUTO,
            "fr-FR",
            SpeechPatience.NORMAL,
            RecordingListener(),
        )
        routing.createGlassesAudioOnly(
            SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE,
            TranscriptionLanguage.AUTO,
            "fr-FR",
            SpeechPatience.NORMAL,
            RecordingListener(),
        )

        // The Android delegate never sees an ordinary create for a glasses-only request.
        assertEquals(listOf(SpeechEngine.ANDROID_RECOGNIZER), android.glassesOnlyEngines)
        assertEquals(emptyList<SpeechEngine>(), android.engines)
        assertEquals(listOf(SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE), cloud.glassesOnlyEngines)
    }

    @Test
    fun closeAlwaysClosesBothDelegates() {
        val cloud = RecordingFactory(closeFailure = IllegalStateException("cloud close"))
        val android = RecordingFactory()
        val routing = RoutingSttSessionFactory(cloud, android)

        runCatching { routing.close() }

        assertEquals(1, cloud.closeCalls)
        assertEquals(1, android.closeCalls)
    }

    private class RecordingFactory(
        private val closeFailure: Throwable? = null,
    ) : SpeechSttSessionFactory {
        val engines = mutableListOf<SpeechEngine>()
        val glassesOnlyEngines = mutableListOf<SpeechEngine>()
        val session = NoOpSession()
        var closeCalls = 0

        override fun create(
            engine: SpeechEngine,
            language: TranscriptionLanguage,
            phoneLanguageTag: String,
            patience: SpeechPatience,
            listener: SttSessionListener,
        ): SttSession {
            engines += engine
            return session
        }

        override fun createGlassesAudioOnly(
            engine: SpeechEngine,
            language: TranscriptionLanguage,
            phoneLanguageTag: String,
            patience: SpeechPatience,
            listener: SttSessionListener,
        ): SttSession {
            glassesOnlyEngines += engine
            return session
        }

        override fun close() {
            closeCalls += 1
            closeFailure?.let { throw it }
        }
    }

    private class NoOpSession : SttSession {
        override fun start(): Boolean = true
        override fun acceptPcm(data: ByteArray, offset: Int, length: Int) = Unit
        override fun finishAudio() = Unit
        override fun cancel() = Unit
    }

    private class RecordingListener : SttSessionListener {
        override fun onReady() = Unit
        override fun onPartial(text: String) = Unit
        override fun onFinal(text: String) = Unit
        override fun onError(error: SttError) = Unit
    }
}
