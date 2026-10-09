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
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * A visible `nav:main` follows the listener itself: access lost, access back, Navigation switched
 * off. Real listener, runtime, plugin service and SDK client against a page-v1 hub.
 */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the SDK resolves the hub with.
@Config(sdk = [32])
class NavPagesListenerTest {
    private class FakeHub : IBusService.Stub() {
        val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
        var callback: IBusCallback? = null
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
            deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, 1))
            return PluginRegistrationResult.APPROVED
        }
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(pluginId: String): String = "surfaces"

        fun deliver(path: String, payload: JSONObject) {
            checkNotNull(callback).onMessage(path, "hub-${nextId++}", payload.put("pluginId", "nav").toString().toByteArray(Charsets.UTF_8))
        }

        fun mainAnswers(): List<JSONObject> = synchronized(sent) {
            sent.filter { it.first == BusPaths.PAGE_RESPONSE && it.second.optString("pageId") == NavPages.MAIN }.map { it.second }
        }
    }

    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private lateinit var listener: ServiceController<NavNotificationListener>
    private lateinit var service: ServiceController<NavPluginService>

    private fun idleFor(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    @Before
    fun setUp() {
        val packages = shadowOf(app.packageManager)
        val hubComponent = ComponentName("com.anezium.rokidbus.phone", "com.anezium.rokidbus.phone.BusHubService")
        packages.addServiceIfNotPresent(hubComponent)
        packages.addIntentFilterForService(hubComponent, IntentFilter(BusConstants.ACTION_HUB))
        shadowOf(app).setComponentNameAndServiceForBindService(hubComponent, hub)
        // As on a device: an explicit unbind is not a disconnect.
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
        NavSettings(app).setEnabled(true)
        listener = Robolectric.buildService(NavNotificationListener::class.java).create()
        listener.get().onListenerConnected()
        listener.get().onNotificationPosted(mapsGuidance())
        service = Robolectric.buildService(NavPluginService::class.java).create()
        idleFor(0)
    }

    @After
    fun tearDown() {
        service.destroy()
        listener.destroy()
        NavState.guidance = null
        NavState.updatedAtMs = null
        NavSettings(app).setEnabled(true)
        idleFor(0)
    }

    /** Shaped on GoogleMapsParserTest's walking capture, as Maps posts it. */
    private fun mapsGuidance(): StatusBarNotification {
        val notification = Notification.Builder(app, "navigation")
            .setSmallIcon(android.R.drawable.ic_dialog_map)
            .setCategory("navigation")
            .setOngoing(true)
            .setContentTitle("80 m · Prendre à droite sur Rue de Rivoli")
            .setSubText("Arrivée à 22:50")
            .build()
        return StatusBarNotification(
            NavSource.GOOGLE_MAPS.packageName, NavSource.GOOGLE_MAPS.packageName, 1, null, 10_456, 0, 0,
            notification, Process.myUserHandle(), System.currentTimeMillis(),
        )
    }

    private fun primary(answer: JSONObject) = answer.getJSONObject("body").optString("primary")

    @Test
    fun `losing access, getting it back and switching off each revise the visible page`() {
        hub.deliver(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, NavPages.MAIN))
                .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        hub.deliver(
            BusPaths.PAGE_VISIBILITY,
            JSONObject().put("pageId", NavPages.MAIN).put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000L)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        idleFor(0)
        assertEquals("80 m", primary(hub.mainAnswers().last()))

        // Notification Access revoked: the old turn and ETA go.
        listener.get().onListenerDisconnected()
        idleFor(1_000)
        assertEquals("No notification access", primary(hub.mainAnswers().last()))
        assertTrue(hub.mainAnswers().last().toString(), "22:50" !in hub.mainAnswers().last().toString())

        // Access back with no route running.
        listener.get().onListenerConnected()
        idleFor(1_000)
        assertEquals("No active route", primary(hub.mainAnswers().last()))

        // What the settings switch does.
        NavSettings(app).setEnabled(false)
        NavControl.settingsChanged()
        idleFor(1_000)
        assertEquals("Navigation is off", primary(hub.mainAnswers().last()))

        val revisions = hub.mainAnswers().map { it.getLong("revision") }
        assertEquals(revisions.sorted().distinct(), revisions)
    }
}
