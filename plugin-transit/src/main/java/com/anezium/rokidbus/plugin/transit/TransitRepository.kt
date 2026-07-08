package com.anezium.rokidbus.plugin.transit

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant

class TransitRepository(
    private val baseUrl: String = "https://api.transitous.org/api/v1",
) {
    fun nearbyStops(location: TransitCoordinate): List<TransitStop> {
        val json = get(
            "$baseUrl/reverse-geocode?place=${location.lat},${location.lon}&type=STOP",
        )
        return parseStops(json, location)
    }

    fun departures(stopId: String): List<TransitDeparture> {
        val encodedStopId = URLEncoder.encode(stopId, Charsets.UTF_8.name())
        return parseDepartures(get("$baseUrl/stoptimes?stopId=$encodedStopId&n=12"))
    }

    fun searchStops(query: String): List<TransitStopMatch> {
        val encodedQuery = URLEncoder.encode(query, Charsets.UTF_8.name())
        return parseStopMatches(get("$baseUrl/geocode?text=$encodedQuery&type=STOP")).take(MAX_SEARCH_RESULTS)
    }

    private fun get(urlText: String): String {
        var lastFailure: Throwable? = null
        repeat(2) { attempt ->
            try {
                return getOnce(urlText)
            } catch (t: Throwable) {
                lastFailure = t
                if (attempt == 0) Thread.sleep(750L)
            }
        }
        throw IOException(lastFailure?.message ?: "Transitous request failed", lastFailure)
    }

    private fun getOnce(urlText: String): String {
        val connection = (URL(urlText).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) throw IOException("HTTP $status: ${body.take(120)}")
            body
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "RokidNexus/0.1 (+https://github.com/Anezium)"

        fun parseStops(json: String, origin: TransitCoordinate): List<TransitStop> {
            val array = JSONArray(json)
            return (0 until array.length())
                .mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    if (item.optString("type") != "STOP") return@mapNotNull null
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isBlank() || name.isBlank()) return@mapNotNull null
                    val lat = item.optDouble("lat", Double.NaN)
                    val lon = item.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                    val coordinate = TransitCoordinate(lat, lon)
                    TransitStop(
                        id = id,
                        name = name,
                        lat = lat,
                        lon = lon,
                        distanceMeters = haversineMeters(origin, coordinate),
                        modes = stringList(item.optJSONArray("modes")),
                    )
                }
                .sortedBy { it.distanceMeters }
        }

        fun parseStopMatches(json: String): List<TransitStopMatch> {
            val array = itemArray(json)
            return (0 until array.length())
                .mapNotNull { index ->
                    val item = array.optJSONObject(index) ?: return@mapNotNull null
                    if (item.optString("type") != "STOP") return@mapNotNull null
                    val id = item.optString("id")
                    val name = item.optString("name")
                    if (id.isBlank() || name.isBlank()) return@mapNotNull null
                    val lat = item.optDouble("lat", Double.NaN)
                    val lon = item.optDouble("lon", Double.NaN)
                    if (lat.isNaN() || lon.isNaN()) return@mapNotNull null
                    TransitStopMatch(
                        stop = TransitStop(
                            id = id,
                            name = name,
                            lat = lat,
                            lon = lon,
                            distanceMeters = UNKNOWN_DISTANCE_METERS,
                        ),
                        city = defaultCity(item.optJSONArray("areas")),
                    )
                }
                .take(MAX_SEARCH_RESULTS)
        }

        fun parseDepartures(json: String): List<TransitDeparture> {
            val array = JSONObject(json).optJSONArray("stopTimes") ?: JSONArray()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val place = item.optJSONObject("place") ?: JSONObject()
                val departureText = place.optString("departure")
                    .ifBlank { place.optString("scheduledDeparture") }
                val departure = parseInstantOrNull(departureText) ?: return@mapNotNull null
                TransitDeparture(
                    mode = item.optString("mode"),
                    routeShortName = item.optString("routeShortName").takeIf { it.isNotBlank() },
                    headsign = resolveHeadsign(
                        headsign = item.optString("headsign"),
                        tripTo = item.optJSONObject("tripTo")?.optString("name").orEmpty(),
                    ),
                    departure = departure,
                    scheduledDeparture = parseInstantOrNull(place.optString("scheduledDeparture")),
                    cancelled = item.optBoolean("cancelled") ||
                        item.optBoolean("tripCancelled") ||
                        place.optBoolean("cancelled"),
                )
            }
        }

        /**
         * SNCF rail feeds (RER, Transilien) publish four-letter mission codes
         * as headsign (ZECO, VOPA, ILUS...); the trip's terminus is the human
         * destination. Metro and bus headsigns are already real names — and
         * often more precise than the terminus — so they are kept as-is.
         */
        private fun resolveHeadsign(headsign: String, tripTo: String): String = when {
            headsign.isBlank() -> tripTo
            MISSION_CODE.matches(headsign) && tripTo.isNotBlank() -> tripTo
            else -> headsign
        }

        private val MISSION_CODE = Regex("^[A-Z]{4}$")

        private fun parseInstantOrNull(value: String): Instant? =
            runCatching { Instant.parse(value) }.getOrNull()

        private fun stringList(array: JSONArray?): List<String> {
            if (array == null) return emptyList()
            return buildList {
                for (index in 0 until array.length()) {
                    val value = array.optString(index).trim()
                    if (value.isNotBlank()) add(value)
                }
            }
        }

        private fun itemArray(json: String): JSONArray {
            val trimmed = json.trim()
            if (trimmed.startsWith("[")) return JSONArray(trimmed)
            val root = JSONObject(trimmed)
            return root.optJSONArray("items")
                ?: root.optJSONArray("results")
                ?: root.optJSONArray("features")
                ?: JSONArray()
        }

        private fun defaultCity(areas: JSONArray?): String {
            if (areas == null) return ""
            for (index in 0 until areas.length()) {
                val area = areas.optJSONObject(index) ?: continue
                if (area.optBoolean("default", false)) return area.optString("name")
            }
            return ""
        }

        private const val MAX_SEARCH_RESULTS = 8
    }
}
