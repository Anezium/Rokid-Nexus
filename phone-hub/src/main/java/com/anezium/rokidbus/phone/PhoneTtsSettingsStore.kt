package com.anezium.rokidbus.phone

import android.content.Context
import java.util.Locale

class PhoneTtsSettingsStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    init {
        migrateLegacyVoiceName()
    }

    fun speechRate(): Float = normalizeRate(
        preferences.getFloat(KEY_SPEECH_RATE, DEFAULT_SPEECH_RATE),
    )

    fun setSpeechRate(rate: Float) {
        preferences.edit().putFloat(KEY_SPEECH_RATE, normalizeRate(rate)).apply()
    }

    fun languageTag(): String? = preferences.getString(KEY_LANGUAGE_TAG, null)
        ?.let(::normalizeLanguageTag)

    fun setLanguageTag(languageTag: String?) {
        val editor = preferences.edit()
        val normalized = languageTag?.let(::normalizeLanguageTag)
        if (normalized == null) {
            editor.remove(KEY_LANGUAGE_TAG)
        } else {
            editor.putString(KEY_LANGUAGE_TAG, normalized)
        }
        editor.apply()
    }

    fun voiceName(locale: Locale): String? =
        preferences.getString(voiceKey(locale), null)

    fun setVoiceName(locale: Locale, name: String?) {
        val editor = preferences.edit()
        val key = voiceKey(locale)
        if (name == null) editor.remove(key) else editor.putString(key, name)
        editor.apply()
    }

    private fun migrateLegacyVoiceName() {
        if (!preferences.contains(KEY_VOICE_NAME)) return
        val legacyName = preferences.getString(KEY_VOICE_NAME, null)
            ?.takeIf { it.isNotBlank() }
        val migratedKey = voiceKey(Locale.getDefault())
        preferences.edit().apply {
            if (legacyName != null && !preferences.contains(migratedKey)) {
                putString(migratedKey, legacyName)
            }
            remove(KEY_VOICE_NAME)
        }.apply()
    }

    private fun voiceKey(locale: Locale): String =
        KEY_VOICE_NAME_PREFIX + locale.toLanguageTag()

    private fun normalizeLanguageTag(languageTag: String): String? {
        val locale = Locale.forLanguageTag(languageTag.trim())
        return locale.toLanguageTag().takeUnless { it == UNDEFINED_LANGUAGE_TAG }
    }

    private fun normalizeRate(rate: Float): Float =
        if (rate.isFinite()) rate.coerceIn(MIN_SPEECH_RATE, MAX_SPEECH_RATE) else DEFAULT_SPEECH_RATE

    internal companion object {
        const val DEFAULT_SPEECH_RATE = 1.0f
        const val MIN_SPEECH_RATE = 0.5f
        const val MAX_SPEECH_RATE = 2.0f
        const val KEY_SPEECH_RATE = "phone_tts_speech_rate"
        const val KEY_LANGUAGE_TAG = "phone_tts_language_tag"
        const val KEY_VOICE_NAME_PREFIX = "phone_tts_voice_name."
        const val KEY_VOICE_NAME = "phone_tts_voice_name"
        private const val UNDEFINED_LANGUAGE_TAG = "und"
    }
}
