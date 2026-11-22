package com.anezium.rokidbus.lyrics.settings

import com.anezium.rokidbus.client.plugin.NexusTimedLinesPlacement
import com.anezium.rokidbus.client.plugin.NexusTimedLinesTextSize
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricsDisplaySettingsStoreTest {
    @Test
    fun emptyStorageKeepsTheOriginalLayout() {
        val store = LyricsDisplaySettingsStore(InMemorySharedPreferences())

        assertEquals(LyricsDisplaySettings(), store.load())
        assertEquals(NexusTimedLinesPlacement.CENTER, store.load().placement)
        assertEquals(NexusTimedLinesTextSize.NORMAL, store.load().textSize)
    }

    @Test
    fun savedLayoutRoundTrips() {
        val preferences = InMemorySharedPreferences()
        val chosen = LyricsDisplaySettings(NexusTimedLinesPlacement.TOP, NexusTimedLinesTextSize.SMALL)

        LyricsDisplaySettingsStore(preferences).save(chosen)

        assertEquals(chosen, LyricsDisplaySettingsStore(preferences).load())
    }

    @Test
    fun unknownStoredValuesFallBackToDefaults() {
        val preferences = InMemorySharedPreferences()
        preferences.edit().putString("placement", "sideways").putString("text_size", "huge").commit()

        assertEquals(LyricsDisplaySettings(), LyricsDisplaySettingsStore(preferences).load())
    }
}
