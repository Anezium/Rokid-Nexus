package com.anezium.rokidbus.phone.speech

import android.Manifest
import android.app.Application
import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SpeechSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(SpeechSettingsStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        context.getSharedPreferences(HubSecretStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        shadowOf(context as Application).denyPermissions(Manifest.permission.RECORD_AUDIO)
    }

    @Test
    fun unsetPreferenceDefaultsToAndroidAndLanguageDefaultsToAuto() {
        val store = SpeechSettingsStore(context)
        assertNull(store.selectedEngineId)
        assertSame(SpeechEngine.ANDROID_RECOGNIZER, store.selectedEngine())
        assertEquals("auto", store.selectedLanguageId)
        assertSame(TranscriptionLanguage.AUTO, store.selectedLanguage())
    }

    @Test
    fun cloudEnginesPreserveForcedLanguage() {
        val store = SpeechSettingsStore(context)
        store.selectedLanguageId = TranscriptionLanguage.CANTONESE.id

        SpeechEngine.values().filterNot { it.usesAndroidRecognizer }.forEach { engine ->
            assertSame(
                TranscriptionLanguage.CANTONESE,
                store.selectedLanguageForEngine(engine),
            )
        }
    }

    @Test
    fun androidEngineRunsOnAutoWithoutTouchingTheStoredLanguage() {
        val store = SpeechSettingsStore(context)
        store.selectedLanguageId = TranscriptionLanguage.CANTONESE.id

        assertSame(
            TranscriptionLanguage.AUTO,
            store.selectedLanguageForEngine(SpeechEngine.ANDROID_RECOGNIZER),
        )
        // The stored preference survives, so switching back to a cloud engine keeps Cantonese.
        assertSame(TranscriptionLanguage.CANTONESE, store.selectedLanguage())
        assertSame(
            TranscriptionLanguage.CANTONESE,
            store.selectedLanguageForEngine(SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE),
        )
    }

    @Test
    fun explicitCloudEngineChoiceRemainsAuthoritative() {
        val store = SpeechSettingsStore(context)
        store.selectedEngineId = SpeechEngine.ELEVENLABS_SCRIBE_V2.id

        assertSame(SpeechEngine.ELEVENLABS_SCRIBE_V2, store.selectedEngine())
    }

    @Test
    fun readinessDistinguishesAndroidPermissionCloudKeysAndInvalidIds() {
        val store = SpeechSettingsStore(context)
        val secrets = HubSecretStore(context)

        assertTrue(secrets.hasCredential(SpeechCredentialKind.NONE))
        assertEquals(SpeechReadiness.MISSING_MIC_PERMISSION, store.readiness(secrets))

        shadowOf(context as Application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        assertTrue(store.isRecordAudioPermissionGranted())
        assertEquals(SpeechReadiness.READY, store.readiness(secrets))

        store.selectedEngineId = SpeechEngine.OPENAI_GPT_4O_TRANSCRIBE.id
        assertEquals(SpeechReadiness.MISSING_KEY, store.readiness(secrets))

        store.selectedEngineId = "removed-engine"
        assertEquals(SpeechReadiness.NO_ENGINE, store.readiness(secrets))
    }

    @Test
    fun patienceDefaultsToNormal() {
        val store = SpeechSettingsStore(context)
        assertSame(SpeechPatience.NORMAL, store.patience())
    }

    @Test
    fun patiencePersistsAndReturns() {
        val store = SpeechSettingsStore(context)
        store.setPatience(SpeechPatience.PATIENT)
        assertSame(SpeechPatience.PATIENT, store.patience())
    }

    @Test
    fun patienceReturnsNormalForUnknownValue() {
        context.getSharedPreferences(SpeechSettingsStore.PREFERENCES_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString("speech_patience", "invalid_value")
            .commit()
        val store = SpeechSettingsStore(context)
        assertSame(SpeechPatience.NORMAL, store.patience())
    }
}
