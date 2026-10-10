package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
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

    @Test
    fun departures_returnsKmbRealtimeOverlay() {
        val repository = TransitRepository(
            baseUrl = "https://transit.test",
            http = { url ->
                when {
                    url.startsWith("https://transit.test/stoptimes") -> scheduledDeparturesJson()
                    "/kmb/stop-eta/610F9B32EEAE091D" in url ->
                        """
                            {"type":"StopETA","version":"1.0","data":[
                              {"co":"KMB","route":"42A","dir":"I","dest_en":"TSING YI",
                               "eta_seq":1,"eta":"2026-08-05T19:12:27+08:00","rmk_en":""}
                            ]}
                        """.trimIndent()
                    else -> error("Unexpected URL: $url")
                }
            },
        )

        val departures = repository.departures("hk-Hong-Kong-Transit_KMB-610F9B32EEAE091D")

        assertEquals(1, departures.size)
        assertEquals("42A", departures.single().routeShortName)
        assertEquals("Tsing Yi", departures.single().headsign)
        assertEquals(Instant.parse("2026-08-05T11:12:27Z"), departures.single().departure)
    }

    @Test
    fun departures_fallsBackToScheduledListWhenRealtimeFetchThrows() {
        val repository = TransitRepository(
            baseUrl = "https://transit.test",
            http = { url ->
                if (url.startsWith("https://transit.test/stoptimes")) {
                    scheduledDeparturesJson()
                } else {
                    throw IOException("Realtime unavailable")
                }
            },
        )

        val departures = repository.departures("hk-Hong-Kong-Transit_KMB-610F9B32EEAE091D")

        assertEquals(1, departures.size)
        assertEquals("S1", departures.single().routeShortName)
        assertEquals("Scheduled destination", departures.single().headsign)
        assertEquals(Instant.parse("2026-08-05T11:30:00Z"), departures.single().departure)
    }

    private fun scheduledDeparturesJson() =
        """
            {"stopTimes":[
              {"place":{"departure":"2026-08-05T11:30:00Z","scheduledDeparture":"2026-08-05T11:30:00Z"},
               "mode":"BUS","routeShortName":"S1","headsign":"Scheduled destination","cancelled":false}
            ]}
        """.trimIndent()

    @Test
    fun parseDepartures_keepsTheFeedsRealtimeFlagAndTripIdentity() {
        val departures = TransitRepository.parseDepartures(
            """
                {"stopTimes": [
                  {"place": {"departure": "2026-07-08T14:00:00Z"}, "mode": "TRAM", "headsign": "A",
                   "realTime": true, "tripId": "trip-1"},
                  {"place": {"departure": "2026-07-08T14:05:00Z"}, "mode": "TRAM", "headsign": "A",
                   "realTime": false},
                  {"place": {"departure": "2026-07-08T14:10:00Z"}, "mode": "TRAM", "headsign": "A"}
                ]}
            """.trimIndent(),
        )

        assertEquals(listOf(true, false, null), departures.map { it.realTime })
        assertEquals(listOf("trip-1", null, null), departures.map { it.tripId })
    }

    @Test
    fun parsePlaceMatches_offersAddressesWithTheirArea() {
        val places = TransitRepository.parsePlaceMatches(
            """
                [
                  {"type":"ADDRESS","name":"Rue de Rivoli","houseNumber":"12","street":"Rue de Rivoli",
                   "lat":48.8566,"lon":2.3522,"areas":[{"name":"Paris","default":true}]},
                  {"type":"PLACE","name":"Louvre","lat":48.8606,"lon":2.3376},
                  {"type":"ADDRESS","name":"No position"}
                ]
            """.trimIndent(),
        )

        assertEquals(2, places.size)
        assertEquals("Rue de Rivoli, 12 Rue de Rivoli", places[0].name)
        assertEquals("Paris", places[0].area)
        assertEquals("Louvre", places[1].name)
    }

    @Test
    fun searchStopsUpTo_asksForOneMoreThanItShows() {
        val json = (1..9).joinToString(",", "[", "]") {
            """{"type":"STOP","name":"S$it","id":"s$it","lat":48.0,"lon":2.0}"""
        }
        val repository = TransitRepository(http = { json })

        assertEquals(9, repository.searchStopsUpTo("S", 9).size)
        assertEquals(8, repository.searchStops("S").size)
    }

    @Test
    fun userAgent_identifiesTransitWithItsVersionAndContact() {
        assertEquals(
            "RokidNexus-Transit/1.1.0 (+https://github.com/Anezium)",
            TransitRepository.userAgentFor("1.1.0"),
        )
    }
}
