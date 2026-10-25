package com.anezium.rokidbus.plugin.nav

import android.app.Application
import android.app.Notification
import android.content.ComponentName
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.os.Process
import android.service.notification.StatusBarNotification
import com.anezium.rokidbus.client.IBusCallback
import com.anezium.rokidbus.client.IBusService
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

/** Notification rewrites and late hub acknowledgements through the listener, runtime and SDK. */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class NavActivityLifecycleTest {
    private class FakeHub : IBusService.Stub() {
        private val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
        private var callback: IBusCallback? = null
        private var nextId = 0

        override fun apiVersion(): Int = 3
        override fun register(clientId: String, pathPrefixes: Array<String>, cb: IBusCallback) = Unit
        override fun unregister(cb: IBusCallback) = Unit
        override fun send(path: String, id: String, payload: ByteArray) {
            sent += path to JSONObject(String(payload, Charsets.UTF_8))
        }
        override fun linkState(): Int = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) = Unit
        override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int {
            callback = cb
            deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"))
            return PluginRegistrationResult.APPROVED
        }
        override fun capabilities(): Int = BusCapabilityBits.ACTIVITY_SURFACE
        override fun approvedCapabilities(pluginId: String): String = "surfaces"

        fun deliver(path: String, payload: JSONObject) {
            checkNotNull(callback).onMessage(path, "hub-${nextId++}", payload.put("pluginId", "nav").toString().toByteArray(Charsets.UTF_8))
        }

        fun close(reason: ActivityCloseReason) = deliver(
            BusPaths.ACTIVITY_CLOSED,
            JSONObject().put("activityId", "nav:activity").put("reason", reason.wireValue),
        )

        fun traffic(): List<Pair<String, JSONObject>> = synchronized(sent) {
            sent.filter { it.first.startsWith("/activity/") }
        }

        fun paths(): List<String> = traffic().map { it.first }
    }

    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private lateinit var listener: ServiceController<NavNotificationListener>
    private lateinit var service: ServiceController<NavPluginService>
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
                }
            },
        )
        NavSettings(app).setEnabled(true)
        listener = Robolectric.buildService(NavNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        service = Robolectric.buildService(NavPluginService::class.java).create()
        idleFor(0)
    }

    @After
    fun tearDown() {
        journey?.let {
            it.shutdown()
            NavControl.detachJourney(it)
        }
        listener.destroy()
        service.destroy()
        NavState.guidance = null
        NavState.updatedAtMs = null
        idleFor(0)
    }

    private fun maps(title: String = "300 m · Prendre à droite sur Rue de Rivoli", id: Int = 1): StatusBarNotification {
        val notification = Notification.Builder(app, "1_foreground_1")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setCategory("navigation")
            .setOngoing(true)
            .setContentTitle(title)
            .setSubText("Arrivée à 22:50")
            .build()
        return StatusBarNotification(
            NavSource.GOOGLE_MAPS.packageName, NavSource.GOOGLE_MAPS.packageName, id, null, 10_456, 0, 0,
            notification, Process.myUserHandle(), System.currentTimeMillis(),
        )
    }

    private fun post(notification: StatusBarNotification = maps()) {
        listener.get().onNotificationPosted(notification)
        idleFor(0)
    }

    @Test
    fun `readable guidance within the grace updates the same activity and keeps planner significance`() {
        post()
        post(maps(title = ""))
        assertEquals("300 m", NavState.guidance?.primary)
        idleFor(350)
        post(maps(title = "450 m · Continuez sur Bd Saint-Michel"))
        idleFor(3_000)

        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE), hub.paths())
        val update = hub.traffic().last().second
        assertEquals("450 m", update.getString("primary"))
        assertTrue(update.getBoolean("significant"))
        assertFalse(update.optBoolean("urgent"))
    }

    @Test
    fun `quiet recovery keeps the existing one second deferral`() {
        post()
        post(maps(title = ""))
        idleFor(350)
        post(maps(title = "280 m · Prendre à droite sur Rue de Rivoli"))
        idleFor(649)
        assertEquals(listOf(BusPaths.ACTIVITY_START), hub.paths())
        idleFor(1)

        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE), hub.paths())
        assertEquals("280 m", hub.traffic().last().second.getString("primary"))
        assertFalse(hub.traffic().last().second.optBoolean("significant"))
        idleFor(3_000)
        assertEquals(2, hub.traffic().size)
    }

    @Test
    fun `unreadable guidance ends once after three seconds even if unreadable posts keep arriving`() {
        post()
        post(maps(title = ""))
        idleFor(2_000)
        post(maps(title = ""))
        idleFor(999)
        assertEquals(listOf(BusPaths.ACTIVITY_START), hub.paths())
        assertNotNull(NavState.guidance)
        idleFor(1)

        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END), hub.paths())
        assertNull(NavState.guidance)
        post(maps(title = ""))
        idleFor(4_000)
        assertEquals(2, hub.traffic().size)
    }

    @Test
    fun `removing guidance ends promptly`() {
        post()
        listener.get().onNotificationRemoved(maps())
        idleFor(0)

        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END), hub.paths())
        assertNull(NavState.guidance)
    }

    @Test
    fun `removing unreadable guidance ends promptly and cancels its deadline`() {
        post()
        post(maps(title = ""))
        idleFor(350)
        listener.get().onNotificationRemoved(maps(title = ""))
        idleFor(0)
        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END), hub.paths())

        post(maps(id = 2))
        idleFor(4_000)
        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START), hub.paths())
        assertNotNull(NavState.guidance)
    }

    @Test
    fun `a late owner close of an ended route does not restart its replacement`() {
        post()
        listener.get().onNotificationRemoved(maps())
        post(maps(id = 2))
        hub.close(ActivityCloseReason.OWNER)
        idleFor(0)
        post(maps(title = "280 m · Prendre à droite sur Rue de Rivoli", id = 2))
        idleFor(1_000)

        assertEquals(
            listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE),
            hub.paths(),
        )
        assertFalse(hub.traffic().last().second.optBoolean("significant"))
    }

    @Test
    fun `a late owner close from notifications does not restart the journey that took over`() {
        post()
        val guidance = checkNotNull(NavState.guidance).copy(source = NavSource.NEXUS, stepKey = "journey")
        val runtime = NavRuntime(app).also { journey = it }
        NavControl.attachJourney(runtime) {}
        runtime.onGuidance(guidance)
        idleFor(0)
        hub.close(ActivityCloseReason.OWNER)
        idleFor(0)
        runtime.onGuidance(guidance.copy(primary = "280 m"))
        idleFor(1_000)

        assertEquals(
            listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE),
            hub.paths(),
        )
    }

    @Test
    fun `a current activity evicted by the hub still restarts on new guidance`() {
        post()
        hub.close(ActivityCloseReason.REPLACED)
        idleFor(0)
        post(maps(title = "280 m · Prendre à droite sur Rue de Rivoli"))

        assertEquals(listOf(BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_START), hub.paths())
    }

    @Test
    fun `an unreadable notification with another key does not end the live route`() {
        post()
        post(maps(title = "", id = 2))
        idleFor(4_000)

        assertEquals(listOf(BusPaths.ACTIVITY_START), hub.paths())
        assertNotNull(NavState.guidance)
    }
}
