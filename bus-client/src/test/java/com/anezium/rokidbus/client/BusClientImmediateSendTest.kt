package com.anezium.rokidbus.client

import android.app.Application
import android.content.IntentFilter
import android.os.DeadObjectException
import android.os.Looper
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.client.plugin.NexusPageRequest
import com.anezium.rokidbus.client.plugin.NexusPluginCallbacks
import com.anezium.rokidbus.client.plugin.NexusPluginClient
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.BusConstants
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.Collections

/** Page traffic over the real Android transport: sent now or dropped, never queued for a reconnect. */
@RunWith(RobolectricTestRunner::class)
// API 32: Robolectric 4.13 does not shadow the API 33+ flag overloads the client resolves the hub with.
@Config(sdk = [32])
class BusClientImmediateSendTest {
    private class FakeHub : IBusService.Stub() {
        val sent: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
        var callback: IBusCallback? = null
        var registrations = 0
        @Volatile var dead = false
        private var nextId = 0

        override fun apiVersion(): Int = 3
        override fun register(clientId: String, pathPrefixes: Array<String>, cb: IBusCallback) = Unit
        override fun unregister(cb: IBusCallback) = Unit
        override fun send(path: String, id: String, payload: ByteArray) {
            if (dead) throw DeadObjectException()
            sent += path to JSONObject(String(payload, Charsets.UTF_8))
        }
        override fun linkState(): Int = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        override fun sendBinary(path: String, id: String, meta: ByteArray, data: ByteArray) = Unit
        override fun registerPlugin(packageName: String, pluginId: String, cb: IBusCallback): Int {
            callback = cb
            registrations += 1
            deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"))
            deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION, 1))
            return PluginRegistrationResult.APPROVED
        }
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(pluginId: String): String = "surfaces"

        fun deliver(path: String, payload: JSONObject) {
            checkNotNull(callback).onMessage(path, "hub-${nextId++}", payload.put("pluginId", "relay").toString().toByteArray(Charsets.UTF_8))
        }

        fun sentTo(path: String): List<JSONObject> = synchronized(sent) { sent.filter { it.first == path }.map { it.second } }
    }

    private val app: Application = RuntimeEnvironment.getApplication()
    private val hub = FakeHub()
    private val requests = mutableListOf<NexusPageRequest>()
    private val callbacks = object : NexusPluginCallbacks {
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onPageRequest(request: NexusPageRequest): NexusPage {
            requests += request
            return content(revision = 1)
        }
    }

    private fun content(revision: Long) = NexusPage.Content(
        revision, "Relay", PageBody.SelectableList(listOf(PageRow("a", "Mika"))),
        actions = listOf(NexusPageActionSpec("read", "Read")), live = true,
    )

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun connectedClient(): NexusPluginClient {
        val packages = shadowOf(app.packageManager)
        val component = android.content.ComponentName(HubTarget.PHONE.packageName, HubTarget.PHONE.serviceClassName)
        packages.addServiceIfNotPresent(component)
        packages.addIntentFilterForService(component, IntentFilter(BusConstants.ACTION_HUB))
        shadowOf(app).setComponentNameAndServiceForBindService(component, hub)
        // As on a device: an explicit unbind is not a disconnect.
        shadowOf(app).setUnbindServiceCallsOnServiceDisconnected(false)
        val client = NexusPluginClient.create(app, "relay", callbacks)
        client.connect()
        idle()
        assertTrue(client.isApproved)
        assertTrue(client.supportsPageSession)
        return client
    }

    @Test
    fun `a page answer Binder refuses is dropped, grants nothing and is not replayed after a reconnect`() {
        val client = connectedClient()
        hub.dead = true
        hub.deliver(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "relay:main"))
                .put("reason", "open").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        idle()
        assertEquals(1, requests.size)
        assertTrue(hub.sentTo(BusPaths.PAGE_RESPONSE).isEmpty())
        hub.deliver(
            BusPaths.PAGE_VISIBILITY,
            JSONObject().put("pageId", "relay:main").put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put("leaseUntilMs", 999_999L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000L)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        idle()
        // The failed answer earned no publishing right, so a live revision is refused, not queued.
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(requests.single(), content(revision = 2)))
        // Ordinary traffic keeps its reconnect queue.
        assertTrue(client.send("/plugin/relay/ping", "ping-1", JSONObject()))

        hub.dead = false
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(2, hub.registrations)
        assertEquals(1, hub.sentTo("/plugin/relay/ping").size)
        assertTrue(hub.sentTo(BusPaths.PAGE_RESPONSE).isEmpty())
        assertTrue(hub.sentTo(BusPaths.PAGE_RESULT).isEmpty())
        client.close()
    }
}
