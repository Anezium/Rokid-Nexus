package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.shared.skills.SkillErrorCodes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.ZoneId

class TransitReadSkillsTest {
    private class Favorites(var stops: List<TransitStop>) : TransitFavoritesSource {
        override fun list() = stops
        override fun add(stop: TransitStop) = Unit
        override fun remove(id: String) = Unit
        override fun lastMode() = TransitMode.FAVORITES
        override fun setLastMode(mode: TransitMode) = Unit
    }

    private class Data : TransitSkillData {
        var boards = mutableMapOf<String, List<TransitDeparture>>()
        var matches = emptyList<TransitStopMatch>()
        var fail = false
        var departureCalls = 0
        override fun searchStops(query: String, limit: Int): List<TransitStopMatch> {
            if (fail) throw IOException("offline")
            return matches.take(limit)
        }
        override fun departures(stopId: String): List<TransitDeparture> {
            departureCalls++
            if (fail) throw IOException("offline")
            return boards[stopId].orEmpty()
        }
    }

    private var now = T0
    private val zone = ZoneId.of("Europe/Paris")
    private val stop = TransitStop("stop-1", "Central", 48.85, 2.35)
    private val favorites = Favorites((1..25).map { TransitStop("id-$it", "Stop $it", 48.0, 2.0) })
    private val skills = TransitReadSkills(favorites, clock = { now }, zone = { zone })
    private val data = Data()

    private fun departure(
        line: String?,
        headsign: String,
        minutes: Long,
        scheduled: Long? = minutes,
        trip: String? = null,
        realTime: Boolean? = true,
        cancelled: Boolean = false,
    ) = TransitDeparture("TRAM", line, headsign, at(minutes), scheduled?.let(::at), cancelled, realTime, trip)

    private fun completed(outcome: TransitSkillOutcome): JSONObject =
        (outcome as TransitSkillOutcome.Completed).data

    private fun departures(arguments: JSONObject = JSONObject()): JSONObject {
        val data = completed(skills.getDepartures(arguments.put("stop", TransitSkillContract.encodeStop(stop)), this.data))
        assertMatchesOutput(TransitSkillContract.GET_DEPARTURES, data)
        return data
    }

    @Test
    fun `favorites page by twenty with a cursor tied to the list`() {
        val first = completed(skills.listFavorites(JSONObject()))
        assertMatchesOutput(TransitSkillContract.LIST_FAVORITES, first)
        assertEquals(20, first.getJSONArray("stops").length())
        assertEquals(25, first.getInt("total"))
        val cursor = first.getString("next_cursor")

        val second = completed(skills.listFavorites(JSONObject().put("cursor", cursor)))
        assertEquals(5, second.getJSONArray("stops").length())
        assertFalse(second.has("next_cursor"))

        favorites.stops = favorites.stops.drop(1)
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE),
            skills.listFavorites(JSONObject().put("cursor", cursor)),
        )
    }

    @Test
    fun `favorites filter by name and an empty list is a valid answer`() {
        val filtered = completed(skills.listFavorites(JSONObject().put("filter", "stop 2")))
        assertEquals(7, filtered.getInt("total"))

        favorites.stops = emptyList()
        val empty = completed(skills.listFavorites(JSONObject()))
        assertEquals(0, empty.getJSONArray("stops").length())
        assertMatchesOutput(TransitSkillContract.LIST_FAVORITES, empty)
    }

    @Test
    fun `search returns at most eight stops and says when it cut the list`() {
        data.matches = (1..9).map { TransitStopMatch(TransitStop("s$it", "Central $it", 48.0, 2.0), "Paris") }
        val result = completed(skills.searchStops(JSONObject().put("query", "Central"), data))
        assertMatchesOutput(TransitSkillContract.SEARCH_STOPS, result)
        assertEquals(8, result.getJSONArray("stops").length())
        assertTrue(result.getBoolean("truncated"))

        data.fail = true
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE),
            skills.searchStops(JSONObject().put("query", "Central"), data),
        )
    }

    @Test
    fun `a board states its observation time, freshness, and realtime quality`() {
        data.boards[stop.id] = listOf(
            departure("T3", "Porte de Vincennes", 4, realTime = true),
            departure("T3", "Porte de Vincennes", 12, realTime = false),
            departure("T3", "Porte de Vincennes", 20, realTime = null),
            departure("T3", "Porte de Vincennes", 25, cancelled = true),
        )
        val board = departures()

        assertEquals("live", board.getString("freshness"))
        assertEquals(T0.toString(), board.getString("observed_at"))
        assertEquals("10:00", board.getString("observed_local"))
        val rows = board.getJSONArray("departures")
        assertEquals(listOf("live", "scheduled", "unknown", "live"), (0 until rows.length()).map { rows.getJSONObject(it).getString("realtime") })
        assertEquals("10:04", rows.getJSONObject(0).getString("time_local"))
        assertEquals(4, rows.getJSONObject(0).getInt("minutes"))
        assertTrue(rows.getJSONObject(3).getBoolean("cancelled"))
    }

    @Test
    fun `a follow-up within thirty seconds reuses the board, later ones refresh`() {
        data.boards[stop.id] = listOf(departure("T3", "A", 4))
        departures()
        now = now.plusSeconds(20)
        assertEquals("cached", departures().getString("freshness"))
        assertEquals(1, data.departureCalls)

        now = now.plusSeconds(20)
        assertEquals("live", departures().getString("freshness"))
        assertEquals(2, data.departureCalls)
    }

    @Test
    fun `a failed refresh is unavailable, never an old board presented as current`() {
        data.boards[stop.id] = listOf(departure("T3", "A", 4))
        departures()
        now = now.plusSeconds(60)
        data.fail = true

        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.UNAVAILABLE),
            skills.getDepartures(JSONObject().put("stop", TransitSkillContract.encodeStop(stop)), data),
        )
    }

    @Test
    fun `line and direction selectors distinguish no match from an ambiguous direction`() {
        data.boards[stop.id] = listOf(
            departure("T3", "North", 3),
            departure("T3", "South", 5),
            departure("B7", "East", 6),
        )
        assertEquals("no_matching_line", departures(JSONObject().put("line", "T9")).getString("match"))
        val ambiguous = departures(JSONObject().put("line", "T3"))
        assertEquals("ambiguous_direction", ambiguous.getString("match"))
        assertEquals(2, ambiguous.getJSONArray("directions").length())
        val exact = departures(JSONObject().put("line", "T3").put("direction", "South"))
        assertEquals("filtered", exact.getString("match"))
        assertEquals(1, exact.getJSONArray("departures").length())
        assertEquals("South", exact.getJSONObject("focus").getString("direction"))
    }

    @Test
    fun `the one after that follows the trip in its own group, not the next row`() {
        data.boards[stop.id] = listOf(
            departure("T3", "North", 3, trip = "t1"),
            departure("B7", "East", 4, trip = "b1"),
            departure("T3", "North", 9, trip = "t2"),
            departure("T3", "North", 15, trip = "t3"),
        )
        val first = departures(JSONObject().put("line", "T3").put("direction", "North"))
        val anchor = first.getJSONObject("focus").getString("departure")

        now = now.plusSeconds(60)
        val next = departures(JSONObject().put("after", anchor))
        assertEquals("after_anchor", next.getString("match"))
        val rows = next.getJSONArray("departures")
        assertEquals(listOf("10:09", "10:15"), (0 until rows.length()).map { rows.getJSONObject(it).getString("time_local") })
        assertEquals("T3", next.getJSONObject("focus").getString("line"))
    }

    @Test
    fun `without trip ids the anchor is the unique line, direction, and scheduled time`() {
        data.boards[stop.id] = listOf(departure("T3", "North", 3), departure("T3", "North", 9))
        val anchor = departures(JSONObject().put("line", "T3")).getJSONObject("focus").getString("departure")

        now = now.plusSeconds(45)
        // The live estimate moved, the scheduled time did not: still the same departure.
        data.boards[stop.id] = listOf(departure("T3", "North", 4, scheduled = 3), departure("T3", "North", 9))
        val next = departures(JSONObject().put("after", anchor))

        assertEquals("after_anchor", next.getString("match"))
        assertEquals(1, next.getJSONArray("departures").length())
    }

    @Test
    fun `an ambiguous or vanished anchor says the board changed`() {
        data.boards[stop.id] = listOf(departure("T3", "North", 3, scheduled = null), departure("T3", "North", 9, scheduled = null))
        val anchor = departures().getJSONObject("focus").getString("departure")

        now = now.plusSeconds(45)
        data.boards[stop.id] = listOf(departure("T3", "North", 5, scheduled = null), departure("T3", "North", 9, scheduled = null))
        assertEquals("board_changed", departures(JSONObject().put("after", anchor)).getString("match"))

        now = now.plusSeconds(600)
        data.boards[stop.id] = listOf(departure("T3", "North", 20, scheduled = null))
        assertEquals("anchor_departed", departures(JSONObject().put("after", anchor)).getString("match"))
    }

    @Test
    fun `references to another stop or of unknown shape are refused`() {
        val otherStop = TransitStop("stop-2", "Elsewhere", 1.0, 1.0)
        val foreign = TransitSkillContract.encodeDeparture(
            TransitSkillContract.DepartureSnapshot("stop-2", "T3", "North", "TRAM", T0, T0, null, T0),
        )
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.INVALID_ARGUMENTS),
            skills.getDepartures(
                JSONObject().put("stop", TransitSkillContract.encodeStop(stop)).put("after", foreign),
                data,
            ),
        )
        assertEquals(
            TransitSkillOutcome.Failed(SkillErrorCodes.STALE_REFERENCE),
            skills.getDepartures(JSONObject().put("stop", "stop-1"), data),
        )
        assertNull(TransitSkillContract.decodeStop("""{"i":"x","n":"","a":0,"o":0}"""))
        assertEquals(otherStop, TransitSkillContract.decodeStop(TransitSkillContract.encodeStop(otherStop)))
    }

    @Test
    fun `an empty board is a completed answer`() {
        data.boards[stop.id] = emptyList()
        val board = departures()
        assertEquals(0, board.getJSONArray("departures").length())
        assertFalse(board.getJSONObject("focus").has("departure"))
    }
}
