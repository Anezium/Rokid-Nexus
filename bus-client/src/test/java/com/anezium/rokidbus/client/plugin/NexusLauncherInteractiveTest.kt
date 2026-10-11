package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteGeometryContract
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusLauncherInteractiveTest {
    private class FakeTransport : NexusPluginTransport {
        lateinit var listener: NexusPluginTransport.Listener
        var featureBits = 0
        var immediateAccepted = true
        val sends = mutableListOf<Pair<String, JSONObject>>()
        val immediate = mutableListOf<Pair<String, JSONObject>>()

        override fun connect(listener: NexusPluginTransport.Listener) {
            this.listener = listener
        }

        override fun send(path: String, id: String, payload: JSONObject): Boolean {
            sends += path to JSONObject(payload.toString())
            return true
        }

        override fun sendImmediate(path: String, id: String, payload: JSONObject): Boolean {
            if (immediateAccepted) immediate += path to JSONObject(payload.toString())
            return immediateAccepted
        }

        override fun sendBinary(path: String, id: String, payload: JSONObject, data: ByteArray): Boolean = true
        override fun capabilities(): Int = featureBits
        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    /** A plugin written before commands: it only knows the legacy action callback. */
    private open class LegacyCallbacks : NexusPluginCallbacks {
        val events = mutableListOf<String>()
        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onRegistrationState(result: Int) = Unit
        override fun onActivityAction(id: String) {
            events += "action:$id"
        }
    }

    private class CommandCallbacks : LegacyCallbacks() {
        val commands = mutableListOf<NexusActivityCommand>()
        var answer: (NexusActivityCommand) -> NexusActivityCommandResult? = { NexusActivityCommandResult.done("Playing") }
        override fun onActivityCommand(command: NexusActivityCommand): NexusActivityCommandResult? {
            commands += command
            return answer(command)
        }
    }

    private val interactive = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.NOTICE_SURFACE or
        BusCapabilityBits.LAUNCHER_INTERACTIVE

    private fun <C : NexusPluginCallbacks> client(
        callbacks: C,
        featureBits: Int = interactive,
        capabilities: String = "surfaces",
        link: Int = LinkStateBits.SPP_DATA_UP or LinkStateBits.CXR_CONTROL_UP,
    ): Pair<NexusPluginClient, FakeTransport> {
        val transport = FakeTransport()
        val client = NexusPluginClient("media", callbacks, transport)
        client.connect()
        transport.featureBits = featureBits
        transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration",
            JSONObject().put("pluginId", "media").put("result", PluginRegistrationResult.APPROVED).put("capabilities", capabilities),
        )
        transport.listener.onLinkState(link)
        return client to transport
    }

    private var nextEvent = 0

    private fun FakeTransport.deliver(path: String, payload: JSONObject) {
        listener.onMessage(path, "event-${nextEvent++}", payload.put("pluginId", "media"))
    }

    private fun command(invocationId: String = "inv-1", activityId: String = "media:activity", typed: JSONObject? = JSONObject().put("type", "media.play")) =
        JSONObject().put("activityId", activityId).put("id", "play").put("invocationId", invocationId).put("seq", 41).apply {
            typed?.let { put("typed", it) }
        }

    private fun player() = NexusActivity(
        glyph = "music",
        primary = "Teardrop",
        actions = listOf(
            NexusActivityAction("prev", "previous", "Previous", NexusActionType.MediaPrevious),
            NexusActivityAction("play", "play", "Play", NexusActionType.MediaPlay),
            NexusActivityAction("next", "next", "Next", NexusActionType.MediaNext),
        ),
        destination = NexusPageDestination("media:now"),
        activation = NexusActivityActivation.COMMANDS,
        defaultActionId = "play",
        persistence = NexusActivityPersistence.ONGOING,
    )

    @Test
    fun `support needs the surfaces grant and either the bit or the page announcement`() {
        assertTrue(client(LegacyCallbacks()).first.supportsLauncherInteractive())
        assertFalse(client(LegacyCallbacks(), featureBits = BusCapabilityBits.ACTIVITY_SURFACE).first.supportsLauncherInteractive())
        assertFalse(client(LegacyCallbacks(), capabilities = "http_proxy").first.supportsLauncherInteractive())

        val (viaPages, transport) = client(LegacyCallbacks(), featureBits = BusCapabilityBits.ACTIVITY_SURFACE)
        transport.deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1).put("launcherInteractiveVersion", 1))
        assertTrue(viaPages.supportsLauncherInteractive())
        transport.deliver(BusPaths.HUB_CAPABILITIES, JSONObject().put("pageSessionVersion", 1).put("launcherInteractiveVersion", 0))
        assertFalse(viaPages.supportsLauncherInteractive())
    }

    @Test
    fun `launcher fields reach a capable hub and are stripped for any other`() {
        val (capable, capableTransport) = client(LegacyCallbacks())
        assertEquals(NexusSdkResult.SENT, capable.startActivity(player()))
        val sent = capableTransport.sends.single().second
        assertEquals("media:now", sent.getJSONObject("destination").getString("pageId"))
        assertEquals("commands", sent.getString("activation"))
        assertEquals("play", sent.getString("defaultActionId"))
        assertEquals("ongoing", sent.getString("persistence"))
        assertEquals("media.play", sent.getJSONArray("actions").getJSONObject(1).getJSONObject("typed").getString("type"))

        val (old, oldTransport) = client(LegacyCallbacks(), featureBits = BusCapabilityBits.ACTIVITY_SURFACE)
        assertEquals(NexusSdkResult.SENT, old.startActivity(player()))
        assertEquals(NexusSdkResult.SENT, old.updateActivity(player(), significant = true))
        oldTransport.sends.forEach { (_, payload) ->
            listOf("destination", "activation", "defaultActionId", "persistence").forEach { assertFalse(it, payload.has(it)) }
            assertFalse(payload.getJSONArray("actions").getJSONObject(1).has("typed"))
        }
        // What is left is a plain activity the old hub accepts.
        assertTrue(ActivitySurfaceContract.validateStart(oldTransport.sends.first().second) is ActivitySurfaceValidationResult.Valid)
    }

    @Test
    fun `a destination outside this plugin's pages is refused before sending`() {
        val (client, transport) = client(LegacyCallbacks())
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.startActivity(player().copy(destination = NexusPageDestination("relay:main"))))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.updateActivity(player().copy(destination = NexusPageDestination("relay:main"))))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.showNotice(NexusNotice(title = "x", destination = NexusPageDestination("relay:main"))))
        assertTrue(transport.sends.isEmpty())
    }

    @Test
    fun `the model refuses activation without its prerequisite and loose interactive ids`() {
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivity(glyph = "music", primary = "x", activation = NexusActivityActivation.DESTINATION)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivity(glyph = "music", primary = "x", activation = NexusActivityActivation.COMMANDS)
        }
        assertThrows(IllegalArgumentException::class.java) {
            player().copy(defaultActionId = "stop")
        }
        assertThrows(IllegalArgumentException::class.java) {
            player().copy(actions = listOf(NexusActivityAction("a b", "play", "Play")), defaultActionId = null)
        }
        // The same loose id is fine on an activity that uses nothing of the bundle.
        NexusActivity(glyph = "music", primary = "x", actions = listOf(NexusActivityAction("a b", "play", "Play")))
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivityAction("check", "check", "Done", NexusActionType.SetChecked(true))
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusPageDestination("nexus:activities")
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusPageDestination("media:now", JSONObject().put("k", "x".repeat(2048)))
        }
        assertEquals(NexusPageDestination("media:now", JSONObject().put("k", 1)), NexusPageDestination("media:now", JSONObject().put("k", 1)))
    }

    @Test
    fun `a command reaches the plugin once and its answer goes back as a result`() {
        val callbacks = CommandCallbacks()
        val (_, transport) = client(callbacks)
        transport.deliver(BusPaths.ACTIVITY_ACTION, command())
        transport.deliver(BusPaths.ACTIVITY_ACTION, command())
        transport.deliver(BusPaths.ACTIVITY_ACTION, command(invocationId = "inv-2", activityId = "relay:activity"))

        val delivered = callbacks.commands.single()
        assertEquals("play", delivered.actionId)
        assertEquals("inv-1", delivered.invocationId)
        assertEquals(41L, delivered.seq)
        assertEquals(NexusActionType.MediaPlay, delivered.typed)
        assertTrue(callbacks.events.isEmpty())

        val (path, result) = transport.immediate.single()
        assertEquals(BusPaths.ACTIVITY_RESULT, path)
        assertEquals("media:activity", result.getString("activityId"))
        assertEquals("inv-1", result.getString("invocationId"))
        assertEquals("done", result.getString("status"))
        assertEquals("Playing", result.getString("message"))
        assertEquals(ActivitySurfaceContract.LOCAL_SURFACE_ID, result.getString("surfaceId"))
        assertTrue("results never wait in the reconnect queue", transport.sends.none { it.first == BusPaths.ACTIVITY_RESULT })
    }

    @Test
    fun `a plugin that knows only actions sends no result and invents no success`() {
        val callbacks = LegacyCallbacks()
        val (_, transport) = client(callbacks)
        transport.deliver(BusPaths.ACTIVITY_ACTION, command())
        assertEquals(listOf("action:play"), callbacks.events)
        assertTrue(transport.immediate.isEmpty())

        // The v1 shape keeps calling the legacy callback.
        transport.deliver(BusPaths.ACTIVITY_ACTION, JSONObject().put("activityId", "media:activity").put("id", "next"))
        assertEquals(listOf("action:play", "action:next"), callbacks.events)
    }

    @Test
    fun `a late answer is sent once and a throwing callback is a rejection`() {
        val callbacks = CommandCallbacks().apply { answer = { null } }
        val (client, transport) = client(callbacks)
        transport.deliver(BusPaths.ACTIVITY_ACTION, command())
        assertTrue(transport.immediate.isEmpty())
        val pending = callbacks.commands.single()
        assertEquals(NexusSdkResult.SENT, client.sendActivityCommandResult(pending, NexusActivityCommandResult.stale()))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.sendActivityCommandResult(pending, NexusActivityCommandResult.done()))
        assertEquals("stale", transport.immediate.single().second.getString("status"))

        callbacks.answer = { throw IllegalStateException("player gone") }
        transport.deliver(BusPaths.ACTIVITY_ACTION, command(invocationId = "inv-9"))
        assertEquals("rejected", transport.immediate.last().second.getString("status"))
        assertFalse(transport.immediate.last().second.has("message"))
        assertThrows(IllegalArgumentException::class.java) { NexusActivityCommandResult.done("x".repeat(65)) }
    }

    @Test
    fun `notices carry destinations and withdraw only with the bundle`() {
        val notice = NexusNotice(
            title = "Mika",
            body = "On se voit à 8h ?",
            destination = NexusPageDestination("media:thread", JSONObject().put("t", "9f2c")),
            eventKey = "9f2c-1",
            conversation = true,
        )
        val (capable, transport) = client(LegacyCallbacks())
        assertEquals(NexusSdkResult.SENT, capable.showNotice(notice))
        val shown = transport.sends.last().second
        assertEquals("9f2c-1", shown.getString("eventKey"))
        assertTrue(shown.getBoolean("conversation"))
        assertEquals(NexusSdkResult.SENT, capable.withdrawNotice("9f2c-1"))
        val (path, withdraw) = transport.sends.last()
        assertEquals(BusPaths.NOTICE_WITHDRAW, path)
        assertEquals("9f2c-1", NoticeSurfaceContract.parseWithdraw(withdraw))
        assertEquals(NoticeSurfaceContract.LOCAL_SURFACE_ID, withdraw.getString("surfaceId"))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, capable.withdrawNotice("has space"))

        val (old, oldTransport) = client(LegacyCallbacks(), featureBits = BusCapabilityBits.NOTICE_SURFACE)
        assertEquals(NexusSdkResult.SENT, old.showNotice(notice))
        val legacy = oldTransport.sends.last().second
        listOf("destination", "eventKey", "conversation").forEach { assertFalse(it, legacy.has(it)) }
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, old.withdrawNotice("9f2c-1"))
        assertThrows(IllegalArgumentException::class.java) { NexusNotice(title = "x", conversation = true) }
    }

    @Test
    fun `geometry needs the bundle and the data plane, and is never queued`() {
        val geometry = RouteGeometry(400.0, listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 900), RoutePoint(500, 100)))))
        val (client, transport) = client(LegacyCallbacks())
        assertEquals(NexusSdkResult.SENT, client.publishActivityGeometry(3, geometry))
        val (path, sent) = transport.immediate.single()
        assertEquals(BusPaths.ACTIVITY_GEOMETRY, path)
        assertEquals(3L, sent.getLong("revision"))
        assertEquals(geometry, RouteGeometryContract.parseOrNull(sent.getJSONObject("geometry")))
        assertEquals(ActivitySurfaceContract.LOCAL_SURFACE_ID, sent.getString("surfaceId"))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityGeometry(-1, geometry))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityGeometry(4, geometry.copy(spanMeters = 0.0)))
        assertTrue(transport.sends.isEmpty())

        val (controlOnly, _) = client(LegacyCallbacks(), link = LinkStateBits.CXR_CONTROL_UP)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, controlOnly.publishActivityGeometry(1, geometry))
        // The phone forwards a heading over the data plane only, so control alone cannot carry it.
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, controlOnly.publishActivityHeading(90.0, accuracyDeg = 10.0))

        val (old, _) = client(LegacyCallbacks(), featureBits = BusCapabilityBits.ACTIVITY_SURFACE)
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, old.publishActivityGeometry(1, geometry))
        assertEquals(NexusSdkResult.CAPABILITY_NOT_AVAILABLE, old.publishActivityHeading(90.0))
    }

    @Test
    fun `heading samples are bounded`() {
        val (client, transport) = client(LegacyCallbacks())
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityHeading(360.0))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityHeading(-1.0))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityHeading(Double.NaN))
        assertEquals(NexusSdkResult.INVALID_PAYLOAD, client.publishActivityHeading(10.0, accuracyDeg = 200.0))
        assertEquals(NexusSdkResult.SENT, client.publishActivityHeading(359.9))
        val (path, heading) = transport.immediate.single()
        assertEquals(BusPaths.ACTIVITY_HEADING, path)
        assertEquals(359.9, heading.getDouble("deg"), 0.0)
        assertTrue(heading.getLong("atWallMs") > 0)
        assertNull(ActivitySurfaceContract.parseHeading(JSONObject().put("deg", 1)))
    }
}
