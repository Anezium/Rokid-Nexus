package com.anezium.rokidbus.phone

import android.content.Context
import com.anezium.rokidbus.shared.YoutubeSetupContract

/** Phone-owned opt-in; the remote session's package is never persisted. */
internal class YoutubeKeyboardSettings(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        NexusPhoneState.PREFS,
        Context.MODE_PRIVATE,
    )

    var autoOpen: Boolean
        get() = preferences.getBoolean(KEY_AUTO_OPEN, false)
        set(value) { preferences.edit().putBoolean(KEY_AUTO_OPEN, value).apply() }

    fun shouldRequestKeyboard(packageName: String?, requestedByGlasses: Boolean): Boolean =
        requestedByGlasses || (autoOpen &&
            (packageName == YoutubeSetupContract.YOUTUBE || packageName == YoutubeSetupContract.YOUTUBE_TEST))

    private companion object {
        const val KEY_AUTO_OPEN = "youtube_auto_keyboard"
    }
}
