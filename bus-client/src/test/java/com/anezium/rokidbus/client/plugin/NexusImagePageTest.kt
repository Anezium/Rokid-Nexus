package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.*
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class NexusImagePageTest {
    private class Transport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sent = mutableListOf<Triple<String, String, JSONObject>>()
        override fun connect(listener: NexusPluginTransport.Listener) { this.listener = listener }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sent += Triple(path, id, JSONObject(payload.toString()))
            return true
        }
        override fun sendImmediate(path: String, id: String, payload: JSONObject) = send(path, id, payload)
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities() = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private val image = LauncherArtwork(512, 512, Base64.getEncoder().encodeToString(ByteArray(32_768)))
    private fun preview(revision: Long = 3) = NexusPage.Content(revision, "Photo", PageBody.Image(image, "Ready"),
        listOf("send-photo", "retake", "cancel").map { NexusPageActionSpec(it, it) }, live = true)
    private val transport = Transport()
    private lateinit var request: NexusPageRequest
    private var answer: NexusPage = preview(1)
    private var actionHandler: (NexusPageAction) -> NexusPageResult = { NexusPageResult.rejected() }
    private var opens = 0
    private val client = NexusPluginClient("photo", object : NexusPluginCallbacks {
        override fun onOpen() { opens++ }
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onPageRequest(request: NexusPageRequest): NexusPage {
            this@NexusImagePageTest.request = request
            return answer
        }
        override fun onPageAction(action: NexusPageAction) = actionHandler(action)
    }, transport) { 1_000L }

    private var nextEvent = 0
    private fun deliver(path: String, payload: JSONObject) = transport.listener.onMessage(path, "event-${nextEvent++}",
        payload.put("pluginId", "photo").put("providerRegistrationId", "reg"))

    private fun start(version: Int, capabilities: String = "surfaces,camera") {
        client.connect()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", capabilities))
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1).put("launcherInteractiveVersion", version))
        deliver(BusPaths.PAGE_REQUEST, PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "photo:main")).put("reason", "open"))
        deliver(BusPaths.PAGE_VISIBILITY, JSONObject().put("pageId", "photo:main").put("sessionGeneration", 1).put("frameIndex", 1)
            .put("visible", true).put("leaseUntilMs", 121_000L).put("leaseDurationMs", 120_000L))
    }

    private fun invoke(action: String, revision: Long = 1) = deliver(BusPaths.PAGE_ACTION, JSONObject()
        .put("invocationId", "i$revision").put("pageId", "photo:main").put("sessionGeneration", 1).put("frameIndex", 1)
        .put("revision", revision).put("actionId", action).put("mode", "confirmed"))

    @Test fun `image page and supports check follow v6 and older pairs get no pixels or send actions`() {
        start(5)
        assertFalse(client.supportsImagePages())
        val fallback = transport.sent.first { it.first == BusPaths.PAGE_RESPONSE }.third
        assertEquals("summary", fallback.getString("template"))
        assertFalse(fallback.getJSONObject("body").has("image"))
        assertEquals(0, fallback.getJSONArray("actions").length())
        var invoked = false
        actionHandler = { invoked = true; NexusPageResult.done() }
        invoke("send-photo")
        assertFalse(invoked)
    }

    @Test fun `plugin photo tool captures without open and publishes preview after an immediate progress result`() {
        answer = NexusPage.Content(1, "Mika", PageBody.Conversation("thread", emptyList(), composer = ConversationComposer(
            "Mika", "send-text", 500, true, tools = listOf(ComposerTool("photo", "Photo", "capture", "camera")))),
            listOf(NexusPageActionSpec("send-text", "Send", typed = NexusActionType.ComposeSend), NexusPageActionSpec("capture", "Photo")), true)
        start(6)
        assertTrue(client.supportsImagePages())
        val snapshot = client.snapshotSession(object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) { assertEquals(NexusSdkResult.SENT, client.publishPage(request, preview())) }
            override fun onSnapshotError(error: NexusSnapshotError) = fail("Unexpected $error")
        })
        actionHandler = {
            assertEquals("capture", it.actionId)
            assertEquals(NexusSdkResult.SENT, snapshot.capture())
            NexusPageResult.done(replacement = NexusPage.Content(2, "Photo", PageBody.Summary(primary = "Capturing…"), live = true))
        }
        invoke("capture")
        val result = transport.sent.last { it.first == BusPaths.PAGE_RESULT }.third
        assertEquals("done", result.getString("status"))
        assertEquals("summary", result.getJSONObject("replacement").getString("template"))
        val capture = transport.sent.single { it.first == BusPaths.CAMERA_SNAPSHOT_REQUEST }
        transport.listener.onBinary(BusPaths.CAMERA_SNAPSHOT_RESULT, capture.second, JSONObject().put("pluginId", "photo")
            .put("requestId", capture.second).put("mimeType", "image/jpeg"), byteArrayOf(1))
        val ready = transport.sent.last { it.first == BusPaths.PAGE_RESPONSE }.third
        assertEquals("image", ready.getString("template"))
        assertEquals(3, ready.getJSONArray("actions").length())
        var sentPhoto = false
        actionHandler = { sentPhoto = it.actionId == "send-photo"; NexusPageResult.done() }
        invoke("send-photo", 3)
        assertTrue(sentPhoto)
        assertEquals(0, opens)
        deliver(BusPaths.PAGE_VISIBILITY, JSONObject().put("pageId", "photo:main").put("sessionGeneration", 1).put("frameIndex", 1).put("visible", false))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, preview(4)))
    }

    @Test fun `page surface approval never grants camera access`() {
        start(6, capabilities = "surfaces")
        val snapshot = client.snapshotSession(object : NexusSnapshotCallbacks {
            override fun onSnapshotCaptured(jpeg: ByteArray) = Unit
            override fun onSnapshotError(error: NexusSnapshotError) = Unit
        })
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, snapshot.capture())
        assertFalse(transport.sent.any { it.first == BusPaths.CAMERA_SNAPSHOT_REQUEST })
    }

    @Test fun `older pairs strip image replacements and invalid captions are refused before stripping`() {
        answer = NexusPage.Content(1, "Photo", PageBody.Summary(primary = "Photo"), listOf(NexusPageActionSpec("capture", "Photo")), true)
        start(5)
        actionHandler = { NexusPageResult.done(replacement = preview(2)) }
        invoke("capture")
        val replacement = transport.sent.last { it.first == BusPaths.PAGE_RESULT }.third.getJSONObject("replacement")
        assertEquals("summary", replacement.getString("template"))
        assertEquals(0, replacement.getJSONArray("actions").length())
        assertFalse(replacement.getJSONObject("body").has("image"))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishPage(request,
            preview(3).copy(body = PageBody.Image(image, "x".repeat(241)))))
    }
}
