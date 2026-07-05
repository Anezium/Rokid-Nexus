package com.anezium.rokidbus.lyrics.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LrcLibLyricsClientTest {
    private val client = LrcLibLyricsClient()

    @Test
    fun pickSearchCandidate_prefersClosestTrackMatchOverFirstSyncedResult() {
        val request = LyricsLookupRequest(
            title = "Phantom",
            artist = "Jolagreen23",
            album = "23 jours plus tard",
            durationSeconds = 172,
        )

        val candidates = candidates(
            """
            [
              {
                "trackName": "Phantom",
                "artistName": "Wrong Artist",
                "albumName": "Wrong Album",
                "duration": 172,
                "syncedLyrics": "[00:01.00]wrong"
              },
              {
                "trackName": "Phantom",
                "artistName": "Jolagreen23",
                "albumName": "23 jours plus tard",
                "duration": 172,
                "syncedLyrics": "[00:01.00]right"
              },
              {
                "trackName": "Phantom Remix",
                "artistName": "Jolagreen23 feat. Guest",
                "albumName": "23 jours plus tard",
                "duration": 171,
                "syncedLyrics": "[00:01.00]remix"
              }
            ]
            """.trimIndent()
        )

        val picked = client.pickSearchCandidate(candidates, request)

        assertEquals("Jolagreen23", picked?.get("artistName")?.asString)
        assertEquals("23 jours plus tard", picked?.get("albumName")?.asString)
    }

    @Test
    fun pickSearchCandidate_rejectsWeakMatches() {
        val request = LyricsLookupRequest(
            title = "Phantom",
            artist = "Jolagreen23",
            durationSeconds = 172,
        )

        val candidates = candidates(
            """
            [
              {
                "trackName": "Another Day",
                "artistName": "Different Artist",
                "duration": 172,
                "syncedLyrics": "[00:01.00]wrong"
              },
              {
                "trackName": "Phantom",
                "artistName": "Someone Else",
                "duration": 172,
                "syncedLyrics": "[00:01.00]still wrong"
              }
            ]
            """.trimIndent()
        )

        val picked = client.pickSearchCandidate(candidates, request)

        assertNull(picked)
    }

    private fun candidates(rawJson: String): JsonArray =
        JsonParser.parseString(rawJson).asJsonArray
}
