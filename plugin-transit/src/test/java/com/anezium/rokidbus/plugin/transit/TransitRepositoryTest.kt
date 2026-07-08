package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class TransitRepositoryTest {
    @Test
    fun parseStops_sortsReturnedStopsByHaversineDistance() {
        val stops = TransitRepository.parseStops(
            json = """
                [
                  {"type":"STOP","name":"Far Stop","id":"far","lat":48.85827,"lon":2.34854},
                  {"type":"ADDRESS","name":"Ignored","id":"ignored","lat":48.0,"lon":2.0},
                  {"type":"STOP","name":"Near Stop","id":"near","lat":48.85662,"lon":2.35154}
                ]
            """.trimIndent(),
            origin = TransitCoordinate(48.8566, 2.3522),
        )

        assertEquals(listOf("near", "far"), stops.map { it.id })
        assertTrue(stops[0].distanceMeters < stops[1].distanceMeters)
    }

    @Test
    fun parseStops_readsModesAndDefaultsAbsentModes() {
        val stops = TransitRepository.parseStops(
            json = """
                [
                  {"type":"STOP","name":"Metro Stop","id":"metro","lat":48.85662,"lon":2.35154,"modes":["BUS","SUBWAY"]},
                  {"type":"STOP","name":"Plain Stop","id":"plain","lat":48.85827,"lon":2.34854}
                ]
            """.trimIndent(),
            origin = TransitCoordinate(48.8566, 2.3522),
        )

        val byId = stops.associateBy { it.id }
        assertEquals(listOf("BUS", "SUBWAY"), byId.getValue("metro").modes)
        assertEquals(emptyList<String>(), byId.getValue("plain").modes)
    }

    @Test
    fun parseDepartures_replacesMissionCodeHeadsignsWithTripTerminus() {
        val departures = TransitRepository.parseDepartures(
            """
                {
                  "stopTimes": [
                    {
                      "place": {"departure": "2026-07-08T14:00:00Z"},
                      "mode": "REGIONAL_RAIL",
                      "headsign": "ZECO",
                      "routeShortName": "D",
                      "tripTo": {"name": "Melun"}
                    },
                    {
                      "place": {"departure": "2026-07-08T14:01:00Z"},
                      "mode": "SUBWAY",
                      "headsign": "La Défense (Grande Arche)",
                      "routeShortName": "1",
                      "tripTo": {"name": "La Défense"}
                    },
                    {
                      "place": {"departure": "2026-07-08T14:02:00Z"},
                      "mode": "BUS",
                      "headsign": "",
                      "routeShortName": "32",
                      "tripTo": {"name": "Victor Basch"}
                    },
                    {
                      "place": {"departure": "2026-07-08T14:03:00Z"},
                      "mode": "REGIONAL_RAIL",
                      "headsign": "VOPA",
                      "routeShortName": "D"
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf("Melun", "La Défense (Grande Arche)", "Victor Basch", "VOPA"),
            departures.map { it.headsign },
        )
    }

    @Test
    fun parseDepartures_readsRouteShortNameCancellationAndFallbackShape() {
        val departures = TransitRepository.parseDepartures(
            """
                {
                  "stopTimes": [
                    {
                      "place": {
                        "name": "Hotel de Ville",
                        "departure": "2026-07-07T19:04:00Z",
                        "scheduledDeparture": "2026-07-07T18:58:00Z",
                        "cancelled": false
                      },
                      "mode": "BUS",
                      "realTime": true,
                      "headsign": "Gare Montparnasse",
                      "routeId": "route-96",
                      "routeShortName": "96",
                      "cancelled": false
                    },
                    {
                      "place": {
                        "name": "Hotel de Ville",
                        "departure": "2026-07-07T19:05:00Z",
                        "scheduledDeparture": "2026-07-07T19:05:00Z",
                        "cancelled": true
                      },
                      "mode": "BUS",
                      "headsign": "Porte des Lilas",
                      "routeId": "route-69",
                      "routeShortName": "69",
                      "cancelled": false
                    },
                    {
                      "place": {
                        "name": "Hotel de Ville",
                        "departure": "2026-07-07T19:06:00Z",
                        "scheduledDeparture": "2026-07-07T19:04:00Z",
                        "cancelled": false
                      },
                      "mode": "SUBWAY",
                      "realTime": true,
                      "headsign": "Chatelet",
                      "routeId": "route-missing-short-name",
                      "cancelled": false
                    }
                  ],
                  "place": {"name":"Hotel de Ville"}
                }
            """.trimIndent(),
        )

        assertEquals(3, departures.size)
        assertEquals("96", departures[0].routeShortName)
        assertEquals(Instant.parse("2026-07-07T19:04:00Z"), departures[0].departure)
        assertEquals(Instant.parse("2026-07-07T18:58:00Z"), departures[0].scheduledDeparture)
        assertTrue(departures[1].cancelled)
        assertFalse(departures[2].cancelled)
        assertNull(departures[2].routeShortName)
        assertEquals("SUBWAY", departures[2].mode)
    }

    @Test
    fun parseStopMatches_readsENotationCityAndFiltersNonStops() {
        val matches = TransitRepository.parseStopMatches(
            """
                {
                  "items": [
                    {
                      "type": "ADDRESS",
                      "name": "Ignored",
                      "id": "ignored",
                      "lat": 4.885827E1,
                      "lon": 2.34854E0,
                      "areas": [{"name": "Paris", "default": true}]
                    },
                    {
                      "type": "STOP",
                      "name": "Hotel de Ville",
                      "id": "stop:hotel-de-ville",
                      "lat": 4.885662E1,
                      "lon": 2.35154E0,
                      "areas": [
                        {"name": "Ile-de-France", "default": false},
                        {"name": "Paris", "default": true}
                      ]
                    },
                    {
                      "type": "STOP",
                      "name": "No City",
                      "id": "stop:no-city",
                      "lat": 4.885600E1,
                      "lon": 2.35220E0,
                      "areas": [{"name": "Fallback", "default": false}]
                    }
                  ]
                }
            """.trimIndent(),
        )

        assertEquals(listOf("stop:hotel-de-ville", "stop:no-city"), matches.map { it.stop.id })
        assertEquals(48.85662, matches[0].stop.lat, 0.00001)
        assertEquals(2.35154, matches[0].stop.lon, 0.00001)
        assertEquals("Paris", matches[0].city)
        assertEquals("", matches[1].city)
        assertEquals(UNKNOWN_DISTANCE_METERS, matches[0].stop.distanceMeters)
    }
}
