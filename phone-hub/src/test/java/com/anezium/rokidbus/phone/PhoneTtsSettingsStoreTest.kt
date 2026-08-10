package com.anezium.rokidbus.phone

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
class PhoneTtsSettingsStoreTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `rate defaults clamps and persists`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)

        assertEquals(1.0f, store.speechRate())
        store.setSpeechRate(0.25f)
        assertEquals(0.5f, PhoneTtsSettingsStore(context).speechRate())
        store.setSpeechRate(2.5f)
        assertEquals(2.0f, PhoneTtsSettingsStore(context).speechRate())
        store.setSpeechRate(Float.NaN)
        assertEquals(1.0f, PhoneTtsSettingsStore(context).speechRate())
    }

    @Test
    fun `language tag persists and null restores system default`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)

        assertNull(store.languageTag())
        store.setLanguageTag("pt-br")
        assertEquals("pt-BR", PhoneTtsSettingsStore(context).languageTag())
        store.setLanguageTag(null)
        assertNull(PhoneTtsSettingsStore(context).languageTag())
    }

    @Test
    fun `voice names persist independently for each language`() {
        clearPreferences()
        val store = PhoneTtsSettingsStore(context)
        val english = Locale.forLanguageTag("en-US")
        val portuguese = Locale.forLanguageTag("pt-BR")

        store.setVoiceName(english, "english.voice")
        store.setVoiceName(portuguese, "portuguese.voice")

        val restored = PhoneTtsSettingsStore(context)
        assertEquals("english.voice", restored.voiceName(english))
        assertEquals("portuguese.voice", restored.voiceName(portuguese))
        restored.setVoiceName(english, null)
        assertNull(PhoneTtsSettingsStore(context).voiceName(english))
        assertEquals("portuguese.voice", PhoneTtsSettingsStore(context).voiceName(portuguese))
    }

    @Test
    fun `legacy global voice migrates to the current default locale`() {
        clearPreferences()
        val originalLocale = Locale.getDefault()
        val portuguese = Locale.forLanguageTag("pt-BR")
        try {
            Locale.setDefault(portuguese)
            val preferences = context.getSharedPreferences(
                NexusPhoneState.PREFS,
                Context.MODE_PRIVATE,
            )
            preferences.edit()
                .putString(PhoneTtsSettingsStore.KEY_VOICE_NAME, "legacy.voice")
                .commit()

            val store = PhoneTtsSettingsStore(context)

            assertEquals("legacy.voice", store.voiceName(portuguese))
            assertFalse(preferences.contains(PhoneTtsSettingsStore.KEY_VOICE_NAME))
            assertEquals(
                "legacy.voice",
                preferences.getString(
                    PhoneTtsSettingsStore.KEY_VOICE_NAME_PREFIX + portuguese.toLanguageTag(),
                    null,
                ),
            )
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `stored glasses only mode is ignored`() {
        clearPreferences()
        context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(LEGACY_OUTPUT_MODE_KEY, "glasses")
            .commit()

        val store = PhoneTtsSettingsStore(context)

        assertEquals(PhoneTtsSettingsStore.DEFAULT_SPEECH_RATE, store.speechRate())
        store.setVoiceName(Locale.US, "engine.voice.id")
        assertEquals("engine.voice.id", PhoneTtsSettingsStore(context).voiceName(Locale.US))
    }

    private fun clearPreferences() {
        val preferences = context.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
        val editor = preferences.edit()
            .remove(PhoneTtsSettingsStore.KEY_SPEECH_RATE)
            .remove(PhoneTtsSettingsStore.KEY_LANGUAGE_TAG)
            .remove(PhoneTtsSettingsStore.KEY_VOICE_NAME)
            .remove(LEGACY_OUTPUT_MODE_KEY)
        preferences.all.keys
            .filter { it.startsWith(PhoneTtsSettingsStore.KEY_VOICE_NAME_PREFIX) }
            .forEach { editor.remove(it) }
        editor.commit()
    }

    private companion object {
        const val LEGACY_OUTPUT_MODE_KEY = "phone_tts_output_mode"
    }
}
