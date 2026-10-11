package com.anezium.rokidbus.plugin.nav

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder

/**
 * Transitous geocoding and walking plans, each one request the wearer asked for: no retry loop,
 * no background refresh, nothing kept once the journey ends. One instance serves one request and
 * [cancel] stops it from any thread.
 */
internal class TransitousPlanner(private val fetch: ((String, Map<String, String>) -> String)? = null) {
    @Volatile private var connection: HttpURLConnection? = null

    @Volatile private var cancelled = false

    fun search(query: String): List<NavDestination> {
        require(query.trim().length in 3..200) { "Enter a place or address" }
        val json = JSONArray(get("/api/v1/geocode", mapOf("text" to query.trim(), "numResults" to "5")))
        return (0 until json.length().coerceAtMost(5)).mapNotNull { index ->
            runCatching {
                val match = json.getJSONObject(index)
                val area = match.optJSONArray("areas")?.let { areas ->
                    (0 until areas.length()).mapNotNull { areas.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }
                        .takeLast(2).joinToString(", ")
                }.orEmpty()
                NavDestination(
                    listOf(match.getString("name"), area).filter(String::isNotBlank).joinToString(", ").take(160),
                    GeoPoint(match.getDouble("lat"), match.getDouble("lon")),
                )
            }.getOrNull()
        }.distinctBy { it.point }
    }

    /** A walking route only: transit legs are never turned into walking guidance. */
    fun plan(from: GeoPoint, destination: NavDestination): NavRoute = TransitousRouteParser.parse(
        JSONObject(
            get(
                "/api/v2/plan",
                mapOf(
                    "fromPlace" to "${from.lat},${from.lon}",
                    "toPlace" to "${destination.point.lat},${destination.point.lon}",
                    "directModes" to "WALK",
                    "transitModes" to "",
                    "detailedLegs" to "true",
                    "numItineraries" to "1",
                ),
            ),
        ),
        destination,
    )

    fun cancel() {
        cancelled = true
        connection?.disconnect()
    }

    private fun get(path: String, query: Map<String, String>): String {
        check(!cancelled && !Thread.currentThread().isInterrupted) { "Request cancelled" }
        fetch?.let { return it(path, query) }
        val parameters = query.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
        val request = URI("$BASE_URL$path?$parameters").toURL().openConnection() as HttpURLConnection
        connection = request
        try {
            request.connectTimeout = CONNECT_TIMEOUT_MS
            request.readTimeout = READ_TIMEOUT_MS
            request.instanceFollowRedirects = false
            request.setRequestProperty("User-Agent", USER_AGENT)
            request.setRequestProperty("Accept", "application/json")
            check(!cancelled) { "Request cancelled" }
            when (request.responseCode) {
                200 -> Unit
                429 -> error("Transitous is busy. Try again later.")
                else -> error("Transitous could not complete the request")
            }
            return request.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    check(!cancelled && !Thread.currentThread().isInterrupted) { "Request cancelled" }
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "Route response is too large" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } finally {
            request.disconnect()
            connection = null
        }
    }

    private companion object {
        const val BASE_URL = "https://api.transitous.org"
        const val USER_AGENT = "RokidNexus-Navigation (+https://github.com/Anezium/Rokid-Nexus)"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    }
}
