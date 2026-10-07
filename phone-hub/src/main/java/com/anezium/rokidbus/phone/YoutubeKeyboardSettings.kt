package com.anezium.rokidbus.phone

import android.content.Context
import android.text.InputType
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

    fun shouldRequestKeyboard(packageName: String?, requestedByGlasses: Boolean, inputType: Int): Boolean =
        requestedByGlasses || (autoOpen && (inputType and InputType.TYPE_MASK_CLASS) in 1..4 &&
            (packageName == YoutubeSetupContract.YOUTUBE || packageName == YoutubeSetupContract.YOUTUBE_TEST))

    private companion object {
        const val KEY_AUTO_OPEN = "youtube_auto_keyboard"
    }
}
