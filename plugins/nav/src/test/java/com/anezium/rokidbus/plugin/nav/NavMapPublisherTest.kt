package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusRenderedMap
import com.anezium.rokidbus.client.plugin.NexusRenderedMapView
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** When a journey's geometry and heading go out beside its activity, and when they never do. */
class NavMapPublisherTest {
    private var now = 100_000L
    private var supported = true
    private var geometryResult = NexusSdkResult.SENT
    private val geometries = mutableListOf<Pair<Long, RouteGeometry>>()
    private val headings = mutableListOf<Pair<Double, Double?>>()
    private val publisher = NavMapPublisher(
        supported = { supported },
        sendGeometry = { revision, geometry ->
            geometries += revision to geometry
            geometryResult
        },
        sendHeading = { deg, accuracy ->
            headings += deg to accuracy
            NexusSdkResult.SENT
        },
        now = { now },
    )
    private val route = straight()

    @Test
    fun `a peer downgrade cannot publish streets retained in the last journey snapshot`() {
        var streetsSupported = true
        val sent = mutableListOf<RouteGeometry>()
        val key = TileKey(14, 1, 1)
        val streets = NavBasemapTiles("© OpenStreetMap", 14, mapOf(key to LocalTile(key, emptyList(), emptyList())))
        val guarded = NavMapPublisher(
            supported = { true }, sendGeometry = { _, geometry -> sent += geometry; NexusSdkResult.SENT },
            sendHeading = { _, _ -> NexusSdkResult.SENT }, now = { now },
            geometryOf = { snapshot -> RouteGeometry(400.0, emptyList(), attribution = snapshot.basemap.attribution.takeIf { snapshot.basemap.tiles.isNotEmpty() }) },
            streetsSupported = { streetsSupported },
        )
        guarded.update(live(100.0).copy(basemap = streets), send = false)
        guarded.activityStarted()
        assertEquals("© OpenStreetMap", sent.last().attribution)
        streetsSupported = false
        guarded.linkChanged()
        assertNull(sent.last().attribution)
    }

    @Test
    fun `nothing goes out before the activity is live or without the launcher bundle`() {
        publisher.update(live(100.0, bearing = 10))
        assertTrue(geometries.isEmpty())
        supported = false
        publisher.activityStarted()
        assertTrue(geometries.isEmpty())
        assertTrue(headings.isEmpty())
        supported = true
        publisher.flush()
        assertEquals(1, geometries.size)
        assertEquals(1, headings.size)
        // Ended: the hub cleared it, and nothing follows until the next start.
        publisher.activityEnded()
        now += 5_000
        publisher.update(live(150.0, bearing = 20, fixAt = now))
        assertEquals(1, geometries.size)
        assertEquals(1, headings.size)
    }

    @Test
    fun `geometry goes out at most once a second, only when it changed, with rising revisions`() {
        publisher.update(live(100.0), send = false)
        publisher.activityStarted()
        assertEquals(1, geometries.size)
        now += 100
        publisher.update(live(100.0))
        assertEquals(1, geometries.size)
        now += 400
        publisher.update(live(140.0))
        assertEquals(1, geometries.size)
        now += 500
        publisher.flush()
        assertEquals(2, geometries.size)
        assertEquals(NavMapProjection.geometry(live(140.0), NavMapView.FOLLOW), geometries.last().second)
        now += 5_000
        publisher.flush()
        assertEquals(2, geometries.size)
        // A new activity is drawn from scratch, above every revision sent before.
        publisher.activityEnded()
        publisher.activityStarted()
        assertEquals(3, geometries.size)
        val revisions = geometries.map { it.first }
        assertEquals(revisions.sorted().distinct(), revisions)
    }

    @Test
    fun `a refused geometry is not queued, the next one is built from the current journey`() {
        geometryResult = NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        publisher.update(live(100.0), send = false)
        publisher.activityStarted()
        assertEquals(1, geometries.size)
        geometryResult = NexusSdkResult.SENT
        now += 200
        publisher.update(live(160.0))
        assertEquals(2, geometries.size)
        assertEquals(NavMapProjection.geometry(live(160.0), NavMapView.FOLLOW), geometries.last().second)
        // After a link change the current geometry is sent whole at once, whatever was sent before.
        now += 10
        publisher.linkChanged()
        assertEquals(3, geometries.size)
        assertEquals(geometries[1].second, geometries[2].second)
    }

    @Test
    fun `a drawn A revision cannot authorize disjoint B while publishing is throttled refused or awaiting draw`() {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.1, 2.0))
        val walk = NavRoute(NavDestination("There", points.last()), points, 10_000,
            listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "There")))
        fun snapshot(progress: Double) = NavRouteSnapshot(walk, progress, walk.line.pointAt(progress),
            now, quality = RouteQuality.TRACKING, atMs = now)
        val a = snapshot(1_000.0)
        val b = snapshot(10_000.0)
        // An identical clipped straight route can still describe a different geographic frame.
        assertEquals(NavMapProjection.geometry(a, NavMapView.FOLLOW), NavMapProjection.geometry(b, NavMapView.FOLLOW))
        val keysA = NavMapProjection.tiles(a, NavMapView.FOLLOW, OsmShortbreadSource)
        val keysB = NavMapProjection.tiles(b, NavMapView.FOLLOW, OsmShortbreadSource)
        assertTrue(keysA.isNotEmpty() && keysB.isNotEmpty())
        assertTrue(keysA.toSet().intersect(keysB.toSet()).isEmpty())
        val frames = NavRenderedFrames()
        val sent = mutableListOf<Long>()
        var result = NexusSdkResult.SENT
        val guarded = NavMapPublisher(supported = { true }, now = { now },
            sendGeometry = { revision, _ -> sent += revision; result }, sendHeading = { _, _ -> NexusSdkResult.SENT },
            onGeometrySent = { revision, state -> frames.remember(revision, state) })
        guarded.update(a, send = false)
        guarded.activityStarted()
        val acknowledgmentA = setOf(NexusRenderedMap(NexusRenderedMapView.FOLLOW, sent.last()))
        assertEquals(keysA, frames.keys(acknowledgmentA))
        now += 100
        guarded.update(b)
        assertEquals(1, sent.size)
        assertEquals(keysA, frames.keys(acknowledgmentA))
        now += 1_000
        result = NexusSdkResult.CAPABILITY_NOT_AVAILABLE
        guarded.flush()
        val rejected = sent.last()
        assertTrue(frames.keys(setOf(NexusRenderedMap(NexusRenderedMapView.FOLLOW, rejected))).isEmpty())
        assertEquals(keysA, frames.keys(acknowledgmentA))
        result = NexusSdkResult.SENT
        guarded.flush()
        assertEquals(keysA, frames.keys(acknowledgmentA))
        val acknowledgmentB = setOf(NexusRenderedMap(NexusRenderedMapView.FOLLOW, sent.last()))
        assertEquals(keysB, frames.keys(acknowledgmentB))
        assertTrue(frames.keys(emptySet()).isEmpty())
        frames.clear()
        assertTrue(frames.keys(acknowledgmentB).isEmpty())
    }

    @Test
    fun `acknowledged covers survive unrelated publications in either direction`() {
        for (pageId in listOf(null, NavPages.MAIN)) {
            val frames = NavRenderedFrames()
            val shown = live(100.0)
            frames.remember(1, shown, pageId)
            val acknowledged = setOf(NexusRenderedMap(NexusRenderedMapView.FOLLOW, 1, pageId))
            val cover = NavMapProjection.tiles(shown, NavMapView.FOLLOW, OsmShortbreadSource)
            assertTrue(cover.isNotEmpty())
            repeat(120) { index ->
                frames.remember(index + 2L, live(300.0), if (pageId == null) NavPages.MAIN else null)
                assertEquals(cover, frames.keys(acknowledged))
            }
            // Retention alone is never demand, and an abandoned publication is still bounded.
            assertTrue(frames.keys(emptySet()).isEmpty())
            repeat(48) { frames.remember(it + 1_000L, live(300.0), NavPages.MAIN) }
            assertTrue(frames.keys(acknowledged).isEmpty())
        }
    }

    @Test
    fun `publication pacing is independent per surface and follows a fresh page sequence`() {
        val frames = NavRenderedFrames()
        val a = NexusRenderedMap(NexusRenderedMapView.FOLLOW, 100)
        val p = NexusRenderedMap(NexusRenderedMapView.FOLLOW, 100, NavPages.MAIN)
        frames.remember(100, live(100.0))
        frames.remember(100, live(100.0), NavPages.MAIN)
        assertNull(frames.acknowledgmentDelayMs(setOf(a, p), 5_000))
        assertNull(frames.acknowledgmentDelayMs(setOf(a, p), 5_000, NavPages.MAIN))
        frames.remember(101, live(200.0), NavPages.MAIN)
        assertEquals(5_000L, frames.acknowledgmentDelayMs(setOf(a, p), 5_000, NavPages.MAIN))
        assertNull(frames.acknowledgmentDelayMs(setOf(a, p), 5_000))
        assertTrue(frames.acknowledgmentDelayMs(setOf(a), 5_000, NavPages.MAIN)!! > 0)
        assertNull(frames.acknowledgmentDelayMs(setOf(p.copy(revision = 101)), 5_000, NavPages.MAIN))
        // A recreated provider can start a new revision sequence after registration loss.
        frames.remember(1, live(300.0), NavPages.MAIN)
        assertNull(frames.acknowledgmentDelayMs(setOf(p.copy(revision = 1)), 5_000, NavPages.MAIN))
        frames.clear()
        assertNull(frames.acknowledgmentDelayMs(setOf(a), 5_000))
    }

    @Test
    fun `the first acknowledgment and expiry recovery hold only until a bounded deadline`() {
        val frames = NavRenderedFrames(now = { now })
        assertNull(frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
        frames.remember(1, live(100.0), NavPages.MAIN)
        assertEquals(5_000L, frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
        repeat(4) {
            now += 1_000
            assertEquals(4_000L - it * 1_000, frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
        }
        assertTrue("waiting is never tile authority", frames.keys(emptySet()).isEmpty())
        now += 1_000
        assertNull("missing feedback must allow a retry", frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
        frames.remember(2, live(200.0), NavPages.MAIN)
        now += 1_000
        assertEquals(4_000L, frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
        val drawn = setOf(NexusRenderedMap(NexusRenderedMapView.OVERVIEW, 2, NavPages.MAIN))
        assertNull("first positive feedback releases immediately", frames.acknowledgmentDelayMs(drawn, 5_000, NavPages.MAIN))
        frames.remember(3, live(300.0), NavPages.MAIN)
        assertEquals(4_000L, frames.acknowledgmentDelayMs(drawn, 4_000, NavPages.MAIN))
        now += 4_000
        assertEquals("expiry does not reopen the bootstrap race", 1_000L,
            frames.acknowledgmentDelayMs(emptySet(), null, NavPages.MAIN))
    }

    @Test
    fun `stale and missing positions are still drawn, degraded, with no heading`() {
        publisher.update(stale(100.0, bearing = 30), send = false)
        publisher.activityStarted()
        val stale = geometries.single().second.position!!
        assertEquals(RoutePositionQuality.STALE, stale.quality)
        assertEquals(42, stale.fixAgeS)
        now += 1_000
        publisher.update(NavRouteSnapshot(route, quality = RouteQuality.WAITING, atMs = now))
        val none = geometries.last().second.position!!
        assertEquals(RoutePositionQuality.NONE, none.quality)
        assertNull(none.point)
        assertTrue(headings.isEmpty())
    }

    @Test
    fun `a heading needs a fresh moving fix, once per fix and never above five a second`() {
        publisher.update(live(100.0, bearing = 90, bearingAccuracy = 12.0), send = false)
        publisher.activityStarted()
        assertEquals(listOf(90.0 to 12.0), headings)
        // The same fix again: nothing new to say.
        now += 300
        publisher.update(live(100.0, bearing = 90, fixAt = now - 300))
        assertEquals(1, headings.size)
        // A new fix goes out; one 100 ms after it waits, since 200 ms is the hub's five a second.
        now += 100
        publisher.update(live(105.0, bearing = 95, fixAt = now))
        assertEquals(2, headings.size)
        now += 100
        publisher.update(live(106.0, bearing = 96, fixAt = now))
        assertEquals(2, headings.size)
        now += 100
        publisher.update(live(107.0, bearing = 97, fixAt = now))
        assertEquals(3, headings.size)
        // A fix more than two seconds old, or with no bearing because the wearer stands still, sends none.
        now += 3_000
        publisher.update(live(108.0, bearing = 98, fixAt = now - 2_500))
        publisher.update(live(109.0, fixAt = now))
        assertEquals(3, headings.size)
        // An accuracy the hub would refuse is left out, not the heading.
        now += 1_000
        publisher.update(live(110.0, bearing = 100, bearingAccuracy = 400.0, fixAt = now))
        assertEquals(100.0 to null, headings.last())
    }

    private fun live(progress: Double, bearing: Int? = null, bearingAccuracy: Double? = null, fixAt: Long = now) = NavRouteSnapshot(
        route,
        progressMeters = progress,
        position = route.line.pointAt(progress),
        positionAtMs = fixAt,
        bearing = bearing,
        bearingAccuracyDeg = bearingAccuracy,
        quality = RouteQuality.TRACKING,
        atMs = now,
    )

    // A stale snapshot keeps a bearing here only to prove it is never used.
    private fun stale(progress: Double, bearing: Int) = NavRouteSnapshot(
        route,
        progressMeters = progress,
        position = route.line.pointAt(progress),
        positionAtMs = now - 42_000,
        bearing = bearing,
        quality = RouteQuality.STALE,
        atMs = now,
    )

    private fun straight(): NavRoute {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        return NavRoute(
            NavDestination("Destination", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", "Test Street"), RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Destination")),
        )
    }
}
