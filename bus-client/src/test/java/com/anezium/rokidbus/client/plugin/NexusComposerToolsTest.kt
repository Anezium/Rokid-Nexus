package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ComposerSlot
import com.anezium.rokidbus.shared.ComposerTool
import com.anezium.rokidbus.shared.ComposerToolContext
import com.anezium.rokidbus.shared.ComposerToolContract
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.PluginOpenTypes
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** The SDK side of launcher bundle v5: composer tools on the wire, and the open that names its tool. */
class NexusComposerToolsTest {
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
        val opens = mutableListOf<Pair<String, ComposerToolContext?>>()
        var page: NexusPage = thread()
        override fun onOpen() = Unit
        override fun onOpen(openType: String, context: ComposerToolContext?) {
            opens += openType to context
        }
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onPageRequest(request: NexusPageRequest): NexusPage = page
    }

    // Declared before the provider, whose first page already carries it.
    private val thumb = LauncherArtwork(8, 8, Base64.getEncoder().encodeToString(ByteArray(8) { 0x55 }))
    private val transport = FakeTransport()
    private val provider = Provider()
    private val client = NexusPluginClient("meta", provider, transport) { 1_000L }
    private var nextEvent = 0

    private fun thread() = NexusPage.Content(
        revision = 1,
        title = "Mika",
        body = PageBody.Conversation(
            threadKey = "9f2c",
            messages = listOf(
                ConversationMessage("m1", ConversationRole.RECEIVED, "Look at this", sender = "Mika", image = thumb),
                ConversationMessage("m2", ConversationRole.OURS, "Nice"),
            ),
            composer = ConversationComposer(
                "Mika", "send", maxChars = 200, replyable = true,
                tools = listOf(ComposerTool("photo", "Photo", "open-camera", glyph = "camera")),
                layout = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Dictate, ComposerSlot.Send),
                sendLabel = "Send",
            ),
        ),
        actions = listOf(
            NexusPageActionSpec("send", "Send", typed = NexusActionType.ComposeSend),
            NexusPageActionSpec("open-camera", "Camera", kind = NexusPageActionKind.IMMERSION),
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

    private fun open(pageId: String = "meta:thread") {
        deliver(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r-$pageId", 1, 1, pageId)).put("reason", "open")
                .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg"),
        )
    }

    private fun response(): JSONObject = transport.sends.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }.single()

    @Test
    fun `at bundle v5 the tools layout labels and thumbnails reach the wire as declared`() {
        register(launcher = 5)
        assertTrue(client.supportsComposerTools())
        open()
        val body = response().getJSONObject("body")
        val composer = body.getJSONObject("composer")
        val tool = composer.getJSONArray("tools").getJSONObject(0)
        assertEquals("photo", tool.getString("id"))
        assertEquals("open-camera", tool.getString("actionId"))
        assertEquals("camera", tool.getString("glyph"))
        assertEquals(listOf("photo", "dictate", "send"), (0 until 3).map { composer.getJSONArray("layout").getString(it) })
        assertEquals("Send", composer.getString("sendLabel"))
        assertEquals("mono1", body.getJSONArray("messages").getJSONObject(0).getJSONObject("image").getString("encoding"))
        assertFalse(body.getJSONArray("messages").getJSONObject(1).has("image"))
    }

    @Test
    fun `below v5 the same page is sent with the default bar and text-only bubbles`() {
        register(launcher = 4)
        assertFalse(client.supportsComposerTools())
        open()
        val body = response().getJSONObject("body")
        assertFalse(response().has("error"))
        val composer = body.getJSONObject("composer")
        listOf("tools", "layout", "sendLabel").forEach { assertFalse(it, composer.has(it)) }
        assertEquals("Mika", composer.getString("recipient"))
        assertFalse(body.getJSONArray("messages").getJSONObject(0).has("image"))
        assertEquals("Look at this", body.getJSONArray("messages").getJSONObject(0).getString("text"))
    }

    @Test
    fun `a tool naming the send action makes the page invalid`() {
        register(launcher = 5)
        provider.page = (thread()).let { page ->
            val body = page.body as PageBody.Conversation
            page.copy(body = body.copy(composer = body.composer!!.copy(tools = listOf(ComposerTool("again", "Again", "send")), layout = null)))
        }
        open()
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, response().getString("error"))
    }

    @Test
    fun `a composer tool open carries its context to the plugin and only for its own pages`() {
        register(launcher = 5)
        val context = ComposerToolContext("meta:thread", """{"thread":"9f2c"}""", "9f2c", "photo", "open-camera")
        deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", PluginOpenTypes.COMPOSER_TOOL).put(ComposerToolContract.FIELD_CONTEXT, ComposerToolContract.encode(context)))
        assertEquals(PluginOpenTypes.COMPOSER_TOOL to context, provider.opens.single())

        val foreign = context.copy(pageId = "relay:thread")
        deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", PluginOpenTypes.COMPOSER_TOOL).put(ComposerToolContract.FIELD_CONTEXT, ComposerToolContract.encode(foreign)))
        assertEquals(PluginOpenTypes.COMPOSER_TOOL, provider.opens.last().first)
        assertNull(provider.opens.last().second)

        deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", "open").put(ComposerToolContract.FIELD_CONTEXT, ComposerToolContract.encode(context)))
        assertEquals(PluginOpenTypes.OPEN to null, provider.opens.last())
    }
}
