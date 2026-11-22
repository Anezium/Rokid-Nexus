package com.anezium.rokidbus.lyrics.settings

import android.content.Context
import android.content.SharedPreferences
import com.anezium.rokidbus.client.plugin.NexusTimedLinesPlacement
import com.anezium.rokidbus.client.plugin.NexusTimedLinesTextSize

/** How synced lyrics are laid out on the glasses. The defaults are the original layout. */
data class LyricsDisplaySettings(
    val placement: NexusTimedLinesPlacement = NexusTimedLinesPlacement.CENTER,
    val textSize: NexusTimedLinesTextSize = NexusTimedLinesTextSize.NORMAL,
)

class LyricsDisplaySettingsStore internal constructor(
    private val preferences: SharedPreferences,
) {
    constructor(context: Context) : this(
        context.applicationContext.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE),
    )

    fun load(): LyricsDisplaySettings {
        val placement = preferences.getString(KEY_PLACEMENT, null)
        val textSize = preferences.getString(KEY_TEXT_SIZE, null)
        return LyricsDisplaySettings(
            placement = NexusTimedLinesPlacement.entries.firstOrNull { it.wireValue == placement }
                ?: NexusTimedLinesPlacement.CENTER,
            textSize = NexusTimedLinesTextSize.entries.firstOrNull { it.wireValue == textSize }
                ?: NexusTimedLinesTextSize.NORMAL,
        )
    }

    fun save(settings: LyricsDisplaySettings) {
        preferences.edit()
            .putString(KEY_PLACEMENT, settings.placement.wireValue)
            .putString(KEY_TEXT_SIZE, settings.textSize.wireValue)
            .apply()
    }

    private companion object {
        private const val PREFERENCES_NAME = "nexus_plugin_lyrics_display"
        private const val KEY_PLACEMENT = "placement"
        private const val KEY_TEXT_SIZE = "text_size"
    }
}
