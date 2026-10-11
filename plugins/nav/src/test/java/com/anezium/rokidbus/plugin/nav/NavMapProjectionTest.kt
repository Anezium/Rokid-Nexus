package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** The v2 route geometry built from a walking journey: roles, marks, degraded positions, clipping and budgets. */
class NavMapProjectionTest {
    @Test
    fun `follow centres the wearer and keeps every point in the frame`() {
        val route = straight()
        val snapshot = tracking(route, 250.0)
        val follow = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        assertValid(follow)
        assertEquals(NavMapProjection.FOLLOW_SPAN_METERS, follow.spanMeters, 0.0)
        assertEquals(RoutePosition(RoutePositionQuality.LIVE, RoutePoint(500, 500)), follow.position)
        assertTrue(follow.paths.flatMap { it.points }.all { it.x in 0..1000 && it.y in 0..1000 })
        // No street map exists behind a planned walk: no street or major path is ever drawn.
        assertTrue(follow.paths.none { it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR })
    }

    @Test
    fun `confirmed progress splits the route into the walked part and the route ahead`() {
        val route = straight()
        val fresh = NavMapProjection.geometry(tracking(route, 0.0), NavMapView.OVERVIEW)
        assertEquals(listOf(RoutePathRole.ROUTE), fresh.paths.map { it.role })
        val halfway = NavMapProjection.geometry(tracking(route, 250.0), NavMapView.OVERVIEW)
        assertEquals(setOf(RoutePathRole.ROUTE, RoutePathRole.COMPLETED), halfway.paths.map { it.role }.toSet())
        // Both parts meet at the wearer, and the route ahead is listed first so a tight budget keeps it.
        assertEquals(RoutePathRole.ROUTE, halfway.paths.first().role)
        val completed = halfway.paths.first { it.role == RoutePathRole.COMPLETED }
        assertEquals(completed.points.last(), halfway.paths.first().points.first())
        assertEquals(halfway.position!!.point, completed.points.last())
        assertNotNull(halfway.destination)
        assertValid(halfway)
    }

    @Test
    fun `Liang-Barsky keeps a clipped segment's true direction instead of clamping its ends`() {
        val clipped = NavMapProjection.clip(MapMeters(-500.0, 500.0), MapMeters(1500.0, 1500.0))!!
        assertEquals(0.0, clipped.first.x, 1e-9)
        assertEquals(750.0, clipped.first.y, 1e-9)
        assertEquals(500.0, clipped.second.x, 1e-9)
        assertEquals(1000.0, clipped.second.y, 1e-9)
        assertNull(NavMapProjection.clip(MapMeters(-10.0, -10.0), MapMeters(-5.0, 2000.0)))
        val inside = NavMapProjection.clip(MapMeters(100.0, 100.0), MapMeters(200.0, 300.0))!!
        assertEquals(MapMeters(100.0, 100.0), inside.first)
        assertEquals(MapMeters(200.0, 300.0), inside.second)
    }

    @Test
    fun `a route leaving the follow frame ends on its border where it really crosses`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.02, 2.0))
        val route = NavRoute(NavDestination("North", points.last()), points, 1_600, listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "North")))
        val follow = NavMapProjection.geometry(tracking(route, 0.0), NavMapView.FOLLOW)
        val ahead = follow.paths.single()
        assertEquals(RoutePathRole.ROUTE, ahead.role)
        assertEquals(RoutePoint(500, 500), ahead.points.first())
        assertEquals(RoutePoint(500, 0), ahead.points.last())
        // The destination is far outside the frame, so it is not marked at a fake place on the border.
        assertNull(follow.destination)
    }

    @Test
    fun `a stale position keeps its last place with its age, none has no position at all`() {
        val route = straight()
        val live = NavMapProjection.geometry(tracking(route, 250.0), NavMapView.FOLLOW)
        val stale = NavMapProjection.geometry(
            tracking(route, 250.0).copy(quality = RouteQuality.STALE, positionAtMs = 0, atMs = 42_400),
            NavMapView.FOLLOW,
        )
        assertEquals(RoutePosition(RoutePositionQuality.STALE, RoutePoint(500, 500), 42), stale.position)
        assertEquals(live.paths, stale.paths)
        assertValid(stale)
        val none = NavMapProjection.geometry(tracking(route, 250.0).copy(position = null, positionAtMs = null, quality = RouteQuality.WAITING), NavMapView.FOLLOW)
        assertEquals(RoutePosition(RoutePositionQuality.NONE, null), none.position)
        assertEquals(live.paths, none.paths)
        assertValid(none)
        // A live fix carries no age: it would change the geometry every second for nothing.
        assertNull(live.position!!.fixAgeS)
    }

    @Test
    fun `labels are the planner's street names and the destination, never more than four and short`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        val length = RouteLine(points).lengthMeters
        val turnStreet = "Avenue of a Remarkably Long Name"
        val route = NavRoute(
            NavDestination("Gare de Lyon, Paris, France", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", turnStreet), RouteStep(length, "ARRIVE", "Gare de Lyon")),
            listOf(RouteStreet(0.0, "Boulevard du Montparnasse Prolongé"), RouteStreet(200.0, turnStreet)),
        )
        val follow = NavMapProjection.geometry(tracking(route, 100.0), NavMapView.FOLLOW)
        assertTrue(follow.labels.size <= RouteGeometryContract.MAX_LABELS)
        assertTrue(follow.labels.all { it.text.isNotBlank() && it.text.length <= RouteGeometryContract.MAX_LABEL_CHARS })
        val first = follow.labels.first { it.priority == 0 }
        assertEquals(follow.turn, RoutePoint(first.x, first.y))
        assertTrue(first.text.startsWith("Avenue of a"))
        assertTrue(follow.labels.any { it.priority == 1 && it.text.startsWith("Boulevard du") })
        assertValid(follow)
        val overview = NavMapProjection.geometry(tracking(route, 100.0), NavMapView.OVERVIEW)
        val destination = overview.labels.single { it.priority == 2 }
        assertEquals("Gare de Lyon", destination.text)
        assertEquals(overview.destination, RoutePoint(destination.x, destination.y))
    }

    @Test
    fun `the turn is marked only while a turn is ahead`() {
        val route = straight()
        assertNotNull(NavMapProjection.geometry(tracking(route, 100.0), NavMapView.FOLLOW).turn)
        assertNull(NavMapProjection.geometry(tracking(route, 300.0), NavMapView.FOLLOW).turn)
        val arrived = tracking(route, route.lengthMeters).copy(position = route.destination.point, quality = RouteQuality.ARRIVED)
        val overview = NavMapProjection.geometry(arrived, NavMapView.OVERVIEW)
        assertNull(overview.turn)
        assertEquals(overview.destination, overview.position!!.point)
    }

    @Test
    fun `off the route the follow frame widens until the route is back in it`() {
        val route = straight()
        val away = GeoPoint(48.002, 2.0134)
        val snapshot = NavRouteSnapshot(route, 0.0, away, positionAtMs = 0, quality = RouteQuality.OFF_ROUTE)
        val follow = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        assertTrue(follow.spanMeters > NavMapProjection.FOLLOW_SPAN_METERS)
        assertTrue(follow.paths.isNotEmpty())
        assertEquals(RoutePoint(500, 500), follow.position!!.point)
        assertValid(follow)
    }

    @Test
    fun `a long winding route is simplified to the contract budget, keeping its ends`() {
        val points = (0 until 4_000).map { index -> GeoPoint(48.0 + index * 0.00002, 2.0 + if (index % 2 == 0) 0.0 else 0.0003) }
        val length = RouteLine(points).lengthMeters
        val route = NavRoute(NavDestination("Far", points.last()), points, 43_000, listOf(RouteStep(length, "ARRIVE", "Far")))
        val overview = NavMapProjection.geometry(tracking(route, 0.0), NavMapView.OVERVIEW)
        assertValid(overview)
        assertTrue(overview.paths.sumOf { it.points.size } <= RouteGeometryContract.MAX_POINTS)
        val ahead = overview.paths.single()
        assertEquals(overview.position!!.point, ahead.points.first())
        val end = overview.destination!!
        assertTrue(abs(ahead.points.last().x - end.x) <= 1 && abs(ahead.points.last().y - end.y) <= 1)

        val tight = NavMapBudget(maxPaths = 2, maxPoints = 40, maxBytes = 1_500)
        val small = NavMapProjection.geometry(tracking(route, 0.0), NavMapView.OVERVIEW, tight)
        assertTrue(small.paths.size <= 2)
        assertTrue(small.paths.sumOf { it.points.size } <= 40)
        assertTrue(bytes(small) <= 1_500)
        assertTrue(small.paths.isNotEmpty())
    }

    @Test
    fun `a route weaving through the frame keeps at most 24 paths, the route ahead first`() {
        // Every leg runs about 450 m east or west, wider than the 400 m frame, so each visible leg is its own run.
        val points = (0 until 120).map { index -> GeoPoint(48.0 + index * 0.0001, 2.0 + if (index % 2 == 0) -0.003 else 0.003) }
        val length = RouteLine(points).lengthMeters
        val route = NavRoute(NavDestination("Weave", points.last()), points, 43_000, listOf(RouteStep(length, "ARRIVE", "Weave")))
        val follow = NavMapProjection.geometry(tracking(route, length / 2), NavMapView.FOLLOW)
        assertValid(follow)
        assertEquals(RouteGeometryContract.MAX_PATHS, follow.paths.size)
        assertEquals(RoutePathRole.ROUTE, follow.paths.first().role)
        assertTrue(follow.paths.count { it.role == RoutePathRole.ROUTE } >= follow.paths.count { it.role == RoutePathRole.COMPLETED })
        assertTrue(follow.paths.any { it.role == RoutePathRole.ROUTE && it.points.first() == follow.position!!.point })
    }

    @Test
    fun `the captured public route projects within budget in both views`() {
        val route = TransitousRouteParser.parse(
            org.json.JSONObject(javaClass.getResourceAsStream("/transitous-public-paris-walk.json")!!.bufferedReader().use { it.readText() }),
            NavDestination("Paris City Hall", GeoPoint(48.8566, 2.3522)),
        )
        for (progress in listOf(0.0, 200.0, route.lengthMeters)) {
            for (view in NavMapView.values()) assertValid(NavMapProjection.geometry(tracking(route, progress), view))
        }
        assertFalse(NavMapProjection.geometry(tracking(route, 200.0), NavMapView.OVERVIEW).paths.isEmpty())
    }

    private fun straight(): NavRoute {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        val line = RouteLine(points)
        return NavRoute(
            NavDestination("Destination", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", "Test Street"), RouteStep(line.lengthMeters, "ARRIVE", "Destination")),
        )
    }

    private fun tracking(route: NavRoute, progress: Double) = NavRouteSnapshot(
        route,
        progressMeters = progress,
        position = route.line.pointAt(progress),
        positionAtMs = 0,
        quality = RouteQuality.TRACKING,
    )

    private fun bytes(geometry: RouteGeometry) = RouteGeometryContract.encode(geometry).toString().toByteArray(Charsets.UTF_8).size

    private fun assertValid(geometry: RouteGeometry) {
        assertTrue(RouteGeometryContract.validate(RouteGeometryContract.encode(geometry)).toString(), RouteGeometryContract.isValid(geometry))
        assertTrue(geometry.paths.size <= RouteGeometryContract.MAX_PATHS)
        assertTrue(geometry.paths.sumOf { it.points.size } <= RouteGeometryContract.MAX_POINTS)
        assertTrue(bytes(geometry) <= RouteGeometryContract.MAX_BYTES)
        assertTrue(geometry.labels.size <= RouteGeometryContract.MAX_LABELS)
    }
}
