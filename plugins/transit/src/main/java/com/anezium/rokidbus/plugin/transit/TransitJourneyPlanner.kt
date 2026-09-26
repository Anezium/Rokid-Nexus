package com.anezium.rokidbus.plugin.transit

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/** What a routing provider answered for one request. */
internal sealed interface TransitPlanResult {
    /** Itineraries in the provider's order; the first is the one to guide. */
    data class Planned(val itineraries: List<TransitItinerary>) : TransitPlanResult
    data object NoRoute : TransitPlanResult
    data class Failed(val cause: Throwable) : TransitPlanResult
}

/**
 * The seam journey guidance plans through. The operations and the panel only ever see
 * [TransitItinerary], so a different provider (Google Routes, with its own key and billing)
 * can replace [TransitousJourneyPlanner] without touching either.
 */
internal interface TransitJourneyPlanner {
    fun plan(
        from: TransitCoordinate,
        to: TransitCoordinate,
        departAt: Instant?,
        deadlineAtMs: Long,
    ): TransitPlanResult
}

/**
 * Plans on the routing endpoint of the service Transit already queries. That service permits
 * non-commercial use only and asks to be contacted before routing use; every request carries
 * Transit's identifying User-Agent (see [TransitRepository.userAgent]).
 */
internal class TransitousJourneyPlanner(
    private val baseUrl: String = TransitRepository.BASE_URL,
    private val http: (url: String, deadlineAtMs: Long) -> String = { url, deadline -> getWithinDeadline(url, deadline) },
) : TransitJourneyPlanner {
    override fun plan(
        from: TransitCoordinate,
        to: TransitCoordinate,
        departAt: Instant?,
        deadlineAtMs: Long,
    ): TransitPlanResult {
        val url = buildString {
            append(baseUrl).append("/plan?fromPlace=").append(from.lat).append(',').append(from.lon)
            append("&toPlace=").append(to.lat).append(',').append(to.lon)
            departAt?.let { append("&time=").append(it.toString()) }
        }
        val body = try {
            http(url, deadlineAtMs)
        } catch (failure: Throwable) {
            if (failure is InterruptedException) throw failure
            return TransitPlanResult.Failed(failure)
        }
        val itineraries = runCatching { parseItineraries(body) }.getOrElse { return TransitPlanResult.Failed(it) }
        return if (itineraries.isEmpty()) TransitPlanResult.NoRoute else TransitPlanResult.Planned(itineraries)
    }

    companion object {
        /** Itineraries with at least one leg and absolute instants on every leg; others are dropped. */
        fun parseItineraries(json: String): List<TransitItinerary> {
            val array = JSONObject(json).optJSONArray("itineraries") ?: JSONArray()
            return (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val legsJson = item.optJSONArray("legs") ?: return@mapNotNull null
                val legs = (0 until legsJson.length()).map { legIndex ->
                    parseLeg(legsJson.optJSONObject(legIndex) ?: return@mapNotNull null) ?: return@mapNotNull null
                }
                if (legs.isEmpty()) return@mapNotNull null
                TransitItinerary(
                    start = instant(item.optString("startTime")) ?: legs.first().start,
                    end = instant(item.optString("endTime")) ?: legs.last().end,
                    transfers = item.optInt("transfers", (legs.count { !it.isWalk } - 1).coerceAtLeast(0)),
                    legs = legs,
                )
            }
        }

        private fun parseLeg(json: JSONObject): TransitLeg? {
            val from = parsePlace(json.optJSONObject("from") ?: return null) ?: return null
            val to = parsePlace(json.optJSONObject("to") ?: return null) ?: return null
            val start = instant(json.optString("startTime")) ?: return null
            val end = instant(json.optString("endTime")) ?: return null
            val intermediate = json.optJSONArray("intermediateStops")?.let { stops ->
                (0 until stops.length()).mapNotNull { stops.optJSONObject(it)?.let(::parsePlace) }
            }.orEmpty()
            val line = json.optString("routeShortName").ifBlank { json.optString("displayName") }
                .trim().takeIf(String::isNotEmpty)
            val headsign = json.optString("headsign").trim().ifBlank {
                json.optJSONObject("tripTo")?.optString("name").orEmpty().trim()
            }.takeIf(String::isNotEmpty)
            return TransitLeg(
                mode = json.optString("mode").ifBlank { "WALK" },
                line = line,
                headsign = headsign,
                from = from,
                to = to,
                start = start,
                end = end,
                scheduledStart = instant(json.optString("scheduledStartTime")),
                scheduledEnd = instant(json.optString("scheduledEndTime")),
                realTime = json.optBoolean("realTime", false),
                tripId = json.optString("tripId").takeIf(String::isNotBlank),
                intermediateStops = intermediate,
                distanceMeters = json.optDouble("distance", Double.NaN).takeIf { !it.isNaN() }?.toInt(),
            )
        }

        private fun parsePlace(json: JSONObject): TransitPlace? {
            val lat = json.optDouble("lat", Double.NaN)
            val lon = json.optDouble("lon", Double.NaN)
            if (lat.isNaN() || lon.isNaN()) return null
            return TransitPlace(
                name = json.optString("name").trim(),
                lat = lat,
                lon = lon,
                stopId = json.optString("stopId").takeIf(String::isNotBlank),
                arrival = instant(json.optString("arrival")),
                departure = instant(json.optString("departure")),
            )
        }

        private fun instant(value: String?): Instant? =
            value?.takeIf(String::isNotBlank)?.let { runCatching { Instant.parse(it) }.getOrNull() }
    }
}
