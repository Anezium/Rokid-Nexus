package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset

class DepartureFormatterTest {
    private val stop = TransitStop(
        id = "stop-1",
        name = "Central Station With A Long Name",
        lat = 48.0,
        lon = 2.0,
        distanceMeters = 123,
    )
    private val now = Instant.parse("2026-07-07T12:00:00Z")

    @Test
    fun format_groupsSameRouteAndHeadsignButSeparatesOppositeDirections() {
        val card = DepartureFormatter.format(
            board = board(
                departure(route = "11", headsign = "Saint-Denis", at = "2026-07-07T12:01:00Z"),
                departure(route = "11", headsign = "Gare", at = "2026-07-07T12:08:00Z"),
                departure(route = "11", headsign = "Saint-Denis", at = "2026-07-07T12:21:00Z"),
            ),
            now = now,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals("Central Station With A Long Name", card.title)
        assertEquals(
            listOf(
                CardLine(text = "Saint-Denis", badge = "11", trail = listOf("1m", "21m")),
                CardLine(text = "Gare", badge = "11", trail = listOf("8m")),
            ),
            card.lines,
        )
    }

    @Test
    fun format_sortsGroupsByEarliestDepartureAndUsesModeFallbackRoute() {
        val card = DepartureFormatter.format(
            board = board(
                departure(route = "2", headsign = "Later", at = "2026-07-07T12:10:00Z"),
                departure(route = null, mode = "SUBWAY", headsign = "Metro", at = "2026-07-07T12:03:00Z"),
                departure(route = "1", headsign = "Middle", at = "2026-07-07T12:05:00Z"),
            ),
            now = now,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            listOf(
                CardLine(text = "Metro", badge = "MET", trail = listOf("3m")),
                CardLine(text = "Middle", badge = "1", trail = listOf("5m")),
                CardLine(text = "Later", badge = "2", trail = listOf("10m")),
            ),
            card.lines,
        )
    }

    @Test
    fun format_keepsFullHeadsignAndCapsTrailAtTwoTimes() {
        val card = DepartureFormatter.format(
            board = board(
                departure(route = "Fil", headsign = "Gare de Cerisiers Nord-Ouest Terminus", at = "2026-07-07T13:40:00Z"),
                departure(route = "Fil", headsign = "Gare de Cerisiers Nord-Ouest Terminus", at = "2026-07-07T13:41:00Z"),
                departure(route = "Fil", headsign = "Gare de Cerisiers Nord-Ouest Terminus", at = "2026-07-07T13:42:00Z"),
                departure(route = "Fil", headsign = "Gare de Cerisiers Nord-Ouest Terminus", at = "2026-07-07T13:43:00Z"),
            ),
            now = now,
            zoneId = ZoneOffset.UTC,
        )

        // The HUD ellipsizes long destinations itself; the formatter no longer truncates.
        assertEquals(
            listOf(
                CardLine(
                    text = "Gare de Cerisiers Nord-Ouest Terminus",
                    badge = "Fil",
                    trail = listOf("99m+", "99m+"),
                ),
            ),
            card.lines,
        )
    }

    @Test
    fun format_pagesSixGroupsAtATimeAndClampsOutOfRangePages() {
        val transitBoard = board(
            departure(route = "1", headsign = "A", at = "2026-07-07T12:01:00Z"),
            departure(route = "2", headsign = "B", at = "2026-07-07T12:02:00Z"),
            departure(route = "3", headsign = "C", at = "2026-07-07T12:03:00Z"),
            departure(route = "4", headsign = "D", at = "2026-07-07T12:04:00Z"),
            departure(route = "5", headsign = "E", at = "2026-07-07T12:05:00Z"),
            departure(route = "6", headsign = "F", at = "2026-07-07T12:06:00Z"),
            departure(route = "7", headsign = "G", at = "2026-07-07T12:07:00Z"),
            departure(route = "8", headsign = "H", at = "2026-07-07T12:08:00Z"),
            departure(route = "9", headsign = "I", at = "2026-07-07T12:09:00Z"),
            departure(route = "10", headsign = "J", at = "2026-07-07T12:10:00Z"),
            departure(route = "11", headsign = "K", at = "2026-07-07T12:11:00Z"),
            departure(route = "12", headsign = "L", at = "2026-07-07T12:12:00Z"),
            departure(route = "13", headsign = "M", at = "2026-07-07T12:13:00Z"),
        )

        assertEquals(3, DepartureFormatter.pageCount(transitBoard, now))
        assertEquals(
            listOf("A", "B", "C", "D", "E", "F"),
            DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC, page = 0).lines.map { it.text },
        )
        assertEquals(
            listOf("G", "H", "I", "J", "K", "L"),
            DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC, page = 1).lines.map { it.text },
        )
        assertEquals(
            listOf(CardLine(text = "M", badge = "13", trail = listOf("13m"))),
            DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC, page = 2).lines,
        )
        assertEquals(
            DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC, page = 2).lines,
            DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC, page = 99).lines,
        )
    }

    @Test
    fun format_preservesMinuteEdgesAndFiltersCancelledAndPastDepartures() {
        val card = DepartureFormatter.format(
            board = board(
                departure(route = "OLD", headsign = "Past", at = "2026-07-07T11:59:59Z"),
                departure(route = "NO", headsign = "Cancelled", at = "2026-07-07T12:01:00Z", cancelled = true),
                departure(route = "A", headsign = "Edge", at = "2026-07-07T12:00:59Z"),
                departure(route = "A", headsign = "Edge", at = "2026-07-07T13:40:00Z"),
            ),
            now = now,
            zoneId = ZoneOffset.UTC,
        )

        assertEquals(
            listOf(CardLine(text = "Edge", badge = "A", trail = listOf("now", "99m+"))),
            card.lines,
        )
        assertEquals(
            listOf(CardLine("No departures.")),
            DepartureFormatter.format(
                board = board(
                    departure(route = "OLD", headsign = "Past", at = "2026-07-07T11:59:59Z"),
                    departure(route = "NO", headsign = "Cancelled", at = "2026-07-07T12:01:00Z", cancelled = true),
                ),
                now = now,
                zoneId = ZoneOffset.UTC,
            ).lines,
        )
    }

    @Test
    fun format_usesFreshAndStaleFooters() {
        val transitBoard = board(
            departure(route = "7", headsign = "Downtown", at = "2026-07-07T12:05:00Z"),
        )

        assertEquals("upd 12:01 . 123m", DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC).footer)
        assertEquals("stale 12:01", DepartureFormatter.format(transitBoard, now, stale = true, zoneId = ZoneOffset.UTC).footer)
    }

    @Test
    fun format_dropsDistanceWhenUnknown() {
        val transitBoard = board(
            departure(route = "7", headsign = "Downtown", at = "2026-07-07T12:05:00Z"),
        ).copy(stop = stop.copy(distanceMeters = UNKNOWN_DISTANCE_METERS))

        assertEquals("upd 12:01", DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC).footer)
    }

    @Test
    fun contentKey_changesWhenOnlyTrailChanges() {
        val transitBoard = board(
            departure(route = "7", headsign = "Downtown", at = "2026-07-07T12:05:00Z"),
        )

        val earlier = DepartureFormatter.format(transitBoard, now, zoneId = ZoneOffset.UTC)
        val later = DepartureFormatter.format(transitBoard, now.plusSeconds(60), zoneId = ZoneOffset.UTC)
        assertTrue(earlier.contentKey() != later.contentKey())
    }

    @Test
    fun message_acceptsMultipleLines() {
        val card = DepartureFormatter.message(
            title = "Transit",
            lines = listOf("No favorites yet.", "Add in phone app."),
        )

        assertEquals("Transit", card.title)
        assertEquals(listOf(CardLine("No favorites yet."), CardLine("Add in phone app.")), card.lines)
        assertTrue(card.lines.all { it.text.length <= 26 })
    }

    private fun board(vararg departures: TransitDeparture): TransitBoard =
        TransitBoard(
            stop = stop,
            departures = departures.toList(),
            fetchedAt = Instant.parse("2026-07-07T12:01:00Z"),
        )

    private fun departure(
        route: String?,
        at: String,
        mode: String = "BUS",
        headsign: String = "Downtown",
        cancelled: Boolean = false,
    ): TransitDeparture =
        TransitDeparture(
            mode = mode,
            routeShortName = route,
            headsign = headsign,
            departure = Instant.parse(at),
            scheduledDeparture = null,
            cancelled = cancelled,
        )
}
