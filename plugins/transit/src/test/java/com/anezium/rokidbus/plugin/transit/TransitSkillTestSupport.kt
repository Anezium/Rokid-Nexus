package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.shared.skills.SkillCatalog
import com.anezium.rokidbus.shared.skills.SkillCatalogParseResult
import com.anezium.rokidbus.shared.skills.SkillCatalogParser
import com.anezium.rokidbus.shared.skills.SkillSchemaValidator
import com.anezium.rokidbus.shared.skills.SkillValidation
import org.json.JSONObject
import org.junit.Assert.assertEquals
import java.io.File
import java.time.Instant

/** Transit's real catalog resource, as the hub would read it. */
internal val transitCatalog: SkillCatalog by lazy {
    val text = File("src/main/res/raw/nexus_skills.json").readText(Charsets.UTF_8)
    (SkillCatalogParser.parse(text) as SkillCatalogParseResult.Valid).catalog
}

/** The hub validates every result against the declared output; so do the tests. */
internal fun assertMatchesOutput(operationId: String, data: JSONObject) {
    val operation = requireNotNull(transitCatalog.operation(operationId))
    assertEquals(
        "output of $operationId: $data",
        SkillValidation.Valid,
        SkillSchemaValidator.validate(data, operation.output),
    )
}

internal fun place(
    name: String,
    lat: Double,
    lon: Double,
    stopId: String? = null,
    arrival: Instant? = null,
    departure: Instant? = null,
) = TransitPlace(name, lat, lon, stopId, arrival, departure)

internal val T0: Instant = Instant.parse("2026-09-27T08:00:00Z")

internal fun at(minutes: Long): Instant = T0.plusSeconds(minutes * 60)

/**
 * A two-ride itinerary along a straight north-south line, stops about 450 m apart:
 * walk to A, bus 38 from A through B and C to D, walk across D, metro M4 from D' through E to F,
 * walk home.
 */
internal fun sampleItinerary(): TransitItinerary {
    val start = place("START", 48.8000, 2.3500)
    val a = place("Stop A", 48.8030, 2.3500, "A", departure = at(5))
    val b = place("Stop B", 48.8070, 2.3500, "B", arrival = at(7), departure = at(7))
    val c = place("Stop C", 48.8110, 2.3500, "C", arrival = at(9), departure = at(9))
    val d = place("Stop D", 48.8150, 2.3500, "D", arrival = at(11))
    val d2 = place("Stop D", 48.8152, 2.3503, "D2", departure = at(14))
    val e = place("Stop E", 48.8200, 2.3503, "E", arrival = at(16), departure = at(16))
    val f = place("Stop F", 48.8250, 2.3503, "F", arrival = at(18))
    val end = place("END", 48.8262, 2.3503)
    return TransitItinerary(
        start = T0,
        end = at(21),
        transfers = 1,
        legs = listOf(
            TransitLeg("WALK", null, null, start, a, T0, at(5), T0, at(5), false, null, distanceMeters = 330),
            TransitLeg("BUS", "38", "Porte d'Orleans", a, d, at(5), at(11), at(5), at(11), true, "trip-38", listOf(b, c)),
            TransitLeg("WALK", null, null, d, d2, at(11), at(13), at(11), at(13), false, null, distanceMeters = 40),
            TransitLeg("SUBWAY", "4", "Mairie de Montrouge", d2, f, at(14), at(18), at(14), at(18), false, "trip-m4", listOf(e)),
            TransitLeg("WALK", null, null, f, end, at(18), at(21), at(18), at(21), false, null, distanceMeters = 140),
        ),
    )
}
