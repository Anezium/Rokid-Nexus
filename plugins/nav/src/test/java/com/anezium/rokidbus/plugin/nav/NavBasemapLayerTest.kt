package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.plugin.nav.MvtFixtures.line
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/**
 * OpenStreetMap streets under a planned walk: drawn only where they are, merged across tile
 * edges, chosen nearest and major first within the route's budget, and never instead of the route.
 */
class NavBasemapLayerTest {
    private val sampleKey = TileKey(14, 8800, 5373)

    @Test
    fun `accepted maximum vertices are rejected before view work regardless of tiny output`() {
        val route = gateRoute()
        // Repeated vertices would produce almost no output, but still cost 300000 visits.
        val feature = line(*List(300_000) { (2048 + it % 2) to 2048 }.toTypedArray(), tags = mapOf("kind" to "residential"))
        val decoded = ShortbreadTiles.decode(sampleKey, MvtFixtures.shortbread(listOf(feature)))
        val local = LocalTile.of(decoded, route.line)
        assertTrue(local.vertices >= 300_000)
        assertFalse(NavBasemapLayer.withinWorkBudget(listOf(local)))
        val snapshot = tracking(route, 100.0)
        val loaded = snapshot.copy(basemap = NavBasemapTiles(OsmShortbreadSource.attribution, 14, mapOf(sampleKey to local)))
        assertEquals(NavMapProjection.geometry(snapshot, NavMapView.FOLLOW), NavMapProjection.geometry(loaded, NavMapView.FOLLOW))
    }

    @Test
    fun `preparation budget is aggregate and also bounds quadratic simplification`() {
        fun tile(x: Int, count: Int, lineSize: Int): LocalTile = LocalTile(TileKey(14, x, 5373),
            List(count) { LocalLine(StreetClass.STREET, DoubleArray(lineSize), DoubleArray(lineSize)) }, emptyList())
        val modest = tile(8800, 1000, 4)
        assertTrue(NavBasemapLayer.withinWorkBudget(listOf(modest)))
        assertFalse(NavBasemapLayer.withinWorkBudget(List(5) { modest }))
        val longLine = tile(8801, 1, 1000)
        assertTrue(longLine.vertices < NavBasemapLayer.MAX_PREPARE_VERTICES)
        assertFalse(NavBasemapLayer.withinWorkBudget(listOf(longLine)))
    }

    @Test
    fun `real streets and names from the public tile are drawn under the route in both views`() {
        val route = gateRoute()
        val snapshot = tracking(route, 120.0).copy(basemap = tiles(route, sampleKey to ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample())))
        val names = ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample()).names.map { it.name }.toSet()
        for (view in NavMapView.entries) {
            val alone = NavMapProjection.geometry(snapshot.copy(basemap = NavBasemapTiles.NONE), view)
            val drawn = NavMapProjection.geometry(snapshot, view)
            assertTrue("$view", RouteGeometryContract.isValid(drawn))
            // The route, its marks and the planner's labels are exactly what they were without streets.
            assertEquals("$view", alone.paths, drawn.paths.take(alone.paths.size))
            assertEquals("$view", alone.labels, drawn.labels.take(alone.labels.size))
            assertEquals(alone.position, drawn.position)
            assertEquals(alone.turn, drawn.turn)
            assertEquals(alone.destination, drawn.destination)
            assertEquals(alone.spanMeters, drawn.spanMeters, 0.0)
            val streets = drawn.paths.drop(alone.paths.size)
            assertTrue("$view", streets.all { it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR })
            assertTrue("$view", streets.any { it.role == RoutePathRole.MAJOR })
            assertTrue("$view", streets.any { it.role == RoutePathRole.STREET })
            assertTrue("$view", streets.sumOf { it.points.size } > 100)
            assertEquals("© OpenStreetMap", drawn.attribution)
            assertTrue(bytes(drawn) <= bytes(alone) + NavBasemapLayer.MAX_BASEMAP_BYTES)
            val added = drawn.labels.drop(alone.labels.size)
            assertTrue("$view", added.isNotEmpty())
            assertTrue("$view", added.all { it.priority == RouteGeometryContract.MAX_LABEL_PRIORITY && it.text in names })
            // The road tunnel under the Tiergarten has a label line in the tile but no street on the surface.
            assertTrue(drawn.labels.none { it.text.startsWith("Tunnel") })
        }
    }

    @Test
    fun `every street stroke lies on a street of the tile`() {
        val route = gateRoute()
        val tile = ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample())
        val local = LocalTile.of(tile, route.line)
        val snapshot = tracking(route, 120.0).copy(basemap = NavBasemapTiles(OsmShortbreadSource.attribution, 14, mapOf(sampleKey to local)))
        for (view in NavMapView.entries) {
            val window = NavMapProjection.window(snapshot, view)
            val sources = local.streets.flatMap { street ->
                (1 until street.xs.size).map {
                    window.normalized(MapMeters(street.xs[it - 1], street.ys[it - 1])) to window.normalized(MapMeters(street.xs[it], street.ys[it]))
                }
            }
            val drawn = NavMapProjection.geometry(snapshot, view)
            val strokes = drawn.paths.filter { it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR }
                .flatMap { it.points.zipWithNext() }
            assertTrue(strokes.size > 50)
            strokes.forEach { (a, b) ->
                // Both ends and the middle sit on the tile's own geometry, within simplification and rounding.
                listOf(a.x to a.y, b.x to b.y, (a.x + b.x) / 2 to (a.y + b.y) / 2).forEach { (x, y) ->
                    val nearest = sources.minOf { (s, e) -> distance(x.toDouble(), y.toDouble(), s, e) }
                    assertTrue("$view stroke $a-$b is $nearest units from any street", nearest <= NavBasemapLayer.SIMPLIFY_UNITS + 1.5)
                }
            }
        }
    }

    @Test
    fun `a street crossing a tile edge is one continuous path, drawn once`() {
        val west = TileKey(14, 8800, 5373)
        val east = TileKey(14, 8801, 5373)
        val street = mapOf("kind" to "residential")
        val westTile = MvtFixtures.shortbread(
            // The same way twice, as overlapping ways come, and reaching into the neighbour's buffer.
            listOf(line(3000 to 2000, 4160 to 2000, tags = street), line(3000 to 2000, 4160 to 2000, tags = street)),
        )
        val eastTile = MvtFixtures.shortbread(listOf(line(-64 to 2000, 1000 to 2000, tags = street)))
        val route = route(GeoPoint(52.5160, 13.3790), GeoPoint(52.5160, 13.3840))
        val snapshot = tracking(route, route.lengthMeters / 2).copy(
            basemap = tiles(route, west to ShortbreadTiles.decode(west, westTile), east to ShortbreadTiles.decode(east, eastTile)),
        )
        val window = NavMapProjection.window(snapshot, NavMapView.FOLLOW)
        val edge = window.normalized(route.line.toMeters(52.5160, WebMercator.lon(8801.0 / (1 shl 14)))).x
        val drawn = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        val streets = drawn.paths.filter { it.role == RoutePathRole.STREET }
        assertEquals(1, streets.size)
        val points = streets.single().points
        assertTrue(points.minOf { it.x } < edge - 50 && points.maxOf { it.x } > edge + 50)
        // A straight street: every stroke once, no way back over it, nothing doubled.
        assertEquals(points.size, points.distinct().size)
        assertTrue(points.zipWithNext().all { (a, b) -> b.x > a.x })
    }

    @Test
    fun `a tight budget keeps the route and the nearest streets, a looser one adds farther ones`() {
        val route = gateRoute()
        val snapshot = tracking(route, 120.0).copy(basemap = tiles(route, sampleKey to ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample())))
        val alone = NavMapProjection.geometry(snapshot.copy(basemap = NavBasemapTiles.NONE), NavMapView.FOLLOW)
        val routePoints = alone.paths.sumOf { it.points.size }
        val tight = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW, NavMapBudget(maxPaths = alone.paths.size + 3, maxPoints = routePoints + 40))
        val loose = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        assertEquals(alone.paths, tight.paths.take(alone.paths.size))
        assertTrue(tight.paths.size <= alone.paths.size + 3)
        assertTrue(tight.paths.sumOf { it.points.size } <= routePoints + 40)
        val tightStrokes = strokes(tight)
        val looseStrokes = strokes(loose)
        assertTrue(tightStrokes.isNotEmpty() && looseStrokes.size > tightStrokes.size)
        // Nearest first: what a tight budget draws, a looser one draws too, and never farther from the wearer.
        assertTrue(looseStrokes.containsAll(tightStrokes))
        val wearer = alone.position!!.point!!
        fun reach(strokes: Set<Pair<RoutePoint, RoutePoint>>) = strokes.maxOf { (a, b) -> hypot(((a.x + b.x) / 2 - wearer.x).toDouble(), ((a.y + b.y) / 2 - wearer.y).toDouble()) }
        assertTrue(reach(tightStrokes) < reach(looseStrokes))
        // A budget the route already fills leaves the map as the route alone, uncredited.
        val full = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW, NavMapBudget(maxPaths = alone.paths.size))
        assertEquals(alone, full)
    }

    @Test
    fun `a major road a little farther comes before a footway close by`() {
        val key = TileKey(14, 8800, 5373)
        // In tile units north of the route: a footway close, a primary road twice as far.
        val body = MvtFixtures.shortbread(
            listOf(
                line(2000 to 2030, 3000 to 2030, tags = mapOf("kind" to "footway")),
                line(2000 to 1960, 3000 to 1960, tags = mapOf("kind" to "primary")),
            ),
        )
        val route = route(GeoPoint(52.5160, 13.3720), GeoPoint(52.5160, 13.3760))
        val snapshot = tracking(route, 100.0).copy(basemap = tiles(route, key to ShortbreadTiles.decode(key, body)))
        val alone = NavMapProjection.geometry(snapshot.copy(basemap = NavBasemapTiles.NONE), NavMapView.FOLLOW)
        val oneStroke = NavMapProjection.geometry(
            snapshot,
            NavMapView.FOLLOW,
            NavMapBudget(maxPoints = alone.paths.sumOf { it.points.size } + 2),
        )
        assertEquals(listOf(RoutePathRole.MAJOR), oneStroke.paths.drop(alone.paths.size).map { it.role })
    }

    @Test
    fun `without street tiles for the view the map is the route alone, uncredited`() {
        val route = gateRoute()
        val snapshot = tracking(route, 120.0)
        val alone = NavMapProjection.geometry(snapshot, NavMapView.FOLLOW)
        assertNull(alone.attribution)
        assertTrue(alone.paths.none { it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR })
        // Tiles of somewhere else are not drawn here.
        val elsewhere = TileKey(14, 100, 100)
        val other = snapshot.copy(basemap = tiles(route, elsewhere to ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample())))
        assertEquals(alone, NavMapProjection.geometry(other, NavMapView.FOLLOW))
        // An empty tile is an answer, and it draws nothing.
        val empty = snapshot.copy(basemap = tiles(route, sampleKey to ShortbreadTiles.decode(sampleKey, ByteArray(0))))
        assertEquals(alone, NavMapProjection.geometry(empty, NavMapView.FOLLOW))
    }

    @Test
    fun `off the route the follow frame still widens to find the route, not a street`() {
        val route = gateRoute()
        val far = route.line.pointAt(0.0).let { GeoPoint(it.lat + 0.004, it.lon) }
        val off = NavRouteSnapshot(route, 50.0, far, 0L, quality = RouteQuality.OFF_ROUTE, atMs = 0L)
        val loaded = off.copy(basemap = tiles(route, sampleKey to ShortbreadTiles.decode(sampleKey, MvtReaderTest.sample())))
        val alone = NavMapProjection.geometry(off, NavMapView.FOLLOW)
        val drawn = NavMapProjection.geometry(loaded, NavMapView.FOLLOW)
        assertTrue(alone.spanMeters > NavMapProjection.FOLLOW_SPAN_METERS)
        assertEquals(alone.spanMeters, drawn.spanMeters, 0.0)
        assertTrue(drawn.paths.any { it.role == RoutePathRole.ROUTE || it.role == RoutePathRole.COMPLETED })
    }

    @Test
    fun `each view asks for the tiles it shows, deepest first, and none when too wide`() {
        val route = gateRoute()
        val snapshot = tracking(route, 120.0)
        assertEquals(listOf(sampleKey), NavMapProjection.tiles(snapshot, NavMapView.FOLLOW, OsmShortbreadSource))
        assertEquals(listOf(sampleKey), NavMapProjection.tiles(snapshot, NavMapView.OVERVIEW, OsmShortbreadSource))
        // A 7 km walk is seen at a shallower zoom, within nine tiles.
        val long = route(GeoPoint(52.5163, 13.3777), GeoPoint(52.5163, 13.4810))
        val overview = NavMapProjection.tiles(tracking(long, 0.0), NavMapView.OVERVIEW, OsmShortbreadSource)
        assertTrue(overview.isNotEmpty() && overview.size <= NavBasemapLayer.OVERVIEW_MAX_TILES)
        assertTrue(overview.all { it.z in NavBasemapLayer.MIN_ZOOM until 14 })
        // A 40 km walk is too wide for any street tile: its overview is the route alone.
        val longer = route(GeoPoint(52.5163, 13.0), GeoPoint(52.5163, 13.59))
        assertTrue(NavMapProjection.tiles(tracking(longer, 0.0), NavMapView.OVERVIEW, OsmShortbreadSource).isEmpty())
        assertFalse(NavMapProjection.tiles(tracking(longer, 0.0), NavMapView.FOLLOW, OsmShortbreadSource).isEmpty())
    }

    private fun strokes(geometry: RouteGeometry): Set<Pair<RoutePoint, RoutePoint>> =
        geometry.paths.filter { it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR }
            .flatMap { path -> path.points.zipWithNext().map { (a, b) -> if (a.x < b.x || (a.x == b.x && a.y <= b.y)) a to b else b to a } }
            .toSet()

    private fun bytes(geometry: RouteGeometry) = RouteGeometryContract.encode(geometry).toString().toByteArray(Charsets.UTF_8).size

    private fun distance(x: Double, y: Double, a: MapMeters, b: MapMeters): Double {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val length = dx * dx + dy * dy
        if (length == 0.0) return hypot(x - a.x, y - a.y)
        val t = (((x - a.x) * dx + (y - a.y) * dy) / length).coerceIn(0.0, 1.0)
        return hypot(x - (a.x + t * dx), y - (a.y + t * dy))
    }

    private fun tiles(route: NavRoute, vararg decoded: Pair<TileKey, BasemapTile>) =
        NavBasemapTiles(OsmShortbreadSource.attribution, OsmShortbreadSource.maxZoom, decoded.associate { (key, tile) -> key to LocalTile.of(tile, route.line) })

    /** West from the Brandenburg Gate along Straße des 17. Juni, then a turn: a public place, a made-up walk. */
    private fun gateRoute(): NavRoute {
        val points = listOf(GeoPoint(52.51630, 13.37650), GeoPoint(52.51630, 13.37100), GeoPoint(52.51480, 13.37100))
        val line = RouteLine(points)
        return NavRoute(
            NavDestination("Tiergarten", points.last()),
            points,
            600,
            listOf(RouteStep(line.cumulative[1], "LEFT", "Ebertstraße"), RouteStep(line.lengthMeters, "ARRIVE", "Tiergarten")),
            listOf(RouteStreet(0.0, "Straße des 17. Juni"), RouteStreet(line.cumulative[1], "Ebertstraße")),
        )
    }

    private fun route(from: GeoPoint, to: GeoPoint): NavRoute {
        val points = listOf(from, to)
        return NavRoute(NavDestination("There", to), points, 600, listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "There")))
    }

    private fun tracking(route: NavRoute, progress: Double) = NavRouteSnapshot(
        route = route,
        progressMeters = progress,
        position = route.line.pointAt(progress),
        positionAtMs = 0L,
        quality = RouteQuality.TRACKING,
        atMs = 0L,
    )
}
