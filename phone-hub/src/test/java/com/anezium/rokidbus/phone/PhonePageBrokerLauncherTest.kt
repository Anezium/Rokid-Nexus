package com.anezium.rokidbus.phone

import android.content.ComponentName
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.plugin.PluginCapability
import com.anezium.rokidbus.shared.plugin.PluginDescriptor
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page broker with the launcher bundle: stamps, typed bodies, invocation modes, result codes. */
class PhonePageBrokerLauncherTest {
    private class FakeHost : PhonePageHost {
        var mapsSupported = true
        override fun renderedMapsSupported(): Boolean = mapsSupported
        var now = 10_000L
        val providers = linkedMapOf<String, PhonePluginPrincipal>()
        val live = linkedMapOf<String, MutableList<PageRegistration>>()
        val delivered = mutableListOf<Triple<String, String, JSONObject>>()
        val glasses = mutableListOf<Pair<String, JSONObject>>()
        val quick = mutableMapOf<String, Set<QuickActionFamily>>()

        override fun nowMs(): Long = now
        override fun pageProvider(pluginId: String): PhonePluginPrincipal? = providers[pluginId]
        override fun registrations(principal: PhonePluginPrincipal): List<PageRegistration> =
            live[principal.descriptor.id].orEmpty().toList()
        override fun providerRegistrations(): List<PageRegistration> = live.values.flatten()
        override fun deliver(registration: PageRegistration, path: String, payload: JSONObject): Boolean {
            delivered += Triple(registration.epoch, path, JSONObject(payload.toString()))
            return live[registration.pluginId]?.any { it.epoch == registration.epoch } == true
        }
        override fun sendToGlasses(path: String, payload: JSONObject): String? {
            glasses += path to JSONObject(payload.toString())
            return null
        }
        override fun bind(principal: PhonePluginPrincipal): Boolean = true
        override fun unbind(principal: PhonePluginPrincipal) = Unit
        override fun scheduleTick(atMs: Long?) = Unit
        override fun log(message: String) = Unit
        override fun quickFamilies(principal: PhonePluginPrincipal): Set<QuickActionFamily> =
            quick[principal.descriptor.id].orEmpty()
    }

    private val host = FakeHost()
    private val broker = PhonePageBroker(
        host,
        localVersion = PageSurfaceContract.VERSION,
        localLauncherVersion = LauncherInteractiveContract.VERSION,
    )
    private val relay = PhonePluginPrincipal(
        packageName = "dev.example.relay",
        serviceComponent = ComponentName("dev.example.relay", "dev.example.relay.Service"),
        uid = 10_005,
        signingDigestSha256 = "digest-relay",
        descriptor = PluginDescriptor(
            "relay", "relay", 3, setOf(PluginCapability.SURFACES), listOf("/plugin/relay", "/system/plugin"), null, true,
            pageSessionVersion = 1,
            quickActionFamilies = setOf(QuickActionFamily.CHECKLIST, QuickActionFamily.MEDIA),
        ),
    )

    /** Glasses announcing [launcher], a live relay registration, then the main page asked for. */
    private fun open(launcher: Int = LauncherInteractiveContract.VERSION): PageRegistration {
        host.providers["relay"] = relay
        broker.onGlassesCapabilities(PageSurfaceContract.VERSION, launcher)
        val registration = PageRegistration("relay-a", "relay", relay.grantKey())
        host.live["relay"] = mutableListOf(registration)
        broker.onRegistered(relay)
        broker.onGlasses(
            BusPaths.PAGE_REQUEST,
            PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "relay:main"))
                .put("reason", "open")
                .put(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS, 8_000),
        )
        return registration
    }

    private fun page(
        revision: Long = 1,
        template: String = PageSurfaceContract.TEMPLATE_SELECTABLE_LIST,
        body: JSONObject = JSONObject().put(
            "items",
            JSONArray().put(JSONObject().put("id", "milk").put("label", "Milk").put("checked", false)),
        ),
        actions: JSONArray = checklistActions(),
    ) = PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "relay:main"))
        .put("revision", revision).put("template", template).put("title", "Relay")
        .put("body", body).put("actions", actions).put("live", true)

    private fun action(id: String, typed: String? = null, hubQuick: Boolean = false) = JSONObject()
        .put("id", id).put("label", id).put("kind", PageSurfaceContract.KIND_PLUGIN).put("confirm", true)
        .apply {
            typed?.let { put("typed", JSONObject().put("type", it).apply { if (it == "setChecked") put("checked", true) }) }
            if (hubQuick) put("hubQuick", true)
        }

    private fun checklistActions() = JSONArray()
        .put(action("check", typed = "setChecked", hubQuick = true))
        .put(action("play", typed = "media.play", hubQuick = true))
        .put(action("read", hubQuick = true))

    private fun visible() = broker.onGlasses(
        BusPaths.PAGE_VISIBILITY,
        JSONObject().put("pageId", "relay:main").put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
            .put("leaseUntilMs", 987_654_321L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000),
    )

    private fun invoke(invocationId: String, actionId: String, mode: String? = null, text: String? = null, revision: Long = 1) =
        broker.onGlasses(
            BusPaths.PAGE_ACTION,
            JSONObject().put("invocationId", invocationId).put("sessionGeneration", 1L).put("frameIndex", 1)
                .put("pageId", "relay:main").put("revision", revision).put("actionId", actionId)
                .apply {
                    mode?.let { put("mode", it) }
                    text?.let { put("text", it) }
                    // The glasses' own copy of a type is never what the provider gets.
                    put("typed", JSONObject().put("type", "media.next"))
                },
        )

    private fun responses() = host.glasses.filter { it.first == BusPaths.PAGE_RESPONSE }.map { it.second }
    private fun invocations() = host.delivered.filter { it.second == BusPaths.PAGE_ACTION }.map { it.third }
    private fun capabilities() = host.delivered.filter { it.second == BusPaths.HUB_CAPABILITIES }.map { it.third }

    @Test
    fun `render feedback needs the accepted route revision request frame lease and registration`() {
        val registration = open()
        val route = JSONObject().put("position", "none").put("follow", com.anezium.rokidbus.shared.RouteGeometryContract.encode(
            com.anezium.rokidbus.shared.RouteGeometry(spanMeters = 400.0, paths = emptyList())))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE,
            page(template = "route", body = route, actions = JSONArray()), registration))
        visible()
        val event = com.anezium.rokidbus.shared.RenderedMapContext("page", "relay:main", 1,
            sessionGeneration = 1, frameIndex = 1, requestId = "r1").toPayload()
            .put("visible", true).put("view", "overview").put("token", "p1").put("pluginId", "forged")
        broker.onRenderedMap(event)
        val delivered = host.delivered.filter { it.second == com.anezium.rokidbus.shared.RenderedMapContract.PATH }
        assertEquals(1, delivered.size)
        assertEquals("relay-a", delivered.single().first)
        assertEquals("relay", delivered.single().third.getString("pluginId"))
        assertFalse(delivered.single().third.has("requestId"))
        assertEquals(1L, delivered.single().third.getLong("revision"))
        assertEquals("relay:main", delivered.single().third.getString("pageId"))
        broker.onRenderedMap(JSONObject(event.toString()).put("revision", 0))
        broker.onRenderedMap(JSONObject(event.toString()).put("requestId", "old"))
        broker.onRenderedMap(JSONObject(event.toString()).put("sessionGeneration", 0))
        broker.onRenderedMap(JSONObject(event.toString()).put("id", "other:main"))
        host.mapsSupported = false
        broker.onRenderedMap(event)
        host.mapsSupported = true
        host.now += PageSurfaceContract.LEASE_MS
        broker.onRenderedMap(event)
        assertEquals(1, host.delivered.count { it.second == com.anezium.rokidbus.shared.RenderedMapContract.PATH })
    }

    @Test
    fun `providers hear the launcher bundle version beside the page version`() {
        open()
        assertEquals(1, capabilities().last().getInt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION))
        assertEquals(1, capabilities().last().getInt(PageSurfaceContract.CAPABILITY_FIELD_PAGE_SESSION_VERSION))
        assertEquals(1, broker.negotiatedLauncherVersion)
        broker.onGlassesCapabilities(PageSurfaceContract.VERSION, 0)
        assertEquals(0, capabilities().last().getInt(LauncherInteractiveContract.CAPABILITY_FIELD_VERSION))
        assertEquals(0, broker.negotiatedLauncherVersion)
    }

    @Test
    fun `the phone stamps quick only on approved typed plugin actions and never keeps a provider's stamp`() {
        val registration = open()
        host.quick["relay"] = setOf(QuickActionFamily.CHECKLIST)
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration))
        val actions = responses().single().getJSONArray("actions")
        assertTrue(actions.getJSONObject(0).getBoolean("hubQuick"))
        assertFalse(actions.getJSONObject(1).has("hubQuick"))
        assertFalse(actions.getJSONObject(2).has("hubQuick"))
        assertEquals("setChecked", actions.getJSONObject(0).getJSONObject("typed").getString("type"))
        assertFalse(responses().single().getJSONObject("body").getJSONArray("items").getJSONObject(0).getBoolean("checked"))
    }

    @Test
    fun `glasses without the bundle get a v1 page and every invocation stays confirmed`() {
        val registration = open(launcher = 0)
        host.quick["relay"] = setOf(QuickActionFamily.CHECKLIST)
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration))
        val forwarded = responses().single()
        val actions = forwarded.getJSONArray("actions")
        (0 until actions.length()).forEach { index ->
            assertFalse(actions.getJSONObject(index).has("typed"))
            assertFalse(actions.getJSONObject(index).has("hubQuick"))
        }
        assertFalse(forwarded.getJSONObject("body").getJSONArray("items").getJSONObject(0).has("checked"))

        visible()
        invoke("i1", "check", mode = PageSurfaceContract.MODE_QUICK)
        assertTrue(invocations().isEmpty())
        invoke("i2", "check")
        val delivered = invocations().single()
        assertFalse(delivered.has("mode"))
        assertFalse(delivered.has("typed"))
    }

    @Test
    fun `a quick invocation needs a quick-eligible family the user approves now`() {
        val registration = open()
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration))
        visible()
        invoke("i1", "check", mode = PageSurfaceContract.MODE_QUICK)
        invoke("i2", "read", mode = PageSurfaceContract.MODE_QUICK)
        assertTrue(invocations().isEmpty())

        host.quick["relay"] = setOf(QuickActionFamily.CHECKLIST)
        invoke("i3", "play", mode = PageSurfaceContract.MODE_QUICK)
        assertTrue(invocations().isEmpty())
        invoke("i4", "check", mode = PageSurfaceContract.MODE_QUICK)
        val delivered = invocations().single()
        assertEquals("i4", delivered.getString("invocationId"))
        assertEquals(PageSurfaceContract.MODE_QUICK, delivered.getString("mode"))
        assertEquals("setChecked", delivered.getJSONObject("typed").getString("type"))
        assertTrue(delivered.getJSONObject("typed").getBoolean("checked"))
    }

    @Test
    fun `withdrawing a quick approval rejects the pending quick invocation`() {
        val registration = open()
        host.quick["relay"] = setOf(QuickActionFamily.CHECKLIST)
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration))
        visible()
        invoke("i1", "check", mode = PageSurfaceContract.MODE_QUICK)
        assertEquals(1, invocations().size)

        host.quick.remove("relay")
        broker.onQuickPolicyChanged("relay")
        assertEquals(
            PhonePageBroker.ERROR_PAGE_NOT_REQUESTED,
            broker.onProvider(BusPaths.PAGE_RESULT, JSONObject().put("invocationId", "i1").put("status", "done"), registration),
        )
        assertTrue(host.glasses.none { it.first == BusPaths.PAGE_RESULT })
    }

    @Test
    fun `compose reaches only the composer's send action with text within its limit`() {
        val registration = open()
        val conversation = JSONObject()
            .put("threadKey", "thread-1")
            .put("messages", JSONArray().put(JSONObject().put("id", "m1").put("role", "received").put("text", "Lunch?")))
            .put(
                "composer",
                JSONObject().put("recipient", "Mika").put("sendActionId", "send").put("maxChars", 10).put("replyable", true),
            )
        val actions = JSONArray()
            .put(action("send", typed = "compose.send"))
            .put(action("mark"))
        assertNull(
            broker.onProvider(
                BusPaths.PAGE_RESPONSE,
                page(template = PageSurfaceContract.TEMPLATE_CONVERSATION, body = conversation, actions = actions),
                registration,
            ),
        )
        visible()

        invoke("i1", "send")
        invoke("i2", "mark", mode = PageSurfaceContract.MODE_COMPOSE, text = "hello")
        invoke("i3", "send", mode = PageSurfaceContract.MODE_COMPOSE, text = "far too long a reply")
        invoke("i4", "send", mode = PageSurfaceContract.MODE_QUICK)
        assertTrue(invocations().isEmpty())

        invoke("i5", "send", mode = PageSurfaceContract.MODE_COMPOSE, text = "on my way")
        val delivered = invocations().single()
        assertEquals(PageSurfaceContract.MODE_COMPOSE, delivered.getString("mode"))
        assertEquals("on my way", delivered.getString("text"))
        assertEquals("compose.send", delivered.getJSONObject("typed").getString("type"))
    }

    @Test
    fun `a composer that cannot reply takes no send`() {
        val registration = open()
        val conversation = JSONObject()
            .put("threadKey", "thread-1")
            .put("messages", JSONArray())
            .put(
                "composer",
                JSONObject().put("recipient", "Mika").put("sendActionId", "send").put("maxChars", 100).put("replyable", false),
            )
        assertNull(
            broker.onProvider(
                BusPaths.PAGE_RESPONSE,
                page(template = PageSurfaceContract.TEMPLATE_CONVERSATION, body = conversation, actions = JSONArray().put(action("send", typed = "compose.send"))),
                registration,
            ),
        )
        visible()
        invoke("i1", "send", mode = PageSurfaceContract.MODE_COMPOSE, text = "hello")
        assertTrue(invocations().isEmpty())
    }

    @Test
    fun `with the bundle the new bodies are decoded typed and ink stays an envelope`() {
        val registration = open()
        val media = JSONObject().put("title", "Song").put("state", "playing").put("controls", JSONArray().put("read"))
        assertEquals(
            PageSurfaceContract.ERROR_INVALID_PAGE,
            broker.onProvider(BusPaths.PAGE_RESPONSE, page(template = PageSurfaceContract.TEMPLATE_MEDIA, body = media), registration),
        )
    }

    @Test
    fun `ink passes as an envelope even with the bundle`() {
        val registration = open()
        assertNull(
            broker.onProvider(
                BusPaths.PAGE_RESPONSE,
                page(template = PageSurfaceContract.TEMPLATE_INK, body = JSONObject().put("anything", 1)),
                registration,
            ),
        )
    }

    @Test
    fun `without the bundle the new bodies pass as envelopes the glasses cannot draw`() {
        val registration = open(launcher = 0)
        val media = JSONObject().put("title", "Song").put("state", "playing").put("controls", JSONArray().put("read"))
        assertNull(broker.onProvider(BusPaths.PAGE_RESPONSE, page(template = PageSurfaceContract.TEMPLATE_MEDIA, body = media), registration))
    }

    @Test
    fun `a result's code passes through with the bundle and is dropped without it`() {
        for (launcher in listOf(1, 0)) {
            val local = FakeHost()
            val localBroker = PhonePageBroker(local, PageSurfaceContract.VERSION, LauncherInteractiveContract.VERSION)
            local.providers["relay"] = relay
            localBroker.onGlassesCapabilities(PageSurfaceContract.VERSION, launcher)
            val registration = PageRegistration("relay-a", "relay", relay.grantKey())
            local.live["relay"] = mutableListOf(registration)
            localBroker.onRegistered(relay)
            localBroker.onGlasses(
                BusPaths.PAGE_REQUEST,
                PageSurfaceContract.correlationPayload(PageCorrelation("r1", 1, 1, "relay:main"))
                    .put("reason", "open").put(PageSurfaceContract.FIELD_REMAINING_TIMEOUT_MS, 8_000),
            )
            assertNull(localBroker.onProvider(BusPaths.PAGE_RESPONSE, page(), registration))
            localBroker.onGlasses(
                BusPaths.PAGE_VISIBILITY,
                JSONObject().put("pageId", "relay:main").put("visible", true).put("sessionGeneration", 1L).put("frameIndex", 1)
                    .put("leaseUntilMs", 1L).put(PageSurfaceContract.FIELD_LEASE_DURATION_MS, 120_000),
            )
            localBroker.onGlasses(
                BusPaths.PAGE_ACTION,
                JSONObject().put("invocationId", "i1").put("sessionGeneration", 1L).put("frameIndex", 1)
                    .put("pageId", "relay:main").put("revision", 1).put("actionId", "read"),
            )
            val result = JSONObject().put("invocationId", "i1").put("status", "rejected").put("code", PageSurfaceContract.CODE_AUTHORITY_LOST)
            assertNull(localBroker.onProvider(BusPaths.PAGE_RESULT, result, registration))
            val forwarded = local.glasses.single { it.first == BusPaths.PAGE_RESULT }.second
            assertEquals(launcher == 1, forwarded.has("code"))
        }
    }
}
