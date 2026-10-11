package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.shared.LauncherInteractiveContract

/** The glasses launcher the user chose on the phone. [DEFAULT] expresses no opinion. */
enum class PhoneLauncherBackendChoice(val wireValue: String?) {
    DEFAULT(null),
    SESSION(LauncherInteractiveContract.BACKEND_SESSION),
    LEGACY(LauncherInteractiveContract.BACKEND_LEGACY),
    ;

    companion object {
        fun fromStored(value: String?): PhoneLauncherBackendChoice =
            entries.firstOrNull { it.wireValue != null && it.wireValue == value } ?: DEFAULT
    }
}

/**
 * The phone's explicit launcher backend choice, sent as `PhoneHubCapabilities.launcherBackend`
 * only when the user picked one. Default leaves the glasses to their own stored backend, so
 * nobody's existing choice is reset; Classic stays the rollback.
 */
class PhoneLauncherBackendStore private constructor(private val preferences: SharedPreferences) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun choice(): PhoneLauncherBackendChoice =
        PhoneLauncherBackendChoice.fromStored(preferences.getString(KEY_BACKEND, null))

    fun setChoice(choice: PhoneLauncherBackendChoice) {
        preferences.edit().apply {
            val wire = choice.wireValue
            if (wire == null) remove(KEY_BACKEND) else putString(KEY_BACKEND, wire)
        }.apply()
    }

    private companion object {
        const val KEY_BACKEND = "launcher_backend"
    }
}
