package com.anezium.rokidbus.lyrics.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeLyricsProviderTest {
    @Test
    fun fetch_returnsFirstSyncedProviderResult() = runBlocking {
        val provider = CompositeLyricsProvider(
            providers = listOf(
                fakeProvider(
                    name = "PRIMARY",
                    attempt = LyricsProviderAttempt.NoMatch("PRIMARY", "No synced lyrics"),
                ),
                fakeProvider(
                    name = "FALLBACK",
                    attempt = LyricsProviderAttempt.Success(
                        LyricsFetchResult(
                            trackTitle = "Song",
                            artistName = "Artist",
                            albumName = "Album",
                            durationSeconds = 120,
                            provider = "FALLBACK",
                            synced = true,
                            lines = listOf(LyricsLine(1000L, "hello")),
                            plainLyrics = "",
                            sourceSummary = "Synced lyrics loaded from fallback provider.",
                        )
                    ),
                ),
            ),
        )

        val result = provider.fetch(
            LyricsLookupRequest(
                title = "Song",
                artist = "Artist",
            )
        ).result

        assertEquals("FALLBACK", result.provider)
        assertTrue(result.synced)
        assertEquals(1, result.lines.size)
        assertEquals(
            "Synced lyrics loaded from fallback provider. Fallback context: PRIMARY: No synced lyrics",
            result.sourceSummary,
        )
    }

    @Test
    fun fetch_returnsNoSyncResultWhenAllProvidersMiss() = runBlocking {
        val provider = CompositeLyricsProvider(
            providers = listOf(
                fakeProvider(
                    name = "PRIMARY",
                    attempt = LyricsProviderAttempt.Disabled("PRIMARY", "Provider not configured"),
                ),
                fakeProvider(
                    name = "FALLBACK",
                    attempt = LyricsProviderAttempt.NoMatch("FALLBACK", "No synced lyrics"),
                ),
            ),
        )

        val result = provider.fetch(
            LyricsLookupRequest(
                title = "Song",
                artist = "Artist",
            )
        ).result

        assertEquals("FALLBACK", result.provider)
        assertEquals(
            "No synced lyrics found on FALLBACK. PRIMARY: Provider not configured | FALLBACK: No synced lyrics",
            result.sourceSummary,
        )
        assertTrue(result.lines.isEmpty())
        assertTrue(!result.synced)
    }

    @Test(expected = CancellationException::class)
    fun fetch_rethrowsCancellation() {
        runBlocking {
            val provider = CompositeLyricsProvider(
                providers = listOf(
                    object : LyricsProvider {
                        override val providerName: String = "PRIMARY"

                        override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt {
                            throw CancellationException("cancelled")
                        }
                    },
                ),
            )

            provider.fetch(
                LyricsLookupRequest(
                    title = "Song",
                    artist = "Artist",
                ),
            )
        }
    }

    private fun fakeProvider(
        name: String,
        attempt: LyricsProviderAttempt,
    ): LyricsProvider = object : LyricsProvider {
        override val providerName: String = name

        override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt = attempt
    }
}
