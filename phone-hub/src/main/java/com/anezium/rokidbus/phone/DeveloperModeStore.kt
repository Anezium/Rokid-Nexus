package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

class DeveloperModeStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun addChangeListener(listener: (Boolean) -> Unit): Subscription {
        val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_ENABLED) listener(isEnabled())
        }
        preferences.registerOnSharedPreferenceChangeListener(preferenceListener)
        return Subscription {
            preferences.unregisterOnSharedPreferenceChangeListener(preferenceListener)
        }
    }

    fun interface Subscription : AutoCloseable {
        override fun close()
    }

    companion object {
        private const val KEY_ENABLED = "developer_mode"
    }
}

internal fun bindDeveloperModeToJournal(
    store: DeveloperModeStore,
    journal: PluginBusJournal,
): DeveloperModeStore.Subscription {
    journal.enabled.set(store.isEnabled())
    return store.addChangeListener(journal.enabled::set)
}
