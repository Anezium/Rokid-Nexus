package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

internal enum class PhoneAudioRelayStatus(val label: String) {
    OFF("Off"), WAITING_FOR_EARBUDS("Waiting for earbuds"), WAITING_FOR_GLASSES("Waiting for glasses"),
    CONNECTING("Connecting…"), ACTIVE("Active"), SETUP_NEEDED("Glasses setup needed — run Repair now"),
    GLASSES_TOO_OLD("Glasses hub too old"),
}

internal class PhoneAudioRelayStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE)
    fun isEnabled() = preferences.getBoolean(KEY_ENABLED, false)
    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled)
            .putString(KEY_STATUS, if (enabled) PhoneAudioRelayStatus.WAITING_FOR_GLASSES.name else PhoneAudioRelayStatus.OFF.name).apply()
    }
    fun status(): PhoneAudioRelayStatus = PhoneAudioRelayStatus.entries.firstOrNull {
        it.name == preferences.getString(KEY_STATUS, null)
    } ?: if (isEnabled()) PhoneAudioRelayStatus.WAITING_FOR_GLASSES else PhoneAudioRelayStatus.OFF
    fun setStatus(status: PhoneAudioRelayStatus) {
        if (status() != status) preferences.edit().putString(KEY_STATUS, status.name).apply()
    }
    fun addChangeListener(enabledOnly: Boolean = false, listener: () -> Unit): AutoCloseable {
        val callback = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED || (!enabledOnly && key == KEY_STATUS)) listener()
        }
        preferences.registerOnSharedPreferenceChangeListener(callback)
        return AutoCloseable { preferences.unregisterOnSharedPreferenceChangeListener(callback) }
    }
    private companion object {
        const val KEY_ENABLED = "glasses_audio_relay"
        const val KEY_STATUS = "glasses_audio_relay_status"
    }
}
