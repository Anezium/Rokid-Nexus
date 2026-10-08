package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusPageTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val sendIds = mutableListOf<String>()
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            sendIds += id
            return true
        }
        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray) = true
        override fun capabilities(): Int = 0
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private inner class Provider : NexusPluginCallbacks {
        val events = mutableListOf<String>()
        val requests = mutableListOf<NexusPageRequest>()
        var answer: (NexusPageRequest) -> NexusPage = { content(it.pageId, revision = 1) }
        var actionAnswer: (NexusPageAction) -> NexusPageResult = { NexusPageResult.done() }
        override fun onOpen() { events += "open" }
        override fun onClose() { events += "close" }
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) { events += "registration:$result" }
        override fun onMessage(path: String, id: String, payload: JSONObject) { events += "message:$path" }
        override fun onPageRequest(request: NexusPageRequest): NexusPage {
            events += "request:${request.pageId}:${request.reason.wireValue}"
            requests += request
            return answer(request)
        }
        override fun onPageAction(action: NexusPageAction): NexusPageResult {
            events += "action:${action.actionId}@${action.revision}"
            return actionAnswer(action)
        }
        override fun onPageVisibility(visibility: NexusPageVisibility) {
            events += "visibility:${visibility.visible}:${visibility.leaseDurationMs}"
        }
        override fun onPageClosed(closed: NexusPageClosed) { events += "closed:${closed.reason}" }
    }

    private var now = 1_000L
    private var nextEventId = 0
    private val transport = FakeTransport()
    private val provider = Provider()
    private val client = NexusPluginClient("relay", provider, transport) { now }

    private fun register(capabilities: String = "surfaces", announce: Int? = 1) {
        client.connect()
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        deliver(BusPaths.PLUGIN_REGISTRATION, JSONObject().put("result", PluginRegistrationResult.APPROVED).put("capabilities", capabilities))
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        announce?.let { deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", it)) }
    }

    private fun deliver(path: String, payload: JSONObject, owner: String = "relay") {
        transport.listener.onMessage(path, "event-${nextEventId++}", payload.put("pluginId", owner))
    }

    private fun request(
        requestId: String = "r1",
        pageId: String = "relay:main",
        frameIndex: Int = 1,
        generation: Long = 1,
        reason: String = "open",
        registration: String = "reg-a",
        minimumRevision: Long? = null,
    ) = PageSurfaceContract.correlationPayload(
        com.anezium.rokidbus.shared.PageCorrelation(requestId, generation, frameIndex, pageId),
    ).put("reason", reason).put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration).apply {
        minimumRevision?.let { put(PageSurfaceContract.FIELD_MINIMUM_REVISION, it) }
    }

    private fun visibility(visible: Boolean, lease: Long? = 120_000, pageId: String = "relay:main", frameIndex: Int = 1, registration: String = "reg-a") =
        JSONObject().put("pageId", pageId).put("visible", visible).put("sessionGeneration", 1L).put("frameIndex", frameIndex)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration).apply {
                if (visible) {
                    put("leaseUntilMs", 999_999L)
                    lease?.let { put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, it) }
                }
            }

    private fun content(pageId: String, revision: Long, live: Boolean = true, body: PageBody = PageBody.SelectableList(listOf(PageRow("a", "Mika")))) =
        NexusPage.Content(revision, "Relay", body, live = live)

    private fun responses(): List<JSONObject> = transport.sends.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }

    @Test
    fun `all four page callbacks dispatch without an open and never close the plugin`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request())
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", "i1").put("sessionGeneration", 1L).put("frameIndex", 1).put("pageId", "relay:main")
                .put("revision", 1L).put("actionId", "read").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        deliver(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", "relay:main").put("reason", "back").put("sessionGeneration", 1L).put("frameIndex", 1).put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))

        assertEquals(
            listOf("request:relay:main:open", "visibility:true:120000", "action:read@1", "closed:back"),
            provider.events.filter { !it.startsWith("registration") },
        )
        assertFalse("open" in provider.events || "close" in provider.events)
        val response = responses().single()
        assertEquals("r1", response.getString("requestId"))
        assertEquals("selectableList", response.getString("template"))
        assertFalse(response.has("pluginId"))
        assertTrue(PageSurfaceContract.validateResponse(response) is PageSurfaceValidationResult.Valid)
        val result = transport.sends.single { it.first == BusPaths.PAGE_RESULT }.second
        assertEquals("i1", result.getString("invocationId"))
        assertEquals("done", result.getString("status"))
    }

    @Test
    fun `an open immersion survives page traffic and capability refreshes`() {
        register()
        deliver(BusPaths.PLUGIN_OPEN, JSONObject().put("type", "open"))
        deliver(BusPaths.PAGE_REQUEST, request())
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
        deliver(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", "relay:main").put("reason", "session_closed").put("sessionGeneration", 1L).put("frameIndex", 1).put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 0))
        assertEquals(listOf("open"), provider.events.filter { it == "open" || it == "close" })
        assertFalse(provider.events.any { it == "message:${BusPaths.HUB_CAPABILITIES}" })
    }

    @Test
    fun `no announced support, no surfaces grant or a foreign owner means no page traffic`() {
        register(announce = null)
        deliver(BusPaths.PAGE_REQUEST, request())
        assertFalse(client.supportsPageSession)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 2))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2"))
        assertFalse(client.supportsPageSession)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r3"), owner = "nav")
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r4", pageId = "nav:main"))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r5", pageId = "nexus:applications"))
        assertTrue(client.supportsPageSession)
        assertTrue(provider.requests.isEmpty())
        assertTrue(responses().isEmpty())

        val ungranted = NexusPageTest()
        ungranted.register(capabilities = "http_proxy")
        ungranted.deliver(BusPaths.PAGE_REQUEST, ungranted.request())
        assertFalse(ungranted.client.supportsPageSession)
        assertTrue(ungranted.provider.requests.isEmpty())
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, ungranted.client.publishPage(placeholderRequest(), ungranted.content("relay:main", 2)))
    }

    @Test
    fun `answers are correlated and refusals carry no private detail`() {
        register()
        provider.answer = { NexusPage.Unavailable }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        provider.answer = { throw IllegalStateException("secret thread name") }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2"))
        provider.answer = { content(it.pageId, revision = 3) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r3", reason = "refresh", minimumRevision = 4))
        provider.answer = {
            content(it.pageId, revision = 5, body = PageBody.SelectableList(listOf(PageRow("x", "X", target = PageTarget.Page("nav:main")))))
        }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r4"))
        val sent = responses()
        assertEquals(listOf("r1", "r2", "r3", "r4"), sent.map { it.getString("requestId") })
        assertEquals(listOf("PAGE_UNAVAILABLE", "PAGE_UNAVAILABLE", "INVALID_PAGE", "INVALID_PAGE"), sent.map { it.getString("error") })
        assertFalse(sent.any { "secret" in it.toString() })
        // Each answer has its own envelope id.
        assertEquals(transport.sendIds.size, transport.sendIds.toSet().size)
    }

    @Test
    fun `requests and actions dedupe by their semantic ids`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        val action = JSONObject().put("invocationId", "i1").put("sessionGeneration", 1L).put("frameIndex", 1).put("pageId", "relay:main")
            .put("revision", 1L).put("actionId", "read").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a")
        deliver(BusPaths.PAGE_ACTION, JSONObject(action.toString()))
        deliver(BusPaths.PAGE_ACTION, JSONObject(action.toString()))
        assertEquals(1, provider.requests.size)
        assertEquals(1, provider.events.count { it.startsWith("action:") })
        assertEquals(1, transport.sends.count { it.first == BusPaths.PAGE_RESULT })
    }

    @Test
    fun `a live revision needs the current visible request under an unexpired lease`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        val first = provider.requests.single()
        // Not yet visible.
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 60_000))
        assertEquals(NexusSdkResult.SENT, client.publishPage(first, content(first.pageId, 2)))
        // The same revision again, or a lower one, is not sent twice.
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishPage(first, content(first.pageId, 2)))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishPage(first, content(first.pageId, 1)))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 3, live = false)))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishPage(first, NexusPage.Unavailable))
        // The lease runs on this device's own clock, whatever the glasses' clock says.
        now += 59_999
        assertEquals(NexusSdkResult.SENT, client.publishPage(first, content(first.pageId, 3)))
        now += 1
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 4)))
        val revisions = responses().drop(1).map { it.getLong("revision") }
        assertEquals(listOf(2L, 3L), revisions)
        assertTrue(responses().drop(1).all { it.getString("requestId") == "r1" })
    }

    @Test
    fun `cover, refresh and close move a frame's publishing right`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        val first = provider.requests.single()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(false))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))
        // Uncover renews the lease, then a refresh request takes over the frame.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "refresh", minimumRevision = 1))
        val refreshed = provider.requests.last()
        assertEquals(1L, refreshed.minimumRevision)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 5)))
        assertEquals(NexusSdkResult.SENT, client.publishPage(refreshed, content(first.pageId, 5)))
        deliver(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", "relay:main").put("reason", "back").put("sessionGeneration", 1L).put("frameIndex", 1).put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(refreshed, content(first.pageId, 6)))
    }

    @Test
    fun `callbacks from another registration epoch are ignored and void the old frames`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1", registration = "reg-a"))
        val first = provider.requests.single()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, registration = "reg-b"))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))
        assertFalse(provider.events.any { it.startsWith("visibility") })
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, registration = "reg-a"))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", frameIndex = 2, pageId = "relay:thread", registration = "reg-b"))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))
    }

    @Test
    fun `a new registration, revocation, link loss or close cancel page work`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        val first = provider.requests.single()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        assertFalse(client.supportsPageSession)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))

        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2"))
        val second = provider.requests.last()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        transport.listener.onLinkState(0)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(second, content(second.pageId, 2)))
        transport.listener.onLinkState(LinkStateBits.SPP_DATA_UP)
        assertTrue(client.supportsPageSession)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(second, content(second.pageId, 2)))

        transport.listener.onRegistrationState(PluginRegistrationResult.DENIED)
        assertEquals(NexusSdkResult.NOT_REGISTERED, client.publishPage(second, content(second.pageId, 3)))
        client.close()
        assertFalse(client.supportsPageSession)
    }

    @Test
    fun `action results are bounded and an invalid replacement becomes a rejection`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        fun act(id: String) = deliver(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", id).put("sessionGeneration", 1L).put("frameIndex", 1).put("pageId", "relay:main")
                .put("revision", 1L).put("actionId", "read").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "reg-a"),
        )
        provider.actionAnswer = { NexusPageResult.done("Marked", content("relay:main", 2)) }
        act("i1")
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 3, body = PageBody.SelectableList(listOf(PageRow("x", "X", target = PageTarget.Page("nav:main")))))) }
        act("i2")
        provider.actionAnswer = { throw IllegalStateException("token=secret") }
        act("i3")
        provider.actionAnswer = { NexusPageResult.stale("x".repeat(65)) }
        act("i4")
        val results = transport.sends.filter { it.first == BusPaths.PAGE_RESULT }.map { it.second }
        assertEquals(listOf("done", "rejected", "rejected", "rejected"), results.map { it.getString("status") })
        val replacement = results[0].getJSONObject("replacement")
        assertEquals("r1", replacement.getString("requestId"))
        assertEquals(2L, replacement.getLong("revision"))
        assertTrue(PageSurfaceContract.validateBoundedResult(results[0]) is PageSurfaceValidationResult.Valid)
        assertFalse(results.drop(1).any { it.has("message") || it.has("replacement") })
        assertFalse(results.any { "secret" in it.toString() })
    }

    @Test
    fun `typed content is validated by the shared codec before it leaves`() {
        register()
        provider.answer = {
            NexusPage.Content(
                revision = 1,
                title = "Navigation",
                body = PageBody.Commands(listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open")))),
                actions = listOf(NexusPageActionSpec("open", "Open full app", NexusPageActionKind.IMMERSION, confirm = false)),
            )
        }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1", pageId = "relay:commands"))
        val page = (PageSurfaceContract.validateResponse(responses().single()) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
        assertEquals("commands", page.template)
        assertEquals("immersion", page.actions.single().kind)
        // An undeclared action target never leaves the provider.
        provider.answer = { NexusPage.Content(1, "Bad", PageBody.Commands(listOf(PageRow("x", "X", target = PageTarget.Action("missing"))))) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", pageId = "relay:commands"))
        assertEquals("INVALID_PAGE", responses().last().getString("error"))
    }

    private fun placeholderRequest(): NexusPageRequest =
        NexusPageRequest("r", 1, 1, "relay:main", NexusPageRequestReason.OPEN, null, null, "reg-a")
}
