package com.anezium.rokidbus.lyrics.lyrics

import com.anezium.rokidbus.lyrics.settings.SpotifySpDcSource
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SpotifyLyricsProviderTest {
    @Test
    fun extractSpDc_acceptsCommonCookieFormats() {
        assertEquals("AQD_raw", SpotifySpDcCookie.extractValue("AQD_raw"))
        assertEquals("AQD_pair", SpotifySpDcCookie.extractValue("sp_dc=AQD_pair"))
        assertEquals(
            "AQD_header",
            SpotifySpDcCookie.extractValue("Cookie: sp_key=old; sp_dc=AQD_header; other=1"),
        )
        assertEquals("AQD_double", SpotifySpDcCookie.extractValue("sp_dc=\"AQD_double\""))
        assertEquals("AQD_single", SpotifySpDcCookie.extractValue("'sp_dc=\'AQD_single\''"))
        assertEquals("AQD_case", SpotifySpDcCookie.extractValue("cOoKiE: SP_DC=AQD_case"))
        assertEquals("AQD_space", SpotifySpDcCookie.extractValue("sp_dc\tAQD_space\t.spotify.com"))
        assertNull(SpotifySpDcCookie.extractValue("sp_key=old; other=1"))
        assertNull(SpotifySpDcCookie.extractValue("  \n\t  "))
    }

    @Test
    fun extractTrackId_acceptsTracksAndRejectsEpisodesOrGarbage() {
        val trackId = "0VjIjW4GlUZAMYd2vXMi3b"

        assertEquals(trackId, SpotifyTrackIdentifier.extract(trackId))
        assertEquals(trackId, SpotifyTrackIdentifier.extract("spotify:track:$trackId"))
        assertEquals(
            trackId,
            SpotifyTrackIdentifier.extract("https://open.spotify.com/intl-fr/track/$trackId?si=abc"),
        )
        assertNull(SpotifyTrackIdentifier.extract("spotify:episode:$trackId"))
        assertNull(SpotifyTrackIdentifier.extract("listen to $trackId now"))
        assertNull(SpotifyTrackIdentifier.extract("garbage"))
    }

    @Test
    fun generateTotp_matchesPinnedHmacSha1Vector() {
        val totp = SpotifyTotp.generate(
            secret = "12345678901234567890".toByteArray(),
            timestampMs = 59_000L,
        )

        assertEquals("287082", totp)
    }

    @Test
    fun transformSecret_xorsValuesAndConcatenatesDecimalResults() {
        assertEquals(
            "123",
            SpotifyTotp.transformSecret(listOf(8, 8, 8)).toString(Charsets.UTF_8),
        )
    }

    @Test
    fun parseColorLyrics_acceptsStringTimesSortsAndMarksInstrumentalLines() {
        val result = SpotifyColorLyricsParser.parse(
            rawJson = """
                {
                  "lyrics": {
                    "syncType": "LINE_SYNCED",
                    "lines": [
                      { "startTimeMs": "3500", "endTimeMs": "5000", "words": "  Second   line  " },
                      { "startTimeMs": "1200", "endTimeMs": "3400", "words": "First line" },
                      { "startTimeMs": 5000, "words": "   " }
                    ]
                  }
                }
            """.trimIndent(),
            trackId = TRACK_ID,
            request = spotifyRequest(),
        )

        assertEquals("SPOTIFY", result.provider)
        assertTrue(result.synced)
        assertEquals(listOf(1200L, 3500L, 5000L), result.lines.map { it.startTimeMs })
        assertEquals("Second line", result.lines[1].text)
        assertEquals("(instrumental)", result.lines[2].text)
        assertEquals(
            "Spotify color-lyrics returned LINE_SYNCED lyrics with 3 timed lines.",
            result.sourceSummary,
        )
    }

    @Test
    fun parseColorLyrics_rejectsUnsyncedPayload() {
        val error = parseFailure(
            """{"lyrics":{"syncType":"UNSYNCED","lines":[{"startTimeMs":"0","words":"Line"}]}}""",
        )

        assertTrue(error.message.orEmpty().contains("syncType=UNSYNCED"))
    }

    @Test
    fun parseColorLyrics_requiresExactLineSyncedValue() {
        val error = parseFailure(
            """{"lyrics":{"syncType":"line_synced","lines":[{"startTimeMs":"0","words":"Line"}]}}""",
        )

        assertTrue(error.message.orEmpty().contains("syncType=line_synced"))
    }

    @Test
    fun parseColorLyrics_rejectsEmptyLines() {
        val error = parseFailure(
            """{"lyrics":{"syncType":"LINE_SYNCED","lines":[]}}""",
        )

        assertTrue(error.message.orEmpty().contains("0 timed lines"))
    }

    @Test
    fun parseColorLyrics_acceptsWrapperLessRoot() {
        val result = SpotifyColorLyricsParser.parse(
            rawJson = """{"syncType":"LINE_SYNCED","lines":[{"startTimeMs":"42","text":"Root line"}]}""",
            trackId = TRACK_ID,
            request = spotifyRequest(),
        )

        assertEquals(1, result.lines.size)
        assertEquals(42L, result.lines.single().startTimeMs)
        assertEquals("Root line", result.lines.single().text)
    }

    @Test
    fun fetch_returnsDisabledWhenSpDcIsMissing() = runBlocking {
        val result = provider(spDc = null).fetch(spotifyRequest())

        assertTrue(result is LyricsProviderAttempt.Disabled)
        assertEquals(
            "Add your Spotify sp_dc cookie in settings to enable Spotify lyrics.",
            (result as LyricsProviderAttempt.Disabled).reason,
        )
    }

    @Test
    fun fetch_returnsNoMatchWhenSpotifyTrackIdIsMissing() = runBlocking {
        val result = provider(spDc = "cookie").fetch(
            LyricsLookupRequest(title = "Song", artist = "Artist"),
        )

        assertTrue(result is LyricsProviderAttempt.NoMatch)
        assertEquals(
            "Spotify lyrics require a Spotify track playing in the Spotify app.",
            (result as LyricsProviderAttempt.NoMatch).reason,
        )
    }

    @Test
    fun fetch_reportsAnonymousTokenAsNoMatch() = runBlocking {
        val client = fakeClient { requestCount, path ->
            when (path) {
                "/secret" -> jsonResponse(requestCount, """{"1":[8,8,8]}""")
                "/server-time" -> jsonResponse(requestCount, """{"serverTime":59}""")
                "/token" -> jsonResponse(
                    requestCount,
                    """{"accessToken":"anonymous-token","isAnonymous":true,"expires_in":3600}""",
                )
                else -> error("Unexpected path $path")
            }
        }
        val result = provider(spDc = "cookie", client = client).fetch(spotifyRequest())

        assertTrue(result is LyricsProviderAttempt.NoMatch)
        assertTrue((result as LyricsProviderAttempt.NoMatch).reason.contains("Refresh sp_dc"))
    }

    @Test
    fun fetch_completesDirectFlowAgainstMockedEndpoints() = runBlocking {
        val paths = mutableListOf<String>()
        val client = fakeClient { request, path ->
            paths += path
            when (path) {
                "/secret" -> jsonResponse(request, """{"2":[1],"7":[8,8,8]}""")
                "/server-time" -> {
                    assertWebPlayerHeaders(request)
                    jsonResponse(request, """{"serverTime":59}""")
                }
                "/token" -> {
                    assertEquals("init", request.url.queryParameter("reason"))
                    assertEquals("web-player", request.url.queryParameter("productType"))
                    assertTrue(
                        request.url.queryParameter("totp").orEmpty().matches(Regex("""\d{6}""")),
                    )
                    assertEquals("7", request.url.queryParameter("totpVer"))
                    assertEquals("59000", request.url.queryParameter("ts"))
                    assertEquals("sp_dc=cookie", request.header("Cookie"))
                    assertWebPlayerHeaders(request)
                    jsonResponse(
                        request,
                        """{"access_token":"web-token","isAnonymous":false,"accessTokenExpirationTimestampMs":9999999}""",
                    )
                }
                "/color-lyrics/$TRACK_ID" -> {
                    assertEquals("Bearer web-token", request.header("Authorization"))
                    assertEquals("sp_dc=cookie", request.header("Cookie"))
                    assertEquals("json", request.url.queryParameter("format"))
                    assertEquals("from_token", request.url.queryParameter("market"))
                    assertWebPlayerHeaders(request)
                    jsonResponse(request, colorLyricsBody())
                }
                else -> error("Unexpected path $path")
            }
        }

        val result = provider(spDc = "cookie", client = client).fetch(spotifyRequest())

        assertTrue(result is LyricsProviderAttempt.Success)
        assertEquals("SPOTIFY", (result as LyricsProviderAttempt.Success).result.provider)
        assertEquals(2, result.result.lines.size)
        assertEquals(listOf("/secret", "/server-time", "/token", "/color-lyrics/$TRACK_ID"), paths)
    }

    @Test
    fun fetch_acceptsAbsoluteAndRelativeTokenExpiryFields() = runBlocking {
        listOf(
            """{"accessToken":"absolute-token","accessTokenExpirationTimestampMs":1060000}""",
            """{"access_token":"relative-token","expires_in":60}""",
        ).forEach { tokenBody ->
            val now = AtomicLong(1_000_000L)
            val tokenRequests = AtomicInteger()
            val client = fakeClient { request, path ->
                when (path) {
                    "/secret" -> jsonResponse(request, """{"1":[8,8,8]}""")
                    "/server-time" -> jsonResponse(request, """{"serverTime":59}""")
                    "/token" -> {
                        tokenRequests.incrementAndGet()
                        jsonResponse(request, tokenBody)
                    }
                    "/color-lyrics/$TRACK_ID" -> jsonResponse(request, colorLyricsBody())
                    else -> error("Unexpected path $path")
                }
            }
            val provider = provider(
                spDc = "cookie",
                client = client,
                nowMs = now::get,
            )

            assertTrue(provider.fetch(spotifyRequest()) is LyricsProviderAttempt.Success)
            assertTrue(provider.fetch(spotifyRequest()) is LyricsProviderAttempt.Success)
            assertEquals(1, tokenRequests.get())

            now.set(1_031_000L)
            assertTrue(provider.fetch(spotifyRequest()) is LyricsProviderAttempt.Success)
            assertEquals(2, tokenRequests.get())
        }
    }

    @Test
    fun fetch_cookieChangeInvalidatesCachedTokenByFingerprint() = runBlocking {
        var spDc = "first-cookie"
        val tokenRequests = AtomicInteger()
        val client = fakeClient { request, path ->
            when (path) {
                "/secret" -> jsonResponse(request, """{"1":[8,8,8]}""")
                "/server-time" -> jsonResponse(request, """{"serverTime":59}""")
                "/token" -> {
                    val number = tokenRequests.incrementAndGet()
                    assertEquals("sp_dc=$spDc", request.header("Cookie"))
                    jsonResponse(request, """{"accessToken":"web-token-$number","expires_in":3600}""")
                }
                "/color-lyrics/$TRACK_ID" -> jsonResponse(request, colorLyricsBody())
                else -> error("Unexpected path $path")
            }
        }
        val provider = provider(
            spDcSource = object : SpotifySpDcSource {
                override fun getSpotifySpDc(): String = spDc
            },
            client = client,
        )

        assertTrue(provider.fetch(spotifyRequest()) is LyricsProviderAttempt.Success)
        spDc = "second-cookie"
        assertTrue(provider.fetch(spotifyRequest()) is LyricsProviderAttempt.Success)

        assertEquals(2, tokenRequests.get())
    }

    @Test
    fun fetch_reportsSecretParsingFailures() = runBlocking {
        listOf(
            "{}" to "numeric version",
            """{"1":"not-an-array"}""" to "version 1 was invalid",
            """{"1":[8,"bad"]}""" to "contained invalid values",
        ).forEach { (secretBody, expectedReason) ->
            val client = fakeClient { request, path ->
                when (path) {
                    "/secret" -> jsonResponse(request, secretBody)
                    else -> error("Unexpected path $path")
                }
            }

            val result = provider(spDc = "cookie", client = client).fetch(spotifyRequest())

            assertTrue(result is LyricsProviderAttempt.NoMatch)
            assertTrue((result as LyricsProviderAttempt.NoMatch).reason.contains(expectedReason))
        }
    }

    @Test
    fun fetch_reportsServerTimeParsingFailure() = runBlocking {
        val client = fakeClient { request, path ->
            when (path) {
                "/secret" -> jsonResponse(request, """{"1":[8,8,8]}""")
                "/server-time" -> jsonResponse(request, """{"serverTime":"not-a-time"}""")
                else -> error("Unexpected path $path")
            }
        }

        val result = provider(spDc = "cookie", client = client).fetch(spotifyRequest())

        assertTrue(result is LyricsProviderAttempt.NoMatch)
        assertTrue((result as LyricsProviderAttempt.NoMatch).reason.contains("server-time response was invalid"))
    }

    @Test
    fun fetch_lyrics401InvalidatesTokenForNextLookup() = runBlocking {
        val tokenRequests = AtomicInteger()
        val secretRequests = AtomicInteger()
        val lyricsRequests = AtomicInteger()
        val client = fakeClient { request, path ->
            when (path) {
                "/secret" -> {
                    secretRequests.incrementAndGet()
                    jsonResponse(request, """{"1":[8,8,8]}""")
                }
                "/server-time" -> jsonResponse(request, """{"serverTime":59}""")
                "/token" -> {
                    val number = tokenRequests.incrementAndGet()
                    jsonResponse(
                        request,
                        """{"accessToken":"web-token-$number","expires_in":3600}""",
                    )
                }
                "/color-lyrics/$TRACK_ID" -> {
                    if (lyricsRequests.incrementAndGet() == 1) {
                        jsonResponse(request, "{}", code = 401, message = "Unauthorized")
                    } else {
                        assertEquals("Bearer web-token-2", request.header("Authorization"))
                        jsonResponse(request, colorLyricsBody())
                    }
                }
                else -> error("Unexpected path $path")
            }
        }
        val provider = provider(spDc = "cookie", client = client)

        val first = provider.fetch(spotifyRequest())
        val second = provider.fetch(spotifyRequest())

        assertTrue(first is LyricsProviderAttempt.NoMatch)
        assertTrue(second is LyricsProviderAttempt.Success)
        assertEquals(2, tokenRequests.get())
        assertEquals(1, secretRequests.get())
        assertEquals(2, lyricsRequests.get())
    }

    private fun parseFailure(rawJson: String): Throwable =
        try {
            SpotifyColorLyricsParser.parse(
                rawJson = rawJson,
                trackId = TRACK_ID,
                request = spotifyRequest(),
            )
            fail("Expected Spotify color-lyrics parsing to fail")
            error("unreachable")
        } catch (error: Throwable) {
            error
        }

    private fun provider(
        spDc: String?,
        client: OkHttpClient = OkHttpClient(),
        nowMs: () -> Long = { 1_000_000L },
    ): SpotifyLyricsProvider = provider(
        spDcSource = object : SpotifySpDcSource {
            override fun getSpotifySpDc(): String? = spDc
        },
        client = client,
        nowMs = nowMs,
    )

    private fun provider(
        spDcSource: SpotifySpDcSource,
        client: OkHttpClient = OkHttpClient(),
        nowMs: () -> Long = { 1_000_000L },
    ): SpotifyLyricsProvider = SpotifyLyricsProvider(
        spDcSource = spDcSource,
        client = client,
        endpoints = SpotifyApiEndpoints(
            secretUrl = "https://spotify.test/secret",
            serverTimeUrl = "https://spotify.test/server-time",
            tokenUrl = "https://spotify.test/token",
            lyricsBaseUrl = "https://spotify.test/color-lyrics",
        ),
        nowMs = nowMs,
    )

    private fun spotifyRequest(): LyricsLookupRequest = LyricsLookupRequest(
        title = "Blinding Lights",
        artist = "The Weeknd",
        album = "After Hours",
        durationSeconds = 200,
        spotifyTrackId = TRACK_ID,
    )

    private fun fakeClient(
        responder: (okhttp3.Request, String) -> Response,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            Interceptor { chain -> responder(chain.request(), chain.request().url.encodedPath) },
        )
        .build()

    private fun jsonResponse(
        request: okhttp3.Request,
        body: String,
        code: Int = 200,
        message: String = "OK",
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(message)
        .body(body.toResponseBody("application/json".toMediaType()))
        .build()

    private fun assertWebPlayerHeaders(request: okhttp3.Request) {
        assertEquals("application/json", request.header("Accept"))
        assertEquals("https://open.spotify.com/", request.header("Origin"))
        assertEquals("https://open.spotify.com/", request.header("Referer"))
        assertEquals("WebPlayer", request.header("App-Platform"))
        assertTrue(request.header("User-Agent").orEmpty().contains("Chrome/127"))
    }

    private fun colorLyricsBody(): String = """
        {
          "lyrics": {
            "syncType": "LINE_SYNCED",
            "lines": [
              { "startTimeMs": "1200", "words": "First Spotify line" },
              { "startTimeMs": "3500", "words": "Second Spotify line" }
            ]
          }
        }
    """.trimIndent()

    private companion object {
        private const val TRACK_ID = "0VjIjW4GlUZAMYd2vXMi3b"
    }
}
