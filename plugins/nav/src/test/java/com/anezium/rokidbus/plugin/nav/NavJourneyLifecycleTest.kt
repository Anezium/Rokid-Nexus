package com.anezium.rokidbus.plugin.nav

import android.Manifest
import android.app.Application
import android.app.Service
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * A walking journey is the wearer's own: it owns the route against notification guidance while it
 * runs, starts nothing without precise location, and ends on Stop, End from the glasses, arrival,
 * permission loss and a failed plan, which is never retried. Its street tiles are asked for only
 * while one of its maps is on the glasses, and never once it has ended.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NavJourneyLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val defaultBasemap = NavJourneyService.newBasemap
    private val runtimes = mutableListOf<NavRuntime>()
    private val services = mutableListOf<NavJourneyService>()
    private var listener: NavNotificationListener? = null

    @After
    fun cleanUp() {
        services.forEach { it.onDestroy() }
        runtimes.forEach {
            it.shutdown()
            NavControl.detachJourney(it)
        }
        listener?.let(NavControl::detach)
        idle(0)
        NavJourneyService.newPlanner = { TransitousPlanner() }
        NavJourneyService.newBasemap = defaultBasemap
        NavState.routePageVisible = false
        NavState.basemapState = BasemapState.NONE
        NavState.journey = null
        NavState.guidance = null
        NavState.journeyMessage = null
        NavState.journeyDestination = null
    }

    @Test
    fun `notification callbacks and listener shutdown cannot replace a walking journey`() {
        val listenerService = Robolectric.buildService(NavNotificationListener::class.java).get().also { listener = it }
        val notifications = NavRuntime(app).also(runtimes::add)
        NavControl.attach(listenerService, notifications)
        notifications.onGuidance(guidance(NavSource.GOOGLE_MAPS, "old notification"))
        assertEquals("old notification", NavState.guidance?.primary)

        val journey = NavRuntime(app).also(runtimes::add)
        NavControl.attachJourney(journey) {}
        // The notification route ended the moment the journey took over.
        assertNull(NavState.guidance)
        journey.onGuidance(guidance(NavSource.NEXUS, "walking"))
        notifications.onGuidance(guidance(NavSource.GOOGLE_MAPS, "new notification"))
        notifications.onRouteEnded(NavSource.GOOGLE_MAPS)
        notifications.shutdown()
        assertEquals(NavSource.NEXUS, NavState.guidance?.source)
        assertEquals("walking", NavState.guidance?.primary)
        assertTrue(NavControl.journeyActive)
        assertTrue(NavControl.owns(journey))
        assertFalse(NavControl.owns(notifications))

        // Once the journey is over, notification guidance owns the route again.
        journey.shutdown(releaseBus = false)
        NavControl.detachJourney(journey)
        assertFalse(NavControl.journeyActive)
        assertTrue(NavControl.owns(notifications))
        notifications.onGuidance(guidance(NavSource.GOOGLE_MAPS, "after"))
        assertEquals("after", NavState.guidance?.primary)
    }

    @Test
    fun `without precise location no journey starts and no location is asked for`() {
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        val service = Robolectric.buildService(NavJourneyService::class.java).create().get().also(services::add)
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(startIntent(), 0, 1))
        assertFalse(NavControl.journeyActive)
        assertNull(NavState.journey)
        assertEquals(NavJourneyService.MESSAGE_PERMISSION_NEEDED, NavState.journeyMessage)
        assertTrue(shadowOf(app.getSystemService(LocationManager::class.java)).getLocationUpdateListeners().isEmpty())
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `an intent that is not a start starts nothing`() {
        grantLocation()
        val service = Robolectric.buildService(NavJourneyService::class.java).create().get().also(services::add)
        service.onStartCommand(Intent(app, NavJourneyService::class.java), 0, 1)
        assertFalse(NavControl.journeyActive)
        assertNull(shadowOf(service).lastForegroundNotification)
    }

    @Test
    fun `Stop releases foreground location and ignores fixes that come after`() {
        val service = startJourney()
        assertTrue(NavControl.journeyActive)
        assertEquals("Test destination", NavState.journeyDestination)
        ReflectionHelpers.setField(service, "tracker", NavRouteTracker(route()))
        service.onStartCommand(Intent(app, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_STOP), 0, 2)
        assertFalse(NavControl.journeyActive)
        assertNull(NavState.journey)
        assertNull(NavState.journeyDestination)
        assertEquals(NavJourneyService.MESSAGE_STOPPED, NavState.journeyMessage)
        assertNull(shadowOf(service).lastForegroundNotification)
        assertTrue(shadowOf(app.getSystemService(LocationManager::class.java)).getLocationUpdateListeners().isEmpty())
        service.onLocationChanged(fix(48.0, 2.0))
        assertNull(NavState.journey)
    }

    @Test
    fun `End from the glasses stops the running journey, and only a running one`() {
        assertFalse(NavControl.stopJourney("Journey ended from the glasses"))
        startJourney()
        assertTrue(NavControl.stopJourney("Journey ended from the glasses"))
        idle(0)
        assertFalse(NavControl.journeyActive)
        assertEquals("Journey ended from the glasses", NavState.journeyMessage)
    }

    @Test
    fun `arrival ends the journey a moment after two fixes at the destination`() {
        val service = startJourney()
        ReflectionHelpers.setField(service, "tracker", NavRouteTracker(route()))
        service.onLocationChanged(fix(48.001, 2.0))
        assertEquals(RouteQuality.TRACKING, NavState.journey?.quality)
        idle(1_000)
        service.onLocationChanged(fix(48.001, 2.0))
        assertEquals(RouteQuality.ARRIVED, NavState.journey?.quality)
        assertTrue(NavControl.journeyActive)
        idle(NavJourneyService.STOP_AFTER_ARRIVAL_MS + NavJourneyService.TICK_MS)
        assertFalse(NavControl.journeyActive)
        assertEquals("Arrived", NavState.journeyMessage)
        assertTrue(shadowOf(service).isStoppedBySelf)
    }

    @Test
    fun `losing location permission ends the journey at the next check`() {
        startJourney()
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        idle(NavJourneyService.TICK_MS)
        assertFalse(NavControl.journeyActive)
        assertEquals(NavJourneyService.MESSAGE_PERMISSION_LOST, NavState.journeyMessage)
    }

    @Test
    fun `a failed plan ends the journey and is never retried`() {
        val attempts = AtomicInteger()
        NavJourneyService.newPlanner = {
            TransitousPlanner(fetch = { _, _ ->
                attempts.incrementAndGet()
                error("Transitous is busy. Try again later.")
            })
        }
        val service = startJourney()
        service.onLocationChanged(fix(48.0, 2.0))
        assertEquals(NavJourneyService.MESSAGE_PLANNING, NavState.journeyMessage)
        val deadline = System.currentTimeMillis() + 5_000
        while (NavControl.journeyActive && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle(0)
        }
        assertFalse(NavControl.journeyActive)
        assertTrue(NavState.journeyMessage!!.startsWith("Could not plan"))
        service.onLocationChanged(fix(48.0, 2.0))
        idle(NavJourneyService.TICK_MS)
        assertEquals(1, attempts.get())
    }

    @Test
    fun `a planned journey asks for street tiles only while a map is on the glasses, and never after Stop`() {
        val streets = StreetRecorder()
        val service = planParisJourney(streets)
        // Planned, but no activity map can be drawn and no route page is up: nothing is asked for.
        idle(NavJourneyService.TICK_MS)
        assertTrue(streets.urls.isEmpty())
        assertEquals(BasemapState.NONE, NavState.basemapState)

        NavState.routePageVisible = true
        NavControl.mapDemandChanged()
        idle(0)
        assertTrue(streets.urls.isNotEmpty())
        assertTrue(streets.urls.all { it.startsWith("https://vector.openstreetmap.org/shortbread_v1/") })
        // Follow and overview each stay within their own few tiles.
        assertTrue(streets.urls.size <= NavBasemapLayer.FOLLOW_MAX_TILES + NavBasemapLayer.OVERVIEW_MAX_TILES)
        idle(0)
        assertTrue(NavState.journey!!.basemap.tiles.isNotEmpty())
        assertEquals(BasemapState.READY, NavState.basemapState)
        val asked = streets.urls.size

        // The page goes: no new tile is asked for, whatever the wearer walks past.
        NavState.routePageVisible = false
        NavControl.mapDemandChanged()
        idle(NavJourneyService.TICK_MS * 3)
        assertEquals(asked, streets.urls.size)

        service.onStartCommand(Intent(app, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_STOP), 0, 2)
        assertTrue(streets.executor.isShutdown)
        assertEquals(BasemapState.NONE, NavState.basemapState)
        NavState.routePageVisible = true
        NavControl.mapDemandChanged()
        idle(NavJourneyService.TICK_MS)
        assertEquals(asked, streets.urls.size)
    }

    @Test
    fun `losing location permission closes the street loader with the journey`() {
        val streets = StreetRecorder()
        planParisJourney(streets)
        NavState.routePageVisible = true
        NavControl.mapDemandChanged()
        idle(0)
        val asked = streets.urls.size
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        idle(NavJourneyService.TICK_MS)
        assertFalse(NavControl.journeyActive)
        assertTrue(streets.executor.isShutdown)
        NavControl.mapDemandChanged()
        idle(NavJourneyService.TICK_MS)
        assertEquals(asked, streets.urls.size)
    }

    /** Records every street tile request a journey makes, and answers each with a small real tile. */
    private class StreetRecorder {
        val urls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val executor = object : java.util.concurrent.AbstractExecutorService() {
            @Volatile private var shut = false
            override fun execute(command: Runnable) {
                if (shut) throw java.util.concurrent.RejectedExecutionException()
                command.run()
            }
            override fun shutdown() { shut = true }
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }
            override fun isShutdown() = shut
            override fun isTerminated() = shut
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        }
        private val tile = MvtFixtures.gzip(
            MvtFixtures.shortbread(listOf(MvtFixtures.line(0 to 2048, 4096 to 2048, tags = mapOf("kind" to "residential")))),
        )
        val transport = TileTransport { url, _, _ ->
            object : TileCall {
                override fun execute(): TileResponse {
                    urls += url
                    return TileResponse(200, mapOf("cache-control" to "max-age=300"), tile)
                }

                override fun cancel() = Unit
            }
        }
    }

    private fun planParisJourney(streets: StreetRecorder): NavJourneyService {
        val plan = javaClass.getResourceAsStream("/transitous-public-paris-walk.json")!!.bufferedReader().use { it.readText() }
        NavJourneyService.newPlanner = { TransitousPlanner(fetch = { _, _ -> plan }) }
        NavJourneyService.newBasemap = { _, line, post, onChanged ->
            NavBasemap(OsmShortbreadSource, line, cache = null, transport = streets.transport, post = post, onChanged = onChanged, executor = streets.executor, elapsed = SystemClock::elapsedRealtime)
        }
        grantLocation()
        val service = Robolectric.buildService(NavJourneyService::class.java).create().get().also(services::add)
        service.onStartCommand(
            Intent(app, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_START)
                .putExtra(NavJourneyService.EXTRA_NAME, "Paris City Hall")
                .putExtra(NavJourneyService.EXTRA_LAT, 48.8566)
                .putExtra(NavJourneyService.EXTRA_LON, 2.3522),
            0,
            1,
        )
        service.onLocationChanged(fix(48.853, 2.349))
        val deadline = System.currentTimeMillis() + 5_000
        while (NavState.journey == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(10)
            idle(0)
        }
        assertTrue(NavState.journey != null)
        return service
    }

    private fun startJourney(): NavJourneyService {
        grantLocation()
        val service = Robolectric.buildService(NavJourneyService::class.java).create().get().also(services::add)
        service.onStartCommand(startIntent(), 0, 1)
        return service
    }

    private fun grantLocation() {
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
    }

    private fun idle(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun startIntent() = Intent(app, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_START)
        .putExtra(NavJourneyService.EXTRA_NAME, "Test destination")
        .putExtra(NavJourneyService.EXTRA_LAT, 48.001)
        .putExtra(NavJourneyService.EXTRA_LON, 2.0)

    private fun fix(lat: Double, lon: Double) = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = lat
        longitude = lon
        accuracy = 5f
        time = System.currentTimeMillis()
        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
    }

    private fun route(): NavRoute {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.001, 2.0))
        return NavRoute(
            NavDestination("Test destination", points.last()),
            points,
            90,
            listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Test destination")),
        )
    }

    private fun guidance(source: NavSource, primary: String) = NavGuidance(source, "route", primary, stepKey = primary)
}
