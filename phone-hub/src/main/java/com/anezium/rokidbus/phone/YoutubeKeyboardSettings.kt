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

    var redditAutoOpen: Boolean
        get() = preferences.getBoolean("reddit_auto_keyboard", false)
        set(value) { preferences.edit().putBoolean("reddit_auto_keyboard", value).apply() }

    fun enabledFor(packageName: String?): Boolean = when (packageName) {
        YoutubeSetupContract.YOUTUBE, YoutubeSetupContract.YOUTUBE_TEST -> autoOpen
        com.anezium.rokidbus.shared.RedditSetupContract.REDDIT -> redditAutoOpen
        else -> false
    }

    fun shouldRequestKeyboard(packageName: String?, requestedByGlasses: Boolean, inputType: Int): Boolean =
        requestedByGlasses || (enabledFor(packageName) && (inputType and InputType.TYPE_MASK_CLASS) in 1..4)

    private companion object {
        const val KEY_AUTO_OPEN = "youtube_auto_keyboard"
    }
}
