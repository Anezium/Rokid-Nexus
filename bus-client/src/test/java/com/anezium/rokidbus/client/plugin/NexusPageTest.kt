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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class NexusPageTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        val sends: MutableList<Pair<String, JSONObject>> = Collections.synchronizedList(mutableListOf())
        val sendIds: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var accept = true

        /** When set, a send signals [entered] and then waits for [release]: a send in flight. */
        @Volatile var entered: CountDownLatch? = null
        @Volatile var release: CountDownLatch? = null
        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }
        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            // The test clears the hooks as soon as entered is signalled.
            val pendingRelease = release
            entered?.countDown()
            pendingRelease?.await(5, TimeUnit.SECONDS)
            sends += path to JSONObject(payload.toString())
            sendIds += id
            return accept
        }
        override fun sendImmediate(path: String, id: String, payload: JSONObject) = send(path, id, payload)
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

    /** Every fixture page advertises the `read` action the tests invoke. */
    private fun content(pageId: String, revision: Long, live: Boolean = true, body: PageBody = PageBody.SelectableList(listOf(PageRow("a", "Mika")))) =
        NexusPage.Content(revision, "Relay", body, actions = listOf(NexusPageActionSpec("read", "Read")), live = live)

    private fun action(
        invocationId: String,
        revision: Long = 1,
        actionId: String = "read",
        registration: String = "reg-a",
        frameIndex: Int = 1,
        generation: Long = 1,
        pageId: String = "relay:main",
    ) = JSONObject().put("invocationId", invocationId).put("sessionGeneration", generation).put("frameIndex", frameIndex)
        .put("pageId", pageId).put("revision", revision).put("actionId", actionId)
        .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration)

    private fun closed(registration: String = "reg-a", frameIndex: Int = 1, pageId: String = "relay:main") =
        JSONObject().put("pageId", pageId).put("reason", "back").put("sessionGeneration", 1L).put("frameIndex", frameIndex)
            .put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, registration)

    private fun results() = transport.sends.filter { it.first == BusPaths.PAGE_RESULT }.map { it.second }

    private fun responses(): List<JSONObject> = transport.sends.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }

    @Test
    fun `all four page callbacks dispatch without an open and never close the plugin`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request())
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_ACTION, action("i1"))
        deliver(BusPaths.PAGE_CLOSED, closed())

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
        // A wearer can only act on a page shown under its lease.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_ACTION, action("i1"))
        deliver(BusPaths.PAGE_ACTION, action("i1"))
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
    fun `another registration epoch never replaces the anchored one, even with every frame closed`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1", registration = "reg-a"))
        val first = provider.requests.single()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, registration = "reg-b"))
        assertFalse(provider.events.any { it.startsWith("visibility") })
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, registration = "reg-a"))
        // A request from another epoch is refused instead of voiding the current work.
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", frameIndex = 2, pageId = "relay:thread", registration = "reg-b"))
        assertEquals(1, provider.requests.size)
        assertEquals(NexusSdkResult.SENT, client.publishPage(first, content(first.pageId, 2)))
        // Closing every frame keeps the epoch anchored: reg-b is still not this registration.
        deliver(BusPaths.PAGE_CLOSED, closed())
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r3", registration = "reg-b"))
        assertEquals(1, provider.requests.size)
        // Only a real registration reset lets a new epoch in.
        transport.listener.onRegistrationState(PluginRegistrationResult.APPROVED)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r4", registration = "reg-b"))
        assertEquals("r4", provider.requests.last().requestId)
    }

    @Test
    fun `an action reaches the plugin only for an accepted page at its revision and advertised action`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        // A wearer can only act on a page shown under its lease.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        provider.answer = { content(it.pageId, revision = 1) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", frameIndex = 2, pageId = "relay:thread"))
        deliver(BusPaths.PAGE_CLOSED, closed(frameIndex = 2, pageId = "relay:thread"))
        listOf(
            action("old-epoch", registration = "reg-old"),
            action("closed-frame", frameIndex = 2, pageId = "relay:thread"),
            action("unknown-frame", frameIndex = 3, pageId = "relay:other"),
            action("unknown-generation", generation = 9),
            action("wrong-revision", revision = 2),
            action("undeclared", actionId = "delete"),
            action("wrong-page", pageId = "relay:else"),
        ).forEach { deliver(BusPaths.PAGE_ACTION, it) }
        assertFalse(provider.events.any { it.startsWith("action:") })
        assertTrue(results().isEmpty())
        // The old epoch did not displace the accepted page either.
        deliver(BusPaths.PAGE_ACTION, action("good"))
        assertEquals(listOf("action:read@1"), provider.events.filter { it.startsWith("action:") })
        assertEquals("good", results().single().getString("invocationId"))
    }

    @Test
    fun `an action reaches the plugin only while its frame is visible under an unexpired lease`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 1_000))
        deliver(BusPaths.PAGE_ACTION, action("visible"))
        // Covered: the glasses no longer show it, so a delayed action does nothing.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(false))
        deliver(BusPaths.PAGE_ACTION, action("covered"))
        // Uncovered with a new lease, then reached at the exact end of it.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 1_000))
        now += 1_000
        deliver(BusPaths.PAGE_ACTION, action("expired"))
        // A renewed lease makes it actionable again.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 1_000))
        deliver(BusPaths.PAGE_ACTION, action("renewed"))
        assertEquals(listOf("visible", "renewed"), results().map { it.getString("invocationId") })
        assertEquals(2, provider.events.count { it.startsWith("action:") })
    }

    @Test
    fun `an action on a page answered unavailable or not yet answered reaches nothing`() {
        register()
        provider.answer = { NexusPage.Unavailable }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_ACTION, action("i1"))
        assertFalse(provider.events.any { it.startsWith("action:") })
        assertTrue(results().isEmpty())
    }

    @Test
    fun `a bond or worn state alone carries no page and loss needs a fresh capability update`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        val first = provider.requests.single()
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        listOf(LinkStateBits.GLASSES_BT_BONDED_OR_PHONE_CONNECTED, LinkStateBits.GLASSES_WORN, 0).forEach { state ->
            transport.listener.onLinkState(state)
            assertFalse("state=$state", client.supportsPageSession)
        }
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(first, content(first.pageId, 2)))
        // A request and an action delayed past the loss are not dispatched.
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "late"))
        deliver(BusPaths.PAGE_ACTION, action("late-action"))
        assertEquals(1, provider.requests.size)
        assertFalse(provider.events.any { it.startsWith("action:") })
        // The link coming back is not enough: support waits for the hub's next update.
        transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        assertFalse(client.supportsPageSession)
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2"))
        assertEquals(1, provider.requests.size)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
        assertTrue(client.supportsPageSession)
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r3"))
        assertEquals("r3", provider.requests.last().requestId)
    }

    @Test
    fun `a new registration, revocation or close cancel page work`() {
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
        assertFalse(client.supportsPageSession)
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1))
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
        // A wearer can only act on a page shown under its lease.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        provider.actionAnswer = { NexusPageResult.done("Marked", content("relay:main", 2)) }
        deliver(BusPaths.PAGE_ACTION, action("i1", revision = 1))
        // The replacement is now what the glasses show: actions target its revision.
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 3, body = PageBody.SelectableList(listOf(PageRow("x", "X", target = PageTarget.Page("nav:main")))))) }
        deliver(BusPaths.PAGE_ACTION, action("i2", revision = 2))
        provider.actionAnswer = { throw IllegalStateException("token=secret") }
        deliver(BusPaths.PAGE_ACTION, action("i3", revision = 2))
        provider.actionAnswer = { NexusPageResult.stale("x".repeat(65)) }
        deliver(BusPaths.PAGE_ACTION, action("i4", revision = 2))
        val results = results()
        assertEquals(listOf("done", "rejected", "rejected", "rejected"), results.map { it.getString("status") })
        val replacement = results[0].getJSONObject("replacement")
        assertEquals("r1", replacement.getString("requestId"))
        assertEquals(2L, replacement.getLong("revision"))
        assertTrue(PageSurfaceContract.validateBoundedResult(results[0]) is PageSurfaceValidationResult.Valid)
        assertFalse(results.drop(1).any { it.has("message") || it.has("replacement") })
        assertFalse(results.any { "secret" in it.toString() })
    }

    @Test
    fun `a sent replacement decides whether its page may publish live revisions`() {
        register()
        provider.answer = { content(it.pageId, revision = 1, live = false) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val request = provider.requests.single()
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 2)))

        // A live replacement makes the page live.
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 3, live = true)) }
        deliver(BusPaths.PAGE_ACTION, action("to-live", revision = 1))
        assertEquals(NexusSdkResult.SENT, client.publishPage(request, content(request.pageId, 4)))

        // A static replacement ends live publishing.
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 5, live = false)) }
        deliver(BusPaths.PAGE_ACTION, action("to-static", revision = 4))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 6)))

        // A live replacement that could not be sent grants nothing.
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 7, live = true)) }
        transport.accept = false
        deliver(BusPaths.PAGE_ACTION, action("unsent", revision = 5))
        transport.accept = true
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 8)))
    }

    @Test
    fun `a replacement must be above the acted revision and everything sent since`() {
        register()
        provider.answer = { content(it.pageId, revision = 4) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        // A wearer can only act on a page shown under its lease.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 4)) }
        deliver(BusPaths.PAGE_ACTION, action("equal", revision = 4))
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 3)) }
        deliver(BusPaths.PAGE_ACTION, action("lower", revision = 4))
        // A live revision taken meanwhile raises the bar for a replacement too.
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val request = provider.requests.single()
        assertEquals(NexusSdkResult.SENT, client.publishPage(request, content("relay:main", 6)))
        provider.actionAnswer = { NexusPageResult.done(replacement = content("relay:main", 5)) }
        deliver(BusPaths.PAGE_ACTION, action("below-live", revision = 6))
        provider.actionAnswer = { NexusPageResult.done("Done", replacement = content("relay:main", 7)) }
        deliver(BusPaths.PAGE_ACTION, action("higher", revision = 6))
        val results = results()
        assertEquals(listOf("rejected", "rejected", "rejected", "done"), results.map { it.getString("status") })
        assertFalse(results.take(3).any { it.has("replacement") })
        assertEquals(7L, results.last().getJSONObject("replacement").getLong("revision"))
    }

    @Test
    fun `a refresh publishes nothing until its own valid live answer is sent`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val refreshPublishes = mutableListOf<NexusSdkResult>()
        provider.answer = { refresh ->
            // Publishing from inside the refresh callback, before it is answered, is refused.
            refreshPublishes += client.publishPage(refresh, content(refresh.pageId, 9))
            content(refresh.pageId, revision = 2)
        }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "refresh", minimumRevision = 1))
        assertEquals(listOf(NexusSdkResult.CAPABILITY_NOT_AVAILABLE), refreshPublishes)
        val refreshed = provider.requests.last()
        assertEquals(NexusSdkResult.SENT, client.publishPage(refreshed, content(refreshed.pageId, 3)))

        fun refusedAfter(requestId: String, answer: (NexusPageRequest) -> NexusPage, floor: Long? = null) {
            provider.answer = answer
            deliver(BusPaths.PAGE_REQUEST, request(requestId = requestId, reason = "refresh", minimumRevision = floor))
            val current = provider.requests.last()
            assertEquals(requestId, NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(current, content(current.pageId, 20)))
        }
        refusedAfter("unavailable", { NexusPage.Unavailable })
        refusedAfter("invalid", { NexusPage.Content(30, "Bad", PageBody.Commands(listOf(PageRow("x", "X", target = PageTarget.Action("missing"))))) })
        refusedAfter("below-floor", { content(it.pageId, revision = 2) }, floor = 3)
        refusedAfter("not-live", { content(it.pageId, revision = 4, live = false) })
        // A live answer whose send failed grants nothing either.
        transport.accept = false
        refusedAfter("send-failed", { content(it.pageId, revision = 5) })
    }

    private fun dispatched() = provider.events.filter { it.startsWith("action:") }

    /**
     * A page accepted at revision 3, whose action is shown to reach the plugin; then a refresh
     * answered by [answer], with old-revision actions while it waits and after it.
     */
    private fun refreshThenOldAction(answer: (NexusPageRequest) -> NexusPage, floor: Long? = null, sendFails: Boolean = false) {
        register()
        provider.answer = { content(it.pageId, revision = 3) }
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_ACTION, action("before", revision = 3))
        assertEquals(listOf("action:read@3"), dispatched())

        provider.answer = { refresh ->
            // An action arriving while this refresh awaits its answer reaches nothing.
            deliver(BusPaths.PAGE_ACTION, action("during", revision = 3))
            answer(refresh)
        }
        transport.accept = !sendFails
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "refresh", minimumRevision = floor))
        transport.accept = true
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_ACTION, action("after", revision = 3))
    }

    private fun assertOnlyTheFirstAction() {
        assertEquals(listOf("action:read@3"), dispatched())
        assertEquals(listOf("before"), results().map { it.getString("invocationId") })
    }

    @Test
    fun `an action on the old revision reaches nothing after an Unavailable refresh`() {
        refreshThenOldAction({ NexusPage.Unavailable })
        assertOnlyTheFirstAction()
    }

    @Test
    fun `an action on the old revision reaches nothing after an invalid refresh answer`() {
        refreshThenOldAction({ NexusPage.Content(4, "Bad", PageBody.Commands(listOf(PageRow("x", "X", target = PageTarget.Action("missing"))))) })
        assertOnlyTheFirstAction()
    }

    @Test
    fun `an action on the old revision reaches nothing after a refresh answer below the floor`() {
        refreshThenOldAction({ content(it.pageId, revision = 2) }, floor = 3)
        assertOnlyTheFirstAction()
    }

    @Test
    fun `an action on the old revision reaches nothing after a refresh answer that was not sent`() {
        refreshThenOldAction({ content(it.pageId, revision = 5) }, sendFails = true)
        assertOnlyTheFirstAction()
    }

    @Test
    fun `a valid refresh that was sent makes only its own revision actionable`() {
        refreshThenOldAction({ content(it.pageId, revision = 6) }, floor = 3)
        deliver(BusPaths.PAGE_ACTION, action("new", revision = 6))
        assertEquals(listOf("action:read@3", "action:read@6"), dispatched())
        assertEquals(listOf("before", "new"), results().map { it.getString("invocationId") })
    }

    /** Runs [send] on another thread with its transport send held open while [during] runs. */
    private fun duringBlockedSend(send: () -> Unit, during: () -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.entered = entered
        transport.release = release
        val sender = thread { send() }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        transport.entered = null
        transport.release = null
        during()
        release.countDown()
        sender.join(5_000)
    }

    private fun sentActionIds(payload: JSONObject): List<String> {
        val actions = payload.getJSONArray("actions")
        return (0 until actions.length()).map { actions.getJSONObject(it).getString("id") }
    }

    @Test
    fun `what may be acted on is exactly what was sent, whatever the caller does to its list`() {
        register()
        val body = PageBody.SelectableList(listOf(PageRow("a", "Mika")))
        val answered = mutableListOf(NexusPageActionSpec("read", "Read"))
        provider.answer = { NexusPage.Content(1, "Relay", body, actions = answered, live = true) }
        duringBlockedSend({ deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1")) }) {
            answered.clear()
            answered += NexusPageActionSpec("delete", "Delete")
        }
        assertEquals(listOf("read"), sentActionIds(responses().last()))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.PAGE_ACTION, action("i1", revision = 1, actionId = "delete"))
        deliver(BusPaths.PAGE_ACTION, action("i2", revision = 1, actionId = "read"))

        // A publication records what it sent, too.
        val request = provider.requests.single()
        val published = mutableListOf(NexusPageActionSpec("archive", "Archive"))
        duringBlockedSend({ client.publishPage(request, NexusPage.Content(2, "Relay", body, actions = published, live = true)) }) {
            published.clear()
        }
        assertEquals(listOf("archive"), sentActionIds(responses().last()))
        deliver(BusPaths.PAGE_ACTION, action("i3", revision = 2, actionId = "archive"))

        // And so does a result's replacement.
        val replaced = mutableListOf(NexusPageActionSpec("pin", "Pin"))
        provider.actionAnswer = { NexusPageResult.done(replacement = NexusPage.Content(3, "Relay", body, actions = replaced, live = true)) }
        duringBlockedSend({ deliver(BusPaths.PAGE_ACTION, action("i4", revision = 2, actionId = "archive")) }) {
            replaced.clear()
            replaced += NexusPageActionSpec("unpin", "Unpin")
        }
        provider.actionAnswer = { NexusPageResult.done() }
        assertEquals(listOf("pin"), sentActionIds(results().last().getJSONObject("replacement")))
        deliver(BusPaths.PAGE_ACTION, action("i5", revision = 3, actionId = "unpin"))
        deliver(BusPaths.PAGE_ACTION, action("i6", revision = 3, actionId = "pin"))

        assertEquals(
            listOf("action:read@1", "action:archive@2", "action:archive@2", "action:pin@3"),
            provider.events.filter { it.startsWith("action:") },
        )
    }

    /** Waits until every one of [threads] has started and is blocked on a monitor. */
    private fun awaitBlocked(started: CountDownLatch, vararg threads: Thread) {
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (threads.any { it.state != Thread.State.BLOCKED }) {
            assertTrue("threads never blocked: ${threads.map { it.state }}", System.nanoTime() < deadline)
            Thread.onSpinWait()
        }
    }

    @Test
    fun `concurrent publishes are serialized, counted and never go back`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val request = provider.requests.single()
        val before = responses().size
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.entered = entered
        transport.release = release
        var first: NexusSdkResult? = null
        val holder = thread { first = client.publishPage(request, content(request.pageId, 2)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        transport.entered = null
        transport.release = null
        // While revision 2 is in flight, the same revision and a later one queue behind it.
        var same: NexusSdkResult? = null
        var later: NexusSdkResult? = null
        val started = CountDownLatch(2)
        val sameThread = thread {
            started.countDown()
            same = client.publishPage(request, content(request.pageId, 2))
        }
        val laterThread = thread {
            started.countDown()
            later = client.publishPage(request, content(request.pageId, 3))
        }
        awaitBlocked(started, sameThread, laterThread)
        assertNull(same)
        assertNull(later)
        assertEquals(before, responses().size)
        release.countDown()
        listOf(holder, sameThread, laterThread).forEach { it.join(5_000) }
        assertEquals(NexusSdkResult.SENT, first)
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, same)
        assertEquals(NexusSdkResult.SENT, later)
        val revisions = responses().drop(before).map { it.getLong("revision") }
        assertEquals(listOf(2L, 3L), revisions)
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishPage(request, content(request.pageId, 3)))
    }

    @Test
    fun `a close during a publication applies after it and stops the next one`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val request = provider.requests.single()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        transport.entered = entered
        transport.release = release
        var inFlight: NexusSdkResult? = null
        val publisher = thread { inFlight = client.publishPage(request, content(request.pageId, 2)) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        transport.entered = null
        transport.release = null
        var closedSeen = false
        val started = CountDownLatch(1)
        val closer = thread {
            started.countDown()
            deliver(BusPaths.PAGE_CLOSED, closed())
            closedSeen = true
        }
        awaitBlocked(started, closer)
        assertFalse(closedSeen)
        release.countDown()
        publisher.join(5_000)
        closer.join(5_000)
        assertEquals(NexusSdkResult.SENT, inFlight)
        assertTrue(closedSeen)
        val sends = responses().size
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 3)))
        assertEquals(sends, responses().size)
    }

    @Test
    fun `a cover or a lost capability before a publication sends nothing`() {
        register()
        deliver(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        val request = provider.requests.single()
        val sends = responses().size
        deliver(BusPaths.PAGE_VISIBILITY, visibility(false))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 2)))
        deliver(BusPaths.PAGE_VISIBILITY, visibility(true))
        deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 0))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, client.publishPage(request, content(request.pageId, 2)))
        assertEquals(sends, responses().size)
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
