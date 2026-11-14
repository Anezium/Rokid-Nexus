package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The SDK side of launcher bundle v9: a row's section reaches the wire only where the pair draws it. */
class NexusRowSectionsTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Pair<String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }
        override fun sendImmediate(path: String, id: String, payload: JSONObject) = send(path, id, payload)
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private inner class Provider : NexusPluginCallbacks {
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onPageRequest(request: NexusPageRequest): NexusPage = services()
    }

    private val transport = FakeTransport()
    private val client = NexusPluginClient("meta", Provider(), transport) { 1_000L }
    private var nextEvent = 0

    private fun services() = NexusPage.Content(
        revision = 1,
        title = "WhatsApp",
        body = PageBody.SelectableList(
            listOf(
                PageRow("a", "Claire", target = PageTarget.Page("meta:chat:a"), section = "PINNED"),
                PageRow("b", "Julie", target = PageTarget.Page("meta:chat:b"), section = "RECENT"),
                PageRow("c", "Tom", target = PageTarget.Page("meta:chat:c")),
            ),
        ),
        live = true,
    )

    private fun register(launcher: Int) {
        client.connect()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", "surfaces"))
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1).put("launcherInteractiveVersion", launcher))
    }

    private fun deliver(path: String, payload: JSONObject) {
        transport.listener.onMessage(path, "event-${nextEvent++}", payload.put("pluginId", "meta"))
    }

    private fun open() {
        deliver(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r-main", 1, 1, "meta:main")).put("reason", "open")
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"),
        )
    }

    private fun items() = transport.sends.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }.single()
        .getJSONObject("body").getJSONArray("items")

    @Test
    fun `at bundle v9 a row section reaches the wire as declared`() {
        register(launcher = 9)
        assertTrue(client.supportsRowSections())
        open()
        val rows = items()
        assertEquals("PINNED", rows.getJSONObject(0).getString("section"))
        assertEquals("RECENT", rows.getJSONObject(1).getString("section"))
        assertFalse(rows.getJSONObject(2).has("section"))
    }

    @Test
    fun `below v9 the same rows are sent in the same order without sections`() {
        register(launcher = 8)
        assertFalse(client.supportsRowSections())
        assertTrue(client.supportsRowIcons())
        open()
        val rows = items()
        assertEquals(listOf("Claire", "Julie", "Tom"), (0 until rows.length()).map { rows.getJSONObject(it).getString("label") })
        assertTrue((0 until rows.length()).none { rows.getJSONObject(it).has("section") })
    }
}
