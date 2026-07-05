package com.anezium.rokidbus.lyrics.lyrics

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonReader
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.settings.MusixmatchCredentials
import com.anezium.rokidbus.lyrics.settings.MusixmatchCredentialsSource
import com.anezium.rokidbus.lyrics.settings.MusixmatchSessionCacheSource
import java.io.StringReader
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class MusixmatchLyricsProvider(
    private val credentialsSource: MusixmatchCredentialsSource,
    private val sessionCacheSource: MusixmatchSessionCacheSource? = null,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : LyricsProvider {
    override val providerName: String = "MUSIXMATCH"

    @Volatile private var cachedSession: MusixmatchSession? = null
    private val sessionLock = Any()

    override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt = withContext(Dispatchers.IO) {
        val credentials = credentialsSource.getMusixmatchCredentials()
            ?: return@withContext LyricsProviderAttempt.Disabled(
                provider = providerName,
                reason = "Sign in to Musixmatch from the phone app to enable synced lyrics.",
            )

        try {
            val matchedTrack = resolveBestTrack(credentials, request)
                ?: return@withContext LyricsProviderAttempt.NoMatch(
                    provider = providerName,
                    reason = "No Musixmatch match with line-synced subtitles for ${request.title} by ${request.artist}.",
                )
            val subtitleBody = fetchSubtitle(credentials, matchedTrack.trackId)
                ?: return@withContext LyricsProviderAttempt.NoMatch(
                    provider = providerName,
                    reason = "Musixmatch found the track but did not return line-synced subtitles.",
                )
            val lines = parseSyncedLyrics(subtitleBody)
            if (lines.isEmpty()) {
                return@withContext LyricsProviderAttempt.NoMatch(
                    provider = providerName,
                    reason = "Musixmatch subtitle payload could not be parsed into timed lines.",
                )
            }
            LyricsProviderAttempt.Success(
                LyricsFetchResult(
                    trackTitle = matchedTrack.trackName.ifBlank { request.title },
                    artistName = matchedTrack.artistName.ifBlank { request.artist },
                    albumName = matchedTrack.albumName.ifBlank { request.album },
                    durationSeconds = matchedTrack.durationSeconds ?: request.durationSeconds,
                    provider = providerName,
                    synced = true,
                    lines = lines,
                    plainLyrics = "",
                    sourceSummary = "Synced lyrics loaded from Musixmatch with ${lines.size} timed lines.",
                ),
            )
        } catch (error: InvalidMusixmatchCredentialsException) {
            invalidateSession()
            LyricsProviderAttempt.Disabled(
                provider = providerName,
                reason = error.message ?: "Musixmatch login failed. Update the saved credentials.",
            )
        }
    }

    fun invalidateSession() {
        synchronized(sessionLock) {
            cachedSession = null
        }
        sessionCacheSource?.clearMusixmatchSessionToken()
    }

    internal fun parseSyncedLyrics(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        return raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
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

    internal fun pickSearchCandidate(
        candidates: List<MusixmatchTrack>,
        request: LyricsLookupRequest,
    ): MusixmatchTrack? =
        candidates
            .distinctBy { it.trackId }
            .mapNotNull { candidate ->
                candidateScore(candidate, request)?.let { score -> candidate to score }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first

    private fun resolveBestTrack(
        credentials: MusixmatchCredentials,
        request: LyricsLookupRequest,
    ): MusixmatchTrack? {
        val matcherCandidate = fetchMatcherCandidate(credentials, request)
        if (matcherCandidate != null && candidateScore(matcherCandidate, request) != null) {
            return matcherCandidate
        }

        val searchQueries = buildList {
            add("${request.title.trim()} ${request.artist.trim()}".trim())
            add(request.title.trim())
        }.distinct().filter { it.isNotBlank() }

        val candidates = mutableListOf<MusixmatchTrack>()
        for (query in searchQueries) {
            candidates += searchTracks(credentials, query)
        }
        return pickSearchCandidate(candidates, request)
    }

    private fun fetchMatcherCandidate(
        credentials: MusixmatchCredentials,
        request: LyricsLookupRequest,
    ): MusixmatchTrack? {
        val response = authenticatedRequest(
            credentials = credentials,
            endpoint = "matcher.track.get",
            params = buildMap {
                put("q_track", request.title.trim())
                put("q_artist", request.artist.trim())
                request.album.trim().takeIf { it.isNotEmpty() }?.let { put("q_album", it) }
                put("subtitle_format", "dfxp")
                put("optional_calls", "track.richsync")
                put(
                    "part",
                    "lyrics_crowd,user,lyrics_vote,track_lyrics_translation_status,lyrics_verified_by,labels,track_isrc,writer_list,credits",
                )
            },
        )
        return response.objectOrNull("message")
            ?.objectOrNull("body")
            ?.objectOrNull("track")
            ?.toMusixmatchTrack()
    }

    private fun searchTracks(
        credentials: MusixmatchCredentials,
        query: String,
    ): List<MusixmatchTrack> {
        val response = authenticatedRequest(
            credentials = credentials,
            endpoint = "macro.search",
            params = mapOf(
                "q" to query,
                "part" to "track_artist,artist_image",
                "track_fields_set" to "android_track_list",
                "artist_fields_set" to "android_track_list_artist",
                "page" to "1",
                "page_size" to "5",
            ),
        )
        val trackList = response.objectOrNull("message")
            ?.objectOrNull("body")
            ?.objectOrNull("macro_result_list")
            ?.getAsJsonArray("track_list")
            ?: return emptyList()
        return trackList.mapNotNull { item ->
            item.asJsonObjectOrNull()?.let { wrapper ->
                (wrapper.objectOrNull("track") ?: wrapper).toMusixmatchTrack()
            }
        }
    }

    private fun fetchSubtitle(
        credentials: MusixmatchCredentials,
        trackId: Long,
    ): String? {
        val response = authenticatedRequest(
            credentials = credentials,
            endpoint = "track.subtitle.get",
            params = mapOf(
                "track_id" to trackId.toString(),
                "subtitle_format" to "lrc",
            ),
        )
        return response.objectOrNull("message")
            ?.objectOrNull("body")
            ?.objectOrNull("subtitle")
            ?.stringOrNull("subtitle_body")
            ?.takeIf { it.isNotBlank() }
    }

    private fun authenticatedRequest(
        credentials: MusixmatchCredentials,
        endpoint: String,
        params: Map<String, String>,
        method: String = "GET",
        body: String? = null,
    ): JsonObject {
        val initialSession = ensureSession(credentials)
        val firstAttempt = executeSignedRequest(endpoint, params, initialSession.userToken, method, body)
        if (firstAttempt.isAuthFailure) {
            invalidateSession()
            val refreshedSession = ensureSession(credentials)
            val retryAttempt = executeSignedRequest(endpoint, params, refreshedSession.userToken, method, body)
            return requireSuccess(endpoint, retryAttempt)
        }
        return requireSuccess(endpoint, firstAttempt)
    }

    private fun ensureSession(credentials: MusixmatchCredentials): MusixmatchSession {
        currentSession(credentials)?.let { return it }

        val persistedSession = sessionCacheSource?.getMusixmatchSessionToken()
            ?.takeIf { it.expiresAtMs > System.currentTimeMillis() + SESSION_EXPIRY_SAFETY_MS }
            ?.let {
                MusixmatchSession(
                    email = credentials.email,
                    password = credentials.password,
                    userToken = it.userToken,
                    expiresAtMs = it.expiresAtMs,
                )
            }
        if (persistedSession != null) {
            synchronized(sessionLock) {
                cachedSession = persistedSession
            }
            return persistedSession
        }

        val token = fetchUserToken()
        login(credentials, token)
        val session = MusixmatchSession(
            email = credentials.email,
            password = credentials.password,
            userToken = token,
            expiresAtMs = System.currentTimeMillis() + TOKEN_EXPIRY_MILLIS,
        )
        synchronized(sessionLock) {
            cachedSession = session
        }
        sessionCacheSource?.saveMusixmatchSessionToken(
            userToken = session.userToken,
            expiresAtMs = session.expiresAtMs,
        )
        return session
    }

    private fun currentSession(credentials: MusixmatchCredentials): MusixmatchSession? =
        synchronized(sessionLock) {
            cachedSession?.takeIf { session ->
                session.email == credentials.email &&
                    session.password == credentials.password &&
                    session.expiresAtMs > System.currentTimeMillis() + SESSION_EXPIRY_SAFETY_MS
            }
        }

    private fun fetchUserToken(): String {
        val response = executeSignedRequest(
            endpoint = "token.get",
            params = emptyMap(),
            userToken = null,
            method = "GET",
            body = null,
        )
        val payload = requireSuccess("token.get", response)
        return payload.objectOrNull("message")
            ?.objectOrNull("body")
            ?.stringOrNull("user_token")
            ?.takeIf { it.isNotBlank() }
            ?: throw IOException("Musixmatch token.get did not return a user token.")
    }

    private fun login(
        credentials: MusixmatchCredentials,
        userToken: String,
    ) {
        val body = """
            {
              "credential_list": [
                {
                  "credential": {
                    "type": "mxm",
                    "action": "login",
                    "email": ${credentials.email.quoteJson()},
                    "password": ${credentials.password.quoteJson()}
                  }
                }
              ]
            }
        """.trimIndent()

        val response = executeSignedRequest(
            endpoint = "credential.post",
            params = emptyMap(),
            userToken = userToken,
            method = "POST",
            body = body,
        )

        if (response.statusCode in 400..499) {
            throw InvalidMusixmatchCredentialsException(
                response.hint ?: "Musixmatch rejected the saved email/password.",
            )
        }
        requireSuccess("credential.post", response)
    }

    private fun executeSignedRequest(
        endpoint: String,
        params: Map<String, String>,
        userToken: String?,
        method: String,
        body: String?,
    ): ApiResponse {
        val url = "$ANDROID_BASE_URL$endpoint".toHttpUrl().newBuilder().apply {
            signedParams(endpoint, params, userToken).forEach { (key, value) ->
                addQueryParameter(key, value)
            }
        }.build()

        val guid = UUID.randomUUID().toString().replace("-", "")
        val requestBuilder = Request.Builder()
            .url(url)
            .header("User-Agent", ANDROID_USER_AGENT)
            .header("Connection", "Keep-Alive")
            .header("x-mxm-endpoint", "default")
            .header(
                "Cookie",
                "x-mxm-token-guid=$guid; mxm-encrypted-token=; x-mxm-user-id=; AWSELB=unknown",
            )

        val request = if (method == "POST") {
            requestBuilder
                .header("Content-Type", "application/json")
                .post((body ?: "{}").toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } else {
            requestBuilder.get().build()
        }

        client.newCall(request).execute().use { response ->
            val bodyString = response.body?.string().orEmpty()
            val json = parseJsonObjectLenient(bodyString)
            val header = json.objectOrNull("message")?.objectOrNull("header")
            val statusCode = header?.intOrNull("status_code") ?: response.code
            return ApiResponse(
                httpCode = response.code,
                statusCode = statusCode,
                hint = header?.stringOrNull("hint"),
                json = json,
            )
        }
    }

    private fun parseJsonObjectLenient(bodyString: String): JsonObject {
        if (bodyString.isBlank()) return JsonObject()
        return runCatching {
            JsonReader(StringReader(bodyString)).use { reader ->
                reader.isLenient = true
                JsonParser.parseReader(reader).asJsonObject
            }
        }.getOrElse {
            JsonObject().apply {
                addProperty("raw_body_excerpt", bodyString.take(200))
            }
        }
    }

    private fun requireSuccess(
        endpoint: String,
        response: ApiResponse,
    ): JsonObject {
        if (response.statusCode == 200) {
            return response.json
        }
        throw IOException(
            "Musixmatch $endpoint failed (${response.statusCode}): ${response.hint ?: "unknown error"}",
        )
    }

    private fun signedParams(
        endpoint: String,
        params: Map<String, String>,
        userToken: String?,
    ): Map<String, String> {
        val now = Instant.now()
        val signed = linkedMapOf(
            "app_id" to ANDROID_APP_ID,
            "usertoken" to (userToken ?: ""),
            "format" to "json",
            "signature" to apiSignature(endpoint, now),
            "signature_protocol" to "sha1",
        )
        if (endpoint == "token.get") {
            signed["timestamp"] = TOKEN_TIMESTAMP_FORMATTER.format(now)
            signed["guid"] = UUID.randomUUID().toString().replace("-", "")
        }
        signed.putAll(params)
        return signed
    }

    private fun apiSignature(
        endpoint: String,
        instant: Instant,
    ): String {
        val date = SIGNATURE_DATE_FORMATTER.format(instant)
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(SIGNING_KEY.toByteArray(Charsets.UTF_8), "HmacSHA1"))
        val digest = mac.doFinal((endpoint + date).toByteArray(Charsets.UTF_8))
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
    }

    private fun candidateScore(
        candidate: MusixmatchTrack,
        request: LyricsLookupRequest,
    ): Int? {
        val titleScore = textMatchScore(request.title, candidate.trackName)
        val artistScore = textMatchScore(request.artist, candidate.artistName)
        if (titleScore < MIN_TITLE_MATCH_SCORE || artistScore < MIN_ARTIST_MATCH_SCORE) {
            return null
        }

        var score = titleScore * 3 + artistScore * 2

        if (request.album.isNotBlank()) {
            score += textMatchScore(request.album, candidate.albumName) / 2
        }

        score += when (candidate.hasSubtitles) {
            true -> 30
            false -> -35
            null -> 0
        }

        val durationDelta = durationDeltaSeconds(request.durationSeconds, candidate.durationSeconds)
        score += when {
            durationDelta == null -> 0
            durationDelta <= 1 -> 25
            durationDelta <= 3 -> 15
            durationDelta <= 6 -> 5
            durationDelta >= 20 -> -40
            else -> -10
        }

        if (normalizedComparable(request.title) == normalizedComparable(candidate.trackName)) {
            score += 40
        }
        if (normalizedComparable(request.artist) == normalizedComparable(candidate.artistName)) {
            score += 30
        }

        return score
    }

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
            .lowercase(Locale.ROOT)
            .replace(PARENTHESIS_REGEX, " ")
            .replace(FEATURING_REGEX, " ")
            .replace(NON_ALPHANUMERIC_REGEX, " ")
            .trim()
            .replace(MULTISPACE_REGEX, " ")

    private fun durationDeltaSeconds(requestDurationSeconds: Int?, candidateDurationSeconds: Int?): Int? {
        if (requestDurationSeconds == null || candidateDurationSeconds == null) return null
        return abs(requestDurationSeconds - candidateDurationSeconds)
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { !it.isJsonNull }?.asJsonObject

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.toMusixmatchTrack(): MusixmatchTrack? {
        val trackId = intOrNull("track_id")?.toLong() ?: return null
        return MusixmatchTrack(
            trackId = trackId,
            trackName = stringOrNull("track_name").orEmpty(),
            artistName = stringOrNull("artist_name").orEmpty(),
            albumName = stringOrNull("album_name").orEmpty(),
            durationSeconds = intOrNull("track_length"),
            hasSubtitles = when (intOrNull("has_subtitles")) {
                null -> null
                0 -> false
                else -> true
            },
        )
    }

    private fun String.quoteJson(): String =
        JsonObject().apply { addProperty("value", this@quoteJson) }.get("value").toString()

    internal data class MusixmatchTrack(
        val trackId: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Int?,
        val hasSubtitles: Boolean?,
    )

    private data class MusixmatchSession(
        val email: String,
        val password: String,
        val userToken: String,
        val expiresAtMs: Long,
    )

    private data class ApiResponse(
        val httpCode: Int,
        val statusCode: Int,
        val hint: String?,
        val json: JsonObject,
    ) {
        val isAuthFailure: Boolean
            get() = httpCode == 401 || statusCode == 401
    }

    private class InvalidMusixmatchCredentialsException(
        message: String,
    ) : IOException(message)

    private companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private val TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2}(?:\.\d+)?)\](.*)""")
        private val PARENTHESIS_REGEX = Regex("""\([^)]*\)|\[[^\]]*]""")
        private val FEATURING_REGEX = Regex("""\b(feat|ft|featuring)\.?\b.*""")
        private val NON_ALPHANUMERIC_REGEX = Regex("""[^a-z0-9]+""")
        private val MULTISPACE_REGEX = Regex("""\s+""")
        private const val MIN_TITLE_MATCH_SCORE = 55
        private const val MIN_ARTIST_MATCH_SCORE = 40
        private const val NETWORK_TIMEOUT_SECONDS = 15L
        private const val ANDROID_BASE_URL = "https://apic.musixmatch.com/ws/1.1/"
        private const val ANDROID_APP_ID = "android-player-v1.0"
        private const val ANDROID_USER_AGENT =
            "Dalvik/2.1.0 (Linux; U; Android 16; Pixel 8 Pro Build/BP31.250502.008)"
        private const val SIGNING_KEY = "IEJ5E8XFaHQvIQNfs7IC"
        private const val TOKEN_EXPIRY_MILLIS = 10 * 60 * 1000L
        private const val SESSION_EXPIRY_SAFETY_MS = 30_000L
        private val SIGNATURE_DATE_FORMATTER = DateTimeFormatter.BASIC_ISO_DATE.withZone(ZoneOffset.UTC)
        private val TOKEN_TIMESTAMP_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
                .withLocale(Locale.US)
                .withZone(ZoneOffset.UTC)
    }
}
