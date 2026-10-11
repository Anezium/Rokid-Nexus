package com.anezium.rokidbus.plugin.transit

import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Transit's skill operations: their ids, their provisional limits, and the encoding of the
 * identifiers Transit hands the hub as entity references.
 *
 * The limits mirror `res/raw/nexus_skills.json`, which declares them to the hub; a unit test
 * keeps the two in step, so a limit is tuned here and in the catalog together.
 */
internal object TransitSkillContract {
    const val LIST_FAVORITES = "list_favorites"
    const val SEARCH_STOPS = "search_stops"
    const val GET_DEPARTURES = "get_departures"
    const val START_JOURNEY = "start_journey"
    const val JOURNEY_STATUS = "journey_status"
    const val STOP_JOURNEY = "stop_journey"

    const val FAVORITES_PAGE_SIZE = 20
    const val MAX_FILTER_CHARS = 60
    const val MAX_CURSOR_CHARS = 64
    const val MAX_QUERY_CHARS = 120
    const val MAX_SEARCH_RESULTS = 8
    const val MAX_BOARD_DEPARTURES = 12
    const val MAX_LINE_CHARS = 16
    const val MAX_LINE_SELECTOR_CHARS = 80
    const val MAX_DIRECTION_CHARS = 80
    const val MAX_NAME_CHARS = 80

    /** A follow-up may answer from a board observed at most this long ago; after that it refreshes. */
    const val BOARD_REUSE_MS = 30_000L

    /** How long before its departure a row stops counting as upcoming. */
    const val DEPARTED_GRACE_MS = 30_000L

    /** Alternatives the summary counts beyond the guided itinerary. */
    const val MAX_ALTERNATIVES = 5

    /** Budget for one position fix inside start_journey, and what is kept back for planning. */
    const val JOURNEY_LOCATION_TIMEOUT_MS = 6_000L
    const val JOURNEY_PLAN_RESERVE_MS = 5_000L

    // Domain error and input codes, beside the platform's.
    const val ERROR_NO_ROUTE = "no_route"
    const val ERROR_LOCATION_UNAVAILABLE = "location_unavailable"
    const val INPUT_HOME_NOT_SET = "home_not_set"

    /** A stop Transit issued: its feed identifier, name, and position (public stop data). */
    fun encodeStop(stop: TransitStop): String = JSONObject()
        .put("i", stop.id)
        .put("n", stop.name.take(MAX_NAME_CHARS))
        .put("a", stop.lat)
        .put("o", stop.lon)
        .toString()

    /** Transit validates its own identifiers again: the hub guarantees origin, not freshness. */
    fun decodeStop(value: String): TransitStop? = runCatching {
        val json = JSONObject(value)
        val id = json.getString("i").takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
            ?: return null
        val name = json.getString("n").takeIf { it.isNotBlank() && it.length <= MAX_NAME_CHARS } ?: return null
        val lat = json.getDouble("a").takeIf { it in -90.0..90.0 } ?: return null
        val lon = json.getDouble("o").takeIf { it in -180.0..180.0 } ?: return null
        TransitStop(id = id, name = name, lat = lat, lon = lon)
    }.getOrNull()

    /** One row of one observed board, identified by what the feed gives rather than its row index. */
    data class DepartureSnapshot(
        val stopId: String,
        val line: String?,
        val direction: String,
        val mode: String,
        val scheduled: Instant?,
        val departure: Instant,
        val tripId: String?,
        val observedAt: Instant,
    )

    fun encodeDeparture(snapshot: DepartureSnapshot): String = JSONObject()
        .put("s", snapshot.stopId)
        .putOpt("l", snapshot.line)
        .put("d", snapshot.direction.take(MAX_DIRECTION_CHARS))
        .put("m", snapshot.mode.take(24))
        .putOpt("c", snapshot.scheduled?.toEpochMilli())
        .put("p", snapshot.departure.toEpochMilli())
        .putOpt("t", snapshot.tripId?.take(160))
        .put("o", snapshot.observedAt.toEpochMilli())
        .toString()

    fun decodeDeparture(value: String): DepartureSnapshot? = runCatching {
        val json = JSONObject(value)
        DepartureSnapshot(
            stopId = json.getString("s").takeIf(String::isNotBlank) ?: return null,
            line = json.optString("l").takeIf(String::isNotBlank),
            direction = json.getString("d"),
            mode = json.optString("m"),
            scheduled = if (json.has("c")) Instant.ofEpochMilli(json.getLong("c")) else null,
            departure = Instant.ofEpochMilli(json.getLong("p")),
            tripId = json.optString("t").takeIf(String::isNotBlank),
            observedAt = Instant.ofEpochMilli(json.getLong("o")),
        )
    }.getOrNull()

    /** A favorites cursor is tied to the list and filter it pages, so a changed list cannot skip rows. */
    fun favoritesRevision(stops: List<TransitStop>, filter: String?): String =
        sha256(stops.joinToString("\n") { it.id } + "\u0000" + filter.orEmpty()).take(12)

    fun localTime(instant: Instant, zone: ZoneId): String = LOCAL_TIME.withZone(zone).format(instant)

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }

    private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
}
