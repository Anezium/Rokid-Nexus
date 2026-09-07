package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import java.security.MessageDigest

/** Stable across pause, provider refresh, and reconnect; stores no readable media metadata. */
internal fun lyricsTrackIdentity(snapshot: LyricsSnapshot): String? {
    if (snapshot.trackTitle.isBlank()) return null
    val parts = listOf(snapshot.trackTitle, snapshot.artistName, snapshot.albumName,
        snapshot.durationSeconds?.toString().orEmpty())
    val encoded = parts.joinToString("") { "${it.length}:$it" }
    return MessageDigest.getInstance("SHA-256").digest(encoded.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it.toInt() and 255) }
}
