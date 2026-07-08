package com.anezium.rokidbus.plugin.transit

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.text.Normalizer

object DepartureFormatter {
    private const val MAX_TITLE_CHARS = 30
    private const val MESSAGE_LINE_CHARS = 26

    private const val GROUPS_PER_PAGE = 6
    // Next departure plus one backup; a third time is noise at glance distance.
    private const val TIMES_PER_GROUP = 2
    private val footerTime = DateTimeFormatter.ofPattern("HH:mm")

    fun format(
        board: TransitBoard,
        now: Instant,
        stale: Boolean = false,
        zoneId: ZoneId = ZoneId.systemDefault(),
        page: Int = 0,
    ): TransitCardContent {
        val groups = departureGroups(board, now)
        val lines = if (groups.isEmpty()) {
            listOf(CardLine("No departures."))
        } else {
            val clampedPage = page.coerceIn(0, pageCount(groups) - 1)
            groups
                .drop(clampedPage * GROUPS_PER_PAGE)
                .take(GROUPS_PER_PAGE)
                .map { group ->
                    CardLine(
                        text = group.headsign,
                        badge = group.route,
                        trail = group.departures
                            .take(TIMES_PER_GROUP)
                            .map { minutesUntil(it.departure, now) },
                    )
                }
        }
        val fetched = footerTime.withZone(zoneId).format(board.fetchedAt)
        val footer = if (stale) {
            "stale $fetched"
        } else if (board.stop.distanceMeters >= 0) {
            "upd $fetched . ${board.stop.distanceMeters}m"
        } else {
            "upd $fetched"
        }
        // No truncation: the HUD marquee-scrolls titles too long for the line.
        return TransitCardContent(
            title = board.stop.name.ifBlank { "Transit" },
            lines = lines,
            footer = footer,
        )
    }

    fun pageCount(board: TransitBoard, now: Instant): Int =
        pageCount(departureGroups(board, now))

    fun message(title: String, line: String, footer: String = "Rokid Nexus"): TransitCardContent =
        message(title, listOf(line), footer)

    fun message(title: String, lines: List<String>, footer: String = "Rokid Nexus"): TransitCardContent =
        TransitCardContent(
            title = truncate(title, MAX_TITLE_CHARS),
            lines = lines.take(5).map { line -> CardLine(truncate(ascii(line), MESSAGE_LINE_CHARS)) },
            footer = truncate(footer, 30),
        )

    private fun departureGroups(board: TransitBoard, now: Instant): List<DepartureGroup> =
        board.departures
            .asSequence()
            .filterNot { it.cancelled }
            .filterNot { it.departure.isBefore(now) }
            .sortedBy { it.departure }
            .groupBy { routeToken(it) to it.headsign.ifBlank { "?" } }
            .map { (key, departures) ->
                DepartureGroup(
                    route = key.first,
                    headsign = key.second,
                    departures = departures.sortedBy { it.departure },
                )
            }
            .sortedBy { it.departures.first().departure }

    private fun pageCount(groups: List<DepartureGroup>): Int =
        ((groups.size + GROUPS_PER_PAGE - 1) / GROUPS_PER_PAGE).coerceAtLeast(1)

    private fun minutesUntil(departure: Instant, now: Instant): String {
        val seconds = Duration.between(now, departure).seconds
        if (seconds < 60) return "now"
        val minutes = seconds / 60
        return if (minutes > 99) "99m+" else "${minutes}m"
    }

    private fun routeToken(departure: TransitDeparture): String =
        departure.routeShortName?.takeIf { it.isNotBlank() } ?: when (departure.mode.uppercase()) {
            "BUS" -> "BUS"
            "SUBWAY", "METRO" -> "MET"
            "TRAM" -> "TRM"
            "RAIL", "TRAIN", "REGIONAL_RAIL" -> "TRN"
            else -> departure.mode.take(3).uppercase().ifBlank { "TRN" }
        }

    private fun truncate(value: String, maxChars: Int): String {
        if (value.length <= maxChars) return value
        if (maxChars <= 3) return value.take(maxChars)
        return value.take(maxChars - 3) + "..."
    }

    private fun ascii(value: String): String =
        Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .filter { it.code in 32..126 }

    private data class DepartureGroup(
        val route: String,
        val headsign: String,
        val departures: List<TransitDeparture>,
    )
}
