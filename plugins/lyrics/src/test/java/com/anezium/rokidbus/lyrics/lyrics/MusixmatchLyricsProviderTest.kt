package com.anezium.rokidbus.lyrics.lyrics

import com.anezium.rokidbus.lyrics.settings.MusixmatchCredentialsSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MusixmatchLyricsProviderTest {
    private val provider = MusixmatchLyricsProvider(
        credentialsSource = object : MusixmatchCredentialsSource {
            override fun getMusixmatchCredentials() = null
        },
    )

    @Test
    fun fetch_returnsDisabledWhenCredentialsAreMissing() = runBlocking {
        val result = provider.fetch(
            LyricsLookupRequest(
                title = "Song",
                artist = "Artist",
            ),
        )

        assertTrue(result is LyricsProviderAttempt.Disabled)
        assertEquals("MUSIXMATCH", (result as LyricsProviderAttempt.Disabled).provider)
    }

    @Test
    fun parseSyncedLyrics_parsesTimedLrcLines() {
        val lines = provider.parseSyncedLyrics(
            """
            [00:01.00]First line
            [00:03.50]Second line
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(1000L, lines[0].startTimeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(3500L, lines[1].startTimeMs)
        assertEquals("Second line", lines[1].text)
    }
}
