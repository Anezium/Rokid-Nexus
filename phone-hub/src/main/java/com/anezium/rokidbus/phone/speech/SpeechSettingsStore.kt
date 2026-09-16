package com.anezium.rokidbus.phone.speech

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Process

enum class SpeechReadiness {
    READY,
    NO_ENGINE,
    MISSING_KEY,
    MISSING_REGION,
    MISSING_MIC_PERMISSION,
}

class SpeechSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(
        PREFERENCES_FILE,
        Context.MODE_PRIVATE,
    )

    var selectedEngineId: String?
        get() = prefs.getString(PREF_ENGINE, null)?.trim()?.takeIf { it.isNotBlank() }
        set(value) {
            prefs.edit().putString(PREF_ENGINE, value?.trim()?.takeIf { it.isNotBlank() }).apply()
        }

    var selectedLanguageId: String
        get() = TranscriptionLanguage.fromId(prefs.getString(PREF_LANGUAGE, null)).id
        set(value) {
            prefs.edit().putString(PREF_LANGUAGE, TranscriptionLanguage.fromId(value).id).apply()
        }

    fun patience(): SpeechPatience =
        SpeechPatience.fromWireValue(prefs.getString(PREF_PATIENCE, null))

    fun setPatience(patience: SpeechPatience) {
        prefs.edit().putString(PREF_PATIENCE, patience.wireValue).apply()
    }

    fun selectedEngine(): SpeechEngine? {
        val savedId = selectedEngineId
        return if (savedId == null) {
            SpeechEngine.ANDROID_RECOGNIZER
        } else {
            SpeechEngine.fromId(savedId)
        }
    }

    fun selectedLanguage(): TranscriptionLanguage =
        TranscriptionLanguage.fromId(selectedLanguageId)

    /**
     * Cloud engines cover the whole language set through provider codes or prompts. The Android
     * recognizer only auto-detects, so it runs on [TranscriptionLanguage.AUTO] whatever is stored.
     * Pure on purpose: the stored preference stays untouched so switching back to a cloud engine
     * restores the user's language. The settings screen disables the language grid instead.
     */
    fun selectedLanguageForEngine(engine: SpeechEngine): TranscriptionLanguage =
        if (engine.usesAndroidRecognizer) TranscriptionLanguage.AUTO else selectedLanguage()

    fun isRecordAudioPermissionGranted(): Boolean =
        appContext.checkPermission(
            Manifest.permission.RECORD_AUDIO,
            Process.myPid(),
            Process.myUid(),
        ) ==
            PackageManager.PERMISSION_GRANTED

    fun readiness(secrets: HubSecretStore): SpeechReadiness {
        val engine = selectedEngine() ?: return SpeechReadiness.NO_ENGINE
        if (engine.usesAndroidRecognizer && !isRecordAudioPermissionGranted()) {
            return SpeechReadiness.MISSING_MIC_PERMISSION
        }
        if (!secrets.hasCredential(engine)) return SpeechReadiness.MISSING_KEY
        if (engine.provider == SpeechProvider.AZURE && secrets.azureRegion().isNullOrBlank()) {
            return SpeechReadiness.MISSING_REGION
        }
        return SpeechReadiness.READY
    }

    companion object {
        internal const val PREFERENCES_FILE = "nexus_speech_settings"
        private const val PREF_ENGINE = "selectedEngineId"
        private const val PREF_LANGUAGE = "selectedLanguageId"
        private const val PREF_PATIENCE = "speech_patience"
    }
}
