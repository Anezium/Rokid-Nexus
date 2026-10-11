package com.anezium.rokidbus.plugin.nav

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ServiceInfo
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
import com.anezium.rokidbus.shared.PageSurfaceContract
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
import java.time.Duration
import java.util.Collections

/**
 * A journey through the real runtime, plugin service and SDK client: its text always goes out
 * first, and its map and heading follow only when both hubs negotiated the launcher bundle.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
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
            deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, 1))
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
        journey?.let {
            it.shutdown()
            NavControl.detachJourney(it)
        }
        service?.destroy()
        NavState.guidance = null
        NavState.journey = null
        idleFor(0)
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
            ),
        )
        idleFor(0)
    }
}
