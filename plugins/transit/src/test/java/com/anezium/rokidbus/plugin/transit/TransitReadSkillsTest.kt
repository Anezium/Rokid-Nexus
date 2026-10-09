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
    fun `direction typography is normalized and exact groups survive in conversation focus`() {
        data.boards[stop.id] = listOf(
            departure("14", "A\u00e9roport d\u2019Orly", 3),
            departure("14", "Saint-Denis Pleyel", 5),
        )
        val board = departures()
        assertEquals(2, board.getJSONObject("focus").getJSONArray("groups").length())
        val selected = departures(JSONObject().put("line", "14").put("direction", "Aeroport d'Orly"))
        assertEquals(1, selected.getJSONArray("departures").length())
        assertEquals("A\u00e9roport d\u2019Orly", selected.getJSONObject("focus").getString("direction"))
        val shortened = departures(JSONObject().put("line", "14").put("direction", "Orly"))
        assertEquals("filtered", shortened.getString("match"))
        assertEquals(1, shortened.getJSONArray("departures").length())
    }

    /** Bibliothèque François Mitterrand as Transitous labels it: metro 14, RER C missions, buses. */
    private fun parisBoard() = listOf(
        departure("14", "Saint-Denis - Pleyel", 1, trip = "m14-n1"),
        departure("14", "Aéroport d'Orly", 2, trip = "m14-o1"),
        departure("C", "SARA", 3, trip = "rerc-1"),
        departure("62", "Porte de France", 4, trip = "b62-1"),
        departure("14", "Saint-Denis - Pleyel", 4, trip = "m14-n2"),
        departure("14", "Aéroport d'Orly", 5, trip = "m14-o2"),
        departure("325", "Château de Vincennes", 6, trip = "b325-1"),
        departure("C", "MONA", 7, trip = "rerc-2"),
        departure("14", "Aéroport d'Orly", 8, trip = "m14-o3"),
    )

    private fun JSONObject.rows(): List<Pair<String?, String>> = getJSONArray("departures").let { rows ->
        (0 until rows.length()).map { rows.getJSONObject(it).let { row -> row.optString("line").ifEmpty { null } to row.getString("direction") } }
    }

    @Test
    fun `device case line 14 toward Orly returns the Orly-bound line 14 departures`() {
        data.boards[stop.id] = parisBoard()
        val orlyBound = List(3) { "14" to "Aéroport d'Orly" }

        val spoken = departures(JSONObject().put("line", "14").put("direction", "Orly"))
        assertEquals("filtered", spoken.getString("match"))
        assertEquals(orlyBound, spoken.rows())
        assertEquals("Aéroport d'Orly", spoken.getJSONObject("focus").getString("direction"))

        val withMode = departures(JSONObject().put("line", "ligne 14").put("direction", "vers Orly"))
        assertEquals("filtered", withMode.getString("match"))
        assertEquals(orlyBound, withMode.rows())
    }

    private fun JSONObject.strings(name: String): List<String> = getJSONArray(name).let { list ->
        (0 until list.length()).map(list::getString)
    }

    @Test
    fun `linePrefix - a line named with its mode word selects the board label`() {
        data.boards[stop.id] = parisBoard()
        listOf("14", "ligne 14", "M14", "Métro 14", "metro-14", "m 14", "line 14").forEach { spoken ->
            val board = departures(JSONObject().put("line", spoken))
            assertEquals(spoken, "ambiguous_direction", board.getString("match"))
            assertEquals(spoken, List(5) { "14" }, board.rows().map { it.first })
            assertEquals(spoken, setOf("Saint-Denis - Pleyel", "Aéroport d'Orly"), board.strings("candidates").toSet())
        }
        assertEquals(setOf("C"), departures(JSONObject().put("line", "RER C")).rows().map { it.first }.toSet())
        val bus = departures(JSONObject().put("line", "bus 62"))
        assertEquals("filtered", bus.getString("match"))
        assertEquals(listOf("62" to "Porte de France"), bus.rows())
        assertEquals("no_matching_line", departures(JSONObject().put("line", "M")).getString("match"))
    }

    @Test
    fun `linePrefix - an exact label wins and a prefix shared by two lines is ambiguous`() {
        data.boards[stop.id] = listOf(
            departure("M1", "Renens", 2),
            departure("1", "Maladière", 3),
            departure("T3a", "Porte de Vincennes", 4),
            departure("T3b", "Porte Dauphine", 5),
        )
        assertEquals(listOf("1" to "Maladière"), departures(JSONObject().put("line", "1")).rows())
        assertEquals(listOf("M1" to "Renens"), departures(JSONObject().put("line", "M1")).rows())
        assertEquals(listOf("T3a" to "Porte de Vincennes"), departures(JSONObject().put("line", "T3a")).rows())
        assertEquals(listOf("T3a" to "Porte de Vincennes"), departures(JSONObject().put("line", "tram T3a")).rows())

        val either = departures(JSONObject().put("line", "métro 1"))
        assertEquals("ambiguous_line", either.getString("match"))
        assertEquals(setOf("M1", "1"), either.strings("candidates").toSet())
        assertEquals(2, either.getJSONArray("departures").length())
        // A direction that only one of the two lines serves settles which line was meant.
        val settled = departures(JSONObject().put("line", "métro 1").put("direction", "Renens"))
        assertEquals("filtered", settled.getString("match"))
        assertEquals(listOf("M1" to "Renens"), settled.rows())
    }

    @Test
    fun `directionPartial - whole words of a headsign select it, with or without a connector`() {
        data.boards[stop.id] = parisBoard()
        val orlyBound = List(3) { "14" to "Aéroport d'Orly" }
        listOf("Orly", "vers Orly", "direction Orly", "towards Orly", "aéroport", "Aéroport Orly", "Aeroport d’Orly")
            .forEach { spoken ->
                val board = departures(JSONObject().put("line", "14").put("direction", spoken))
                assertEquals(spoken, "filtered", board.getString("match"))
                assertEquals(spoken, orlyBound, board.rows())
            }
        assertEquals(orlyBound, departures(JSONObject().put("direction", "vers Orly")).rows())
        val pleyel = departures(JSONObject().put("line", "M14").put("direction", "Saint-Denis Pleyel"))
        assertEquals("filtered", pleyel.getString("match"))
        assertEquals(List(2) { "14" to "Saint-Denis - Pleyel" }, pleyel.rows())
    }

    @Test
    fun `ambiguousDirection - a word shared by several headsigns lists them, an exact headsign wins`() {
        data.boards[stop.id] = parisBoard() + listOf(
            departure("B", "Aéroport Charles de Gaulle 2 TGV", 6),
            departure("Orlybus", "Orly", 9),
        )
        val airports = departures(JSONObject().put("direction", "aéroport"))
        assertEquals("ambiguous_direction", airports.getString("match"))
        assertEquals(4, airports.getJSONArray("departures").length())
        assertEquals(
            setOf("Aéroport d'Orly", "Aéroport Charles de Gaulle 2 TGV"),
            airports.strings("candidates").toSet(),
        )
        assertEquals("filtered", departures(JSONObject().put("line", "14").put("direction", "aéroport")).getString("match"))

        val exact = departures(JSONObject().put("direction", "Orly"))
        assertEquals("filtered", exact.getString("match"))
        assertEquals(listOf("Orlybus" to "Orly"), exact.rows())
    }

    @Test
    fun `noMatch - an unknown line or direction lists what the board actually has`() {
        data.boards[stop.id] = parisBoard()
        val noLine = departures(JSONObject().put("line", "ligne 7").put("direction", "Orly"))
        assertEquals("no_matching_line", noLine.getString("match"))
        assertEquals(0, noLine.getJSONArray("departures").length())
        assertEquals(listOf("14", "C", "62", "325"), noLine.strings("lines"))

        val noDirection = departures(JSONObject().put("line", "Métro 14").put("direction", "Versailles"))
        assertEquals("no_matching_direction", noDirection.getString("match"))
        assertEquals(0, noDirection.getJSONArray("departures").length())
        assertEquals(listOf("Saint-Denis - Pleyel", "Aéroport d'Orly"), noDirection.strings("directions"))

        val anywhere = departures(JSONObject().put("direction", "vers Versailles"))
        assertEquals("no_matching_direction", anywhere.getString("match"))
        assertEquals(6, anywhere.strings("directions").size)
    }

    @Test
    fun `afterWithFilter - the one after that keeps the named line and direction`() {
        data.boards[stop.id] = parisBoard()
        val first = departures(JSONObject().put("line", "ligne 14").put("direction", "vers Orly"))
        assertEquals("10:02", first.getJSONArray("departures").getJSONObject(0).getString("time_local"))
        val anchor = first.getJSONObject("focus").getString("departure")

        now = now.plusSeconds(20)
        val next = departures(JSONObject().put("line", "14").put("direction", "Orly").put("after", anchor))
        assertEquals("after_anchor", next.getString("match"))
        assertEquals(List(2) { "14" to "Aéroport d'Orly" }, next.rows())
        assertEquals(
            listOf("10:05", "10:08"),
            (0 until 2).map { next.getJSONArray("departures").getJSONObject(it).getString("time_local") },
        )
        assertEquals(listOf("Saint-Denis - Pleyel", "Aéroport d'Orly"), next.strings("directions"))
    }

    @Test
    fun `linePrefix - a mode word glued to a line code still names it`() {
        data.boards[stop.id] = listOf(
            departure("C", "SARA", 2),
            departure("T3a", "Porte de Vincennes", 3),
            departure("A", "Marne-la-Vallée Chessy", 4),
        )
        assertEquals(listOf("C" to "SARA"), departures(JSONObject().put("line", "RERC")).rows())
        assertEquals(listOf("C" to "SARA"), departures(JSONObject().put("line", "RER-C")).rows())
        assertEquals(listOf("T3a" to "Porte de Vincennes"), departures(JSONObject().put("line", "tramT3a")).rows())
        assertEquals("no_matching_line", departures(JSONObject().put("line", "Linea")).getString("match"))
    }

    @Test
    fun `unrelated - ordinary words that start with a mode word are never stripped`() {
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("Linea", listOf("A", "a")))
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("Tramway", listOf("way", "WAY")))
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("Metropole", listOf("pole", "ropole")))
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("Busway", listOf("way")))
    }

    @Test
    fun `linePrefix - a glued mode word names a line code in any casing`() {
        data.boards[stop.id] = listOf(
            departure("C", "SARA", 2),
            departure("T3a", "Porte de Vincennes", 3),
            departure("14", "Saint-Denis - Pleyel", 4),
        )
        listOf("rerc", "RERC", "rerC").forEach { spoken ->
            assertEquals(spoken, listOf("C" to "SARA"), departures(JSONObject().put("line", spoken)).rows())
        }
        listOf("tramt3a", "tramT3a", "TRAMT3A").forEach { spoken ->
            assertEquals(spoken, listOf("T3a" to "Porte de Vincennes"), departures(JSONObject().put("line", spoken)).rows())
        }
        listOf("metro14", "LIGNE14", "m14").forEach { spoken ->
            assertEquals(spoken, listOf("14" to "Saint-Denis - Pleyel"), departures(JSONObject().put("line", spoken)).rows())
        }
    }

    @Test
    fun `unrelated - a word that starts with a mode word is never a line code in any casing`() {
        data.boards[stop.id] = listOf(departure("WAY", "North", 2))
        listOf("TRAMWAY", "BUSWAY", "Tramway", "tramway").forEach { spoken ->
            assertEquals(spoken, "no_matching_line", departures(JSONObject().put("line", spoken)).getString("match"))
        }
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("WAY", listOf("TRAMWAY", "Tramway")))
        listOf("Linea", "LINEA", "linea").forEach { spoken ->
            assertEquals(spoken, emptySet<String>(), TransitLabelMatch.lines(spoken, listOf("A")))
        }
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("METROPOLE", listOf("POLE")))
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("LINES", listOf("S")))
        assertEquals(emptySet<String>(), TransitLabelMatch.lines("BUSES", listOf("ES")))
        listOf("TRAMWAY1" to "WAY1", "BUSWAY4" to "WAY4", "METROPOLE1" to "POLE1", "LINEAGE1" to "AGE1").forEach { (spoken, line) ->
            assertEquals(spoken, emptySet<String>(), TransitLabelMatch.lines(spoken, listOf(line)))
            assertEquals(line, emptySet<String>(), TransitLabelMatch.lines(line, listOf(spoken)))
        }
        assertEquals(setOf("N01"), TransitLabelMatch.lines("busn01", listOf("N01")))
    }

    @Test
    fun `directionPartial - an exact headsign wins over the same words without a connector`() {
        data.boards[stop.id] = listOf(
            departure("7", "to Orly", 2),
            departure("8", "Orly", 3),
        )
        val exact = departures(JSONObject().put("direction", "to Orly"))
        assertEquals("filtered", exact.getString("match"))
        assertEquals(listOf("7" to "to Orly"), exact.rows())
        assertEquals(listOf("8" to "Orly"), departures(JSONObject().put("direction", "Orly")).rows())
        assertEquals(listOf("8" to "Orly"), departures(JSONObject().put("direction", "vers Orly")).rows())
    }

    @Test
    fun `unrelated - a direction or line never matches by a fragment of a word`() {
        data.boards[stop.id] = listOf(
            departure("14", "Olympiades", 2),
            departure("14", "Aéroport d'Orly", 3),
            departure("4", "Bagneux - Lucie Aubrac", 4),
        )
        val orly = departures(JSONObject().put("direction", "Orly"))
        assertEquals("filtered", orly.getString("match"))
        assertEquals(listOf("14" to "Aéroport d'Orly"), orly.rows())
        assertTrue(orly.rows().none { it.second == "Olympiades" })

        assertEquals("no_matching_direction", departures(JSONObject().put("direction", "Or")).getString("match"))
        assertEquals("no_matching_direction", departures(JSONObject().put("line", "14").put("direction", "Or")).getString("match"))
        assertEquals("no_matching_line", departures(JSONObject().put("line", "1")).getString("match"))
        assertEquals(listOf("4" to "Bagneux - Lucie Aubrac"), departures(JSONObject().put("line", "ligne 4")).rows())
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
