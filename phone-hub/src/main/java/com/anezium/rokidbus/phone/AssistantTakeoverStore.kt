package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * Whether the assist button hands over to the approved assistant plugin.
 *
 * Kept apart from the plugin's `assistant` grant on purpose: the grant is the wearer's decision,
 * made on the phone with the plugin's identity in front of them, that a plugin *may* replace
 * Rokid's assistant. This is the day-to-day switch — flipped from the glasses as often as from
 * here — that says whether it *does* right now. The hub reads it on every button press, so a
 * flip takes effect on the next press without any rebind.
 */
class AssistantTakeoverStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isEnabled(): Boolean = preferences.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)

    fun setEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    companion object {
        private const val KEY_ENABLED = "assistant_button_takeover"

        /** On, so granting the capability keeps meaning what it always has until someone pauses it. */
        const val DEFAULT_ENABLED = true
    }
}
