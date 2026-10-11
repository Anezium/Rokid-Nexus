package com.anezium.rokidbus.phone.speech

import android.content.Context

internal class AndroidSttSessionFactory(
    context: Context,
) : SpeechSttSessionFactory {
    private val appContext = context.applicationContext

    override fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession = session(engine, language, patience, listener, injectedAudioOnly = false)

    /** Only recognizers known to read the glasses audio they are handed; never the phone mic. */
    override fun createGlassesAudioOnly(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession = session(engine, language, patience, listener, injectedAudioOnly = true)

    private fun session(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        patience: SpeechPatience,
        listener: SttSessionListener,
        injectedAudioOnly: Boolean,
    ): SttSession {
        require(engine.usesAndroidRecognizer) {
            "AndroidSttSessionFactory cannot create engine ${engine.id}"
        }
        return AndroidSttSession(
            context = appContext,
            language = language,
            patience = patience,
            listener = listener,
            injectedAudioOnly = injectedAudioOnly,
        )
    }
}

internal class RoutingSttSessionFactory(
    private val cloud: SpeechSttSessionFactory,
    private val android: SpeechSttSessionFactory,
) : SpeechSttSessionFactory {
    override fun create(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession =
        if (engine.usesAndroidRecognizer) {
            android.create(engine, language, phoneLanguageTag, patience, listener)
        } else {
            cloud.create(engine, language, phoneLanguageTag, patience, listener)
        }

    override fun createGlassesAudioOnly(
        engine: SpeechEngine,
        language: TranscriptionLanguage,
        phoneLanguageTag: String,
        patience: SpeechPatience,
        listener: SttSessionListener,
    ): SttSession =
        if (engine.usesAndroidRecognizer) {
            android.createGlassesAudioOnly(engine, language, phoneLanguageTag, patience, listener)
        } else {
            cloud.createGlassesAudioOnly(engine, language, phoneLanguageTag, patience, listener)
        }

    override fun close() {
        try {
            cloud.close()
        } finally {
            android.close()
        }
    }
}
