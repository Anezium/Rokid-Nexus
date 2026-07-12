package com.anezium.rokidbus.lyrics.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

class NeteaseLyricsProvider(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
) : LyricsProvider {
    override val providerName: String = "NETEASE"

    override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt = withContext(Dispatchers.IO) {
        val prepared = PreparedLookupRequest.from(request)
        val candidate = resolveBestTrack(prepared, request)
            ?: return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = "No Netease match with synced lyrics for ${request.title} by ${request.artist}.",
            )

        val payload = fetchLyricPayload(candidate.trackId)
            ?: return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = "Netease found the track but no lyrics payload was available.",
            )

        val parsedPayload = parseLyricPayload(payload)
        if (parsedPayload.lines.isEmpty()) {
            return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = parsedPayload.failureReason ?: "Netease lyrics payload could not be parsed into timed lines.",
            )
        }

        LyricsProviderAttempt.Success(
            LyricsFetchResult(
                trackTitle = candidate.trackName.ifBlank { request.title },
                artistName = candidate.artistName.ifBlank { request.artist },
                albumName = candidate.albumName.ifBlank { request.album },
                durationSeconds = candidate.durationSeconds ?: request.durationSeconds,
                provider = providerName,
                synced = true,
                lines = parsedPayload.lines,
                plainLyrics = "",
                sourceSummary = "Synced lyrics loaded from Netease ${parsedPayload.sourceLabel} with ${parsedPayload.lines.size} timed lines.",
            ),
        )
    }

    internal fun pickSearchCandidate(
        candidates: List<NeteaseTrack>,
        request: LyricsLookupRequest,
    ): NeteaseTrack? =
        pickSearchCandidate(
            candidates = candidates,
            prepared = PreparedLookupRequest.from(request),
            request = request,
        )

    internal fun parseTimedLyrics(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        var instrumental = false
        val parsed = buildList {
            raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach lineLoop@{ line ->
                    val timestamps = TIMESTAMP_REGEX.findAll(line).toList()
                    if (timestamps.isEmpty()) return@lineLoop
                    val text = cleanupLineText(line.replace(TIMESTAMP_REGEX, " "))
                    if (text.isBlank()) return@lineLoop
                    if (isInstrumentalLine(text)) {
                        instrumental = true
                        return@lineLoop
                    }
                    if (isCreditLine(text)) return@lineLoop

                    timestamps.forEach timestampLoop@{ match ->
                        val minutes = match.groupValues[1].toLongOrNull() ?: return@timestampLoop
                        val seconds = match.groupValues[2].toDoubleOrNull() ?: return@timestampLoop
                        add(
                            LyricsLine(
                                startTimeMs = minutes * 60_000L + (seconds * 1000).toLong(),
                                text = text,
                            ),
                        )
                    }
                }
        }
        if (parsed.isEmpty() && instrumental) return emptyList()
        return parsed.distinctBy { it.startTimeMs to it.text }
    }

    internal fun parseKaraokeLyrics(raw: String): List<LyricsLine> {
        if (raw.isBlank()) return emptyList()
        var instrumental = false
        val parsed = raw.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .mapNotNull { line ->
                val match = KARAOKE_LINE_REGEX.matchEntire(line) ?: return@mapNotNull null
                val startTimeMs = match.groupValues[1].toLongOrNull() ?: return@mapNotNull null
                val text = cleanupLineText(match.groupValues[3].replace(KARAOKE_WORD_REGEX, " "))
                when {
                    text.isBlank() -> null
                    isInstrumentalLine(text) -> {
                        instrumental = true
                        null
                    }
                    isCreditLine(text) -> null
                    else -> LyricsLine(
                        startTimeMs = startTimeMs,
                        text = text,
                    )
                }
            }
            .toList()
        if (parsed.isEmpty() && instrumental) return emptyList()
        return parsed.distinctBy { it.startTimeMs to it.text }
    }

    internal fun parseLyricPayload(payload: NeteaseLyricPayload): ParsedLyricPayload {
        if (payload.instrumentalHint) {
            return ParsedLyricPayload(
                lines = emptyList(),
                failureReason = "Netease marks this track as instrumental.",
            )
        }
        if (payload.unavailableHint) {
            return ParsedLyricPayload(
                lines = emptyList(),
                failureReason = "Netease matched the track but no lyrics are available.",
            )
        }

        val timedLines = parseTimedLyrics(payload.lrc)
        if (timedLines.isNotEmpty()) {
            return ParsedLyricPayload(
                lines = timedLines,
                sourceLabel = "(LRC)",
            )
        }

        val karaokeLines = parseKaraokeLyrics(payload.klyric)
        if (karaokeLines.isNotEmpty()) {
            return ParsedLyricPayload(
                lines = karaokeLines,
                sourceLabel = "(karaoke)",
            )
        }

        if (containsInstrumentalMarker(payload.lrc, timed = true) || containsInstrumentalMarker(payload.klyric, timed = false)) {
            return ParsedLyricPayload(
                lines = emptyList(),
                failureReason = "Netease marks this track as instrumental.",
            )
        }

        if (containsOnlyCreditLines(payload.lrc, timed = true) || containsOnlyCreditLines(payload.klyric, timed = false)) {
            return ParsedLyricPayload(
                lines = emptyList(),
                failureReason = "Netease returned only credit or metadata lines for this track.",
            )
        }

        val hasPayloadBody = payload.lrc.isNotBlank() || payload.klyric.isNotBlank()
        return ParsedLyricPayload(
            lines = emptyList(),
            failureReason = if (hasPayloadBody) {
                "Netease lyrics payload could not be parsed into timed lines."
            } else {
                "Netease found the track but no lyrics payload was available."
            },
        )
    }

    private fun resolveBestTrack(
        prepared: PreparedLookupRequest,
        request: LyricsLookupRequest,
    ): NeteaseTrack? {
        val candidates = mutableListOf<NeteaseTrack>()
        prepared.searchQueries.forEach { query ->
            candidates += searchTracks(query)
        }
        return pickSearchCandidate(candidates, prepared, request)
    }

    private fun pickSearchCandidate(
        candidates: List<NeteaseTrack>,
        prepared: PreparedLookupRequest,
        request: LyricsLookupRequest,
    ): NeteaseTrack? =
        candidates
            .distinctBy { it.trackId }
            .mapNotNull { candidate ->
                candidateScore(candidate, prepared, request)?.let { score -> candidate to score }
            }
            .maxByOrNull { (_, score) -> score }
            ?.first

    private fun searchTracks(query: String): List<NeteaseTrack> =
        officialSearchTracks(query) ?: legacySearchTracks(query)

    private fun officialSearchTracks(query: String): List<NeteaseTrack>? {
        val payload = executeWeapiRequest(
            url = OFFICIAL_SEARCH_URL,
            formPayload = mapOf(
                "csrf_token" to "",
                "s" to query,
                "offset" to 0,
                "type" to 1,
                "limit" to SEARCH_LIMIT,
            ),
        ) ?: return null
        if (payload.intOrNull("code") != 200) return null
        return payload.objectOrNull("result")
            ?.arrayOrNull("songs")
            ?.mapNotNull { it.asJsonObjectOrNull()?.toNeteaseTrack() }
            ?: emptyList()
    }

    private fun legacySearchTracks(query: String): List<NeteaseTrack> {
        val url = LEGACY_SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("csrf_token", "")
            .addQueryParameter("hlpretag", "")
            .addQueryParameter("hlposttag", "")
            .addQueryParameter("s", query)
            .addQueryParameter("type", "1")
            .addQueryParameter("offset", "0")
            .addQueryParameter("total", "true")
            .addQueryParameter("limit", "6")
            .build()
        val payload = executeGetJsonRequest(url = url.toString()) ?: return emptyList()
        if (payload.intOrNull("code") != 200) return emptyList()
        return payload.objectOrNull("result")
            ?.arrayOrNull("songs")
            ?.mapNotNull { it.asJsonObjectOrNull()?.toNeteaseTrack() }
            ?: emptyList()
    }

    private fun fetchLyricPayload(trackId: Long): NeteaseLyricPayload? =
        officialLyricPayload(trackId) ?: legacyLyricPayload(trackId)

    private fun officialLyricPayload(trackId: Long): NeteaseLyricPayload? {
        val payload = executeWeapiRequest(
            url = OFFICIAL_LYRIC_URL,
            formPayload = mapOf(
                "OS" to "pc",
                "id" to trackId,
                "lv" to -1,
                "kv" to -1,
                "tv" to -1,
                "rv" to -1,
            ),
        ) ?: return null
        if (payload.intOrNull("code") != 200) return null
        return payload.toNeteaseLyricPayload()
    }

    private fun legacyLyricPayload(trackId: Long): NeteaseLyricPayload? {
        val payload = executeGetJsonRequest(
            url = LEGACY_LYRIC_URL.toHttpUrl().newBuilder()
                .addQueryParameter("os", "pc")
                .addQueryParameter("id", trackId.toString())
                .addQueryParameter("lv", "-1")
                .addQueryParameter("kv", "-1")
                .addQueryParameter("tv", "-1")
                .build()
                .toString(),
            extraHeaders = mapOf("Cookie" to "appver=1.5.0.75771;"),
        ) ?: return null
        if (payload.intOrNull("code") != 200) return null
        return payload.toNeteaseLyricPayload()
    }

    private fun executeWeapiRequest(
        url: String,
        formPayload: Map<String, Any>,
    ): JsonObject? = runCatching {
        val requestJson = formPayload.toJsonObject().toString()
        val secretKey = createSecretKey(SECRET_KEY_LENGTH)
        val params = aesEncode(
            aesEncode(requestJson, NONCE),
            secretKey,
        )
        val encSecKey = rsaEncode(secretKey)
        val body = FormBody.Builder()
            .add("params", params)
            .add("encSecKey", encSecKey)
            .build()
        val request = Request.Builder()
            .url(url)
            .header("Referer", MUSIC_163_REFERER)
            .header("User-Agent", NETEASE_USER_AGENT)
            .post(body)
            .build()
        executeJsonRequest(request)
    }.getOrNull()

    private fun executeGetJsonRequest(
        url: String,
        extraHeaders: Map<String, String> = emptyMap(),
    ): JsonObject? = runCatching {
        val builder = Request.Builder()
            .url(url)
            .header("Referer", MUSIC_163_REFERER)
            .header("User-Agent", NETEASE_USER_AGENT)
        extraHeaders.forEach { (name, value) -> builder.header(name, value) }
        executeJsonRequest(builder.get().build())
    }.getOrNull()

    private fun executeJsonRequest(request: Request): JsonObject? =
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val body = response.body?.string()?.takeIf { it.isNotBlank() } ?: return null
            runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        }

    private fun candidateScore(
        candidate: NeteaseTrack,
        prepared: PreparedLookupRequest,
        request: LyricsLookupRequest,
    ): Int? {
        val titleScore = max(
            textMatchScore(prepared.titleForMatch, candidate.trackName),
            textMatchScore(request.title, candidate.trackName),
        )
        val artistScore = max(
            textMatchScore(prepared.artistForMatch, candidate.artistName),
            textMatchScore(request.artist, candidate.artistName),
        )
        if (titleScore < MIN_TITLE_MATCH_SCORE || artistScore < MIN_ARTIST_MATCH_SCORE) {
            return null
        }

        var score = titleScore * 3 + artistScore * 2

        val album = request.album.trim()
        if (album.isNotBlank()) {
            val albumScore = textMatchScore(album, candidate.albumName)
            score += when {
                albumScore >= 95 -> 28
                albumScore >= 80 -> 16
                else -> albumScore / 3
            }
        }

        val durationDelta = durationDeltaSeconds(request.durationSeconds, candidate.durationSeconds)
        score += when {
            durationDelta == null -> 0
            durationDelta <= 1 -> 25
            durationDelta <= 3 -> 15
            durationDelta <= 6 -> 5
            durationDelta >= 30 -> -65
            durationDelta >= 20 -> -40
            else -> -10
        }

        if (normalizedStrictComparable(prepared.titleForMatch) == normalizedStrictComparable(candidate.trackName)) {
            score += 40
        }
        if (normalizedComparable(prepared.artistForMatch) == normalizedComparable(candidate.artistName)) {
            score += 30
        }
        score += primaryArtistBonus(prepared.artistForMatch, candidate.artistName)
        score -= variantPenalty(request.title, candidate.trackName, candidate.albumName)

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
            .replace(NON_TEXT_REGEX, " ")
            .trim()
            .replace(MULTISPACE_REGEX, " ")

    private fun normalizedStrictComparable(value: String): String =
        value
            .lowercase(Locale.ROOT)
            .replace(NON_TEXT_REGEX, " ")
            .trim()
            .replace(MULTISPACE_REGEX, " ")

    private fun cleanupLineText(value: String): String =
        value
            .trim()
            .replace(MULTISPACE_REGEX, " ")

    private fun isCreditLine(text: String): Boolean = CREDIT_LINE_REGEX.containsMatchIn(text)

    private fun isInstrumentalLine(text: String): Boolean =
        INSTRUMENTAL_MARKERS.any { marker -> text.equals(marker, ignoreCase = true) }

    private fun isVariantTitle(
        requestTitle: String,
        candidateTitle: String,
    ): Boolean =
        TITLE_VARIANT_REGEX.containsMatchIn(candidateTitle) &&
            !TITLE_VARIANT_REGEX.containsMatchIn(requestTitle)

    private fun variantPenalty(
        requestTitle: String,
        candidateTitle: String,
        candidateAlbum: String,
    ): Int {
        var penalty = 0
        if (isVariantTitle(requestTitle, candidateTitle)) {
            penalty += 55
        }
        if (TITLE_VARIANT_REGEX.containsMatchIn(candidateAlbum) && !TITLE_VARIANT_REGEX.containsMatchIn(requestTitle)) {
            penalty += 20
        }
        return penalty
    }

    private fun primaryArtistBonus(
        requestArtist: String,
        candidateArtist: String,
    ): Int {
        val requestPrimary = splitArtistTokens(requestArtist).firstOrNull().orEmpty()
        val candidatePrimary = splitArtistTokens(candidateArtist).firstOrNull().orEmpty()
        if (requestPrimary.isBlank() || candidatePrimary.isBlank()) return 0
        val primaryScore = textMatchScore(requestPrimary, candidatePrimary)
        return when {
            primaryScore >= 95 -> 24
            primaryScore >= 80 -> 12
            else -> 0
        }
    }

    private fun splitArtistTokens(value: String): List<String> =
        value.split(ARTIST_TOKEN_SPLIT_REGEX).map(String::trim).filter(String::isNotBlank)

    private fun containsOnlyCreditLines(
        raw: String,
        timed: Boolean,
    ): Boolean {
        val textLines = payloadTextLines(raw, timed)
        return textLines.isNotEmpty() && textLines.all(::isCreditLine)
    }

    private fun containsInstrumentalMarker(
        raw: String,
        timed: Boolean,
    ): Boolean =
        payloadTextLines(raw, timed).any(::isInstrumentalLine)

    private fun payloadTextLines(
        raw: String,
        timed: Boolean,
    ): List<String> {
        if (raw.isBlank()) return emptyList()
        return if (timed) {
            raw.lineSequence()
                .map { line -> cleanupLineText(line.replace(TIMESTAMP_REGEX, " ")) }
                .filter(String::isNotBlank)
                .toList()
        } else {
            raw.lineSequence()
                .mapNotNull { line ->
                    val match = KARAOKE_LINE_REGEX.matchEntire(line.trim()) ?: return@mapNotNull null
                    cleanupLineText(match.groupValues[3].replace(KARAOKE_WORD_REGEX, " "))
                        .takeIf(String::isNotBlank)
                }
                .toList()
        }
    }

    private fun durationDeltaSeconds(requestDurationSeconds: Int?, candidateDurationSeconds: Int?): Int? {
        if (requestDurationSeconds == null || candidateDurationSeconds == null) return null
        return abs(requestDurationSeconds - candidateDurationSeconds)
    }

    private fun createSecretKey(length: Int): String {
        val chars = SECRET_KEY_ALPHABET
        return buildString(length) {
            repeat(length) {
                append(chars[random.nextInt(chars.length)])
            }
        }
    }

    private fun aesEncode(value: String, secret: String): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(IV.toByteArray(Charsets.UTF_8)),
        )
        return Base64.getEncoder().encodeToString(cipher.doFinal(value.toByteArray(Charsets.UTF_8)))
    }

    private fun rsaEncode(value: String): String {
        val reversed = value.reversed().toByteArray(Charsets.UTF_8)
        val encoded = BigInteger(1, reversed).modPow(RSA_PUBLIC_EXPONENT, RSA_MODULUS).toString(16)
        return encoded.padStart(RSA_HEX_LENGTH, '0').takeLast(RSA_HEX_LENGTH)
    }

    private fun Map<String, Any>.toJsonObject(): JsonObject =
        JsonObject().also { json ->
            forEach { (key, value) ->
                when (value) {
                    is String -> json.addProperty(key, value)
                    is Number -> json.addProperty(key, value)
                    is Boolean -> json.addProperty(key, value)
                    else -> json.addProperty(key, value.toString())
                }
            }
        }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        runCatching { asJsonObject }.getOrNull()

    private fun JsonObject.objectOrNull(key: String): JsonObject? =
        get(key)?.takeIf { !it.isJsonNull }?.asJsonObject

    private fun JsonObject.arrayOrNull(key: String): JsonArray? =
        get(key)?.takeIf { !it.isJsonNull }?.asJsonArray

    private fun JsonObject.stringOrNull(key: String): String? =
        get(key)?.takeIf { !it.isJsonNull }?.asString

    private fun JsonObject.intOrNull(key: String): Int? =
        get(key)?.takeIf { !it.isJsonNull }?.asInt

    private fun JsonObject.longOrNull(key: String): Long? =
        get(key)?.takeIf { !it.isJsonNull }?.asLong

    private fun JsonObject.truthyOrFalse(key: String): Boolean {
        val value = get(key)?.takeIf { !it.isJsonNull } ?: return false
        val primitive = value.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
        return when {
            primitive.isBoolean -> primitive.asBoolean
            primitive.isNumber -> primitive.asInt != 0
            primitive.isString -> primitive.asString.equals("true", ignoreCase = true) || primitive.asString == "1"
            else -> false
        }
    }

    private fun JsonObject.toNeteaseTrack(): NeteaseTrack? {
        val trackId = longOrNull("id") ?: return null
        val artistNames = (arrayOrNull("artists") ?: arrayOrNull("ar"))
            ?.mapNotNull { it.asJsonObjectOrNull()?.stringOrNull("name")?.takeIf(String::isNotBlank) }
            .orEmpty()
        val album = objectOrNull("album") ?: objectOrNull("al")
        return NeteaseTrack(
            trackId = trackId,
            trackName = stringOrNull("name").orEmpty(),
            artistName = artistNames.joinToString(", "),
            albumName = album?.stringOrNull("name").orEmpty(),
            durationSeconds = (intOrNull("duration") ?: intOrNull("dt"))?.let { durationMs -> durationMs / 1000 },
        )
    }

    private fun JsonObject.toNeteaseLyricPayload(): NeteaseLyricPayload? {
        val lrc = objectOrNull("lrc")?.stringOrNull("lyric").orEmpty()
        val klyric = objectOrNull("klyric")?.stringOrNull("lyric").orEmpty()
        val unavailableHint = truthyOrFalse("nolyric") || truthyOrFalse("uncollected")
        val instrumentalHint = truthyOrFalse("pureMusic")
        if (lrc.isBlank() && klyric.isBlank() && !unavailableHint && !instrumentalHint) return null
        return NeteaseLyricPayload(
            lrc = lrc,
            klyric = klyric,
            unavailableHint = unavailableHint,
            instrumentalHint = instrumentalHint,
        )
    }

    internal data class NeteaseTrack(
        val trackId: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSeconds: Int?,
    )

    internal data class NeteaseLyricPayload(
        val lrc: String,
        val klyric: String,
        val unavailableHint: Boolean = false,
        val instrumentalHint: Boolean = false,
    )

    internal data class ParsedLyricPayload(
        val lines: List<LyricsLine>,
        val sourceLabel: String = "",
        val failureReason: String? = null,
    )

    private data class PreparedLookupRequest(
        val titleForMatch: String,
        val artistForMatch: String,
        val searchQueries: List<String>,
    ) {
        companion object {
            fun from(request: LyricsLookupRequest): PreparedLookupRequest {
                val (titleWithoutArtist, extractedArtists) = splitTitleArtist(request.title, request.artist)
                val titleForMatch = titleWithoutArtist.ifBlank { request.title.trim() }
                val artistForMatch = extractedArtists.joinToString(" ").ifBlank { request.artist.trim() }
                val searchQueries = buildList {
                    request.title.trim().takeIf { it.isNotEmpty() }?.let(::add)
                    add(titleForMatch)
                    add("$titleForMatch $artistForMatch".trim())
                    request.album.trim().takeIf { it.isNotEmpty() }?.let { album ->
                        add("$titleForMatch $artistForMatch $album".trim())
                    }
                }.distinct().filter { it.isNotBlank() }
                return PreparedLookupRequest(
                    titleForMatch = titleForMatch,
                    artistForMatch = artistForMatch,
                    searchQueries = searchQueries,
                )
            }

            private fun splitTitleArtist(
                title: String,
                artist: String,
            ): Pair<String, List<String>> {
                val (artistWithoutFeat, artistFeat) = extractFeat(sanitizeString(artist))
                val artists = splitArtists(artistWithoutFeat).toMutableList()
                artists += artistFeat

                val (titleWithoutFeat, titleFeat) = extractFeat(sanitizeString(title))
                artists += titleFeat
                artists.removeAll { it.isBlank() }
                artists.sort()
                return titleWithoutFeat to artists
            }

            private fun sanitizeString(value: String): String =
                value
                    .replace('（', '(')
                    .replace('）', ')')
                    .replace('\u00A0', ' ')
                    .trim()

            private fun extractFeat(value: String): Pair<String, List<String>> {
                val match = FEAT_PATTERN_WITH_PARENTHESES.find(value)
                    ?: FEAT_PATTERN_WITHOUT_PARENTHESES.find(value)
                    ?: return value to emptyList()
                val base = value.removeRange(match.range).trim()
                val featClause = match.groupValues.getOrNull(1)
                    ?.trimStart('.', ' ')
                    ?.removeSuffix(")")
                    .orEmpty()
                return base to splitArtists(featClause)
            }

            private fun splitArtists(value: String): List<String> {
                if (value.isBlank()) return emptyList()
                var parts = listOf(value)
                ARTIST_DELIMITERS.forEach { delimiter ->
                    parts = parts.flatMap { part -> part.split(delimiter) }
                }
                return parts.map(String::trim).filter(String::isNotBlank)
            }
        }
    }

    private companion object {
        private const val NETWORK_TIMEOUT_SECONDS = 15L
        private const val MUSIC_163_REFERER = "https://music.163.com"
        private const val NETEASE_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
        private const val OFFICIAL_SEARCH_URL = "https://music.163.com/weapi/search/get"
        private const val OFFICIAL_LYRIC_URL = "https://music.163.com/weapi/song/lyric?csrf_token="
        private const val LEGACY_SEARCH_URL = "http://music.163.com/api/search/get/"
        private const val LEGACY_LYRIC_URL = "http://music.163.com/api/song/lyric"
        private const val SEARCH_LIMIT = 20
        private const val SECRET_KEY_LENGTH = 16
        private const val NONCE = "0CoJUm6Qyw8W8jud"
        private const val IV = "0102030405060708"
        private const val RSA_HEX_LENGTH = 256
        private val RSA_PUBLIC_EXPONENT = BigInteger("010001", 16)
        private val RSA_MODULUS = BigInteger(
            "00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7",
            16,
        )
        private const val SECRET_KEY_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        private val TIMESTAMP_REGEX = Regex("""\[(\d+):(\d{2}(?:\.\d+)?)\]""")
        private val KARAOKE_LINE_REGEX = Regex("""\[(\d+),(\d+)](.*)""")
        private val KARAOKE_WORD_REGEX = Regex("""\(\d+,\d+\)""")
        private val PARENTHESIS_REGEX = Regex("""\([^)]*\)|\[[^\]]*]""")
        private val FEATURING_REGEX = Regex("""\b(feat|ft|featuring)\.?\b.*""", RegexOption.IGNORE_CASE)
        private val NON_TEXT_REGEX = Regex("""[^\p{L}\p{N}]+""")
        private val MULTISPACE_REGEX = Regex("""\s+""")
        private val FEAT_PATTERN_WITH_PARENTHESES = Regex("""\s*\(feat(.+)\)""", RegexOption.IGNORE_CASE)
        private val FEAT_PATTERN_WITHOUT_PARENTHESES = Regex("""\s+feat(.+)""", RegexOption.IGNORE_CASE)
        private val CREDIT_LINE_REGEX = Regex(
            """^(作词|作曲|编曲|制作人|监制|混音|母带|和声|和音|录音|企划|统筹|人声|演唱|主唱|歌手|lyrics by|written by|composed by|arranged by|producer|composer|lyricist|mixing|mastering|recording|engineer|vocal edit|vocals|vocal|artist|programming)\s*[:：]""",
            RegexOption.IGNORE_CASE,
        )
        private val INSTRUMENTAL_MARKERS = setOf(
            "纯音乐，请欣赏",
            "純音樂，請欣賞",
            "instrumental",
        )
        private val ARTIST_DELIMITERS = arrayOf("/", "&", ",", "，", " x ", " * ", "×", "·")
        private val ARTIST_TOKEN_SPLIT_REGEX = Regex("""\s*(?:/|&|,|，| x | \* |×|·)\s*""")
        private val TITLE_VARIANT_REGEX = Regex(
            """\b(remix|live|cover|instrumental|inst|version|ver|bootleg|edit)\b|伴奏|翻唱|原唱|版|现场|dj""",
            RegexOption.IGNORE_CASE,
        )
        private const val MIN_TITLE_MATCH_SCORE = 55
        private const val MIN_ARTIST_MATCH_SCORE = 40
        private val random = SecureRandom()
    }
}
