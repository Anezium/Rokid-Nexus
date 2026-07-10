package com.anezium.rokidbus.plugin.transit

import android.content.Context
import com.anezium.rokidbus.client.PluginRegistrationResult

internal class TransitPluginStatusStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun setRegistration(result: Int) {
        preferences.edit().putString(KEY_REGISTRATION, registrationLabel(result)).apply()
    }

    fun setLinkState(state: Int) {
        preferences.edit().putBoolean(KEY_LINK_UP, state != 0).apply()
    }

    fun setDisconnected() {
        preferences.edit().putBoolean(KEY_LINK_UP, false).apply()
    }

    fun summary(): String {
        val registration = preferences.getString(KEY_REGISTRATION, "Not connected").orEmpty()
        val link = if (preferences.getBoolean(KEY_LINK_UP, false)) "glasses link available" else "glasses link unavailable"
        return "$registration · $link"
    }

    private fun registrationLabel(result: Int): String = when (result) {
        PluginRegistrationResult.APPROVED -> "Nexus access approved"
        PluginRegistrationResult.PENDING_USER_APPROVAL -> "Pending Nexus approval"
        PluginRegistrationResult.DENIED -> "Nexus access denied"
        PluginRegistrationResult.IDENTITY_MISMATCH -> "Plugin identity rejected"
        PluginRegistrationResult.INVALID_DESCRIPTOR -> "Plugin descriptor invalid"
        PluginRegistrationResult.UNSUPPORTED_API -> "Nexus API unsupported"
        else -> "Nexus connection unavailable"
    }

    private companion object {
        const val PREFERENCES = "transit_plugin_status"
        const val KEY_REGISTRATION = "registration"
        const val KEY_LINK_UP = "linkUp"
    }
}
