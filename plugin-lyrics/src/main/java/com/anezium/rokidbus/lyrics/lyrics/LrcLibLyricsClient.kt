package com.anezium.rokidbus.lyrics.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LrcLibLyricsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : LyricsProvider {
    override val providerName: String = "LRCLIB"

    override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt = withContext(Dispatchers.IO) {
        val exact = fetchTrack("/api/get-cached", request)
            ?: fetchTrack("/api/get", request)
            ?: fetchSearchFallback(request)
            ?: return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = "No lyrics found on LRCLIB for ${request.title} by ${request.artist}.",
            )
        val parsed = parseTrack(exact, request)
        if (!parsed.synced || parsed.lines.isEmpty()) {
            return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = "No lyrics found on LRCLIB for ${request.title} by ${request.artist}.",
            )
        }
        LyricsProviderAttempt.Success(parsed)
    }

    private fun fetchTrack(path: String, request: LyricsLookupRequest): JsonObject? {
        val httpUrl = baseUrl(path, request)
        val httpRequest = Request.Builder()
            .url(httpUrl)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            return runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        }
    }

    private fun fetchSearchFallback(request: LyricsLookupRequest): JsonObject? {
        val urlBuilder = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", request.title.trim())
            .addQueryParameter("artist_name", request.artist.trim())
        request.album.trim().takeIf { it.isNotEmpty() }?.let { urlBuilder.addQueryParameter("album_name", it) }
        val httpRequest = Request.Builder()
            .url(urlBuilder.build())
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            val candidates = runCatching { JsonParser.parseString(body).asJsonArray }.getOrNull() ?: return null
            return pickSearchCandidate(candidates, request)
        }
    }

    internal fun pickSearchCandidate(candidates: JsonArray, request: LyricsLookupRequest): JsonObject? =
        candidates
            .mapNotNull { it.asJsonObjectOrNull() }
            .mapNotNull { candidate ->
                candidateScore(candidate, request)?.let { score -> candidate to score }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first

    private fun candidateScore(candidate: JsonObject, request: LyricsLookupRequest): Int? {
        val titleScore = textMatchScore(request.title, candidate.stringOrNull("trackName").orEmpty())
        val artistScore = textMatchScore(request.artist, candidate.stringOrNull("artistName").orEmpty())
        if (titleScore < MIN_TITLE_MATCH_SCORE || artistScore < MIN_ARTIST_MATCH_SCORE) {
            return null
        }

        var score = titleScore * 3 + artistScore * 2

        val album = request.album.trim()
        if (album.isNotBlank()) {
            score += textMatchScore(album, candidate.stringOrNull("albumName").orEmpty()) / 2
        }

        if (!candidate.stringOrNull("syncedLyrics").isNullOrBlank()) {
            score += 35
        } else if (!candidate.stringOrNull("plainLyrics").isNullOrBlank()) {
            score += 10
        } else {
            score -= 25
        }

        val durationDelta = durationDeltaSeconds(request.durationSeconds, candidate.intOrNull("duration"))
        score += when {
            durationDelta == null -> 0
            durationDelta <= 1 -> 25
            durationDelta <= 3 -> 15
            durationDelta <= 6 -> 5
            durationDelta >= 20 -> -40
            else -> -10
        }

        if (normalizedComparable(request.title) == normalizedComparable(candidate.stringOrNull("trackName").orEmpty())) {
            score += 40
        }
        if (normalizedComparable(request.artist) == normalizedComparable(candidate.stringOrNull("artistName").orEmpty())) {
            score += 30
        }

        return score
    }

    private fun parseTrack(track: JsonObject, request: LyricsLookupRequest): LyricsFetchResult {
        val syncedLyrics = track.stringOrNull("syncedLyrics").orEmpty()
        val plainLyrics = track.stringOrNull("plainLyrics").orEmpty()
        val instrumental = track.booleanOrFalse("instrumental")
        val lines = parseSyncedLyrics(syncedLyrics)
        val trackTitle = track.stringOrNull("trackName") ?: request.title
        val artistName = track.stringOrNull("artistName") ?: request.artist
        val albumName = track.stringOrNull("albumName") ?: request.album
        val duration = track.intOrNull("duration") ?: request.durationSeconds
        val synced = lines.isNotEmpty()
        val summary = when {
            instrumental -> "Instrumental track reported by LRCLIB."
            synced -> "Synced lyrics loaded from LRCLIB with ${lines.size} timed lines."
            plainLyrics.isNotBlank() -> "Plain lyrics loaded from LRCLIB."
            else -> "Track resolved on LRCLIB, but no lyrics payload was returned."
        }
        return LyricsFetchResult(
            trackTitle = trackTitle,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = duration,
            provider = "LRCLIB",
            synced = synced,
            lines = lines,
            plainLyrics = plainLyrics,
            sourceSummary = summary,
        )
    }

    private fun parseSyncedLyrics(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { line -> line.trim() }
            .filter { line -> line.isNotEmpty() }
            .mapNotNull { line ->
                val match = TIMESTAMP_REGEX.matchEntire(line) ?: return@mapNotNull null
                val minutes = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val seconds = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
                val text = match.groupValues[3].trim().ifEmpty { "(instrumental)" }
                LyricsLine(
                    startTimeMs = minutes * 60_000L + (seconds * 1000).toLong(),
                    text = text,
                )
            }
            .toList()
    }

    private fun baseUrl(path: String, request: LyricsLookupRequest) =
        "https://lrclib.net$path".toHttpUrl().newBuilder().apply {
            addQueryParameter("track_name", request.title.trim())
            addQueryParameter("artist_name", request.artist.trim())
            request.album.trim().takeIf { it.isNotEmpty() }?.let { addQueryParameter("album_name", it) }
            request.durationSeconds?.takeIf { it > 0 }?.let { addQueryParameter("duration", it.toString()) }
        }.build()

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.booleanOrFalse(key: String): Boolean =
        get(key)?.takeIf { !it.isJsonNull }?.asBoolean ?: false

    private fun textMatchScore(request: String, candidate: String): Int {
        val normalizedRequest = normalizedComparable(request)
        val normalizedCandidate = normalizedComparable(candidate)
        if (normalizedRequest.isBlank() || normalizedCandidate.isBlank()) return 0
        if (normalizedRequest == normalizedCandidate) return 100
        if (normalizedCandidate.contains(normalizedRequest) || normalizedRequest.contains(normalizedCandidate)) {
            return 88
        }

        val requestTokens = normalizedRequest.split(' ').filter { it.isNotBlank() }
        val candidateTokens = normalizedCandidate.split(' ').filter { it.isNotBlank() }
        if (requestTokens.isEmpty() || candidateTokens.isEmpty()) return 0

        val sharedTokens = requestTokens.toSet().intersect(candidateTokens.toSet()).size
        if (sharedTokens == 0) return 0

        return ((sharedTokens.toDouble() / max(requestTokens.size, candidateTokens.size)) * 100.0).roundToInt()
    }

    private fun normalizedComparable(value: String): String =
        value
            .lowercase()
            .replace(PARENTHESIS_REGEX, " ")
            .replace(FEATURING_REGEX, " ")
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()
            .replace(MULTISPACE_REGEX, " ")

    private fun durationDeltaSeconds(requestDurationSeconds: Int?, candidateDurationSeconds: Int?): Int? {
        if (requestDurationSeconds == null || candidateDurationSeconds == null) return null
        return abs(requestDurationSeconds - candidateDurationSeconds)
    }

    private companion object {
        private val TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2}(?:\.\d+)?)\](.*)""")
        private val PARENTHESIS_REGEX = Regex("""\([^)]*\)|\[[^\]]*]""")
        private val FEATURING_REGEX = Regex("""\b(feat|ft|featuring)\.?\b.*""")
        private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")
        private val MULTISPACE_REGEX = Regex("""\s+""")
        private const val MIN_TITLE_MATCH_SCORE = 55
        private const val MIN_ARTIST_MATCH_SCORE = 40
        private const val NETWORK_TIMEOUT_SECONDS = 15L
        private const val USER_AGENT = "Rokid-Lyrics/0.1"
    }
}
