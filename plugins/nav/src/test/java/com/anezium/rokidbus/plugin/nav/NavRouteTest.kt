package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RoutePositionQuality
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToLong

/** Route decoding, local projection, and what the tracker accepts as progress and position. */
class NavRouteTest {
    @Test
    fun `parses the captured public walking route with explicit precision`() {
        val route = TransitousRouteParser.parse(publicResponse(), NavDestination("Paris City Hall", GeoPoint(48.8566, 2.3522)))
        assertEquals(118, route.points.size)
        assertEquals(514, route.durationSeconds)
        assertEquals(556.98, route.lengthMeters, 0.1)
        assertEquals("ARRIVE", route.steps.last().direction)
        assertTrue(route.steps.zipWithNext().all { (a, b) -> a.atMeters <= b.atMeters })
        // The real response labels every step CONTINUE, so it must not invent turns.
        assertEquals(1, route.steps.size)
        assertEquals("route", NavRouteSnapshot(route, quality = RouteQuality.TRACKING).guidance().glyph)
        // Street names come only from what the planner named, in route order.
        assertTrue(route.streets.any { "Notre-Dame" in it.name })
        assertTrue(route.streets.zipWithNext().all { (a, b) -> a.atMeters <= b.atMeters })
    }

    @Test
    fun `does not use transit itineraries as a walking route`() {
        val payload = publicResponse()
        payload.getJSONArray("direct").getJSONObject(0).getJSONArray("legs").getJSONObject(0).put("mode", "BUS")
        assertThrows(IllegalStateException::class.java) {
            TransitousRouteParser.parse(payload, NavDestination("Paris", GeoPoint(48.8566, 2.3522)))
        }
    }

    @Test
    fun `rejects a route that does not reach the selected place`() {
        assertThrows(IllegalArgumentException::class.java) {
            TransitousRouteParser.parse(publicResponse(), NavDestination("Elsewhere", GeoPoint(48.87, 2.30)))
        }
    }

    @Test
    fun `decodes the reference polyline and longitudes above the old 107 degree limit`() {
        val reference = RoutePolyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(GeoPoint(38.5, -120.2), reference.first())
        assertEquals(GeoPoint(43.252, -126.453), reference.last())
        val tokyo = listOf(GeoPoint(35.681236, 139.767125), GeoPoint(35.681436, 139.767425))
        for (precision in listOf(6, 7)) assertEquals(tokyo, RoutePolyline.decode(encode(tokyo, precision), precision))
        assertThrows(IllegalArgumentException::class.java) { RoutePolyline.decode("_", 6) }
        assertThrows(IllegalArgumentException::class.java) { RoutePolyline.decode("??", 6) }
        assertThrows(IllegalArgumentException::class.java) { RoutePolyline.decode("????", 8) }
    }

    @Test
    fun `projection wraps longitude across the date line`() {
        val line = RouteLine(listOf(GeoPoint(10.0, 179.999), GeoPoint(10.0, -179.999)))
        assertTrue(line.lengthMeters in 200.0..225.0)
    }

    @Test
    fun `segment projection finds the closest point within the allowed stretch`() {
        val line = testRoute().line
        val beside = line.pointAt(150.0).let { GeoPoint(it.lat, it.lon + 0.0002) }
        val free = line.project(beside)
        assertEquals(150.0, free.alongMeters, 0.5)
        assertEquals(14.9, free.distanceMeters, 0.5)
        // Bounded to a later stretch, the closest allowed point is that stretch's start.
        val bounded = line.project(beside, minimum = 300.0)
        assertEquals(300.0, bounded.alongMeters, 0.5)
    }

    @Test
    fun `no fix, poor fixes and stale fixes never advance progress`() {
        val route = testRoute()
        val tracker = NavRouteTracker(route)
        assertEquals(RouteQuality.WAITING, tracker.update(null, 10_000).quality)
        val point = route.line.pointAt(100.0)
        assertEquals(RouteQuality.INACCURATE, tracker.update(NavFix(point, 90.0, 10_000), 10_000).quality)
        assertEquals(0.0, tracker.snapshot.progressMeters, 0.001)
        // No fix was ever trusted: there is no position to show, not even a stale one.
        assertEquals(RoutePositionQuality.NONE, tracker.snapshot.positionQuality)
        assertEquals(RouteQuality.STALE, tracker.update(NavFix(point, 5.0, 10_000), 50_001).quality)
        assertEquals(0.0, tracker.snapshot.progressMeters, 0.001)
        assertNull(tracker.snapshot.position)
        val fresh = tracker.update(NavFix(point, 5.0, 50_001), 50_001)
        assertEquals(100.0, fresh.progressMeters, 0.01)
        assertEquals(RoutePositionQuality.LIVE, fresh.positionQuality)
    }

    @Test
    fun `a stale fix keeps the last trusted position with its age and drops its bearing`() {
        val route = testRoute()
        val tracker = NavRouteTracker(route)
        val point = route.line.pointAt(100.0)
        val live = tracker.update(NavFix(point, 5.0, 50_001, bearing = 0, bearingAccuracyDeg = 10.0), 50_001)
        assertEquals(0, live.liveBearing)
        val stale = tracker.update(NavFix(point, 5.0, 50_001, bearing = 0, bearingAccuracyDeg = 10.0), 80_002)
        assertEquals(RouteQuality.STALE, stale.quality)
        assertEquals(100.0, stale.progressMeters, 0.01)
        assertEquals(live.position, stale.position)
        assertEquals(RoutePositionQuality.STALE, stale.positionQuality)
        assertEquals(30, stale.fixAgeS)
        assertNull(stale.bearing)
        assertNull(stale.liveBearing)
        // Losing the fix altogether keeps the same last position, still stale and ageing.
        val lost = tracker.update(null, 95_001)
        assertEquals(RouteQuality.STALE, lost.quality)
        assertEquals(live.position, lost.position)
        assertEquals(45, lost.fixAgeS)
        // A vague fix carries no trusted bearing either.
        val vague = tracker.update(NavFix(point, 80.0, 95_001, bearing = 90), 95_001)
        assertEquals(RouteQuality.INACCURATE, vague.quality)
        assertNull(vague.liveBearing)
        assertEquals(RoutePositionQuality.STALE, vague.positionQuality)
    }

    @Test
    fun `progress only moves forward`() {
        val route = testRoute()
        val tracker = NavRouteTracker(route)
        for (index in 0..4) tracker.update(NavFix(route.line.pointAt(index * 50.0), 5.0, index * 20_000L), index * 20_000L)
        assertEquals(200.0, tracker.snapshot.progressMeters, 0.5)
        val back = tracker.update(NavFix(route.line.pointAt(150.0), 5.0, 100_000), 100_000)
        assertEquals(RouteQuality.TRACKING, back.quality)
        assertEquals(200.0, back.progressMeters, 0.5)
    }

    @Test
    fun `off route and implausible jumps keep the last confirmed progress`() {
        val route = testRoute()
        val tracker = NavRouteTracker(route)
        tracker.update(NavFix(route.points.first(), 5.0, 1_000), 1_000)
        val jump = tracker.update(NavFix(route.line.pointAt(400.0), 5.0, 2_000), 2_000)
        assertEquals(RouteQuality.OFF_ROUTE, jump.quality)
        assertEquals(0.0, jump.progressMeters, 0.01)
        val away = GeoPoint(48.002, 2.005)
        val off = tracker.update(NavFix(away, 5.0, 20_000), 20_000)
        assertEquals(RouteQuality.OFF_ROUTE, off.quality)
        assertEquals(0.0, off.progressMeters, 0.01)
        // Where the wearer is, not a point snapped onto the route they left.
        assertEquals(away, off.position)
        assertEquals(RoutePositionQuality.LIVE, off.positionQuality)
    }

    @Test
    fun `arrival needs two distinct accurate fixes at the endpoint`() {
        val route = testRoute()
        val tracker = NavRouteTracker(route)
        for (index in 0..8) {
            val time = index * 20_000L
            tracker.update(NavFix(route.line.pointAt(index * 50.0), 5.0, time), time)
        }
        val endpoint = NavFix(route.points.last(), 5.0, 180_000)
        assertEquals(RouteQuality.TRACKING, tracker.update(endpoint, 180_000).quality)
        assertEquals(RouteQuality.TRACKING, tracker.update(endpoint, 180_001).quality)
        assertEquals(RouteQuality.ARRIVED, tracker.update(endpoint.copy(elapsedMs = 181_000), 181_000).quality)
        assertTrue(tracker.snapshot.guidance().arrived)
        // Arrival is final, whatever comes after.
        assertEquals(RouteQuality.ARRIVED, tracker.update(null, 400_000).quality)
    }

    @Test
    fun `arrival uses the selected destination when the route endpoint was snapped away from it`() {
        val original = testRoute()
        val destination = GeoPoint(original.points.last().lat, original.points.last().lon + 0.001)
        val route = NavRoute(NavDestination("Selected place", destination), original.points, 400, original.steps)
        val tracker = NavRouteTracker(route)
        for (index in 0..8) tracker.update(NavFix(route.line.pointAt(index * 50.0), 5.0, index * 20_000L), index * 20_000L)
        tracker.update(NavFix(route.points.last(), 5.0, 180_000), 180_000)
        tracker.update(NavFix(route.points.last(), 5.0, 181_000), 181_000)
        assertFalse(tracker.snapshot.guidance().arrived)
        tracker.update(NavFix(destination, 5.0, 200_000), 200_000)
        assertEquals(RouteQuality.ARRIVED, tracker.update(NavFix(destination, 5.0, 201_000), 201_000).quality)
        assertEquals(destination, tracker.snapshot.position)
    }

    @Test
    fun `the maneuver stays shown while approaching and advances after passing it`() {
        val route = testRoute()
        assertEquals("LEFT", NavRouteSnapshot(route, progressMeters = 195.0).nextStep.direction)
        assertEquals("LEFT", NavRouteSnapshot(route, progressMeters = 205.0).nextStep.direction)
        assertEquals("ARRIVE", NavRouteSnapshot(route, progressMeters = 215.0).nextStep.direction)
    }

    @Test
    fun `degraded guidance says what is wrong instead of a distance`() {
        val route = testRoute()
        val stale = NavRouteSnapshot(route, 100.0, route.line.pointAt(100.0), positionAtMs = 0, quality = RouteQuality.STALE, atMs = 40_000)
        val guidance = stale.guidance()
        assertEquals("GPS lost", guidance.primary)
        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
        assertNull(guidance.eta)
        val moving = NavRouteSnapshot(route, 100.0, route.line.pointAt(100.0), positionAtMs = 0, quality = RouteQuality.TRACKING).guidance()
        assertEquals("turn-left", moving.glyph)
        assertEquals("100 m", moving.primary)
        assertNotNull(moving.eta)
    }

    private fun testRoute(): NavRoute {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        val line = RouteLine(points)
        return NavRoute(
            NavDestination("Destination", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", "Test Street"), RouteStep(line.lengthMeters, "ARRIVE", "Destination")),
        )
    }

    private fun publicResponse() =
        JSONObject(javaClass.getResourceAsStream("/transitous-public-paris-walk.json")!!.bufferedReader().use { it.readText() })

    private fun encode(points: List<GeoPoint>, precision: Int): String = buildString {
        val scale = Math.pow(10.0, precision.toDouble())
        var lat = 0L
        var lon = 0L
        fun appendDelta(delta: Long) {
            var value = if (delta < 0) (delta shl 1).inv() else delta shl 1
            while (value >= 32) {
                append(((32 or (value and 31).toInt()) + 63).toChar())
                value = value shr 5
            }
            append((value + 63).toInt().toChar())
        }
        points.forEach { point ->
            val nextLat = (point.lat * scale).roundToLong()
            val nextLon = (point.lon * scale).roundToLong()
            appendDelta(nextLat - lat)
            appendDelta(nextLon - lon)
            lat = nextLat
            lon = nextLon
        }
    }
}
