package com.anezium.rokidbus.plugin.nav

import android.Manifest
import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.RenderedMapContext
import com.anezium.rokidbus.shared.RenderedMapContract
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RoutePathRole
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration
import java.util.Collections

/**
 * A journey through the real runtime, plugin service and SDK client: its text always goes out
 * first, and its map and heading follow only when both hubs negotiated the launcher bundle.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
@LooperMode(LooperMode.Mode.PAUSED)
class NavJourneyHubTest {
    private class FakeHub : IBusService.Stub() {
        val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
        var callback: IBusCallback? = null
        var features = BusCapabilityBits.ACTIVITY_SURFACE
        var link = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        private var nextId = 0

        override fun apiVersion(): Int = 3
        override fun register(clientId: String, pathPrefixes: Array<String>, cb: IBusCallback) = Unit
        override fun unregister(cb: IBusCallback) = Unit
        override fun send(path: String, id: String, payload: ByteArray) {
            sent += path to JSONObject(String(payload, Charsets.UTF_8))
        }
        override fun linkState(): Int = link
        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) = Unit
        override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int {
            callback = cb
            deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"))
            deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, 1)
                .put("launcherInteractiveVersion", if (features and BusCapabilityBits.LAUNCHER_INTERACTIVE != 0) 1 else 0))
            return PluginRegistrationResult.APPROVED
        }
        override fun capabilities(): Int = features
        override fun approvedCapabilities(pluginId: String): String = "surfaces"

        fun deliver(path: String, payload: JSONObject) {
            checkNotNull(callback).onMessage(path, "hub-${nextId++}", payload.put("pluginId", "nav").toString().toByteArray(Charsets.UTF_8))
        }

        fun activityTraffic(): List<Pair<String, JSONObject>> = synchronized(sent) { sent.filter { it.first.startsWith("/activity/") } }
    }

    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private var service: ServiceController<NavPluginService>? = null
    private var journey: NavRuntime? = null
    private var walking: ServiceController<NavJourneyService>? = null
    private var rejectedFeedback = 0

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        val packages = shadowOf(app.packageManager)
        val hubComponent = ComponentName("com.anezium.rokidbus.phone", "com.anezium.rokidbus.phone.BusHubService")
        packages.addServiceIfNotPresent(hubComponent)
        packages.addIntentFilterForService(hubComponent, IntentFilter(BusConstants.ACTION_HUB))
        shadowOf(app).setComponentNameAndServiceForBindService(hubComponent, hub)
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val own = ComponentName(app, NavPluginService::class.java)
        packages.addOrUpdateService(
            ServiceInfo().apply {
                packageName = own.packageName
                name = own.className
                exported = true
                applicationInfo = app.applicationInfo
                metaData = Bundle().apply {
                    putString("com.anezium.rokidbus.plugin.ID", "nav")
                    putString("com.anezium.rokidbus.plugin.DISPLAY_NAME", "Navigation")
                    putString("com.anezium.rokidbus.plugin.API_VERSION", "3")
                    putString("com.anezium.rokidbus.plugin.CAPABILITIES", "surfaces")
                    putString("com.anezium.rokidbus.plugin.RECEIVE_PREFIXES", "/plugin/nav,/system/plugin")
                    putString("com.anezium.rokidbus.plugin.LAUNCHABLE", "true")
                    putString("rokidbus.plugin.pages", "1")
                }
            },
        )
    }

    @After
    fun tearDown() {
        walking?.destroy()
        journey?.let {
            it.shutdown()
            NavControl.detachJourney(it)
        }
        service?.destroy()
        NavControl.mapDemandListener = null
        NavState.guidance = null
        NavState.journey = null
        idleFor(0)
    }

    @Test
    fun `streets survive thirty seconds of page and ambient publications and return home`() {
        val requests = startWalkingJourney()
        // Home publishes and acknowledges the PANEL island before the route page opens.
        repeat(25) {
            move(it)
            feedback("ambient")
            idleFor(1_000)
        }
        assertStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))
        openPage()
        val activityPublications = hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY }
        repeat(35) { second ->
            move(second + 25)
            feedback("ambient")
            idleFor(500)
            feedback("page")
            idleFor(500)
            assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))
            assertTrue("page second $second", NavState.journey!!.basemap.tiles.isNotEmpty())
        }
        assertTrue(hub.sent.count { it.first == BusPaths.PAGE_RESPONSE } >= 30)
        assertTrue(hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY } - activityPublications >= 30)
        hub.deliver(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", NavPages.MAIN)
            .put("sessionGeneration", 1L).put("frameIndex", 1).put("reason", "back")
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        feedback("ambient")
        idleFor(0)
        assertStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))
        assertEquals("stationary covers reuse loaded tiles", requests.distinct().size, requests.size)
    }

    @Test
    fun `Hidden Text Commands and page close cancel only their own rendered demand`() {
        val requests = startWalkingJourney()
        move(0)
        feedback("ambient")
        idleFor(1_000)
        openPage()
        feedback("page")
        for (presentation in listOf("Hidden", "Text")) {
            feedback("ambient", visible = false)
            assertEquals(presentation, setOf(NavPages.MAIN), NavControl.serviceClient!!.renderedMapFrames.map { it.pageId }.toSet())
            assertTrue(NavState.journey!!.basemap.tiles.isNotEmpty())
            feedback("ambient")
        }
        // Commands removes the map while its page lease remains visible.
        feedback("page", visible = false)
        assertEquals(setOf(null), NavControl.serviceClient!!.renderedMapFrames.map { it.pageId }.toSet())
        feedback("page")
        hub.deliver(BusPaths.PAGE_VISIBILITY, JSONObject().put("pageId", NavPages.MAIN).put("visible", false)
            .put("sessionGeneration", 1L).put("frameIndex", 1)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        idleFor(0)
        assertEquals(setOf(null), NavControl.serviceClient!!.renderedMapFrames.map { it.pageId }.toSet())
        // A fresh drawn page still has independent demand when the island goes away.
        hub.deliver(BusPaths.PAGE_VISIBILITY, JSONObject().put("pageId", NavPages.MAIN).put("visible", true)
            .put("sessionGeneration", 1L).put("frameIndex", 1).put("leaseDurationMs", PageSurfaceContract.LEASE_MS)
            .put("leaseUntilMs", SystemClock.elapsedRealtime() + PageSurfaceContract.LEASE_MS)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        idleFor(0)
        feedback("page")
        feedback("ambient", visible = false)
        assertTrue(NavState.journey!!.basemap.tiles.isNotEmpty())
        hub.deliver(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", NavPages.MAIN)
            .put("sessionGeneration", 1L).put("frameIndex", 1).put("reason", "back")
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        idleFor(0)
        assertTrue(NavControl.serviceClient!!.renderedMapFrames.isEmpty())
        assertTrue(NavState.journey!!.basemap.tiles.isEmpty())
        val asked = requests.size
        walking!!.get().onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 48.03
            longitude = 2.0
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos() + 1_000_000
        })
        idleFor(NavJourneyService.TICK_MS * 2)
        assertEquals(asked, requests.size)
        assertTrue(NavState.journey!!.basemap.tiles.isEmpty())
    }

    @Test
    fun `the first Follow page report survives one Hz fixes before any map lease exists`() {
        assertColdPageFeedback("follow")
    }

    @Test
    fun `the first Overview page report survives one Hz fixes before any map lease exists`() {
        assertColdPageFeedback("overview")
    }

    @Test
    fun `a new maneuver bypasses unacknowledged page and ambient holds`() {
        assertSignificantChange { it.copy(progressMeters = 230.0, position = it.route.line.pointAt(230.0)) }
    }

    @Test
    fun `off route bypasses unacknowledged page and ambient holds`() {
        assertSignificantChange { it.copy(quality = RouteQuality.OFF_ROUTE) }
    }

    @Test
    fun `arrival bypasses unacknowledged page and ambient holds`() {
        assertSignificantChange { it.copy(quality = RouteQuality.ARRIVED) }
    }

    @Test
    fun `a replacement route bypasses unacknowledged page and ambient holds`() {
        assertSignificantChange {
            val points = listOf(GeoPoint(48.0, 2.0001), it.route.points.last())
            val route = NavRoute(it.route.destination, points, 400,
                listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Destination")))
            it.copy(route = route)
        }
    }

    private fun assertSignificantChange(change: (NavRouteSnapshot) -> NavRouteSnapshot) {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE or BusCapabilityBits.RENDERED_MAP
        startJourney(bearing = null)
        openPage()
        val initial = NavState.journey!!
        val pageBefore = latest(BusPaths.PAGE_RESPONSE).getLong("revision")
        val geometryBefore = latest(BusPaths.ACTIVITY_GEOMETRY).getLong("revision")
        idleFor(1_000)
        val quiet = initial.copy(progressMeters = 110.0, position = initial.route.line.pointAt(110.0),
            atMs = SystemClock.elapsedRealtime(), positionAtMs = SystemClock.elapsedRealtime())
        NavState.journey = quiet
        journey!!.onJourney(quiet)
        idleFor(0)
        assertEquals(pageBefore, latest(BusPaths.PAGE_RESPONSE).getLong("revision"))
        assertEquals(geometryBefore, latest(BusPaths.ACTIVITY_GEOMETRY).getLong("revision"))
        val significant = change(quiet)
        NavState.journey = significant
        journey!!.onJourney(significant)
        idleFor(0)
        assertTrue("significant page must not wait for an acknowledgment",
            latest(BusPaths.PAGE_RESPONSE).getLong("revision") > pageBefore)
        assertTrue("significant geometry must not wait for an acknowledgment",
            latest(BusPaths.ACTIVITY_GEOMETRY).getLong("revision") > geometryBefore)
        assertEquals(NavMapProjection.geometry(significant, NavMapView.FOLLOW),
            RouteGeometryContract.parseOrNull(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry")))
        assertTrue("bypassing a hold cannot authorize map demand", NavControl.serviceClient!!.renderedMapFrames.isEmpty())
    }

    @Test
    fun `Overview recovers when the return delay exceeds both five and ten seconds`() {
        val requests = startWalkingJourney()
        move(0)
        openPage()
        var acknowledgedAt: Int? = null
        var streetsDrawn = false
        repeat(35) { second ->
            move(second)
            feedback("page", delayMs = 10_500, view = "overview")
            idleFor(1_000)
            streetsDrawn = streetsDrawn || hasStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("overview"))
            if (NavControl.serviceClient!!.renderedMapFrames.any { it.pageId == NavPages.MAIN }) {
                if (acknowledgedAt == null) acknowledgedAt = second + 1
            } else if (acknowledgedAt == null) {
                assertTrue("a retry window grants no street demand", requests.isEmpty())
            }
        }
        assertTrue("a twenty-second retry window must admit the delayed draw, got $acknowledgedAt",
            acknowledgedAt != null && acknowledgedAt!! <= 27)
        assertTrue("a real acknowledgment starts street requests", requests.isNotEmpty())
        assertTrue("exercise exact-revision rejections before recovery", rejectedFeedback > 0)
        assertTrue("the accepted draw must publish loaded streets", streetsDrawn)
    }

    @Test
    fun `page and ambient feedback recover through a loaded serial phone queue`() {
        val requests = startWalkingJourney()
        move(0)
        openPage()
        var nextReceive = SystemClock.elapsedRealtime() + 10_000L
        val drawn = mutableMapOf<String, Long>()
        var firstAck: Int? = null
        var maxDelay = 0L
        repeat(60) { second ->
            move(second)
            for (kind in listOf("ambient", "page")) {
                val revision = latest(if (kind == "page") BusPaths.PAGE_RESPONSE else BusPaths.ACTIVITY_GEOMETRY).getLong("revision")
                // A draw reports a changed revision; a stable map renews every two seconds.
                if (drawn[kind] != revision || second % 2 == 0) {
                    drawn[kind] = revision
                    val at = SystemClock.elapsedRealtime()
                    nextReceive = maxOf(nextReceive, at) + 730L
                    val delay = nextReceive - at
                    maxDelay = maxOf(maxDelay, delay)
                    feedback(kind, delayMs = delay, view = if (kind == "page") "overview" else "follow")
                }
            }
            idleFor(1_000)
            if (firstAck == null && NavControl.serviceClient!!.renderedMapFrames.any { it.pageId == NavPages.MAIN }) {
                firstAck = second + 1
            }
        }
        assertTrue("model a return queue longer than the initial retry window", maxDelay > RenderedMapContract.LEASE_MS)
        assertTrue("the loaded queue must admit a page acknowledgment, got $firstAck", firstAck != null && firstAck!! <= 35)
        assertTrue(requests.isNotEmpty())
        assertTrue("the phone must reject superseded reports", rejectedFeedback > 0)
        assertTrue("coalesced publications keep flowing", hub.sent.count { it.first == BusPaths.PAGE_RESPONSE } >= 5)
        assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("overview"))
    }

    private fun assertColdPageFeedback(view: String) {
        val requests = startWalkingJourney()
        move(0)
        openPage()
        val initialRevision = latest(BusPaths.PAGE_RESPONSE).getLong("revision")
        repeat(35) { second ->
            move(second)
            // No immediate seed acknowledgment: even the first report returns after two fixes.
            feedback("page", delayMs = 2_500, view = view)
            idleFor(1_000)
            if (second < 2) {
                assertEquals("do not supersede the unacknowledged first page", initialRevision,
                    latest(BusPaths.PAGE_RESPONSE).getLong("revision"))
                assertTrue("a quiet publication window authorizes no requests", requests.isEmpty())
            }
            if (second >= 7) {
                assertTrue("the selected page keeps a live lease at second $second",
                    NavControl.serviceClient!!.renderedMapFrames.any { it.pageId == NavPages.MAIN && it.view.name.equals(view, true) })
                assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject(view))
            }
        }
        assertTrue("first delayed feedback must start street demand", requests.isNotEmpty())
        assertTrue("coalesced updates keep flowing", hub.sent.count { it.first == BusPaths.PAGE_RESPONSE } >= 10)
        assertTrue("the page alone authorizes demand", NavControl.serviceClient!!.renderedMapFrames.none { it.pageId == null })
    }

    @Test
    fun `a missing first page report retries the latest fix at its deadline without another event`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE or BusCapabilityBits.RENDERED_MAP
        startJourney(bearing = null)
        openPage()
        val before = latest(BusPaths.PAGE_RESPONSE).getLong("revision")
        idleFor(1_000)
        val initial = NavState.journey!!
        val moved = initial.copy(progressMeters = 130.0, position = initial.route.line.pointAt(130.0),
            atMs = SystemClock.elapsedRealtime(), positionAtMs = SystemClock.elapsedRealtime())
        NavState.journey = moved
        journey!!.onJourney(moved)
        idleFor(0)
        val wait = NavControl.mapPublicationDelayMs(NavPages.MAIN)
        assertEquals(4_000L, wait)
        idleFor(checkNotNull(wait) - 1)
        assertEquals(before, latest(BusPaths.PAGE_RESPONSE).getLong("revision"))
        idleFor(1)
        assertTrue(latest(BusPaths.PAGE_RESPONSE).getLong("revision") > before)
        val received = RouteGeometryContract.parseOrNull(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))!!
        assertEquals(NavMapProjection.geometry(moved, NavMapView.FOLLOW).paths, received.paths)
        assertTrue("retrying a publication grants no map lease", NavControl.serviceClient!!.renderedMapFrames.isEmpty())
    }

    @Test
    fun `the first ambient report survives one Hz fixes without an immediate seed lease`() {
        val requests = startWalkingJourney()
        move(0)
        val first = latest(BusPaths.ACTIVITY_GEOMETRY).getLong("revision")
        repeat(12) { second ->
            move(second)
            feedback("ambient", delayMs = 1_500)
            idleFor(1_000)
            if (second == 0) {
                assertEquals(first, latest(BusPaths.ACTIVITY_GEOMETRY).getLong("revision"))
                assertTrue(requests.isEmpty())
            }
            if (second >= 4) assertStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))
        }
        assertTrue(requests.isNotEmpty())
        assertTrue(NavControl.serviceClient!!.renderedMapFrames.any { it.pageId == null })
    }

    @Test
    fun `feedback delayed behind newer revisions cannot starve a drawn page of streets`() {
        startWalkingJourney()
        move(0)
        feedback("ambient")
        idleFor(1_000)
        openPage()
        feedback("page")
        val opened = SystemClock.elapsedRealtime()
        val pagePublications = hub.sent.count { it.first == BusPaths.PAGE_RESPONSE }
        val geometryPublications = hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY }
        repeat(35) { second ->
            move(second + 1)
            // The capture's return path grows from near zero to over one publication interval.
            val delay = (second * 200L).coerceAtMost(1_500L)
            feedback("ambient", delayMs = delay)
            idleFor(500)
            feedback("page", delayMs = delay)
            idleFor(500)
            assertTrue("streets lost ${SystemClock.elapsedRealtime() - opened} ms after page open",
                NavState.journey!!.basemap.tiles.isNotEmpty())
            assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))
        }
        assertTrue("exercise the phone's current-revision gate", rejectedFeedback > 0)
        assertTrue("page updates must keep flowing under delay",
            hub.sent.count { it.first == BusPaths.PAGE_RESPONSE } - pagePublications >= 16)
        assertTrue("geometry updates must keep flowing under delay",
            hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY } - geometryPublications >= 16)
        // A new fix replaces the held snapshot and reaches the page within the next round trip.
        feedback("page")
        idleFor(NavPageProvider.MIN_INTERVAL_MS)
        val before = latest(BusPaths.PAGE_RESPONSE).getLong("revision")
        move(100, latitudeOffset = 0.0001)
        val expected = NavMapProjection.geometry(NavState.journey!!, NavMapView.FOLLOW).paths.filter { it.role == RoutePathRole.ROUTE }
        feedback("page", delayMs = 1_500L)
        idleFor(1_500L)
        assertTrue("latest fix published within one return trip", latest(BusPaths.PAGE_RESPONSE).getLong("revision") > before)
        val received = RouteGeometryContract.parseOrNull(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))!!
        assertEquals(expected, received.paths.filter { it.role == RoutePathRole.ROUTE })
        feedback("page", visible = false)
        feedback("ambient")
        idleFor(NavMapPublisher.MIN_GEOMETRY_INTERVAL_MS)
        assertStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))
    }

    @Test
    fun `a dirty held page catches up from reconciled streets in the acknowledging turn`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE or BusCapabilityBits.RENDERED_MAP
        startJourney(bearing = null)
        val runtime = journey!!
        val initial = NavState.journey!!
        val tiles = NavMapProjection.tiles(initial, NavMapView.FOLLOW, NavJourneyService.BASEMAP_SOURCE).associateWith { key ->
            LocalTile(key, listOf(LocalLine(StreetClass.STREET, doubleArrayOf(-200.0, 200.0), doubleArrayOf(30.0, 30.0))), emptyList())
        }
        val streets = NavBasemapTiles(OsmShortbreadSource.attribution, OsmShortbreadSource.maxZoom, tiles)
        openPage()
        feedback("page")
        idleFor(1_000)
        fun update(progress: Double) {
            val snapshot = initial.copy(progressMeters = progress, position = initial.route.line.pointAt(progress),
                atMs = SystemClock.elapsedRealtime(), positionAtMs = SystemClock.elapsedRealtime())
            NavState.journey = snapshot
            runtime.onJourney(snapshot)
            idleFor(0)
        }
        update(110.0)
        idleFor(NavPageProvider.MIN_INTERVAL_MS)
        update(120.0)
        val before = hub.sent.count { it.first == BusPaths.PAGE_RESPONSE }
        NavControl.mapDemandListener = {
            android.os.Handler(Looper.getMainLooper()).post {
                val restored = NavState.journey!!.copy(basemap = streets)
                NavState.journey = restored
                NavState.basemapState = BasemapState.READY
                runtime.onJourney(restored)
            }
        }
        feedback("page")
        val released = hub.sent.filter { it.first == BusPaths.PAGE_RESPONSE }.drop(before)
        assertEquals("release only the reconciled page", 1, released.size)
        assertStreets(released.single().second.getJSONObject("body").getJSONObject("follow"))
    }

    @Test
    fun `a lost ambient ack retries the latest map at the acknowledgment deadline without another journey event`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE or BusCapabilityBits.RENDERED_MAP
        startJourney(bearing = null)
        feedback("ambient")
        idleFor(1_000)
        val runtime = journey!!
        val initial = NavState.journey!!
        fun update(progress: Double): NavRouteSnapshot {
            val snapshot = initial.copy(progressMeters = progress, position = initial.route.line.pointAt(progress),
                atMs = SystemClock.elapsedRealtime(), positionAtMs = SystemClock.elapsedRealtime())
            runtime.onJourney(snapshot)
            idleFor(0)
            return snapshot
        }
        update(110.0)
        val latestFix = update(130.0)
        idleFor(NavMapPublisher.MIN_GEOMETRY_INTERVAL_MS)
        val before = hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY }
        val remaining = NavControl.serviceClient!!.renderedMapLeaseRemainingMs!!
        idleFor(remaining - 1)
        assertEquals(before, hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY })
        idleFor(1)
        // Expiry cannot supersede a replacement which is still inside its first-ack window.
        val recoveryWait = NavControl.mapPublicationDelayMs() ?: 0L
        if (recoveryWait > 0) {
            idleFor(recoveryWait - 1)
            assertEquals(before, hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY })
            idleFor(1)
        }
        assertEquals(before + 1, hub.sent.count { it.first == BusPaths.ACTIVITY_GEOMETRY })
        val received = RouteGeometryContract.parseOrNull(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))!!
        val expected = NavMapProjection.geometry(latestFix, NavMapView.FOLLOW)
        assertEquals(expected.paths, received.paths)
    }

    @Test
    fun `feedback restores loaded streets in the runtime and page before another fix or tick`() {
        val requests = startWalkingJourney()
        move(0)
        feedback("ambient")
        idleFor(1_000)
        openPage()
        feedback("page")
        feedback("ambient", visible = false)
        idleFor(1_000)
        assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))
        // Demand is withdrawn immediately; an unacknowledged publication may wait for its deadline.
        feedback("page", visible = false)
        idleFor(0)
        assertTrue(NavState.journey!!.basemap.tiles.isEmpty())
        val asked = requests.size
        idleFor(RenderedMapContract.LEASE_MS)
        assertEquals("waiting without feedback requests no tiles", asked, requests.size)
        assertFalse(hasStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow")))
        assertFalse(hasStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry")))
        idleFor(100)
        feedback("page")
        idleFor(NavPageProvider.MIN_INTERVAL_MS)
        assertStreets(latest(BusPaths.PAGE_RESPONSE).getJSONObject("body").getJSONObject("follow"))
        // Returning to the island acknowledges its pending route-only publication as well.
        feedback("ambient")
        idleFor(NavMapPublisher.MIN_GEOMETRY_INTERVAL_MS - NavPageProvider.MIN_INTERVAL_MS - 100)
        assertStreets(latest(BusPaths.ACTIVITY_GEOMETRY).getJSONObject("geometry"))
        val published = hub.sent.count { it.first == BusPaths.PAGE_RESPONSE }
        feedback("page")
        idleFor(0)
        assertEquals(published, hub.sent.count { it.first == BusPaths.PAGE_RESPONSE })
        assertEquals(asked, requests.size)
    }

    private fun latest(path: String): JSONObject = synchronized(hub.sent) { hub.sent.last { it.first == path }.second }

    private fun hasStreets(geometry: JSONObject): Boolean = RouteGeometryContract.parseOrNull(geometry)!!.paths.any {
        it.role == RoutePathRole.STREET || it.role == RoutePathRole.MAJOR
    }

    private fun assertStreets(geometry: JSONObject) {
        assertTrue(geometry.toString(), hasStreets(geometry))
        assertEquals(OsmShortbreadSource.attribution, geometry.getString("attribution"))
    }

    private fun openPage() {
        hub.deliver(BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, NavPages.MAIN))
                .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        idleFor(0)
        hub.deliver(BusPaths.PAGE_VISIBILITY, JSONObject().put("pageId", NavPages.MAIN).put("visible", true)
            .put("sessionGeneration", 1L).put("frameIndex", 1).put("leaseDurationMs", PageSurfaceContract.LEASE_MS)
            .put("leaseUntilMs", SystemClock.elapsedRealtime() + PageSurfaceContract.LEASE_MS)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        idleFor(0)
    }

    private fun feedback(kind: String, visible: Boolean = true, delayMs: Long = 0, view: String = "follow") {
        val revision = latest(if (kind == "page") BusPaths.PAGE_RESPONSE else BusPaths.ACTIVITY_GEOMETRY).getLong("revision")
        val context = RenderedMapContext(kind, if (kind == "page") NavPages.MAIN else "nav:route", revision,
            seq = 1, sessionGeneration = 1, frameIndex = 1, requestId = "r1")
        val deliver = Runnable {
            // Both phone routers reject a positive acknowledgment of a superseded publication.
            val current = latest(if (kind == "page") BusPaths.PAGE_RESPONSE else BusPaths.ACTIVITY_GEOMETRY).getLong("revision")
            if (visible && revision != current) {
                rejectedFeedback++
            } else {
                hub.deliver(RenderedMapContract.PATH, RenderedMapContract.delivery(context.toPayload()
                    .put("token", "rendered-$kind").put("view", view).put("visible", visible), "nav"))
            }
        }
        if (delayMs == 0L) deliver.run() else android.os.Handler(Looper.getMainLooper()).postDelayed(deliver, delayMs)
        idleFor(0)
    }

    private fun move(second: Int, latitudeOffset: Double = second % 2 * 0.00002) {
        walking!!.get().onLocationChanged(Location(LocationManager.GPS_PROVIDER).apply {
            latitude = 48.0 + latitudeOffset
            longitude = 2.0
            accuracy = 5f
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
        })
        idleFor(0)
    }

    private fun startWalkingJourney(): MutableList<String> {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE or BusCapabilityBits.RENDERED_MAP
        service = Robolectric.buildService(NavPluginService::class.java).create()
        idleFor(0)
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        shadowOf(app.getSystemService(LocationManager::class.java)).setProviderEnabled(LocationManager.GPS_PROVIDER, true)
        walking = Robolectric.buildService(NavJourneyService::class.java).create()
        walking!!.get().onStartCommand(Intent(app, NavJourneyService::class.java).setAction(NavJourneyService.ACTION_START)
            .putExtra(NavJourneyService.EXTRA_NAME, "Destination").putExtra(NavJourneyService.EXTRA_LAT, 48.004)
            .putExtra(NavJourneyService.EXTRA_LON, 2.0), 0, 1)
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        val route = NavRoute(NavDestination("Destination", points.last()), points, 400,
            listOf(RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Destination")))
        val requests = mutableListOf<String>()
        val executor = object : java.util.concurrent.AbstractExecutorService() {
            private var shut = false
            override fun execute(command: Runnable) = command.run()
            override fun shutdown() { shut = true }
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf<Runnable>().also { shut = true }
            override fun isShutdown() = shut
            override fun isTerminated() = shut
            override fun awaitTermination(timeout: Long, unit: java.util.concurrent.TimeUnit) = true
        }
        val transport = TileTransport { url, _, _ -> object : TileCall {
            override fun execute(): TileResponse {
                requests += url
                return TileResponse(200, mapOf("cache-control" to "max-age=300"), byteArrayOf(1))
            }
            override fun cancel() = Unit
        } }
        val loader = NavBasemap(OsmShortbreadSource, route.line, null, transport,
            { android.os.Handler(Looper.getMainLooper()).post(it) },
            { ReflectionHelpers.callInstanceMethod<Unit>(walking!!.get(), "publish",
                ReflectionHelpers.ClassParameter.from(Long::class.javaPrimitiveType!!, SystemClock.elapsedRealtime())) },
            executor, SystemClock::elapsedRealtime,
            prepare = { key, _, _ -> LocalTile(key,
                listOf(LocalLine(StreetClass.STREET, doubleArrayOf(-200.0, 200.0), doubleArrayOf(30.0, 30.0))), emptyList()) })
        ReflectionHelpers.setField(walking!!.get(), "tracker", NavRouteTracker(route))
        ReflectionHelpers.setField(walking!!.get(), "basemap", loader)
        idleFor(1_000)
        return requests
    }

    @Test
    fun `with the launcher bundle the text goes first, then the map and a fresh heading`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE
        startJourney(bearing = 45)
        val traffic = hub.activityTraffic()
        val paths = traffic.map { it.first }
        val start = paths.indexOf(BusPaths.ACTIVITY_START)
        assertTrue(paths.toString(), start >= 0)
        assertTrue(paths.toString(), paths.indexOf(BusPaths.ACTIVITY_GEOMETRY) > start)
        assertTrue(paths.toString(), paths.indexOf(BusPaths.ACTIVITY_HEADING) > start)
        // The activity opens the route page itself.
        assertEquals(NavPages.MAIN, traffic[start].second.getJSONObject("destination").getString("pageId"))
        val geometry = traffic.first { it.first == BusPaths.ACTIVITY_GEOMETRY }.second
        assertEquals("live", geometry.getJSONObject("geometry").getJSONObject("position").getString("quality"))
        assertEquals(45.0, traffic.first { it.first == BusPaths.ACTIVITY_HEADING }.second.getDouble("deg"), 0.0)
    }

    @Test
    fun `without the launcher bundle the same journey is text only`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE
        startJourney(bearing = 45)
        val traffic = hub.activityTraffic()
        val paths = traffic.map { it.first }
        assertTrue(paths.toString(), BusPaths.ACTIVITY_START in paths)
        assertFalse(paths.toString(), BusPaths.ACTIVITY_GEOMETRY in paths)
        assertFalse(paths.toString(), BusPaths.ACTIVITY_HEADING in paths)
        assertFalse(traffic.first { it.first == BusPaths.ACTIVITY_START }.second.has("destination"))
    }

    @Test
    fun `with only the control link the text still goes out and no geometry is queued`() {
        hub.features = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.LAUNCHER_INTERACTIVE
        hub.link = LinkStateBits.CXR_CONTROL_UP
        startJourney(bearing = null)
        val paths = hub.activityTraffic().map { it.first }
        assertTrue(paths.toString(), BusPaths.ACTIVITY_START in paths)
        assertFalse(paths.toString(), BusPaths.ACTIVITY_GEOMETRY in paths)
    }

    private fun startJourney(bearing: Int?) {
        service = Robolectric.buildService(NavPluginService::class.java).create()
        idleFor(0)
        val runtime = NavRuntime(app).also { journey = it }
        NavControl.attachJourney(runtime) {}
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        val route = NavRoute(
            NavDestination("Destination", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", "Test Street"), RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Destination")),
        )
        val now = SystemClock.elapsedRealtime()
        runtime.onJourney(
            NavRouteSnapshot(
                route,
                progressMeters = 100.0,
                position = route.line.pointAt(100.0),
                positionAtMs = now,
                bearing = bearing,
                quality = RouteQuality.TRACKING,
                atMs = now,
            ).also { NavState.journey = it },
        )
        idleFor(0)
    }
}
