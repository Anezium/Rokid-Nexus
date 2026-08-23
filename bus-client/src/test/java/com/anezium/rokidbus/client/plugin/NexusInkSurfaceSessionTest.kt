package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.ink.InkEngine
import com.anezium.rokidbus.ink.InkProblemCodes
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusInkSurfaceSessionTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var featureBits = 0
        val sends = mutableListOf<Pair<String, JSONObject>>()

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }

        override fun sendBinary(
            path: String,
            id: String,
            payload: JSONObject,
            data: ByteArray,
        ): Boolean = true

        override fun capabilities(): Int = featureBits
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private class RecordingCallbacks : NexusPluginCallbacks {
        val ready = mutableListOf<String>()
        val actions = mutableListOf<Triple<String, String, JSONObject>>()
        val closed = mutableListOf<Pair<String, NexusInkCloseReason>>()
        val errors = mutableListOf<Pair<String, List<NexusInkProblem>>>()

        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onInkReady(surfaceId: String) {
            ready += surfaceId
        }

        override fun onInkAction(surfaceId: String, actionId: String, dataset: JSONObject) {
            actions += Triple(surfaceId, actionId, JSONObject(dataset.toString()))
        }

        override fun onInkClosed(surfaceId: String, reason: NexusInkCloseReason) {
            closed += surfaceId to reason
        }

        override fun onInkError(surfaceId: String, problems: List<NexusInkProblem>) {
            errors += surfaceId to problems
        }
    }

    @Test
    fun `grant capability and spp gate an ink show`() {
        val (client, transport, _) = fixture()
        val session = client.inkSurfaceSession("main")

        assertEquals(NexusSdkResult.NOT_REGISTERED, session.show(PAGE))
        approve(transport, capabilities = "surfaces")
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, session.show(PAGE))

        approve(transport, capabilities = "ink_surface")
        transport.featureBits = BusCapabilityBits.INK_SURFACE
        transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        assertFalse(client.supportsInkSurface)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, session.show(PAGE))

        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertTrue(client.supportsInkSurface)
        assertEquals(
            NexusSdkResult.SENT,
            session.show(PAGE, JSONObject().put("value", "one"), handlesBack = false),
        )
        val (path, payload) = transport.sends.single()
        assertEquals(BusPaths.INK_SHOW, path)
        assertEquals("main", payload.getString("surfaceId"))
        assertEquals(PAGE, payload.getString("page"))
        assertEquals("one", payload.getJSONObject("data").getString("value"))
        assertFalse(payload.getBoolean("handlesBack"))
    }

    @Test
    fun `page and patch byte budgets fail locally with typed problems`() {
        val (client, transport, callbacks) = fixture()
        approveInk(transport)
        val session = client.inkSurfaceSession("main")

        assertEquals(
            NexusSdkResult.INVALID_PAYLOAD,
            session.show("x".repeat(InkEngine.MAX_PAGE_BYTES + 1)),
        )
        assertEquals(
            NexusSdkResult.INVALID_PAYLOAD,
            session.update(JSONObject().put("value", "x".repeat(InkEngine.MAX_DATA_BYTES))),
        )
        assertTrue(transport.sends.isEmpty())
        assertEquals(2, callbacks.errors.size)
        assertTrue(callbacks.errors.all { (_, problems) ->
            problems.single().code == InkProblemCodes.BUDGET_SIZE &&
                problems.single().sdkResult == NexusSdkResult.INVALID_PAYLOAD
        })
    }

    @Test
    fun `ink owner events are decoded into typed callbacks`() {
        val (_, transport, callbacks) = fixture()
        approveInk(transport)

        event(transport, "ready", InkSurfaceContract.EVENT_READY)
        event(
            transport,
            "action",
            InkSurfaceContract.EVENT_ACTION,
            JSONObject()
                .put("actionId", "refresh")
                .put("dataset", JSONObject().put("row", "updated")),
        )
        event(
            transport,
            "closed",
            InkSurfaceContract.EVENT_CLOSED,
            JSONObject().put("reason", InkSurfaceContract.CLOSE_USER),
        )
        event(
            transport,
            "error",
            InkSurfaceContract.EVENT_ERROR,
            JSONObject().put(
                "problems",
                JSONArray().put(
                    JSONObject()
                        .put("code", "SURFACE_BUSY")
                        .put("message", "Another surface owns the foreground"),
                ),
            ),
        )

        assertEquals(listOf("main"), callbacks.ready)
        assertEquals("refresh", callbacks.actions.single().second)
        assertEquals("updated", callbacks.actions.single().third.getString("row"))
        assertEquals("main" to NexusInkCloseReason.USER, callbacks.closed.single())
        assertEquals(NexusSdkResult.SURFACE_BUSY, callbacks.errors.single().second.single().sdkResult)
    }

    @Test
    fun `DISPLAY_MUTED ink problem maps to the typed sdk result`() {
        val problem = NexusInkProblem(code = "DISPLAY_MUTED", message = "Display policy forbids this slot")
        assertEquals(NexusSdkResult.DISPLAY_MUTED, problem.sdkResult)
    }

    private fun fixture(): Triple<NexusPluginClient, FakeTransport, RecordingCallbacks> {
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        return Triple(client, transport, callbacks)
    }

    private fun approveInk(transport: FakeTransport) {
        transport.featureBits = BusCapabilityBits.INK_SURFACE
        approve(transport, capabilities = "ink_surface")
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
    }

    private fun approve(transport: FakeTransport, capabilities: String) {
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-$capabilities",
            JSONObject()
                .put("pluginId", "hello")
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities),
        )
    }

    private fun event(
        transport: FakeTransport,
        id: String,
        type: String,
        extra: JSONObject = JSONObject(),
    ) {
        transport.listener.onMessage(
            BusPaths.INK_EVENT,
            id,
            JSONObject(extra.toString())
                .put("pluginId", "hello")
                .put("surfaceId", "main")
                .put("type", type),
        )
    }

    private companion object {
        const val PAGE = "<page><view><text>{{ value }}</text></view></page>"
    }
}
