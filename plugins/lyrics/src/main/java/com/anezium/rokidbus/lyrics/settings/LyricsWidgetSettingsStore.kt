package com.anezium.rokidbus.lyrics.settings

import android.content.Context
import android.content.SharedPreferences

/** The ambient home-widget mode. Off/Glance/Karaoke; default Karaoke. */
enum class LyricsWidgetMode(val wireValue: String) {
    OFF("off"),
    GLANCE("glance"),
    KARAOKE("karaoke"),
    ;

    companion object {
        val DEFAULT = KARAOKE

        fun fromWire(value: String?): LyricsWidgetMode =
            entries.firstOrNull { it.wireValue.equals(value, ignoreCase = true) } ?: DEFAULT
    }
}

/**
 * Stores the ambient widget mode in the plugin's own plain preferences. Unlike the credential
 * store this holds no secret, so it lives in the ordinary (non-encrypted) preferences file.
 */
class LyricsWidgetSettingsStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE),
    )

    fun mode(): LyricsWidgetMode {
        val stored = preferences.getString(KEY_WIDGET_MODE, null)
        if (stored == null) return LyricsWidgetMode.DEFAULT
        return LyricsWidgetMode.fromWire(stored)
    }

    fun setMode(mode: LyricsWidgetMode): Boolean =
        preferences.edit().putString(KEY_WIDGET_MODE, mode.wireValue).commit()

    companion object {
        private const val PREF_FILE = "nexus_plugin_lyrics_settings"
        private const val KEY_WIDGET_MODE = "widget_mode"
    }
}