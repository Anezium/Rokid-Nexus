package com.anezium.rokidbus.lyrics.lyrics

import android.util.Log
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.settings.SpotifySpDcSource
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Base64
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

object SpotifySpDcCookie {
    fun extractValue(input: String): String? {
        var normalized = input.stripSurroundingQuotesAndWhitespace()
        if (normalized.isBlank()) return null

        if (normalized.startsWith("cookie:", ignoreCase = true)) {
            normalized = normalized.substring("cookie:".length)
                .stripSurroundingQuotesAndWhitespace()
        }

        normalized.split(';').forEach { rawPart ->
            val part = rawPart.stripSurroundingQuotesAndWhitespace()
            val equalsIndex = part.indexOf('=')
            if (equalsIndex < 0) return@forEach
            val name = part.substring(0, equalsIndex).stripSurroundingQuotesAndWhitespace()
            if (!name.equals("sp_dc", ignoreCase = true)) return@forEach
            val value = part.substring(equalsIndex + 1)
                .stripSurroundingQuotesAndWhitespace()
                .takeIf { it.isNotBlank() }
            if (value != null) return value
        }

        val whitespaceParts = normalized
            .split(Regex("""\s+"""))
            .map { it.stripSurroundingQuotesAndWhitespace() }
            .filter { it.isNotBlank() }
        val nameIndex = whitespaceParts.indexOfFirst { it.equals("sp_dc", ignoreCase = true) }
        if (nameIndex >= 0 && nameIndex + 1 < whitespaceParts.size) {
            return whitespaceParts[nameIndex + 1].takeIf { it.isNotBlank() }
        }

        if ('=' in normalized || ';' in normalized) return null
        return normalized.takeIf { it.isNotBlank() }
    }

    private fun String.stripSurroundingQuotesAndWhitespace(): String {
        var value = trim()
        while (
            value.length >= 2 &&
            ((value.first() == '"' && value.last() == '"') ||
                (value.first() == '\'' && value.last() == '\''))
        ) {
            value = value.substring(1, value.length - 1).trim()
        }
        return value
    }
}

object SpotifyTrackIdentifier {
    fun extract(input: String): String? {
        val trimmed = input.trim()
        if (BARE_TRACK_ID.matches(trimmed)) return trimmed

        SPOTIFY_TRACK_URI.matchEntire(trimmed)?.let { return it.groupValues[1] }

        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (!uri.scheme.equals("https", ignoreCase = true) ||
            !uri.host.equals("open.spotify.com", ignoreCase = true)
        ) {
            return null
        }
        val pathSegments = uri.path.orEmpty().split('/').filter { it.isNotBlank() }
        val trackIndex = pathSegments.indexOf("track")
        if (trackIndex < 0 || trackIndex + 1 >= pathSegments.size) return null
        return pathSegments[trackIndex + 1].takeIf(BARE_TRACK_ID::matches)
    }

    private val BARE_TRACK_ID = Regex("""[A-Za-z0-9]{22}""")
    private val SPOTIFY_TRACK_URI = Regex("""spotify:track:([A-Za-z0-9]{22})""")
}

data class SpotifyApiEndpoints(
    val secretUrl: String =
        "https://raw.githubusercontent.com/xyloflake/spot-secrets-go/main/secrets/secretDict.json",
    val serverTimeUrl: String = "https://open.spotify.com/api/server-time",
    val tokenUrl: String = "https://open.spotify.com/api/token",
    val lyricsBaseUrl: String = "https://spclient.wg.spotify.com/color-lyrics/v2/track",
)

class SpotifyLyricsProvider(
    private val spDcSource: SpotifySpDcSource,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(NETWORK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build(),
    private val endpoints: SpotifyApiEndpoints = SpotifyApiEndpoints(),
    private val nowMs: () -> Long = System::currentTimeMillis,
) : LyricsProvider {
    override val providerName: String = "SPOTIFY"

    private val tokenLock = Any()
    private val secretLock = Any()
    @Volatile private var cachedToken: SpotifyBearerToken? = null
    @Volatile private var cachedSecret: SpotifyTotpSecret? = null

    override suspend fun fetch(request: LyricsLookupRequest): LyricsProviderAttempt = withContext(Dispatchers.IO) {
        val configuredSpDc = try {
            spDcSource.getSpotifySpDc()
        } catch (error: Throwable) {
            return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = error.message ?: "Spotify settings could not be read.",
            )
        }
        val spDc = SpotifySpDcCookie.extractValue(configuredSpDc.orEmpty())
            ?: return@withContext LyricsProviderAttempt.Disabled(
                provider = providerName,
                reason = "Add your Spotify sp_dc cookie in settings to enable Spotify lyrics.",
            )
        val trackId = SpotifyTrackIdentifier.extract(request.spotifyTrackId.orEmpty())
            ?: return@withContext LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = "Spotify lyrics require a Spotify track playing in the Spotify app.",
            )

        try {
            val bearerToken = validBearerToken(spDc)
            val url = endpoints.lyricsBaseUrl.trimEnd('/').toHttpUrl().newBuilder()
                .addPathSegment(trackId)
                .addQueryParameter("format", "json")
                .addQueryParameter("market", "from_token")
                .build()
            val response = executeWebRequest(
                request = Request.Builder().url(url),
                authorization = "Bearer ${bearerToken.accessToken}",
                cookie = "sp_dc=$spDc",
            )
            response.use {
                val body = it.body?.string().orEmpty()
                debugLog { "lyrics_http_status=${it.code} content_type=${it.header("Content-Type") ?: "unknown"}" }
                if (it.code == 401) {
                    invalidateSession()
                    return@withContext LyricsProviderAttempt.NoMatch(
                        provider = providerName,
                        reason = "Spotify lyrics request failed with HTTP 401. The web token will be refreshed on the next lookup.",
                    )
                }
                if (!it.isSuccessful) {
                    throw SpotifyLyricsException("Spotify lyrics request failed with HTTP ${it.code}.")
                }
                LyricsProviderAttempt.Success(
                    SpotifyColorLyricsParser.parse(
                        rawJson = body,
                        trackId = trackId,
                        request = request,
                    ),
                )
            }
        } catch (error: Throwable) {
            debugLog { "lookup_failed reason='${error.message ?: "unknown error"}'" }
            LyricsProviderAttempt.NoMatch(
                provider = providerName,
                reason = error.message ?: "Spotify lyrics lookup failed.",
            )
        }
    }

    fun invalidateSession() {
        synchronized(tokenLock) {
            cachedToken = null
        }
    }

    private fun validBearerToken(spDc: String): SpotifyBearerToken {
        val fingerprint = cookieFingerprint(spDc)
        return synchronized(tokenLock) {
            cachedToken?.takeIf { token ->
                token.cookieFingerprint == fingerprint &&
                    token.expiresAtMs > nowMs() + TOKEN_EXPIRY_SAFETY_MS
            } ?: fetchBearerToken(spDc, fingerprint).also { cachedToken = it }
        }
    }

    private fun fetchBearerToken(
        spDc: String,
        fingerprint: String,
    ): SpotifyBearerToken {
        val secret = validSecret()
        val serverTimeMs = fetchServerTimeMs()
        val totp = SpotifyTotp.generate(
            secret = secret.secret,
            timestampMs = serverTimeMs,
        )
        val url = endpoints.tokenUrl.toHttpUrl().newBuilder()
            .addQueryParameter("reason", "init")
            .addQueryParameter("productType", "web-player")
            .addQueryParameter("totp", totp)
            .addQueryParameter("totpVer", secret.version)
            .addQueryParameter("ts", serverTimeMs.toString())
            .build()
        val response = executeWebRequest(
            request = Request.Builder().url(url),
            cookie = "sp_dc=$spDc",
        )
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw SpotifyLyricsException("Spotify token request failed with HTTP ${it.code}.")
            }
            val root = parseJsonObject(body, "Spotify token")
            val accessToken = root.stringOrNull("accessToken")
                .orEmpty()
                .ifBlank { root.stringOrNull("access_token").orEmpty() }
                .trim()
            if (accessToken.isBlank()) {
                throw SpotifyLyricsException("Spotify token response did not include an access token.")
            }
            if (root.truthy("isAnonymous")) {
                throw SpotifyLyricsException(
                    "Spotify returned an anonymous web token. Refresh sp_dc from a logged-in Spotify web session.",
                )
            }
            val expiresAtMs = root.longOrNull("accessTokenExpirationTimestampMs")
                ?: root.longOrNull("expires_in")?.let { expiresIn -> nowMs() + expiresIn * 1_000L }
                ?: nowMs() + DEFAULT_TOKEN_LIFETIME_MS
            debugLog {
                "token_http_status=${it.code} content_type=${it.header("Content-Type") ?: "unknown"} is_anonymous=false auth_token_len=${accessToken.length}"
            }
            return SpotifyBearerToken(
                accessToken = accessToken,
                expiresAtMs = expiresAtMs,
                cookieFingerprint = fingerprint,
            )
        }
    }

    private fun validSecret(): SpotifyTotpSecret = synchronized(secretLock) {
        cachedSecret ?: fetchSecret().also { cachedSecret = it }
    }

    private fun fetchSecret(): SpotifyTotpSecret {
        val request = Request.Builder()
            .url(endpoints.secretUrl)
            .header("Accept", "application/json")
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw SpotifyLyricsException("Spotify TOTP secret request failed with HTTP ${response.code}.")
            }
            val root = parseJsonObject(body, "Spotify TOTP secret")
            val version = root.keySet()
                .mapNotNull(String::toIntOrNull)
                .maxOrNull()
                ?.toString()
                ?: throw SpotifyLyricsException("Spotify TOTP secret response did not include a numeric version.")
            val values = root.arrayOrNull(version)
                ?.map { element -> element.intOrNull() }
                ?: throw SpotifyLyricsException("Spotify TOTP secret version $version was invalid.")
            if (values.any { it == null }) {
                throw SpotifyLyricsException("Spotify TOTP secret version $version contained invalid values.")
            }
            return SpotifyTotpSecret(
                secret = SpotifyTotp.transformSecret(values.filterNotNull()),
                version = version,
            )
        }
    }

    private fun fetchServerTimeMs(): Long {
        val response = executeWebRequest(Request.Builder().url(endpoints.serverTimeUrl))
        response.use {
            val body = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                throw SpotifyLyricsException("Spotify server-time request failed with HTTP ${it.code}.")
            }
            val serverTimeSeconds = parseJsonObject(body, "Spotify server-time")
                .doubleOrNull("serverTime")
                ?: throw SpotifyLyricsException("Spotify server-time response was invalid.")
            return (serverTimeSeconds * 1_000.0).toLong()
        }
    }

    private fun executeWebRequest(
        request: Request.Builder,
        authorization: String? = null,
        cookie: String? = null,
    ) = client.newCall(
        request.apply {
            SPOTIFY_WEB_HEADERS.forEach { (name, value) -> header(name, value) }
            authorization?.let { header("Authorization", it) }
            cookie?.let { header("Cookie", it) }
            get()
        }.build(),
    ).execute()

    private fun cookieFingerprint(value: String): String =
        Base64.getEncoder().encodeToString(
            MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
        )

    private fun debugLog(message: () -> String) {
        runCatching { Log.d(TAG, message()) }
    }

    private data class SpotifyBearerToken(
        val accessToken: String,
        val expiresAtMs: Long,
        val cookieFingerprint: String,
    )

    private data class SpotifyTotpSecret(
        val secret: ByteArray,
        val version: String,
    )

    private companion object {
        private const val TAG = "LyricsSpotify"
        private const val NETWORK_TIMEOUT_SECONDS = 8L
        private const val TOKEN_EXPIRY_SAFETY_MS = 30_000L
        private const val DEFAULT_TOKEN_LIFETIME_MS = 50L * 60L * 1_000L
    }
}

internal object SpotifyTotp {
    fun transformSecret(values: List<Int>): ByteArray =
        values.mapIndexed { index, value -> value xor ((index % 33) + 9) }
            .joinToString(separator = "")
            .toByteArray(Charsets.UTF_8)

    fun generate(
        secret: ByteArray,
        timestampMs: Long,
    ): String {
        var counter = timestampMs / 1_000L / 30L
        val counterBytes = ByteArray(8)
        for (index in counterBytes.indices.reversed()) {
            counterBytes[index] = (counter and 0xFFL).toByte()
            counter = counter ushr 8
        }
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val digest = mac.doFinal(counterBytes)
        val offset = digest.last().toInt() and 0x0F
        val binary =
            ((digest[offset].toInt() and 0x7F) shl 24) or
                ((digest[offset + 1].toInt() and 0xFF) shl 16) or
                ((digest[offset + 2].toInt() and 0xFF) shl 8) or
                (digest[offset + 3].toInt() and 0xFF)
        return String.format(Locale.US, "%06d", binary % 1_000_000)
    }
}

internal object SpotifyColorLyricsParser {
    fun parse(
        rawJson: String,
        trackId: String,
        request: LyricsLookupRequest,
    ): LyricsFetchResult = parse(
        root = parseJsonObject(rawJson, "Spotify color-lyrics"),
        trackId = trackId,
        request = request,
    )

    fun parse(
        root: JsonObject,
        trackId: String,
        request: LyricsLookupRequest,
    ): LyricsFetchResult {
        val lyrics = root.objectOrNull("lyrics") ?: root
        val syncType = lyrics.stringOrNull("syncType")
            ?: root.stringOrNull("syncType")
            ?: "UNKNOWN"
        val rawLines = lyrics.arrayOrNull("lines") ?: root.arrayOrNull("lines") ?: JsonArray()
        val lines = rawLines.mapNotNull { element ->
            val line = element.objectOrNull() ?: return@mapNotNull null
            val startTimeMs = line.longOrNull("startTimeMs") ?: return@mapNotNull null
            val text = line.stringOrNull("words")
                .orEmpty()
                .ifBlank { line.stringOrNull("text").orEmpty() }
                .trim()
                .replace(MULTISPACE_REGEX, " ")
                .ifBlank { "(instrumental)" }
            LyricsLine(
                startTimeMs = startTimeMs,
                text = text,
            )
        }.sortedBy(LyricsLine::startTimeMs)

        if (syncType != "LINE_SYNCED" || lines.isEmpty()) {
            throw SpotifyLyricsException(
                "Spotify returned syncType=$syncType with ${lines.size} timed lines; LINE_SYNCED is required.",
            )
        }

        val trackTitle = root.stringOrNull("trackTitle")
            .orEmpty()
            .ifBlank { root.stringOrNull("title").orEmpty() }
            .ifBlank { request.title }
            .ifBlank { "Spotify track $trackId" }
        val artistName = root.stringOrNull("artistName")
            .orEmpty()
            .ifBlank { root.stringOrNull("artist").orEmpty() }
            .ifBlank { request.artist }
        val albumName = root.stringOrNull("albumName")
            .orEmpty()
            .ifBlank { root.stringOrNull("album").orEmpty() }
            .ifBlank { request.album }

        return LyricsFetchResult(
            trackTitle = trackTitle,
            artistName = artistName,
            albumName = albumName,
            durationSeconds = root.intOrNull("durationSeconds") ?: request.durationSeconds,
            provider = "SPOTIFY",
            synced = true,
            lines = lines,
            plainLyrics = "",
            sourceSummary = "Spotify color-lyrics returned LINE_SYNCED lyrics with ${lines.size} timed lines.",
        )
    }

    private val MULTISPACE_REGEX = Regex("""\s+""")
}

private class SpotifyLyricsException(message: String) : IOException(message)

private fun parseJsonObject(
    rawJson: String,
    label: String,
): JsonObject = runCatching {
    JsonParser.parseString(rawJson).asJsonObject
}.getOrElse {
    throw SpotifyLyricsException("$label returned an invalid JSON response.")
}

private fun JsonElement.objectOrNull(): JsonObject? =
    takeIf { isJsonObject }?.asJsonObject

private fun JsonObject.objectOrNull(key: String): JsonObject? =
    get(key)?.objectOrNull()

private fun JsonObject.arrayOrNull(key: String): JsonArray? =
    get(key)?.takeIf { it.isJsonArray }?.asJsonArray

private fun JsonObject.stringOrNull(key: String): String? =
    get(key)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString

private fun JsonObject.longOrNull(key: String): Long? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { value ->
        runCatching { value.asLong }.getOrNull()
            ?: runCatching { value.asString.toLong() }.getOrNull()
    }

private fun JsonObject.intOrNull(key: String): Int? =
    longOrNull(key)?.toInt()

private fun JsonObject.doubleOrNull(key: String): Double? =
    get(key)?.takeIf { it.isJsonPrimitive }?.let { value ->
        runCatching { value.asDouble }.getOrNull()
            ?: runCatching { value.asString.toDouble() }.getOrNull()
    }

private fun JsonObject.truthy(key: String): Boolean {
    val value = get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return false
    if (value.isBoolean) return value.asBoolean
    if (value.isNumber) return runCatching { value.asDouble != 0.0 }.getOrDefault(false)
    return value.asString == "1" || value.asString.equals("true", ignoreCase = true)
}

private fun JsonElement.intOrNull(): Int? =
    takeIf { isJsonPrimitive }?.let { value -> runCatching { value.asInt }.getOrNull() }

private val SPOTIFY_WEB_HEADERS = linkedMapOf(
    "Accept" to "application/json",
    "Accept-Language" to "en-US",
    "Content-Type" to "application/json",
    "Origin" to "https://open.spotify.com/",
    "Priority" to "u=1, i",
    "Referer" to "https://open.spotify.com/",
    "Sec-CH-UA" to "\"Not)A;Brand\";v=\"99\", \"Google Chrome\";v=\"127\", \"Chromium\";v=\"127\"",
    "Sec-CH-UA-Mobile" to "?0",
    "Sec-CH-UA-Platform" to "\"Windows\"",
    "Sec-Fetch-Dest" to "empty",
    "Sec-Fetch-Mode" to "cors",
    "Sec-Fetch-Site" to "same-site",
    "User-Agent" to
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/127.0.0.0 Safari/537.36",
    "Spotify-App-Version" to "1.2.46.25.g7f189073",
    "App-Platform" to "WebPlayer",
)

