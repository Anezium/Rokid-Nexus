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
 * permission loss and a failed plan, which is never retried.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NavJourneyLifecycleTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
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
