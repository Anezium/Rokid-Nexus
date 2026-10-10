package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusPageInteractiveTest {
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
        val actions = mutableListOf<NexusPageAction>()
        var page: NexusPage = todo()
        var result: NexusPageResult = NexusPageResult.done()
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onPageRequest(request: NexusPageRequest): NexusPage = page
        override fun onPageAction(action: NexusPageAction): NexusPageResult {
            actions += action
            return result
        }
    }

    private val transport = FakeTransport()
    private val provider = Provider()
    private val client = NexusPluginClient("todo", provider, transport) { 1_000L }
    private var nextEvent = 0

    private fun todo() = NexusPage.Content(
        revision = 1,
        title = "Courses",
        body = PageBody.SelectableList(
            listOf(
                PageRow("milk", "Milk", target = PageTarget.Action("check:milk:1"), checked = false),
                PageRow("note", "Note", target = PageTarget.Action("note")),
            ),
        ),
        actions = listOf(
            NexusPageActionSpec("check:milk:1", "Done", typed = NexusActionType.SetChecked(true)),
            NexusPageActionSpec("note", "Note"),
        ),
        live = true,
    )

    private fun thread() = NexusPage.Content(
        revision = 1,
        title = "Mika",
        body = PageBody.Conversation(
            threadKey = "9f2c",
            messages = listOf(ConversationMessage("m1", ConversationRole.RECEIVED, "On se voit à 8h ?", sender = "Mika")),
            composer = ConversationComposer("Mika", "send", maxChars = 20, replyable = true),
        ),
        actions = listOf(NexusPageActionSpec("send", "Send", typed = NexusActionType.ComposeSend)),
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
        transport.listener.onMessage(path, "event-${nextEvent++}", payload.put("pluginId", "todo"))
    }

    private fun open(pageId: String = "todo:main") {
        deliver(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r-$pageId", 1, 1, pageId)).put("reason", "open")
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"),
        )
        deliver(
            BusPaths.PAGE_VISIBILITY,
            JSONObject().put("pageId", pageId).put("visible", true).put("leaseUntilMs", 999_999L).put("sessionGeneration", 1L)
                .put("frameIndex", 1).put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg")
                .put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 60_000L),
        )
    }

    private fun invoke(invocationId: String, actionId: String, mode: String? = null, text: String? = null, pageId: String = "todo:main") {
        deliver(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", invocationId).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put("pageId", pageId).put("revision", 1L).put("actionId", actionId)
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg")
                .apply {
                    mode?.let { put("mode", it) }
                    text?.let { put("text", it) }
                },
        )
    }

    private fun responses() = transport.sends.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }
    private fun results() = transport.sends.filter { it.first == BusPaths.PAGE_RESULT }.map { it.second }

    @Test
    fun `typed actions and checked rows reach the wire as declared`() {
        register(launcher = 1)
        open()
        val response = responses().single()
        val action = response.getJSONArray("actions").getJSONObject(0)
        assertEquals("setChecked", action.getJSONObject("typed").getString("type"))
        assertTrue(action.getJSONObject("typed").getBoolean("checked"))
        assertFalse("a plugin never stamps itself quick", action.has("hubQuick"))
        assertFalse(response.getJSONObject("body").getJSONArray("items").getJSONObject(0).getBoolean("checked"))
    }

    @Test
    fun `quick reaches the plugin only for a quick-eligible declared type`() {
        register(launcher = 1)
        open()
        invoke("i1", "check:milk:1", mode = "quick")
        invoke("i2", "note", mode = "quick")
        invoke("i3", "note")
        assertEquals(listOf("check:milk:1", "note"), provider.actions.map { it.actionId })
        val quick = provider.actions.first()
        assertEquals(NexusPageActionMode.QUICK, quick.mode)
        assertEquals(NexusActionType.SetChecked(true), quick.typed)
        assertNull(quick.text)
        assertEquals(NexusPageActionMode.CONFIRMED, provider.actions.last().mode)
        assertNull(provider.actions.last().typed)
        assertEquals(2, results().size)
    }

    @Test
    fun `the composer's send arrives with its text and nowhere else`() {
        register(launcher = 1)
        provider.page = thread()
        open("todo:thread")
        assertTrue(responses().single().has("template"))
        invoke("i1", "send", pageId = "todo:thread")
        invoke("i2", "send", mode = "quick", pageId = "todo:thread")
        invoke("i3", "send", mode = "compose", text = "x".repeat(21), pageId = "todo:thread")
        invoke("i4", "send", mode = "compose", text = "J'arrive", pageId = "todo:thread")
        val send = provider.actions.single()
        assertEquals(NexusPageActionMode.COMPOSE, send.mode)
        assertEquals("J'arrive", send.text)
        assertEquals(NexusActionType.ComposeSend, send.typed)
    }

    @Test
    fun `new bodies answer as invalid until both hubs negotiated the bundle`() {
        register(launcher = 0)
        provider.page = thread()
        open("todo:thread")
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, responses().single().getString("error"))
        // Quick needs the bundle too, even for an eligible type.
        provider.page = todo()
        open()
        invoke("i1", "check:milk:1", mode = "quick")
        assertTrue(provider.actions.isEmpty())
    }

    @Test
    fun `a result may carry the authority-lost code`() {
        register(launcher = 1)
        provider.page = thread()
        provider.result = NexusPageResult.rejected("Reply from the phone", code = NexusPageResult.CODE_AUTHORITY_LOST)
        open("todo:thread")
        invoke("i1", "send", mode = "compose", text = "J'arrive", pageId = "todo:thread")
        val result = results().single()
        assertEquals("rejected", result.getString("status"))
        assertEquals("authority_lost", result.getString("code"))
        assertTrue(PageSurfaceContract.validateBoundedResult(result) is com.anezium.rokidbus.shared.PageSurfaceValidationResult.Valid)
        assertThrows(IllegalArgumentException::class.java) { NexusPageResult.rejected(code = "has space") }
        assertNull(NexusPageResult.done().code)
    }
}
