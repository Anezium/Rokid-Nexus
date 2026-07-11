package com.anezium.rokidbus.lyrics.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsLine

data class LyricsLookupRequest(
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSeconds: Int? = null,
    val spotifyTrackId: String? = null,
)

data class LyricsFetchResult(
    val trackTitle: String,
    val artistName: String,
    val albumName: String,
    val durationSeconds: Int?,
    val provider: String,
    val synced: Boolean,
    val lines: List<LyricsLine>,
    val plainLyrics: String,
    val sourceSummary: String,
)
