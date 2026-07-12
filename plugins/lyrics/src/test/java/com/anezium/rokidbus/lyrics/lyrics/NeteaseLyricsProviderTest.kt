package com.anezium.rokidbus.lyrics.lyrics

import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NeteaseLyricsProviderTest {
    @Test
    fun fetch_returnsSuccessForTimedLrcPayload() = runBlocking {
        val provider = NeteaseLyricsProvider(
            client = fakeClient(
                searchResponse = """
                    {
                      "code": 200,
                      "result": {
                        "songs": [
                          {
                            "id": 2630955921,
                            "name": "一起寂寞 Lonely Duet",
                            "duration": 221052,
                            "album": { "name": "COlOR Free" },
                            "artists": [
                              { "name": "邱锋泽 Feng Ze" },
                              { "name": "艾薇 Ivy" }
                            ]
                          }
                        ]
                      }
                    }
                """.trimIndent(),
                lyricResponse = """
                    {
                      "code": 200,
                      "lrc": {
                        "lyric": "[00:00.00]作词 : 陈信延 Yuen Chen\n[00:03.50]一起拥抱着沉默\n[00:08.00]让寂寞开口说"
                      }
                    }
                """.trimIndent(),
            ),
        )

        val result = provider.fetch(
            LyricsLookupRequest(
                title = "一起寂寞 Lonely Duet",
                artist = "邱鋒澤 Feng Ze",
                album = "COlOR Free",
                durationSeconds = 221,
            ),
        )

        assertTrue(result is LyricsProviderAttempt.Success)
        val success = result as LyricsProviderAttempt.Success
        assertEquals("NETEASE", success.result.provider)
        assertEquals(2, success.result.lines.size)
        assertEquals(3500L, success.result.lines[0].startTimeMs)
        assertEquals("一起拥抱着沉默", success.result.lines[0].text)
        assertEquals("让寂寞开口说", success.result.lines[1].text)
    }

    @Test
    fun parseKaraokeLyrics_parsesWordTimedPayload() {
        val provider = NeteaseLyricsProvider()

        val lines = provider.parseKaraokeLyrics(
            """
            [0,1800](0,800)First(800,1000) line
            [1800,1600](1800,800)Second(2600,800) line
            """.trimIndent(),
        )

        assertEquals(2, lines.size)
        assertEquals(0L, lines[0].startTimeMs)
        assertEquals("First line", lines[0].text)
        assertEquals(1800L, lines[1].startTimeMs)
        assertEquals("Second line", lines[1].text)
    }

    @Test
    fun pickSearchCandidate_prefersMatchingArtistAndDuration() {
        val provider = NeteaseLyricsProvider()

        val candidate = provider.pickSearchCandidate(
            candidates = listOf(
                NeteaseLyricsProvider.NeteaseTrack(
                    trackId = 1L,
                    trackName = "Ditto (Areia Remix)",
                    artistName = "NewJeans",
                    albumName = "",
                    durationSeconds = 196,
                ),
                NeteaseLyricsProvider.NeteaseTrack(
                    trackId = 2L,
                    trackName = "Ditto",
                    artistName = "NewJeans, DANIELLE",
                    albumName = "NewJeans OMG세상에",
                    durationSeconds = 185,
                ),
                NeteaseLyricsProvider.NeteaseTrack(
                    trackId = 3L,
                    trackName = "Ditto",
                    artistName = "裴梨",
                    albumName = "Ditto",
                    durationSeconds = 186,
                ),
            ),
            request = LyricsLookupRequest(
                title = "Ditto",
                artist = "NewJeans",
                durationSeconds = 186,
            ),
        )

        assertEquals(2L, candidate?.trackId)
    }

    @Test
    fun parseLyricPayload_marksPureMusicAsInstrumental() {
        val provider = NeteaseLyricsProvider()

        val parsed = provider.parseLyricPayload(
            NeteaseLyricsProvider.NeteaseLyricPayload(
                lrc = "",
                klyric = "",
                instrumentalHint = true,
            ),
        )

        assertTrue(parsed.lines.isEmpty())
        assertEquals("Netease marks this track as instrumental.", parsed.failureReason)
    }

    @Test
    fun parseLyricPayload_marksNoLyricPayloadAsUnavailable() {
        val provider = NeteaseLyricsProvider()

        val parsed = provider.parseLyricPayload(
            NeteaseLyricsProvider.NeteaseLyricPayload(
                lrc = "",
                klyric = "",
                unavailableHint = true,
            ),
        )

        assertTrue(parsed.lines.isEmpty())
        assertEquals("Netease matched the track but no lyrics are available.", parsed.failureReason)
    }

    @Test
    fun parseLyricPayload_reportsCreditOnlyPayloadClearly() {
        val provider = NeteaseLyricsProvider()

        val parsed = provider.parseLyricPayload(
            NeteaseLyricsProvider.NeteaseLyricPayload(
                lrc = """
                    [00:00.00]Lyrics by: Someone
                    [00:01.00]Producer: Someone Else
                """.trimIndent(),
                klyric = "",
            ),
        )

        assertTrue(parsed.lines.isEmpty())
        assertEquals("Netease returned only credit or metadata lines for this track.", parsed.failureReason)
    }

    @Test
    fun parseLyricPayload_filtersVoiceCreditLines() {
        val provider = NeteaseLyricsProvider()

        val parsed = provider.parseLyricPayload(
            NeteaseLyricsProvider.NeteaseLyricPayload(
                lrc = "[00:00.00]人声: Green Montana",
                klyric = "",
            ),
        )

        assertTrue(parsed.lines.isEmpty())
        assertEquals("Netease returned only credit or metadata lines for this track.", parsed.failureReason)
    }

    @Test
    fun pickSearchCandidate_penalizesVariantAlbumsAndTitles() {
        val provider = NeteaseLyricsProvider()

        val candidate = provider.pickSearchCandidate(
            candidates = listOf(
                NeteaseLyricsProvider.NeteaseTrack(
                    trackId = 1L,
                    trackName = "Pretender (Live)",
                    artistName = "Official髭男dism",
                    albumName = "Pretender Live Tour",
                    durationSeconds = 305,
                ),
                NeteaseLyricsProvider.NeteaseTrack(
                    trackId = 2L,
                    trackName = "Pretender",
                    artistName = "Official髭男dism",
                    albumName = "Traveler",
                    durationSeconds = 304,
                ),
            ),
            request = LyricsLookupRequest(
                title = "Pretender",
                artist = "Official髭男dism",
                album = "Traveler",
                durationSeconds = 304,
            ),
        )

        assertEquals(2L, candidate?.trackId)
    }

    private fun fakeClient(
        searchResponse: String,
        lyricResponse: String,
    ): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(
                Interceptor { chain ->
                    val body = when (chain.request().url.encodedPath) {
                        "/weapi/search/get" -> searchResponse
                        "/weapi/song/lyric" -> lyricResponse
                        else -> error("Unexpected path ${chain.request().url.encodedPath}")
                    }
                    Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(body.toResponseBody("application/json".toMediaType()))
                        .build()
                },
            )
            .build()
}
