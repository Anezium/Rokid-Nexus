package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.shared.skills.SkillDispatch
import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import kotlin.math.ceil

/** How one Transit skill call ended, before it becomes the SDK's answer. */
internal sealed interface TransitSkillOutcome {
    data class Completed(val data: JSONObject) : TransitSkillOutcome
    data class NeedsInput(val reason: String, val prompt: String? = null) : TransitSkillOutcome
    data class Failed(val code: String, val dispatch: SkillDispatch = SkillDispatch.NONE) : TransitSkillOutcome
}

/** The network reads a skill call may make, bounded by the call's own deadline. */
internal interface TransitSkillData {
    fun searchStops(query: String, limit: Int): List<TransitStopMatch>
    fun departures(stopId: String): List<TransitDeparture>
}

/**
 * Favorites, stop search, and departures for skills. Each call fetches at most once and keeps
 * nothing running: no refresh loop, no location, no surface. A board is cached in memory for
 * [TransitSkillContract.BOARD_REUSE_MS] so a follow-up about "the one after that" can answer from
 * the board the wearer just heard, and is otherwise refreshed; a failed refresh is reported as
 * unavailable rather than served stale.
 */
internal class TransitReadSkills(
    private val favorites: TransitFavoritesSource,
    private val clock: () -> Instant = Instant::now,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    private data class Board(val departures: List<TransitDeparture>, val observedAt: Instant)

    private val boards = object : LinkedHashMap<String, Board>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Board>): Boolean = size > MAX_BOARDS
    }

    fun listFavorites(arguments: JSONObject): TransitSkillOutcome {
        val filter = arguments.optString("filter").trim().takeIf(String::isNotEmpty)
        val all = favorites.list()
        val matching = filter?.let { part -> all.filter { it.name.contains(part, ignoreCase = true) } } ?: all
        val revision = TransitSkillContract.favoritesRevision(all, filter)
        val offset = if (arguments.has("cursor")) {
            val (cursorRevision, cursorOffset) = arguments.getString("cursor").split(':').takeIf { it.size == 2 }
                ?: return TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
            val parsed = cursorOffset.toIntOrNull()
            if (cursorRevision != revision || parsed == null || parsed !in 0..matching.size) {
                return TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
            }
            parsed
        } else {
            0
        }
        val page = matching.drop(offset).take(TransitSkillContract.FAVORITES_PAGE_SIZE)
        val next = offset + page.size
        return TransitSkillOutcome.Completed(
            JSONObject()
                .put(
                    "stops",
                    JSONArray().apply {
                        page.forEach { stop ->
                            put(
                                JSONObject()
                                    .put("stop", TransitSkillContract.encodeStop(stop))
                                    .put("name", stop.name.take(TransitSkillContract.MAX_NAME_CHARS)),
                            )
                        }
                    },
                )
                .put("total", matching.size.coerceAtMost(MAX_TOTAL))
                .apply { if (next < matching.size) put("next_cursor", "$revision:$next") },
        )
    }

    fun searchStops(arguments: JSONObject, data: TransitSkillData): TransitSkillOutcome {
        val query = arguments.optString("query").trim()
        if (query.isEmpty()) return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        val matches = try {
            data.searchStops(query, TransitSkillContract.MAX_SEARCH_RESULTS + 1)
        } catch (failure: InterruptedException) {
            throw failure
        } catch (_: Exception) {
            return TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        }
        return TransitSkillOutcome.Completed(
            JSONObject()
                .put(
                    "stops",
                    JSONArray().apply {
                        matches.take(TransitSkillContract.MAX_SEARCH_RESULTS).forEach { match ->
                            put(
                                JSONObject()
                                    .put("stop", TransitSkillContract.encodeStop(match.stop))
                                    .put("name", match.stop.name.take(TransitSkillContract.MAX_NAME_CHARS))
                                    .apply {
                                        match.city.takeIf(String::isNotBlank)?.let {
                                            put("city", it.take(TransitSkillContract.MAX_NAME_CHARS))
                                        }
                                    },
                            )
                        }
                    },
                )
                .put("truncated", matches.size > TransitSkillContract.MAX_SEARCH_RESULTS),
        )
    }

    fun getDepartures(arguments: JSONObject, data: TransitSkillData): TransitSkillOutcome {
        val stop = TransitSkillContract.decodeStop(arguments.optString("stop"))
            ?: return TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
        val lineSelector = arguments.optString("line").trim().takeIf(String::isNotEmpty)
        val directionSelector = arguments.optString("direction").trim().takeIf(String::isNotEmpty)
        val anchor = if (arguments.has("after")) {
            TransitSkillContract.decodeDeparture(arguments.getString("after"))
                ?: return TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE)
        } else {
            null
        }
        if (anchor != null && anchor.stopId != stop.id) {
            return TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS)
        }
        val now = clock()
        val cached = boards[stop.id]?.takeIf {
            now.toEpochMilli() - it.observedAt.toEpochMilli() <= TransitSkillContract.BOARD_REUSE_MS
        }
        val board = cached ?: try {
            Board(data.departures(stop.id), now).also { boards[stop.id] = it }
        } catch (failure: InterruptedException) {
            throw failure
        } catch (_: Exception) {
            return TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE)
        }
        val upcoming = board.departures
            .filterNot { it.departure.isBefore(now.minusMillis(TransitSkillContract.DEPARTED_GRACE_MS)) }
            .sortedBy(TransitDeparture::departure)

        val requestedModes = lineSelector?.let { TransitLabelMatch.modes(it, upcoming.mapNotNull(::lineOf)) }
        val inMode = if (requestedModes == null) upcoming else upcoming.filter { it.mode.uppercase(java.util.Locale.ROOT) in requestedModes }
        val lineLabels = lineSelector?.let { TransitLabelMatch.lines(it, inMode.mapNotNull(::lineOf).distinct()) }
        val onLine = if (lineLabels == null) inMode else inMode.filter { lineOf(it) in lineLabels }
        val headsigns = directionSelector?.let { TransitLabelMatch.directions(it, onLine.map(::directionOf).distinct(), preferExact = lineSelector != null) }
        val selected = if (headsigns == null) onLine else onLine.filter { directionOf(it) in headsigns }
        val selectedLines = selected.mapNotNull(::lineOf).distinct()
        val selectedDirections = TransitLabelMatch.distinctDirectionLabels(selected.map(::directionOf))

        val (match, rows) = when {
            anchor != null -> followAnchor(anchor, board, upcoming, now)
            lineLabels != null && lineLabels.isEmpty() -> "no_matching_line" to emptyList()
            headsigns != null && headsigns.isEmpty() -> "no_matching_direction" to emptyList()
            lineLabels != null && (TransitLabelMatch.distinctLines(selectedLines) > 1 ||
                selected.map { TransitLabelMatch.modeGroup(it.mode) to lineOf(it) }.distinct().size > 1) -> "ambiguous_line" to selected
            headsigns != null && TransitLabelMatch.distinctDirections(headsigns) > 1 -> "ambiguous_direction" to selected
            lineSelector != null && directionSelector == null && TransitLabelMatch.distinctDirections(selectedDirections) > 1 ->
                "ambiguous_direction" to selected
            lineSelector != null || directionSelector != null -> "filtered" to selected
            else -> "all" to upcoming
        }
        val shown = rows.take(TransitSkillContract.MAX_BOARD_DEPARTURES)
        val focusRow = shown.firstOrNull { !it.cancelled }
        val encoded = shown.associateWith { encodeRow(stop, it, board.observedAt) }
        val zone = zone()
        return TransitSkillOutcome.Completed(
            JSONObject()
                .put("stop_name", stop.name)
                .put("observed_at", board.observedAt.toString())
                .put("observed_local", TransitSkillContract.localTime(board.observedAt, zone))
                .put("freshness", if (cached != null) "cached" else "live")
                .put("match", match)
                .put(
                    "departures",
                    JSONArray().apply {
                        shown.forEach { departure ->
                            put(departureJson(departure, encoded.getValue(departure), now, zone))
                        }
                    },
                )
                .put("lines", JSONArray(upcoming.mapNotNull(::lineOf).distinct().take(MAX_LIST)))
                .put("directions", JSONArray(onLine.map(::directionOf).distinct().take(MAX_LIST)))
                .apply {
                    when (match) {
                        "ambiguous_line" -> put("candidates", JSONArray(
                            (if (selectedLines.size == 1) selected.mapNotNull { TransitLabelMatch.candidate(it.mode, lineOf(it).orEmpty()) }.distinct() else selectedLines).take(MAX_LIST),
                        ))
                        "ambiguous_direction" -> put("candidates", JSONArray(selectedDirections.take(MAX_LIST)))
                    }
                }
                .put(
                    "focus",
                    JSONObject()
                        .put("kind", FOCUS_KIND)
                        .put("stop", TransitSkillContract.encodeStop(stop))
                        .put("stop_name", stop.name)
                        .put("observed_at", board.observedAt.toString())
                        .put("groups", JSONArray().apply {
                            upcoming.distinctBy { Triple(TransitLabelMatch.modeGroup(it.mode), lineOf(it), directionOf(it)) }.take(MAX_LIST)
                                .forEach { row ->
                                    put(JSONObject().putOpt("line", lineOf(row)).put("direction", directionOf(row))
                                        .put("mode", row.mode.take(24).ifBlank { "UNKNOWN" }))
                                }
                        })
                        .apply {
                            val ambiguous = match == "ambiguous_line" || match == "ambiguous_direction"
                            val grouped = anchor != null || lineSelector != null || directionSelector != null
                            focusRow?.takeUnless { ambiguous }?.let { row ->
                                if (grouped) {
                                    lineOf(row)?.let { put("line", it) }
                                    put("direction", directionOf(row))
                                }
                                put("departure", encoded.getValue(row))
                            }
                        },
                ),
        )
    }

    /**
     * The rows after [anchor] in its own line and direction. The anchor is identified by the
     * feed's trip id when both sides have one, otherwise by a unique line, direction, and
     * scheduled time; never by row position or a moving estimate alone.
     */
    private fun followAnchor(
        anchor: TransitSkillContract.DepartureSnapshot,
        board: Board,
        upcoming: List<TransitDeparture>,
        now: Instant,
    ): Pair<String, List<TransitDeparture>> {
        val group = upcoming.filter { TransitLabelMatch.modeGroup(it.mode) == TransitLabelMatch.modeGroup(anchor.mode) &&
            lineOf(it) == anchor.line && TransitLabelMatch.distinctDirections(listOf(directionOf(it), anchor.direction)) == 1 }
        val matches = board.departures.filter { identifies(it, anchor) }
        return when {
            matches.size == 1 -> {
                val found = matches.single()
                val index = group.indexOfFirst { it === found }
                "after_anchor" to if (index >= 0) group.drop(index + 1) else group.filter { it.departure.isAfter(found.departure) }
            }
            matches.isEmpty() && anchor.departure.isBefore(now.minusMillis(TransitSkillContract.DEPARTED_GRACE_MS)) ->
                "anchor_departed" to group
            else -> "board_changed" to group
        }
    }

    private fun identifies(departure: TransitDeparture, anchor: TransitSkillContract.DepartureSnapshot): Boolean {
        if (departure.mode != anchor.mode) return false
        if (departure.tripId != null && anchor.tripId != null) return departure.tripId == anchor.tripId
        if (lineOf(departure) != anchor.line || directionOf(departure) != anchor.direction) return false
        val scheduled = departure.scheduledDeparture
        return if (scheduled != null && anchor.scheduled != null) {
            scheduled == anchor.scheduled
        } else {
            anchor.scheduled == null && scheduled == null && departure.departure == anchor.departure
        }
    }

    private fun encodeRow(stop: TransitStop, departure: TransitDeparture, observedAt: Instant): String =
        TransitSkillContract.encodeDeparture(
            TransitSkillContract.DepartureSnapshot(
                stopId = stop.id,
                line = lineOf(departure),
                direction = directionOf(departure),
                mode = departure.mode,
                scheduled = departure.scheduledDeparture,
                departure = departure.departure,
                tripId = departure.tripId,
                observedAt = observedAt,
            ),
        )

    private fun departureJson(departure: TransitDeparture, reference: String, now: Instant, zone: ZoneId): JSONObject {
        val minutes = ceil((departure.departure.toEpochMilli() - now.toEpochMilli()) / 60_000.0).toInt()
        return JSONObject()
            .put("departure", reference)
            .apply { lineOf(departure)?.let { put("line", it) } }
            .put("direction", directionOf(departure))
            .put("mode", departure.mode.take(24).ifBlank { "UNKNOWN" })
            .put("time", departure.departure.toString())
            .put("time_local", TransitSkillContract.localTime(departure.departure, zone))
            .put("minutes", minutes.coerceIn(-5, 2880))
            .apply {
                departure.scheduledDeparture?.let { scheduled ->
                    put("scheduled_time", scheduled.toString())
                    put("scheduled_local", TransitSkillContract.localTime(scheduled, zone))
                }
            }
            .put(
                "realtime",
                when (departure.realTime) {
                    true -> "live"
                    false -> "scheduled"
                    null -> "unknown"
                },
            )
            .put("cancelled", departure.cancelled)
    }

    private fun lineOf(departure: TransitDeparture): String? =
        departure.routeShortName?.trim()?.take(TransitSkillContract.MAX_LINE_CHARS)?.takeIf(String::isNotEmpty)

    private fun directionOf(departure: TransitDeparture): String =
        departure.headsign.trim().take(TransitSkillContract.MAX_DIRECTION_CHARS)

    companion object {
        const val FOCUS_KIND = "transit_departure_focus"
        private const val MAX_BOARDS = 16
        private const val MAX_LIST = 12
        private const val MAX_TOTAL = 1000
    }
}
