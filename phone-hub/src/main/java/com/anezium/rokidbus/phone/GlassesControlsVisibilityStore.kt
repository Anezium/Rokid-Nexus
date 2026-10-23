package com.anezium.rokidbus.phone

import android.content.Context
import android.content.SharedPreferences

/**
 * Which built-in glasses controls the home screen offers.
 *
 * Both are on out of the box: someone who has never opened them cannot know they
 * exist, so hiding them by default would bury the feature. Once you know, the
 * one you never use is just a card between you and your plugins — hence the
 * switches rather than a fixed section.
 */
class GlassesControlsVisibilityStore private constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(NexusPhoneState.PREFS, Context.MODE_PRIVATE),
    )

    fun isRemoteVisible(): Boolean = preferences.getBoolean(KEY_REMOTE, true)

    fun setRemoteVisible(visible: Boolean) {
        preferences.edit().putBoolean(KEY_REMOTE, visible).apply()
    }

    fun isNativeAppsVisible(): Boolean = preferences.getBoolean(KEY_NATIVE_APPS, true)

    fun setNativeAppsVisible(visible: Boolean) {
        preferences.edit().putBoolean(KEY_NATIVE_APPS, visible).apply()
    }

    fun isFilesVisible(): Boolean = preferences.getBoolean(KEY_FILES, true)

    fun setFilesVisible(visible: Boolean) {
        preferences.edit().putBoolean(KEY_FILES, visible).apply()
    }

    /** The section header and its trailing gap only earn their space if a card follows. */
    fun isSectionVisible(): Boolean = isRemoteVisible() || isNativeAppsVisible() || isFilesVisible()

    companion object {
        private const val KEY_REMOTE = "glasses_controls_remote_visible"
        private const val KEY_NATIVE_APPS = "glasses_controls_native_apps_visible"
        private const val KEY_FILES = "glasses_controls_files_visible"
    }
}
