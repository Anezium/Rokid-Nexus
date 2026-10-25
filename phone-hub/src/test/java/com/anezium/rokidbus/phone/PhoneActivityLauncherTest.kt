package com.anezium.rokidbus.phone

import android.os.IBinder
import com.anezium.rokidbus.shared.ActivityGeometryUpdate
import com.anezium.rokidbus.shared.ActivityHeading
import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.QuickActionFamily
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's activity tier with the launcher bundle: stamps, collection, offline, commands, geometry. */
class PhoneActivityLauncherTest {
    private class FakeLauncherHost : PhoneActivityLauncherHost {
        val registrations = mutableMapOf<String, Int>()
        var dataPlane = true
        val dataPlaneSent = mutableListOf<BusEnvelope>()
        var persisted: List<Pair<String, JSONObject>>? = null

        override fun registrationCount(pluginId: String): Int = registrations[pluginId] ?: 1
        override fun dataPlaneUp(): Boolean = dataPlane
        override fun sendDataPlane(envelope: BusEnvelope): String? {
            if (!dataPlane) return "NO_DATA_PLANE"
            dataPlaneSent += envelope
            return null
        }

        override fun persistOffline(snapshots: List<Pair<String, JSONObject>>) {
            persisted = snapshots
        }
    }

    private var now = 10_000L
    private var wall = 1_000_000L
    private var launcherVersion: Int? = 1
    private val pinned = mutableSetOf<String>()
    private val disabled = mutableSetOf<String>()
    private val quick = mutableMapOf<String, Set<QuickActionFamily>>()
    private val policy = object : PhoneActivityOwnerPolicy {
        override fun isEnabled(ownerPluginId: String): Boolean = ownerPluginId !in disabled
        override fun isPinned(ownerPluginId: String): Boolean = ownerPluginId in pinned
        override fun quickFamilies(ownerPluginId: String): Set<QuickActionFamily> = quick[ownerPluginId].orEmpty()
    }

    /** Every remote send and local delivery in the order they happened. */
    private val events = mutableListOf<String>()
    private val sink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.ACTIVITY_SURFACE)
    private val orderedSink = object : PhoneHudRouteSink by sink {
        override fun sendRemote(envelope: BusEnvelope): String? {
            events += "remote:${envelope.path}:${envelope.payload.optString("ownerPluginId")}"
            return sink.sendRemote(envelope)
        }

        override fun deliverLocal(envelope: BusEnvelope, targetBinder: IBinder?): Boolean {
            events += "local:${envelope.path}:${envelope.payload.optString("pluginId")}"
            return sink.deliverLocal(envelope, targetBinder)
        }
    }
    private val host = FakeLauncherHost()
    private val state = newState()
    private val router = newRouter(state, orderedSink, host)

    @Test
    fun `render feedback is authenticated against live owner sequence and forwarded geometry revision`() {
        sink.capabilityBits = sink.capabilityBits or BusCapabilityBits.RENDERED_MAP
        router.handleLocal(local(start("nav")), hudSender("nav"))
        router.handleLocal(local(start("other")), hudSender("other"))
        val seq = sink.remote.first { it.payload.optString("ownerPluginId") == "nav" }.payload.getLong("seq")
        router.handleLocal(geometry("nav", 7), hudSender("nav"))
        val event = com.anezium.rokidbus.shared.RenderedMapContext("ambient", "nav:activity", 7, seq).toPayload()
            .put("token", "map1").put("visible", true).put("view", "follow").put("pluginId", "other")
        router.handleRenderedMap(event)
        val delivered = sink.local.single { it.path == com.anezium.rokidbus.shared.RenderedMapContract.PATH }.payload
        assertEquals("nav", delivered.getString("pluginId"))
        assertFalse(delivered.has("id"))
        assertFalse(delivered.has("seq"))
        assertEquals(7L, delivered.getLong("revision"))
        assertFalse(delivered.has("pageId"))
        val count = sink.local.size
        router.handleRenderedMap(JSONObject(event.toString()).put("seq", seq - 1))
        router.handleRenderedMap(JSONObject(event.toString()).put("revision", 6))
        router.handleRenderedMap(JSONObject(event.toString()).put("id", "other:activity"))
        host.registrations["nav"] = 2
        router.handleRenderedMap(event)
        host.registrations["nav"] = 1
        host.dataPlane = false
        router.handleRenderedMap(event)
        host.dataPlane = true
        assertEquals(count, sink.local.size)
        router.handleLocal(local(identity("nav"), BusPaths.ACTIVITY_END), hudSender("nav"))
        val afterEnd = sink.local.size
        router.handleRenderedMap(event)
        assertEquals(afterEnd, sink.local.size)
    }

    private fun newState(initialSequence: Long = 40L) = PhoneActivityState(
        nowMs = { now },
        initialSequence = initialSequence,
        wallMs = { wall },
        launcherVersion = { launcherVersion },
        policy = policy,
    )

    private fun newRouter(state: PhoneActivityState, sink: PhoneHudRouteSink, host: PhoneActivityLauncherHost) =
        PhoneActivityRouter(
            state = state,
            sink = sink,
            nowMs = { now },
            postExpiry = {},
            cancelExpiry = {},
            launcher = host,
        )

    @Test
    fun `negotiated glasses get the phone's stamps and never the plugin's`() {
        val forged = start("media", mediaActions())
            .put("state", "offline")
            .put("userPinned", true)
            .put("knownAtWallMs", 5L)
            .put("headline", "Teardrop (Mad Professor remix)")
        forged.getJSONArray("actions").getJSONObject(0).put("hubQuick", true)
        router.handleLocal(local(forged), hudSender("media"))

        val sent = sink.remote.single().payload
        assertEquals("Teardrop (Mad Professor remix)", sent.getString("headline"))
        assertEquals("live", sent.getString("state"))
        assertFalse(sent.getBoolean("userPinned"))
        assertEquals(wall, sent.getLong("knownAtWallMs"))
        val play = sent.getJSONArray("actions").getJSONObject(0)
        assertFalse(play.has("hubQuick"))
        assertEquals("media.play", play.getJSONObject("typed").getString("type"))
    }

    @Test
    fun `an approved family is stamped quick and a policy change restamps under a new seq`() {
        router.handleLocal(local(start("media", mediaActions())), hudSender("media"))
        val firstSeq = sink.remote.single().payload.getLong("seq")

        quick["media"] = setOf(QuickActionFamily.MEDIA)
        router.onQuickPolicyChanged("media")

        val restamp = sink.remote.last()
        assertEquals(BusPaths.ACTIVITY_UPDATE, restamp.path)
        assertTrue(restamp.payload.getLong("seq") > firstSeq)
        val actions = restamp.payload.getJSONArray("actions")
        assertTrue(actions.getJSONObject(0).getBoolean("hubQuick"))
        assertFalse(actions.getJSONObject(1).has("hubQuick"))
    }

    @Test
    fun `glasses without the bundle get a v1 activity with every launcher field stripped`() {
        launcherVersion = 0
        val payload = start("maps", mediaActions())
            .put("destination", JSONObject().put("pageId", "maps:main"))
            .put("persistence", "ongoing")
            .put("defaultActionId", "open")
            .put("headline", "Teardrop (Mad Professor remix)")
        router.handleLocal(local(payload), hudSender("maps"))
        router.handleLocal(local(identity("maps").put("headline", "Angel"), BusPaths.ACTIVITY_UPDATE), hudSender("maps"))

        assertEquals(2, sink.remote.size)
        val sent = sink.remote.first().payload
        listOf("destination", "activation", "defaultActionId", "persistence", "headline", "state", "userPinned", "knownAtWallMs")
            .forEach { assertFalse(it, sent.has(it)) }
        assertEquals("300 m", sent.getString("primary"))
        assertFalse(sink.remote.last().payload.has("headline"))
        val actions = sent.getJSONArray("actions")
        (0 until actions.length()).forEach { index ->
            assertFalse(actions.getJSONObject(index).has("typed"))
            assertFalse(actions.getJSONObject(index).has("hubQuick"))
        }
        // Without the bundle an ongoing entry still ends with its owner, as in v1.
        router.clearForDisconnectedOwner("maps", "binderDied")
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.last().path)
    }

    @Test
    fun `a destination naming another plugin's page is refused on start and on update`() {
        router.handleLocal(local(start("maps").put("destination", JSONObject().put("pageId", "relay:main"))), hudSender("maps"))
        assertEquals(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY, sink.errors.single().second)
        assertTrue(router.ownerPluginIds().isEmpty())

        router.handleLocal(local(start("maps")), hudSender("maps"))
        val update = identity("maps").put("destination", JSONObject().put("pageId", "relay:main"))
        router.handleLocal(local(update, BusPaths.ACTIVITY_UPDATE), hudSender("maps"))
        assertEquals(listOf(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY, ActivitySurfaceContract.ERROR_INVALID_ACTIVITY), sink.errors.map { it.second })
        // An update whose result breaks the interactive rules is refused too.
        router.handleLocal(local(identity("maps").put("activation", "commands"), BusPaths.ACTIVITY_UPDATE), hudSender("maps"))
        assertEquals(3, sink.errors.size)
    }

    @Test
    fun `at capacity an offline entry goes first, then the least recent unpinned, and a pinned one stays`() {
        pinned += "act0"
        router.handleLocal(local(start("act0")), hudSender("act0"))
        router.handleLocal(local(start("act1")), hudSender("act1"))
        router.handleLocal(local(start("act2").put("persistence", "ongoing")), hudSender("act2"))
        (3 until ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES).forEach {
            router.handleLocal(local(start("act$it")), hudSender("act$it"))
        }
        router.clearForDisconnectedOwner("act2", "binderDied")
        assertEquals(ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES, router.ownerPluginIds().size)
        events.clear()

        router.handleLocal(local(start("new1")), hudSender("new1"))
        assertFalse("act2" in router.ownerPluginIds())
        // The offline owner is gone and hears nothing.
        assertTrue(events.none { it.startsWith("local:${BusPaths.ACTIVITY_CLOSED}") })

        router.handleLocal(local(start("new2")), hudSender("new2"))
        assertFalse("act1" in router.ownerPluginIds())
        assertTrue("local:${BusPaths.ACTIVITY_CLOSED}:act1" in events)
        assertEquals("replaced", sink.local.last { it.path == BusPaths.ACTIVITY_CLOSED }.payload.getString("reason"))

        repeat(10) { router.handleLocal(local(start("more$it")), hudSender("more$it")) }
        assertTrue("act0" in router.ownerPluginIds())
        assertEquals(ActivitySurfaceContract.MAX_COLLECTION_ACTIVITIES, router.ownerPluginIds().size)
    }

    @Test
    fun `a capacity drop trims to two and tells evicted owners before anything is resent`() {
        (0 until 4).forEach { router.handleLocal(local(start("act$it")), hudSender("act$it")) }
        events.clear()
        sink.remote.clear()

        launcherVersion = 0
        router.resendIfAvailable()

        assertEquals(setOf("act0", "act3"), router.ownerPluginIds())
        val firstRemote = events.indexOfFirst { it.startsWith("remote:") }
        val closes = events.withIndex().filter { it.value.startsWith("local:${BusPaths.ACTIVITY_CLOSED}") }
        assertEquals(listOf("act1", "act2"), closes.map { it.value.substringAfterLast(':') })
        assertTrue(closes.all { it.index < firstRemote })
        assertTrue(sink.local.filter { it.path == BusPaths.ACTIVITY_CLOSED }.all { it.payload.getString("reason") == "replaced" })
        val starts = sink.remote.filter { it.path == BusPaths.ACTIVITY_START }
        assertEquals(2, starts.size)
        assertTrue(starts.none { it.payload.has("state") })
    }

    @Test
    fun `an ongoing or pinned entry goes offline when its owner leaves and any other ends`() {
        val nav = start("nav", mediaActions())
            .put("persistence", "ongoing")
            .put("destination", JSONObject().put("pageId", "nav:main"))
        router.handleLocal(local(nav), hudSender("nav"))
        pinned += "pin"
        router.handleLocal(local(start("pin")), hudSender("pin"))
        router.handleLocal(local(start("plain")), hudSender("plain"))
        val navSeq = sink.remote.first { it.payload.optString("ownerPluginId") == "nav" }.payload.getLong("seq")
        sink.remote.clear()
        sink.local.clear()
        wall += 60_000L

        router.clearForDisconnectedOwner("nav", "binderDied")
        router.clearForDisconnectedOwner("pin", "binderDied")
        router.clearForDisconnectedOwner("plain", "binderDied")

        val offline = sink.remote.filter { it.path == BusPaths.ACTIVITY_START }.map { it.payload }
        assertEquals(listOf("nav", "pin"), offline.map { it.getString("ownerPluginId") })
        val navOffline = offline.first()
        assertEquals("offline", navOffline.getString("state"))
        assertEquals(0, navOffline.getJSONArray("actions").length())
        assertEquals("nav:main", navOffline.getJSONObject("destination").getString("pageId"))
        assertEquals(wall, navOffline.getLong("knownAtWallMs"))
        assertTrue(navOffline.getLong("seq") > navSeq)
        assertTrue(offline[1].getBoolean("userPinned"))
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.last().path)
        assertEquals("plain", sink.remote.last().payload.getString("ownerPluginId"))
        assertEquals(setOf("nav", "pin"), host.persisted.orEmpty().map { it.first }.toSet())
        assertTrue(state.isOffline("nav"))
        // Revoke takes the offline entry and its snapshot with it.
        router.clearForRevokedOwner("nav", "authorizationChanged")
        assertEquals(setOf("pin"), host.persisted.orEmpty().map { it.first }.toSet())
    }

    @Test
    fun `an offline entry takes no command or update and revives only through a fresh start`() {
        router.handleLocal(local(start("nav", mediaActions()).put("persistence", "ongoing")), hudSender("nav"))
        router.clearForDisconnectedOwner("nav", "binderDied")
        val offlineSeq = sink.remote.last().payload.getLong("seq")
        sink.local.clear()

        router.handleGlassesAction(command("nav", "play", offlineSeq, "inv-1"))
        router.handleLocal(local(identity("nav").put("primary", "1 km"), BusPaths.ACTIVITY_UPDATE), hudSender("nav"))
        assertTrue(sink.local.none { it.path == BusPaths.ACTIVITY_ACTION })
        assertTrue(state.isOffline("nav"))

        router.handleLocal(local(start("nav", mediaActions())), hudSender("nav"))
        val revived = sink.remote.last().payload
        assertEquals("live", revived.getString("state"))
        assertTrue(revived.getLong("seq") > offlineSeq)
        assertTrue(state.isLive("nav"))
    }

    @Test
    fun `persisted offline entries come back offline after a restart and only a fresh start revives them`() {
        router.handleLocal(local(start("nav").put("persistence", "ongoing")), hudSender("nav"))
        router.clearForDisconnectedOwner("nav", "binderDied")
        val key = PluginGrantKey("dev.example.nav", "nav", "digest-nav")
        val stored = PhoneActivitySnapshotStore.decode(
            PhoneActivitySnapshotStore.encode(host.persisted.orEmpty().map { PhoneActivitySnapshot(key, it.second) }),
        )

        val restartedHost = FakeLauncherHost()
        val restartedSink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.ACTIVITY_SURFACE)
        val restartedState = newState(initialSequence = 9_000L)
        val restarted = newRouter(restartedState, restartedSink, restartedHost)
        // A live payload is no offline snapshot and never comes back as one.
        restarted.restoreOffline(stored.map { it.key.pluginId to it.activity } + ("liar" to start("liar")))
        assertEquals(setOf("nav"), restarted.ownerPluginIds())
        assertTrue(restartedState.isOffline("nav"))
        assertEquals(listOf("nav"), restartedHost.persisted.orEmpty().map { it.first })

        restarted.resendIfAvailable()
        val resent = restartedSink.remote.single { it.path == BusPaths.ACTIVITY_START }.payload
        assertEquals("offline", resent.getString("state"))
        assertEquals(wall, resent.getLong("knownAtWallMs"))
        restarted.handleGlassesAction(command("nav", "play", resent.getLong("seq"), "inv-1"))
        assertTrue(restartedSink.local.none { it.path == BusPaths.ACTIVITY_ACTION })

        restarted.handleLocal(local(start("nav")), hudSender("nav"))
        assertTrue(restartedState.isLive("nav"))
    }

    @Test
    fun `a plugin whose activity the user disabled is refused and its entry ends`() {
        router.handleLocal(local(start("maps")), hudSender("maps"))
        disabled += "maps"
        router.onPreferencesChanged("maps")
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.last().path)
        assertEquals("replaced", sink.local.last { it.path == BusPaths.ACTIVITY_CLOSED }.payload.getString("reason"))

        router.handleLocal(local(start("maps")), hudSender("maps"))
        assertEquals(ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED, sink.errors.last().second)
        assertTrue(router.ownerPluginIds().isEmpty())
    }

    @Test
    fun `pinning restamps the entry and unpinning a session entry left offline removes it`() {
        router.handleLocal(local(start("maps")), hudSender("maps"))
        pinned += "maps"
        router.onPreferencesChanged("maps")
        val restamp = sink.remote.last()
        assertEquals(BusPaths.ACTIVITY_UPDATE, restamp.path)
        assertTrue(restamp.payload.getBoolean("userPinned"))

        router.clearForDisconnectedOwner("maps", "binderDied")
        assertTrue(state.isOffline("maps"))
        pinned -= "maps"
        router.onPreferencesChanged("maps")
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.last().path)
        assertTrue(host.persisted.orEmpty().isEmpty())
    }

    @Test
    fun `a command is bound to the visible seq and carries the phone's own type`() {
        router.handleLocal(local(start("media", mediaActions())), hudSender("media"))
        val seq = sink.remote.last().payload.getLong("seq")

        router.handleGlassesAction(command("media", "play", seq - 1, "stale-1"))
        router.handleGlassesAction(command("media", "play", seq, "quick-1", mode = "quick"))
        router.handleGlassesAction(command("media", "open", seq, "quick-2", mode = "quick"))
        router.handleGlassesAction(command("media", "gone", seq, "undeclared-1"))
        router.handleGlassesAction(command("other", "play", seq, "cross-1"))
        router.handleGlassesAction(
            BusEnvelope(
                path = BusPaths.ACTIVITY_ACTION,
                payload = JSONObject().put("activityId", "media:activity").put("id", "play"),
            ),
        )
        assertTrue(actionsDelivered().isEmpty())

        val forged = command("media", "play", seq, "inv-1")
        forged.payload.put("typed", JSONObject().put("type", "media.next"))
        router.handleGlassesAction(forged)
        val delivered = actionsDelivered().single().payload
        assertEquals("media", delivered.getString("pluginId"))
        assertEquals("media:activity", delivered.getString("activityId"))
        assertEquals("inv-1", delivered.getString("invocationId"))
        assertEquals(seq, delivered.getLong("seq"))
        assertEquals("media.play", delivered.getJSONObject("typed").getString("type"))
        assertFalse(delivered.has("mode"))

        // One pending command per owner, and an invocation id is never used twice.
        router.handleGlassesAction(command("media", "open", seq, "inv-2"))
        assertEquals(1, actionsDelivered().size)
        now += ActivitySurfaceContract.COMMAND_DEADLINE_MS
        router.handleGlassesAction(command("media", "open", seq, "inv-1"))
        assertEquals(1, actionsDelivered().size)
        router.handleGlassesAction(command("media", "open", seq, "inv-3"))
        assertEquals(2, actionsDelivered().size)
    }

    @Test
    fun `a command needs exactly one live registration of its owner`() {
        router.handleLocal(local(start("media", mediaActions())), hudSender("media"))
        val seq = sink.remote.last().payload.getLong("seq")
        host.registrations["media"] = 2
        router.handleGlassesAction(command("media", "play", seq, "inv-1"))
        assertTrue(actionsDelivered().isEmpty())
        host.registrations["media"] = 1
        router.handleGlassesAction(command("media", "play", seq, "inv-2"))
        assertEquals(1, actionsDelivered().size)
    }

    @Test
    fun `an approved quick command runs and its result reaches the glasses once`() {
        quick["media"] = setOf(QuickActionFamily.MEDIA)
        router.handleLocal(local(start("media", mediaActions())), hudSender("media"))
        val seq = sink.remote.last().payload.getLong("seq")
        router.handleGlassesAction(command("media", "play", seq, "inv-1", mode = "quick"))
        assertEquals(1, actionsDelivered().size)
        sink.remote.clear()

        router.handleLocal(result("media", "other-invocation"), hudSender("media"))
        router.handleLocal(result("other", "inv-1", activityOwner = "media"), hudSender("other"))
        assertTrue(sink.remote.isEmpty())
        router.handleLocal(result("media", "inv-1"), hudSender("media"))
        val forwarded = sink.remote.single()
        assertEquals(BusPaths.ACTIVITY_RESULT, forwarded.path)
        assertEquals("media", forwarded.payload.getString("ownerPluginId"))
        assertEquals("inv-1", forwarded.payload.getString("invocationId"))
        assertEquals("done", forwarded.payload.getString("status"))
        router.handleLocal(result("media", "inv-1"), hudSender("media"))
        assertEquals(1, sink.remote.size)

        // A result past the eight seconds is late: the glasses already show it unconfirmed.
        router.handleGlassesAction(command("media", "play", seq, "inv-2"))
        now += ActivitySurfaceContract.COMMAND_DEADLINE_MS
        router.handleLocal(result("media", "inv-2"), hudSender("media"))
        assertEquals(1, sink.remote.size)
    }

    @Test
    fun `withdrawing a quick approval mid-flight drops the pending quick command's result`() {
        quick["media"] = setOf(QuickActionFamily.MEDIA)
        router.handleLocal(local(start("media", mediaActions())), hudSender("media"))
        val seq = sink.remote.last().payload.getLong("seq")
        router.handleGlassesAction(command("media", "play", seq, "inv-1", mode = "quick"))
        assertEquals(1, actionsDelivered().size)

        quick.remove("media")
        router.onQuickPolicyChanged("media")
        val restamp = sink.remote.last()
        assertEquals(BusPaths.ACTIVITY_UPDATE, restamp.path)
        assertFalse(restamp.payload.getJSONArray("actions").getJSONObject(0).has("hubQuick"))
        sink.remote.clear()
        router.handleLocal(result("media", "inv-1"), hudSender("media"))
        assertTrue(sink.remote.none { it.path == BusPaths.ACTIVITY_RESULT })
        // Nor can the restamped revision be run quick any more.
        router.handleGlassesAction(command("media", "play", restamp.payload.getLong("seq"), "inv-2", mode = "quick"))
        assertEquals(1, actionsDelivered().size)
    }

    @Test
    fun `glasses without the bundle keep the legacy action and refuse the bundle's plugin paths`() {
        launcherVersion = 0
        router.handleLocal(local(start("maps", mediaActions())), hudSender("maps"))
        router.handleGlassesAction(
            BusEnvelope(
                path = BusPaths.ACTIVITY_ACTION,
                payload = JSONObject().put("activityId", "maps:activity").put("id", "open").put("pluginId", "forged"),
            ),
        )
        val delivered = actionsDelivered().single().payload
        assertEquals("maps", delivered.getString("pluginId"))
        assertEquals("open", delivered.getString("id"))
        assertFalse(delivered.has("invocationId"))

        router.handleLocal(result("maps", "inv-1"), hudSender("maps"))
        router.handleLocal(geometry("maps", 1), hudSender("maps"))
        assertEquals(
            listOf(ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE),
            sink.errors.map { it.second },
        )
    }

    @Test
    fun `route geometry rides the data plane only, latest wins, and is never queued`() {
        router.handleLocal(local(start("nav")), hudSender("nav"))
        host.dataPlane = false
        router.handleLocal(geometry("nav", 1), hudSender("nav"))
        assertTrue(host.dataPlaneSent.isEmpty())

        host.dataPlane = true
        router.handleLocal(geometry("nav", 2), hudSender("nav"))
        router.handleLocal(geometry("nav", 2), hudSender("nav"))
        router.handleLocal(geometry("nav", 1), hudSender("nav"))
        assertEquals(listOf(2L), host.dataPlaneSent.map { it.payload.getLong("revision") })
        assertEquals("nav", host.dataPlaneSent.single().payload.getString("ownerPluginId"))
        assertTrue(sink.remote.none { it.path == BusPaths.ACTIVITY_GEOMETRY })

        // Geometry belongs to one live session: nothing after its end.
        router.handleLocal(local(identity("nav"), BusPaths.ACTIVITY_END), hudSender("nav"))
        router.handleLocal(geometry("nav", 3), hudSender("nav"))
        assertEquals(1, host.dataPlaneSent.size)
        // Nothing was ever queued for the data plane's return.
        assertTrue(sink.errors.isEmpty())
    }

    @Test
    fun `heading is limited to five a second per owner over the data plane`() {
        router.handleLocal(local(start("nav")), hudSender("nav"))
        repeat(ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND + 2) {
            router.handleLocal(heading("nav", 90.0), hudSender("nav"))
        }
        assertEquals(ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND, host.dataPlaneSent.size)
        now += 1_000L
        host.dataPlane = false
        router.handleLocal(heading("nav", 91.0), hudSender("nav"))
        assertEquals(ActivitySurfaceContract.MAX_HEADINGS_PER_SECOND, host.dataPlaneSent.size)
        assertTrue(sink.remote.none { it.path == BusPaths.ACTIVITY_HEADING })
    }

    private fun actionsDelivered() = sink.local.filter { it.path == BusPaths.ACTIVITY_ACTION }

    private fun identity(pluginId: String) = JSONObject()
        .put("surfaceId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", pluginId)

    /** An owner-stamped payload, as the hub hands it to the router after injecting the owner. */
    private fun owned(pluginId: String, payload: JSONObject): JSONObject {
        val stamped = identity(pluginId)
        payload.keys().forEach { key -> stamped.put(key, payload.get(key)) }
        return stamped
    }

    private fun start(pluginId: String, actions: JSONArray = JSONArray()) = identity(pluginId)
        .put("kind", ActivitySurfaceContract.KIND)
        .put("glyph", "straight")
        .put("primary", "300 m")
        .put("actions", actions)

    private fun mediaActions() = JSONArray()
        .put(
            JSONObject().put("id", "play").put("glyph", "play").put("label", "Play")
                .put("typed", JSONObject().put("type", "media.play")),
        )
        .put(JSONObject().put("id", "open").put("glyph", "open").put("label", "Open"))

    private fun local(payload: JSONObject, path: String = BusPaths.ACTIVITY_START) = BusEnvelope(path = path, payload = payload)

    private fun command(pluginId: String, actionId: String, seq: Long, invocationId: String, mode: String = "confirmed") =
        BusEnvelope(
            path = BusPaths.ACTIVITY_ACTION,
            payload = JSONObject()
                .put("activityId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
                .put("id", actionId)
                .put("invocationId", invocationId)
                .put("seq", seq)
                .put("mode", mode),
        )

    private fun result(pluginId: String, invocationId: String, activityOwner: String = pluginId) = local(
        identity(pluginId)
            .put("activityId", "$activityOwner:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
            .put("invocationId", invocationId)
            .put("status", "done"),
        BusPaths.ACTIVITY_RESULT,
    )

    private fun geometry(pluginId: String, revision: Long) = local(
        owned(pluginId, ActivitySurfaceContract.geometryPayload(ActivityGeometryUpdate(revision, ROUTE))),
        BusPaths.ACTIVITY_GEOMETRY,
    )

    private fun heading(pluginId: String, deg: Double) = local(
        owned(pluginId, ActivitySurfaceContract.headingPayload(ActivityHeading(deg, atWallMs = wall))),
        BusPaths.ACTIVITY_HEADING,
    )

    private companion object {
        val ROUTE = RouteGeometry(
            spanMeters = 300.0,
            paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 900), RoutePoint(500, 100)))),
        )
    }
}
