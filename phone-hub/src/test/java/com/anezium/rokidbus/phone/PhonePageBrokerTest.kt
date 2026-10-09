package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The production broker against a fake hub: authenticated registrations, a glasses link, bindings. */
class PhonePageBrokerTest {
    private inner class FakeHost : PhonePageHost {
        var now = 10_000L
        val providers = linkedMapOf<String, PhonePluginPrincipal>()
        val live = linkedMapOf<String, MutableList<PageRegistration>>()
        val delivered = mutableListOf<Triple<String, String, JSONObject>>()
        val glasses = mutableListOf<Pair<String, JSONObject>>()
        val calls = mutableListOf<String>()
        var glassesError: String? = null
        var bindAccepted = true
        var tick: Long? = null

        /** Runs once, inside the broker's flush, when a delivery of [path] goes out. */
        var onDelivered: Pair<String, () -> Unit>? = null

        /** Without SPP, what needs the data plane cannot reach the glasses, as on the real transport. */
        var sppUp = true

        /** Runs once, before the send returns, when a page of that revision goes to the glasses. */
        var onGlassesSend: Pair<Long, () -> Unit>? = null

        /** Runs once after the transport took a page of that revision, before the broker hears of it. */
        var onGlassesSent: Pair<Long, () -> Unit>? = null

        override fun nowMs(): Long = now
        override fun pageProvider(pluginId: String): PhonePluginPrincipal? = providers[pluginId]
        override fun registrations(principal: PhonePluginPrincipal): List<PageRegistration> =
            live[principal.descriptor.id].orEmpty().toList()
        override fun providerRegistrations(): List<PageRegistration> =
            live.filterKeys { providers[it]?.descriptor?.pageSessionVersion == 1 }.values.flatten()
        override fun deliver(registration: PageRegistration, path: String, payload: JSONObject): Boolean {
            delivered += Triple(registration.epoch, path, JSONObject(payload.toString()))
            calls += "deliver:${registration.epoch}:$path"
            onDelivered?.takeIf { it.first == path }?.let { (_, hook) ->
                onDelivered = null
                hook()
            }
            return live[registration.pluginId]?.any { it.epoch == registration.epoch } == true
        }
        override fun sendToGlasses(path: String, payload: JSONObject): String? {
            onGlassesSend?.takeIf { payload.optLong("revision", -1) == it.first }?.let { (_, hook) ->
                onGlassesSend = null
                hook()
            }
            if (!sppUp && PageSurfaceContract.needsDataPlane(path, payload)) return "NO_DATA_PLANE"
            glasses += path to JSONObject(payload.toString())
            onGlassesSent?.takeIf { payload.optLong("revision", -1) == it.first && glassesError == null }?.let { (_, hook) ->
                onGlassesSent = null
                hook()
            }
            return glassesError
        }
        override fun bind(principal: PhonePluginPrincipal): Boolean {
            calls += "bind:${principal.descriptor.id}"
            return bindAccepted
        }
        override fun unbind(principal: PhonePluginPrincipal) {
            calls += "unbind:${principal.descriptor.id}"
        }
        override fun scheduleTick(atMs: Long?) {
            tick = atMs
        }
        override fun log(message: String) {
            // Page content must never reach a log line.
            assertFalse(message, "Secret" in message)
        }
    }

    private val host = FakeHost()
    private val broker = PhonePageBroker(host, localVersion = PageSurfaceContract.VERSION)
    private val relay = principal("relay")
    private val nav = principal("nav")

    private fun principal(id: String, pages: Int = 1) = PhonePluginPrincipal(
        packageName = "dev.example.$id",
        serviceComponent = ComponentName("dev.example.$id", "dev.example.$id.Service"),
        uid = 10_000 + id.length,
        signingDigestSha256 = "digest-$id",
        descriptor = PluginDescriptor(
            id, id, 3, setOf(PluginCapability.SURFACES), listOf("/plugin/$id", "/system/plugin"), null, true,
            pageSessionVersion = pages,
        ),
    )

    private fun registration(principal: PhonePluginPrincipal, epoch: String) =
        PageRegistration(epoch, principal.descriptor.id, principal.grantKey())

    private fun connect(vararg principals: PhonePluginPrincipal) {
        principals.forEach { host.providers[it.descriptor.id] = it }
        broker.onGlassesCapabilities(PageSurfaceContract.VERSION)
    }

    private fun register(principal: PhonePluginPrincipal, epoch: String): PageRegistration {
        val registration = registration(principal, epoch)
        host.live.getOrPut(principal.descriptor.id) { mutableListOf() } += registration
        broker.onRegistered(principal)
        return registration
    }

    private fun request(
        requestId: String = "r1",
        pageId: String = "relay:main",
        frameIndex: Int = 1,
        generation: Long = 1,
        reason: String = "open",
        remaining: Long? = 8_000,
    ) = PageSurfaceContract.correlationPayload(PageCorrelation(requestId, generation, frameIndex, pageId))
        .put("reason", reason)
        .apply { remaining?.let { put(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS, it) } }

    private fun page(
        requestId: String = "r1",
        pageId: String = "relay:main",
        frameIndex: Int = 1,
        revision: Long = 1,
        live: Boolean = true,
        body: JSONObject = JSONObject().put("items", JSONArray().put(JSONObject().put("id", "a").put("label", "Mika"))),
        template: String = "selectableList",
        actions: JSONArray = JSONArray().put(JSONObject().put("id", "read").put("label", "Read").put("kind", "plugin").put("confirm", true)),
    ) = PageSurfaceContract.correlationPayload(PageCorrelation(requestId, 1, frameIndex, pageId))
        .put("revision", revision).put("template", template).put("title", "Relay")
        .put("body", body).put("actions", actions).put("live", live)

    private fun visibility(visible: Boolean, lease: Long? = 120_000, pageId: String = "relay:main", frameIndex: Int = 1) =
        JSONObject().put("pageId", pageId).put("visible", visible).put("sessionGeneration", 1L).put("frameIndex", frameIndex)
            .apply {
                if (visible) {
                    // The glasses' own clock: meaningless here, and far from the phone's.
                    put("leaseUntilMs", 987_654_321L)
                    lease?.let { put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, it) }
                }
            }

    private fun action(invocationId: String = "i1", revision: Long = 1, actionId: String = "read", pageId: String = "relay:main") =
        JSONObject().put("invocationId", invocationId).put("sessionGeneration", 1L).put("frameIndex", 1)
            .put("pageId", pageId).put("revision", revision).put("actionId", actionId)

    private fun result(invocationId: String = "i1", status: String = "done") =
        JSONObject().put("invocationId", invocationId).put("status", status)

    private fun requestsTo(epoch: String) = host.delivered.filter { it.first == epoch && it.second == BusPaths.PAGE_REQUEST }
    private fun responses() = host.glasses.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }

    @Test
    fun `a request reaches only the owner's single live registration stamped with its epoch`() {
        connect(relay, nav)
        register(relay, "relay-a")
        register(nav, "nav-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())

        val delivered = requestsTo("relay-a").single().third
        assertEquals("relay", delivered.getString("pluginId"))
        assertEquals("relay-a", delivered.getString(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID))
        assertTrue(requestsTo("nav-a").isEmpty())
        assertTrue(host.calls.none { it.startsWith("deliver:nav-a:${BusPaths.PAGE_REQUEST}") })
    }

    @Test
    fun `the owner is stamped from the sender and a cross-owner answer is refused`() {
        connect(relay, nav)
        val relayA = register(relay, "relay-a")
        val navA = register(nav, "nav-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())

        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), navA))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page().put("pluginId", "nav").put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, "forged"), relayA))
        val forwarded = responses().single()
        assertEquals("relay", forwarded.getString("pluginId"))
        assertEquals("relay-a", forwarded.getString(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID))
    }

    @Test
    fun `missing or revoked grants, missing metadata and local pages are answered unavailable`() {
        connect(relay)
        register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r1", pageId = "nav:main"))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", pageId = "nexus:applications", frameIndex = 2))
        assertEquals(listOf("PAGE_UNAVAILABLE", "PAGE_UNAVAILABLE"), responses().map { it.getString("error") })
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_REQUEST })
        // A sender that no longer matches the catalog principal is denied outright.
        host.providers.remove("relay")
        assertEquals(PhonePageBroker.ERROR_PAGE_ROUTE_DENIED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration(relay, "relay-a")))
        assertEquals(PhonePageBroker.ERROR_PAGE_ROUTE_DENIED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), null))
    }

    @Test
    fun `without both hubs on version one nothing is routed`() {
        host.providers["relay"] = relay
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        broker.onGlassesCapabilities(2)
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2"))
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_REQUEST })
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))

        val oldPhone = PhonePageBroker(host, localVersion = 0)
        oldPhone.onGlassesCapabilities(PageSurfaceContract.VERSION)
        assertEquals(0, oldPhone.negotiatedVersion)
        oldPhone.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r3"))
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_REQUEST })
    }

    @Test
    fun `plugins may only answer and the glasses may only ask`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        listOf(BusPaths.PAGE_REQUEST, BusPaths.PAGE_ACTION, BusPaths.PAGE_VISIBILITY, BusPaths.PAGE_CLOSED, "/page/other").forEach {
            assertEquals(it, PhonePageBroker.ERROR_PAGE_ROUTE_DENIED, broker.onProvider(it, request(), relayA))
        }
        assertTrue(broker.onGlasses(BusPaths.PAGE_RESPONSE, page()))
        assertTrue(broker.onGlasses(BusPaths.PAGE_RESULT, result()))
        assertTrue(broker.onGlasses("/page/other", JSONObject()))
        assertFalse(broker.onGlasses("/plugin/relay/x", JSONObject()))
        assertTrue(host.delivered.none { it.second.startsWith("/page") })
        assertTrue(host.glasses.isEmpty())
    }

    @Test
    fun `unsolicited answers, results and live revisions fail closed`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESULT, result(), relayA))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1, live = false), relayA))
        // Not visible yet, then visible but not live: neither carries a revision.
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        assertEquals(1, responses().size)
    }

    @Test
    fun `a replaced registration's old work is invalidated visibly and its epoch is dead`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))

        // The same app registers again: a new epoch, and the frame shown under the old one ends.
        host.live["relay"] = mutableListOf()
        broker.onRegistrationRemoved(relayA)
        val relayB = register(relay, "relay-b")
        val invalidation = responses().last()
        assertEquals("PAGE_UNAVAILABLE", invalidation.getString("error"))
        assertEquals("r1", invalidation.getString("requestId"))
        assertEquals("relay", invalidation.getString("pluginId"))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayB))
        broker.onGlasses(BusPaths.PAGE_ACTION, action())
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_ACTION })
    }

    @Test
    fun `two live clients for one plugin are ambiguous and get nothing`() {
        connect(relay)
        register(relay, "relay-a")
        register(relay, "relay-b")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_REQUEST })
        assertEquals("PAGE_UNAVAILABLE", responses().single().getString("error"))
    }

    @Test
    fun `a cold provider is bound and registered within the absolute budget`() {
        connect(relay)
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(remaining = 7_000))
        assertEquals(listOf("bind:relay"), host.calls)
        // Cold registration may take at most five of the seven seconds left.
        assertEquals(15_000L, host.tick)
        host.now += 4_000
        val relayA = register(relay, "relay-a")
        val delivered = requestsTo("relay-a").single().third
        assertEquals(3_000L, delivered.getLong(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS))
        // The provider heard its page version before the request.
        val order = host.delivered.filter { it.first == "relay-a" }.map { it.second }
        assertEquals(listOf(BusPaths.HUB_CAPABILITIES, BusPaths.PAGE_REQUEST), order)
        assertEquals(1, host.delivered.first().third.getInt("pageSessionVersion"))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
    }

    @Test
    fun `a cold registration past its budget is unavailable, never a launch or a rebind loop`() {
        connect(relay)
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(remaining = 3_000))
        assertEquals(13_000L, host.tick)
        host.now = 13_000
        broker.onTick()
        assertEquals("PAGE_UNAVAILABLE", responses().single().getString("error"))
        assertEquals(listOf("bind:relay", "unbind:relay"), host.calls)
        // A late registration finds nothing waiting.
        register(relay, "relay-a")
        assertTrue(requestsTo("relay-a").isEmpty())

        // A bind the system refuses is answered at once and not retried.
        host.live["relay"] = mutableListOf()
        host.bindAccepted = false
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", generation = 2))
        assertEquals(listOf("PAGE_UNAVAILABLE", "PAGE_UNAVAILABLE"), responses().map { it.getString("error") })
        assertEquals(2, host.calls.count { it == "bind:relay" })
    }

    @Test
    fun `a reply exactly at its deadline is missed even without a tick`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(remaining = 8_000))
        host.now += 8_000
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        assertTrue(responses().isEmpty())
    }

    @Test
    fun `a retry takes over the frame and the old request is rejected`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r1"))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "retry"))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(requestId = "r1"), relayA))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(requestId = "r2"), relayA))
        assertEquals(listOf("r2"), responses().map { it.getString("requestId") })
    }

    @Test
    fun `live revisions follow the phone-side lease, not the glasses clock, and never roll back`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 60_000))
        assertEquals(60_000L, host.delivered.last().third.getLong(PageSurfaceContract.FIELD_LEASE_DURATION_MS))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        assertEquals(PageSurfaceContract.ERROR_STALE_GENERATION, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        assertEquals(PageSurfaceContract.ERROR_STALE_GENERATION, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1), relayA))
        host.now += 59_999
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        host.now += 1
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 4), relayA))
        // Renewal restores the right to publish; cover removes it again.
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 120_000))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 4), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(false))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 5), relayA))
        val covered = host.delivered.last { it.second == BusPaths.PAGE_VISIBILITY }.third
        assertFalse(covered.has(PageSurfaceContract.FIELD_LEASE_DURATION_MS))
        assertEquals(listOf(1L, 2L, 3L, 4L), responses().map { it.getLong("revision") })
    }

    @Test
    fun `actions need the advertised id, the shown revision, a visible frame and no pending one`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i0", revision = 3))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i1", actionId = "delete", revision = 3))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i2", revision = 2))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i3", revision = 3))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i4", revision = 3))
        val actions = host.delivered.filter { it.second == BusPaths.PAGE_ACTION }.map { it.third.getString("invocationId") }
        assertEquals(listOf("i3"), actions)
        // A result after the action's deadline is unconfirmed on the glasses, and refused here.
        host.now += 8_000
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESULT, result("i3"), relayA))
        assertTrue(host.glasses.none { it.first == BusPaths.PAGE_RESULT })
    }

    /** An accepted revision 3 with `read`, then a refresh answered by [answer], then a renewed lease. */
    private fun refreshAnswered(answer: (PageRegistration) -> String?): String? {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(
            BusPaths.PAGE_REQUEST,
            request(requestId = "r2", reason = "refresh").put(PageSurfaceContract.FIELD_MINIMUM_REVISION, 3),
        )
        // While the refresh waits for its answer, the old revision takes no action.
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i0", revision = 3))
        val error = answer(relayA)
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i1", revision = 3))
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_ACTION })
        return error
    }

    @Test
    fun `a refused refresh leaves the old revision's actions dead`() {
        val refusal = PageSurfaceContract.correlationPayload(PageCorrelation("r2", 1, 1, "relay:main"))
            .put("error", PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)
        assertNull(refreshAnswered { broker.onProvider(BusPaths.PAGE_RESPONSE, refusal, it) })
        assertEquals(PageSurfaceContract.ERROR_PAGE_UNAVAILABLE, responses().last().getString("error"))
    }

    @Test
    fun `an invalid refresh answer leaves the old revision's actions dead`() {
        val invalid = page(requestId = "r2", revision = 4, body = JSONObject())
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, refreshAnswered { broker.onProvider(BusPaths.PAGE_RESPONSE, invalid, it) })
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, responses().last().getString("error"))
    }

    @Test
    fun `a refresh answer below the glasses' floor is refused and leaves the old actions dead`() {
        val rollback = page(requestId = "r2", revision = 2)
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, refreshAnswered { broker.onProvider(BusPaths.PAGE_RESPONSE, rollback, it) })
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, responses().last().getString("error"))
        assertTrue(responses().none { it.optLong("revision") == 2L })
    }

    @Test
    fun `losing SPP alone ends only the pages that needed it, and their providers hear link_lost`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        val big = JSONObject().put(
            "items",
            JSONArray().apply { (1..40).forEach { put(JSONObject().put("id", "r$it").put("label", "Thread $it " + "x".repeat(90))) } },
        )
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1, body = big), relayA))
        assertTrue(PageSurfaceContract.needsDataPlane(BusPaths.PAGE_RESPONSE, host.glasses.last().second))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", pageId = "relay:small", frameIndex = 2))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(requestId = "r2", pageId = "relay:small", frameIndex = 2), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true, pageId = "relay:small", frameIndex = 2))

        val both = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        broker.onLinkState(both, both)
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_CLOSED })
        broker.onLinkState(both, LinkStateBits.CXR_CONTROL_UP)

        val closed = host.delivered.filter { it.second == BusPaths.PAGE_CLOSED }.map { it.third }
        assertEquals(listOf("relay:main"), closed.map { it.getString("pageId") })
        assertEquals("link_lost", closed.single().getString("reason"))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2, body = big), relayA))
        // The small page goes on over CXR: its action is forwarded and support stays.
        broker.onGlasses(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", "i1").put("sessionGeneration", 1L).put("frameIndex", 2)
                .put("pageId", "relay:small").put("revision", 1).put("actionId", "read"),
        )
        assertEquals(1, host.delivered.count { it.second == BusPaths.PAGE_ACTION })
        assertEquals(PageSurfaceContract.VERSION, broker.negotiatedVersion)

        // Losing CXR too ends everything.
        broker.onLinkState(LinkStateBits.CXR_CONTROL_UP, 0)
        assertEquals(0, broker.negotiatedVersion)
    }

    @Test
    fun `an action already queued for its provider is dropped when a cover or the lease end comes first`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        fun actions() = host.delivered.filter { it.second == BusPaths.PAGE_ACTION }.map { it.third.getString("invocationId") }

        // The action is recorded and queued while the frame is visible; the cover lands before it goes.
        host.onDelivered = BusPaths.PAGE_VISIBILITY to {
            broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "covered", revision = 3))
            broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(false))
        }
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertTrue(actions().isEmpty())

        // Likewise when the lease ends between recording and delivery.
        host.onDelivered = BusPaths.PAGE_VISIBILITY to {
            broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "expired", revision = 3))
            host.now += 120_000
        }
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertTrue(actions().isEmpty())

        // Control: an action under a live lease is delivered.
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "leased", revision = 3))
        assertEquals(listOf("leased"), actions())
    }

    private val big = JSONObject().put(
        "items",
        JSONArray().apply { (1..40).forEach { put(JSONObject().put("id", "r$it").put("label", "Thread $it " + "x".repeat(90))) } },
    )

    @Test
    fun `a replacement that cannot reach the glasses closes the frame on both sides`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        // Over CXR alone: the small page arrives, a replacement too big for CXR cannot.
        host.sppUp = false
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i1", revision = 1))
        assertNull(broker.onProvider(BusPaths.PAGE_RESULT, result("i1").put("replacement", page(revision = 2, body = big)), relayA))

        // The glasses get a small correlated unavailable answer, never the result they cannot hold.
        assertTrue(host.glasses.none { it.first == BusPaths.PAGE_RESULT })
        val answer = responses().last()
        assertEquals(PageSurfaceContract.ERROR_PAGE_UNAVAILABLE, answer.getString("error"))
        assertEquals("relay", answer.getString(PageSurfaceContract.FIELD_PLUGIN_ID))
        assertEquals("r1", answer.getString("requestId"))
        // The provider's page is closed too, and nothing of either revision stays usable.
        val closed = host.delivered.filter { it.second == BusPaths.PAGE_CLOSED }.map { it.third }
        assertEquals(listOf("link_lost"), closed.map { it.getString("reason") })
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i2", revision = 1))
        assertEquals(1, host.delivered.count { it.second == BusPaths.PAGE_ACTION })
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
    }

    @Test
    fun `a revision still on its way keeps the frame classified by what the glasses hold`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1, body = big), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        // A small revision replaces the big page; SPP goes before it reaches the glasses.
        val both = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP
        host.onGlassesSend = 2L to { broker.onLinkState(both, LinkStateBits.CXR_CONTROL_UP) }
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))

        // The glasses still held the big page, which needs SPP: the frame ends on the phone too.
        val closed = host.delivered.filter { it.second == BusPaths.PAGE_CLOSED }.map { it.third }
        assertEquals(listOf("relay:main"), closed.map { it.getString("pageId") })
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
    }

    @Test
    fun `an action queued past its invocation deadline is dropped even under a live lease`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        fun actions() = host.delivered.filter { it.second == BusPaths.PAGE_ACTION }.map { it.third.getString("invocationId") }

        // Admitted while the outbox is busy; it is still waiting when its eight seconds are up,
        // the 120-second lease still runs and no tick came.
        host.onDelivered = BusPaths.PAGE_VISIBILITY to {
            broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "late", revision = 3))
            host.now += PageSurfaceContract.PAGE_TIMEOUT_MS
        }
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertTrue(actions().isEmpty())

        // Control: one millisecond earlier it goes, and the dropped one did not hold the slot.
        host.onDelivered = BusPaths.PAGE_VISIBILITY to {
            broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "in-time", revision = 3))
            host.now += PageSurfaceContract.PAGE_TIMEOUT_MS - 1
        }
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertEquals(listOf("in-time"), actions())
    }

    @Test
    fun `SPP lost after a big revision went out but before it counted closes the frame on both sides`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        val both = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP

        // A small revision that crosses an SPP loss still counts: CXR carries it.
        host.onGlassesSent = 2L to { broker.onLinkState(both, LinkStateBits.CXR_CONTROL_UP) }
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_CLOSED })
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "small", revision = 2))
        assertEquals(1, host.delivered.count { it.second == BusPaths.PAGE_ACTION })
        assertNull(broker.onProvider(BusPaths.PAGE_RESULT, result("small"), relayA))

        // SPP is back; a big revision is written over it, and SPP goes before the broker records it.
        broker.onLinkState(LinkStateBits.CXR_CONTROL_UP, both)
        host.onGlassesSent = 3L to { broker.onLinkState(both, LinkStateBits.CXR_CONTROL_UP) }
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3, body = big), relayA))

        // The provider hears link_lost, and the glasses a small correlated answer after the big page.
        val closed = host.delivered.filter { it.second == BusPaths.PAGE_CLOSED }.map { it.third }
        assertEquals(listOf("relay:main"), closed.map { it.getString("pageId") })
        assertEquals("link_lost", closed.single().getString("reason"))
        val answer = responses().last()
        assertEquals(PageSurfaceContract.ERROR_PAGE_UNAVAILABLE, answer.getString("error"))
        assertEquals("r1", answer.getString("requestId"))
        assertEquals("relay", answer.getString(PageSurfaceContract.FIELD_PLUGIN_ID))
        // Nothing of any revision is usable any more.
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "big", revision = 3))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "old", revision = 2))
        assertEquals(1, host.delivered.count { it.second == BusPaths.PAGE_ACTION })
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 4), relayA))
    }

    @Test
    fun `a big revision queued across an SPP loss and its return counts once it is sent`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 1), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        val both = LinkStateBits.CXR_CONTROL_UP or LinkStateBits.SPP_DATA_UP

        // While the outbox is busy, a big revision is queued; SPP drops and comes back before it is sent.
        var queued: String? = "unset"
        host.onDelivered = BusPaths.PAGE_VISIBILITY to {
            queued = broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2, body = big), relayA)
            broker.onLinkState(both, LinkStateBits.CXR_CONTROL_UP)
            broker.onLinkState(LinkStateBits.CXR_CONTROL_UP, both)
        }
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        assertNull(queued)

        // It went out whole over the new SPP session: nothing is closed and its revision is shown.
        assertTrue(responses().none { it.has("error") })
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_CLOSED })
        assertEquals(2L, responses().last().getLong("revision"))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "after", revision = 2))
        assertEquals(listOf("after"), host.delivered.filter { it.second == BusPaths.PAGE_ACTION }.map { it.third.getString("invocationId") })
    }

    @Test
    fun `results are bounded, correlated and carry a higher replacement`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 3), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_ACTION, action(revision = 3))
        assertEquals(
            PageSurfaceContract.ERROR_INVALID_PAGE,
            broker.onProvider(BusPaths.PAGE_RESULT, result().put("replacement", page(revision = 3)), relayA),
        )
        assertNull(broker.onProvider(BusPaths.PAGE_RESULT, result().put("replacement", page(revision = 4)), relayA))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESULT, result(), relayA))
        val forwarded = host.glasses.single { it.first == BusPaths.PAGE_RESULT }.second
        assertEquals("relay", forwarded.getString("pluginId"))
        // The next action targets the replacement's revision.
        broker.onGlasses(BusPaths.PAGE_ACTION, action(invocationId = "i2", revision = 4))
        assertEquals(2, host.delivered.count { it.second == BusPaths.PAGE_ACTION })
        val base = page(revision = 5, body = JSONObject().put("items", JSONArray()).put("pad", ""))
        val pad = PageSurfaceContract.MAX_PAGE_BYTES - 10 - PageSurfaceContract.serializedBytes(base)
        val padded = result("i2").put("replacement", page(revision = 5, body = JSONObject().put("items", JSONArray()).put("pad", "x".repeat(pad))))
        assertEquals(PageSurfaceContract.ERROR_PAGE_TOO_LARGE, broker.onProvider(BusPaths.PAGE_RESULT, padded, relayA))
    }

    @Test
    fun `stamping is rechecked against the whole page budget`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        val bare = page(body = JSONObject().put("items", JSONArray()))
        val padding = PageSurfaceContract.MAX_PAGE_BYTES - PageSurfaceContract.serializedBytes(bare) - 20
        val exactBeforeStamp = page(body = JSONObject().put("items", JSONArray()).put("p", "x".repeat(padding)))
        assertTrue(PageSurfaceContract.serializedBytes(exactBeforeStamp) <= PageSurfaceContract.MAX_PAGE_BYTES)
        assertEquals(PageSurfaceContract.ERROR_PAGE_TOO_LARGE, broker.onProvider(BusPaths.PAGE_RESPONSE, exactBeforeStamp, relayA))
        assertEquals("PAGE_TOO_LARGE", responses().single().getString("error"))
    }

    @Test
    fun `typed bodies are checked for supported templates and reserved ones pass through`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        val escaping = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().put("id", "a").put("label", "A").put("target", JSONObject().put("pageId", "nav:main"))),
        )
        assertEquals(PageSurfaceContract.ERROR_INVALID_PAGE, broker.onProvider(BusPaths.PAGE_RESPONSE, page(body = escaping), relayA))
        assertEquals("INVALID_PAGE", responses().single().getString("error"))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "retry"))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(requestId = "r2", template = "conversation", body = JSONObject()), relayA))
        assertEquals("conversation", responses().last().getString("template"))
    }

    @Test
    fun `a close reaches its owner before the page-only binding is released`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        host.calls.clear()
        broker.onGlasses(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", "relay:main").put("reason", "back").put("sessionGeneration", 1L).put("frameIndex", 1))
        assertEquals(listOf("deliver:relay-a:${BusPaths.PAGE_CLOSED}", "unbind:relay"), host.calls)
        // A refused seventh frame names no frame: no close is sent for it.
        host.calls.clear()
        broker.onGlasses(BusPaths.PAGE_CLOSED, JSONObject().put("pageId", "relay:deep").put("reason", "frame_limit"))
        assertTrue(host.calls.isEmpty())
    }

    @Test
    fun `cover and lease expiry release page-only demand`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true, lease = 30_000))
        assertTrue("unbind:relay" !in host.calls)
        host.now += 30_000
        broker.onTick()
        assertEquals(1, host.calls.count { it == "unbind:relay" })
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))

        // Uncover binds again for the refresh; covering releases it.
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onGlasses(BusPaths.PAGE_REQUEST, request(requestId = "r2", reason = "refresh"))
        assertEquals(2, host.calls.count { it == "bind:relay" })
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(requestId = "r2", revision = 2), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(false))
        assertEquals(2, host.calls.count { it == "unbind:relay" })
    }

    @Test
    fun `total link loss drops every page, releases bindings and announces version zero`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onLinkLost()
        assertEquals(0, broker.negotiatedVersion)
        assertTrue("unbind:relay" in host.calls)
        assertEquals(0, host.delivered.last { it.second == BusPaths.HUB_CAPABILITIES }.third.getInt("pageSessionVersion"))
        assertEquals(PhonePageBroker.ERROR_PAGE_NOT_REQUESTED, broker.onProvider(BusPaths.PAGE_RESPONSE, page(revision = 2), relayA))
        // Reconnecting replays nothing.
        val before = host.delivered.size
        broker.onGlassesCapabilities(PageSurfaceContract.VERSION)
        assertEquals(1, host.delivered.drop(before).single().third.getInt("pageSessionVersion"))
        assertTrue(host.delivered.drop(before).none { it.second == BusPaths.PAGE_REQUEST })
    }

    @Test
    fun `a page too big for the remaining transport still gets a small honest answer`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        host.glassesError = "NO_DATA_PLANE"
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        assertEquals(listOf(false, true), responses().map { it.has("error") })
        assertEquals("PAGE_UNAVAILABLE", responses().last().getString("error"))
    }

    @Test
    fun `revocation and process death invalidate shown frames and stop routing`() {
        connect(relay)
        val relayA = register(relay, "relay-a")
        broker.onGlasses(BusPaths.PAGE_REQUEST, request())
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), relayA))
        broker.onGlasses(BusPaths.PAGE_VISIBILITY, visibility(true))
        broker.onProviderUnavailable("relay")
        assertEquals("PAGE_UNAVAILABLE", responses().last().getString("error"))
        assertTrue("unbind:relay" in host.calls)
        broker.onGlasses(BusPaths.PAGE_ACTION, action())
        assertTrue(host.delivered.none { it.second == BusPaths.PAGE_ACTION })
    }

    @Test
    fun `providers learn the effective version only when they opted in`() {
        val plain = principal("plain", pages = 0)
        host.providers["plain"] = plain
        connect(relay)
        register(relay, "relay-a")
        register(plain, "plain-a")
        assertTrue(host.delivered.none { it.first == "plain-a" })
        assertEquals(1, host.delivered.single { it.first == "relay-a" }.third.getInt("pageSessionVersion"))
        broker.onGlassesCapabilities(0)
        assertEquals(0, host.delivered.last { it.first == "relay-a" }.third.getInt("pageSessionVersion"))
        assertTrue(host.delivered.none { it.first == "plain-a" })
    }

    @Test
    fun `raw page answers are bounded before parsing`() {
        assertFalse(PhonePageBroker.rawAnswerTooLarge(BusPaths.PAGE_RESPONSE, PageSurfaceContract.MAX_PAGE_BYTES))
        assertTrue(PhonePageBroker.rawAnswerTooLarge(BusPaths.PAGE_RESPONSE, PageSurfaceContract.MAX_PAGE_BYTES + 1))
        assertTrue(PhonePageBroker.rawAnswerTooLarge(BusPaths.PAGE_RESULT, PageSurfaceContract.MAX_PAGE_BYTES + 1))
        assertFalse(PhonePageBroker.rawAnswerTooLarge("/plugin/relay/big", PageSurfaceContract.MAX_PAGE_BYTES + 1))
    }
}
