package com.anezium.rokidbus.client.plugin

import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.ActivitySurfacePatchResult
import com.anezium.rokidbus.shared.ActivitySurfaceValidationResult
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.LinkStateBits
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NexusActivityTest {
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


        // Null, so these keep exercising the registration-message path: the direct

        // call is the fast path, not the only one.

        override fun approvedCapabilities(): String? = null
        override fun close() = Unit
    }

    private class RecordingCallbacks : NexusPluginCallbacks {
        val events = mutableListOf<String>()

        override fun onOpen() = Unit
        override fun onClose() = Unit
        override fun onInput(event: NexusInputEvent) = Unit
        override fun onLinkState(state: Int) = Unit
        override fun onActivityAction(id: String) {
            events += "action:$id"
        }
        override fun onActivityClosed(reason: String) {
            events += "closed:$reason"
        }
        override fun onRegistrationState(result: Int) = Unit
        override fun onMessage(path: String, id: String, payload: JSONObject) {
            events += "message:$path"
        }
    }

    private data class Fixture(
        val client: NexusPluginClient,
        val transport: FakeTransport,
        val callbacks: RecordingCallbacks,
    )

    @Test
    fun `start update and end use the activity paths and normalized payloads`() {
        val fixture = approvedFixture()
        val activity = NexusActivity(
            glyph = "  some-future-glyph  ",
            primary = "  300 m  ",
            secondary = "  Rue de la Paix  ",
            progress = NexusActivityProgress.Percent(42),
            eta = "  12:41  ",
            detail = listOf("  then right  "),
            actions = listOf(
                NexusActivityAction("  mute  ", "  pause  ", "  Mute  "),
            ),
            maxDurationMs = 1L,
        )

        assertEquals(NexusSdkResult.SENT, fixture.client.startActivity(activity))
        assertEquals(
            NexusSdkResult.SENT,
            fixture.client.updateActivity(activity, significant = true),
        )
        assertEquals(NexusSdkResult.SENT, fixture.client.endActivity())

        val start = fixture.transport.sends[0]
        assertEquals(BusPaths.ACTIVITY_START, start.first)
        assertEquals(ActivitySurfaceContract.LOCAL_SURFACE_ID, start.second.getString("surfaceId"))
        assertEquals(ActivitySurfaceContract.KIND, start.second.getString("kind"))
        assertEquals("some-future-glyph", start.second.getString("glyph"))
        assertEquals("300 m", start.second.getString("primary"))
        assertEquals("Rue de la Paix", start.second.getString("secondary"))
        assertEquals(42, start.second.getInt("progress"))
        assertEquals("then right", start.second.getJSONArray("detail").getString(0))
        assertEquals(
            "mute",
            start.second.getJSONArray("actions").getJSONObject(0).getString("id"),
        )
        assertEquals(
            ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            start.second.getLong("maxDurationMs"),
        )
        assertFalse(start.second.has("significant"))
        assertTrue(
            ActivitySurfaceContract.validateStart(start.second) is
                ActivitySurfaceValidationResult.Valid,
        )

        val update = fixture.transport.sends[1]
        assertEquals(BusPaths.ACTIVITY_UPDATE, update.first)
        assertTrue(update.second.getBoolean("significant"))
        assertFalse(update.second.has("maxDurationMs"))
        assertTrue(
            ActivitySurfaceContract.validateUpdate(update.second) is
                ActivitySurfacePatchResult.Valid,
        )

        val end = fixture.transport.sends[2]
        assertEquals(BusPaths.ACTIVITY_END, end.first)
        assertEquals(ActivitySurfaceContract.LOCAL_SURFACE_ID, end.second.getString("surfaceId"))
    }

    @Test
    fun `full object update explicitly clears nullable fields and empty lists`() {
        val fixture = approvedFixture()
        val activity = NexusActivity(
            glyph = "timer",
            primary = "4 min",
            secondary = null,
            progress = null,
            eta = null,
            detail = emptyList(),
            actions = emptyList(),
            maxDurationMs = 900_000L,
        )

        assertEquals(NexusSdkResult.SENT, fixture.client.updateActivity(activity))

        val payload = fixture.transport.sends.single().second
        assertTrue(payload.has("secondary"))
        assertTrue(payload.isNull("secondary"))
        assertTrue(payload.has("progress"))
        assertTrue(payload.isNull("progress"))
        assertTrue(payload.has("eta"))
        assertTrue(payload.isNull("eta"))
        assertEquals(0, payload.getJSONArray("detail").length())
        assertEquals(0, payload.getJSONArray("actions").length())
        assertFalse(payload.has("maxDurationMs"))
        assertFalse(payload.has("significant"))
    }

    @Test
    fun `activity serializes wake display on start but never on update`() {
        val fixture = approvedFixture()
        val activity = NexusActivity(
            glyph = "timer",
            primary = "4 min",
            wakeDisplay = true,
        )

        fixture.client.startActivity(activity)
        fixture.client.updateActivity(activity, significant = true)

        assertTrue(fixture.transport.sends[0].second.getBoolean("wakeDisplay"))
        assertFalse(fixture.transport.sends[1].second.has("wakeDisplay"))
    }

    @Test
    fun `indeterminate progress uses its stable string wire value`() {
        val fixture = approvedFixture()

        assertEquals(
            NexusSdkResult.SENT,
            fixture.client.startActivity(
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    progress = NexusActivityProgress.Indeterminate,
                ),
            ),
        )

        assertEquals(
            "indeterminate",
            fixture.transport.sends.single().second.getString("progress"),
        )
    }

    @Test
    fun `activity calls require approval grant and feature but not a live link`() {
        val unapproved = fixture()
        assertEquals(
            NexusSdkResult.NOT_REGISTERED,
            unapproved.client.startActivity(activity()),
        )

        val noGrant = approvedFixture(capabilities = "http_proxy")
        assertEquals(NexusSdkResult.CAPABILITY_NOT_GRANTED, noGrant.client.endActivity())

        val oldHub = approvedFixture(featureBits = 0)
        assertFalse(oldHub.client.supportsActivitySurface)
        assertEquals(
            NexusSdkResult.CAPABILITY_NOT_AVAILABLE,
            oldHub.client.startActivity(activity()),
        )

        val capable = approvedFixture()
        assertTrue(capable.client.supportsActivitySurface)
        capable.transport.listener.onLinkState(LinkStateBits.CXR_CONTROL_UP)
        assertTrue(capable.client.supportsActivitySurface)
        assertEquals(NexusSdkResult.SENT, capable.client.startActivity(activity()))
    }

    @Test
    fun `extras are reported only with both bits and never refused`() {
        val v1 = approvedFixture()
        assertFalse(v1.client.supportsActivityExtras)
        // An extras-bearing activity still goes out; a v1 hub drops the fields.
        assertEquals(
            NexusSdkResult.SENT,
            v1.client.startActivity(activity().copy(badge = "38")),
        )

        val extrasOnly = approvedFixture(featureBits = BusCapabilityBits.ACTIVITY_EXTRAS)
        assertFalse(extrasOnly.client.supportsActivityExtras)

        val capable = approvedFixture(
            featureBits = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.ACTIVITY_EXTRAS,
        )
        assertTrue(capable.client.supportsActivityExtras)
    }

    @Test
    fun `badge track and urgent reach the wire in the contract shape`() {
        val fixture = approvedFixture(
            featureBits = BusCapabilityBits.ACTIVITY_SURFACE or BusCapabilityBits.ACTIVITY_EXTRAS,
        )
        val ride = NexusActivity(
            glyph = "bus",
            primary = "3 stops",
            progress = NexusActivityProgress.Percent(55),
            badge = "  38 ",
            track = NexusActivityTrack(count = 5, at = 2, target = 4, label = " Luxembourg "),
        )

        assertEquals(NexusSdkResult.SENT, fixture.client.startActivity(ride))
        val start = fixture.transport.sends.last().second
        assertEquals("38", start.getString("badge"))
        assertEquals("Luxembourg", start.getJSONObject("track").getString("label"))
        assertEquals(55, start.getInt("progress"))

        assertEquals(
            NexusSdkResult.SENT,
            fixture.client.updateActivity(ride, significant = true, urgent = true),
        )
        assertEquals("urgent", fixture.transport.sends.last().second.getString("tone"))

        assertEquals(
            NexusSdkResult.INVALID_PAYLOAD,
            fixture.client.updateActivity(ride, significant = false, urgent = true),
        )
    }

    @Test
    fun `extras models enforce their caps`() {
        assertThrows(IllegalArgumentException::class.java) {
            activity().copy(badge = "RER B1")
        }
        assertThrows(IllegalArgumentException::class.java) {
            activity().copy(measure = "1,25 km a")
        }
        assertEquals("250 m", activity().copy(measure = " 250 m ").toStartPayload().getString("measure"))
        assertTrue(activity().toUpdatePayload(significant = false).isNull("measure"))
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivityTrack(count = 1, at = 0, target = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivityTrack(count = 13, at = 0, target = 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivityTrack(count = 5, at = 3, target = 2)
        }
        assertThrows(IllegalArgumentException::class.java) {
            NexusActivityTrack(count = 5, at = 0, target = 1, label = "x".repeat(21))
        }
    }

    @Test
    fun `activity can start immediately on approval before a link callback`() {
        val fixture = fixture()
        fixture.transport.featureBits = BusCapabilityBits.ACTIVITY_SURFACE
        fixture.transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration",
            pluginPayload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", "surfaces"),
        )

        assertTrue(fixture.client.supportsActivitySurface)
        assertEquals(NexusSdkResult.SENT, fixture.client.startActivity(activity()))
    }

    @Test
    fun `activity callbacks are owner checked deduplicated and not sent raw`() {
        val fixture = approvedFixture()
        val action = pluginPayload()
            .put("activityId", "hello:activity")
            .put("id", "mute")
        fixture.transport.listener.onMessage(BusPaths.ACTIVITY_ACTION, "action-1", action)
        fixture.transport.listener.onMessage(BusPaths.ACTIVITY_ACTION, "action-1", action)
        fixture.transport.listener.onMessage(
            BusPaths.ACTIVITY_ACTION,
            "action-wrong-activity",
            pluginPayload().put("activityId", "other:activity").put("id", "pause"),
        )
        fixture.transport.listener.onMessage(
            BusPaths.ACTIVITY_ACTION,
            "action-wrong-plugin",
            JSONObject()
                .put("pluginId", "other")
                .put("activityId", "other:activity")
                .put("id", "pause"),
        )
        fixture.transport.listener.onMessage(
            BusPaths.ACTIVITY_CLOSED,
            "closed-1",
            pluginPayload()
                .put("activityId", "hello:activity")
                .put("reason", "future-reason"),
        )

        assertEquals(
            listOf("action:mute", "closed:future-reason"),
            fixture.callbacks.events,
        )
    }

    @Test
    fun `model enforces every declared cap after trimming`() {
        NexusActivity(
            glyph = "some-future-glyph",
            primary = "  " + "p".repeat(ActivitySurfaceContract.MAX_PRIMARY_CHARS) + "  ",
            secondary =
                "  " + "s".repeat(ActivitySurfaceContract.MAX_SECONDARY_CHARS) + "  ",
            eta = "  " + "e".repeat(ActivitySurfaceContract.MAX_ETA_CHARS) + "  ",
            detail = listOf(
                "d".repeat(ActivitySurfaceContract.MAX_DETAIL_CHARS),
                "second",
            ),
            actions = listOf(
                NexusActivityAction("a", "play", "A"),
                NexusActivityAction("b", "pause", "B"),
                NexusActivityAction("c", "stop", "C"),
            ),
        )

        val invalid = listOf<() -> Unit>(
            {
                NexusActivity(glyph = "Turn_Left", primary = "4 min")
            },
            {
                NexusActivity(glyph = "timer", primary = " ")
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "p".repeat(ActivitySurfaceContract.MAX_PRIMARY_CHARS + 1),
                )
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    secondary = "s".repeat(ActivitySurfaceContract.MAX_SECONDARY_CHARS + 1),
                )
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    eta = "e".repeat(ActivitySurfaceContract.MAX_ETA_CHARS + 1),
                )
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    detail = listOf("a", "b", "c"),
                )
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    detail = listOf(
                        "d".repeat(ActivitySurfaceContract.MAX_DETAIL_CHARS + 1),
                    ),
                )
            },
            {
                NexusActivity(
                    glyph = "timer",
                    primary = "4 min",
                    actions = listOf(
                        NexusActivityAction("a", "play", "A"),
                        NexusActivityAction("b", "pause", "B"),
                        NexusActivityAction("c", "stop", "C"),
                        NexusActivityAction("d", "next", "D"),
                    ),
                )
            },
            {
                NexusActivityProgress.Percent(-1)
            },
            {
                NexusActivityProgress.Percent(101)
            },
            {
                NexusActivityAction(" ", "play", "Play")
            },
            {
                NexusActivityAction("play", "Turn_Left", "Play")
            },
            {
                NexusActivityAction("play", "play", " ")
            },
        )
        invalid.forEach { constructor ->
            assertThrows(IllegalArgumentException::class.java, constructor)
        }
    }

    @Test
    fun `action model invents no length or uniqueness caps`() {
        val long = "x".repeat(512)
        val repeated = NexusActivityAction(long, "some-future-glyph", long)

        val activity = NexusActivity(
            glyph = "timer",
            primary = "4 min",
            actions = listOf(repeated, repeated),
        )

        assertEquals(2, activity.actions.size)
    }

    @Test
    fun `duration is clamped only when serialized`() {
        val low = NexusActivity(glyph = "timer", primary = "4 min", maxDurationMs = Long.MIN_VALUE)
        val high = NexusActivity(glyph = "timer", primary = "4 min", maxDurationMs = Long.MAX_VALUE)

        assertEquals(Long.MIN_VALUE, low.maxDurationMs)
        assertEquals(Long.MAX_VALUE, high.maxDurationMs)
        assertEquals(
            ActivitySurfaceContract.MIN_MAX_DURATION_MS,
            low.toStartPayload().getLong("maxDurationMs"),
        )
        assertEquals(
            ActivitySurfaceContract.MAX_MAX_DURATION_MS,
            high.toStartPayload().getLong("maxDurationMs"),
        )
    }

    private fun approvedFixture(
        capabilities: String = "surfaces",
        featureBits: Int = BusCapabilityBits.ACTIVITY_SURFACE,
    ): Fixture = fixture().also { fixture ->
        fixture.transport.featureBits = featureBits
        fixture.transport.listener.onMessage(
            BusPaths.PLUGIN_REGISTRATION,
            "registration-${System.identityHashCode(fixture)}",
            pluginPayload()
                .put("result", PluginRegistrationResult.APPROVED)
                .put("capabilities", capabilities),
        )
    }

    private fun fixture(): Fixture {
        val transport = FakeTransport()
        val callbacks = RecordingCallbacks()
        val client = NexusPluginClient("hello", callbacks, transport)
        client.connect()
        return Fixture(client, transport, callbacks)
    }

    private fun activity(): NexusActivity = NexusActivity(
        glyph = "timer",
        primary = "4 min",
    )

    private fun pluginPayload(): JSONObject = JSONObject().put("pluginId", "hello")
}
