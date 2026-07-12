package com.anezium.rokidbus.lyrics.lyrics

sealed interface LyricsProviderAttempt {
    data class Success(val result: LyricsFetchResult) : LyricsProviderAttempt

    data class NoMatch(
        val provider: String,
        val reason: String,
    ) : LyricsProviderAttempt

    data class Disabled(
        val provider: String,
        val reason: String,
    ) : LyricsProviderAttempt
}

interface LyricsProvider {
    val providerName: String

    suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt
}
