package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.Instant

/**
 * Parses a response recorded once from the routing service between two public Paris stations
 * (trimmed to what Transit reads). Tests never call the live service.
 */
class TransitJourneyPlannerTest {
    private val fixture = File("src/test/resources/transitous-plan-paris.json").readText(Charsets.UTF_8)

    @Test
    fun `itineraries keep absolute instants, realtime flags, lines, and every stop`() {
        val itineraries = TransitousJourneyPlanner.parseItineraries(fixture)

        assertEquals(2, itineraries.size)
        val first = itineraries.first()
        assertEquals(listOf("WALK", "SUBWAY", "WALK", "SUBWAY", "WALK"), first.legs.map { it.mode })
        assertEquals(listOf("1", "5"), first.rideLegs.map { it.line })
        val ride = first.legs[3]
        assertEquals("Bastille", ride.from.name)
        assertEquals("Gare du Nord", ride.to.name)
        assertEquals(6, ride.intermediateStops.size)
        assertTrue(ride.realTime)
        assertTrue(ride.tripId!!.isNotBlank())
        assertEquals("Bobigny - Pablo Picasso", ride.headsign)
        assertFalse(first.legs.first().realTime)
        assertEquals("START", first.legs.first().from.name)
        assertTrue(first.start.isBefore(first.end))
        assertEquals(Instant.parse(first.legs.last().end.toString()), first.legs.last().end)
    }

    @Test
    fun `the request names both coordinates and the chosen departure instant`() {
        val urls = mutableListOf<String>()
        val planner = TransitousJourneyPlanner(baseUrl = "https://example.test/api/v1") { url, _ ->
            urls += url
            fixture
        }

        val result = planner.plan(
            TransitCoordinate(48.8443, 2.3744),
            TransitCoordinate(48.8809, 2.3553),
            Instant.parse("2026-09-27T08:00:00Z"),
            deadlineAtMs = Long.MAX_VALUE,
        )

        assertTrue(result is TransitPlanResult.Planned)
        assertEquals(
            "https://example.test/api/v1/plan?fromPlace=48.8443,2.3744&toPlace=48.8809,2.3553&time=2026-09-27T08:00:00Z",
            urls.single(),
        )
    }

    @Test
    fun `no itinerary and a failed request are distinct outcomes`() {
        val empty = TransitousJourneyPlanner { _, _ -> """{"itineraries":[]}""" }
        assertEquals(
            TransitPlanResult.NoRoute,
            empty.plan(TransitCoordinate(0.0, 0.0), TransitCoordinate(1.0, 1.0), null, Long.MAX_VALUE),
        )

        val offline = TransitousJourneyPlanner { _, _ -> throw IOException("offline") }
        assertTrue(
            offline.plan(TransitCoordinate(0.0, 0.0), TransitCoordinate(1.0, 1.0), null, Long.MAX_VALUE)
                is TransitPlanResult.Failed,
        )
    }

    @Test
    fun `a deadline-bound fetch refuses to start without time to finish`() {
        val clock = { 10_000L }
        val failure = runCatching { getWithinDeadline("https://example.test", deadlineAtMs = 10_500L, clock = clock) }
        assertTrue(failure.exceptionOrNull() is IOException)
    }
}
