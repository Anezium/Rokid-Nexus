package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class PhoneActivityRouterTest {
    private var now = 10_000L
    private var postedDelay: Long? = null
    private val sink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.ACTIVITY_SURFACE)
    private val router = newRouter(sink)

    private fun newRouter(routeSink: PhoneHudRouteSink) = PhoneActivityRouter(
        state = PhoneActivityState(nowMs = { now }, initialSequence = 40L),
        sink = routeSink,
        nowMs = { now },
        postExpiry = { postedDelay = it },
        cancelExpiry = { postedDelay = null },
    )

    @Test
    fun `offline activities reconnect after a global clear without replaying significance`() {
        sink.linkUp = false
        router.handleLocal(startEnvelope("maps", 120_000L), hudSender("maps"))
        router.handleLocal(startEnvelope("timer", 120_000L), hudSender("timer"))
        router.handleLocal(updateEnvelope("timer").copy(payload = identity("timer").put("significant", true)), hudSender("timer"))
        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.errors.isEmpty())
        assertEquals(setOf("maps", "timer"), router.ownerPluginIds())
        now += 10_000L
        sink.linkUp = true
        router.resendIfAvailable()

        assertEquals(listOf(BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_START), sink.remote.map { it.path })
        assertEquals(ActivitySurfaceContract.EMPTY_ASSERT_OWNER_PLUGIN_ID, sink.remote.first().payload.getString("ownerPluginId"))
        assertEquals(listOf("timer", "maps"), sink.remote.drop(1).map { it.payload.getString("ownerPluginId") })
        sink.remote.drop(1).forEach {
            assertFalse(it.payload.has("significant"))
            assertEquals(110_000L, it.payload.getLong("maxDurationMs"))
        }
        assertTrue(sink.remote.zipWithNext().all { (left, right) -> left.payload.getLong("seq") < right.payload.getLong("seq") })
        assertEquals(110_000L, postedDelay)
    }

    @Test
    fun `failed reconnect clear aborts resident replay without losing canonical sessions`() {
        sink.linkUp = false
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))
        sink.linkUp = true
        sink.sendRemoteError = "NO_DATA_PLANE"
        router.resendIfAvailable()
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.single().path)
        assertEquals(setOf("maps"), router.ownerPluginIds())
        sink.sendRemoteError = null
        sink.remote.clear()
        router.resendIfAvailable()
        assertEquals(listOf(BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START), sink.remote.map { it.path })
    }

    @Test
    fun `owner end while offline closes locally and reconnects as empty`() {
        sink.linkUp = false
        router.handleLocal(startEnvelope("maps", 60_000L), hudSender("maps"))
        router.handleLocal(BusEnvelope(path = BusPaths.ACTIVITY_END, payload = identity("maps")), hudSender("maps"))
        assertNull(postedDelay)
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals("maps", sink.local.single().payload.getString("pluginId"))
        assertEquals("owner", sink.local.single().payload.getString("reason"))
        assertTrue(sink.remote.isEmpty())
        sink.linkUp = true
        router.resendIfAvailable()
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.single().path)
    }

    @Test
    fun `revoke and disconnect clear only their owners without callback to lost clients`() {
        router.handleLocal(startEnvelope("maps", 60_000L), hudSender("maps"))
        router.handleLocal(startEnvelope("timer", 120_000L), hudSender("timer"))
        sink.remote.clear()
        router.clearForRevokedOwner("maps", "revoked")
        assertEquals(setOf("timer"), router.ownerPluginIds())
        assertEquals(120_000L, postedDelay)
        router.clearForDisconnectedOwner("timer", "binder_died")
        assertTrue(router.ownerPluginIds().isEmpty())
        assertNull(postedDelay)
        assertEquals(listOf("maps", "timer"), sink.remote.map { it.payload.getString("ownerPluginId") })
        assertTrue(sink.remote.all { it.path == BusPaths.ACTIVITY_END })
        assertTrue(sink.local.isEmpty())
    }

    @Test
    fun `duration expiry closes exactly the due resident and schedules the next one`() {
        router.handleLocal(startEnvelope("maps", 60_000L), hudSender("maps"))
        router.handleLocal(startEnvelope("timer", 120_000L), hudSender("timer"))
        sink.remote.clear()
        now += 60_000L
        router.expireCanonical()
        assertEquals(setOf("timer"), router.ownerPluginIds())
        assertEquals(60_000L, postedDelay)
        assertEquals(BusPaths.ACTIVITY_END, sink.remote.single().path)
        assertEquals("maps", sink.local.single().payload.getString("pluginId"))
        assertEquals("max-duration", sink.local.single().payload.getString("reason"))
        router.expireCanonical()
        assertEquals(1, sink.local.size)
    }

    @Test
    fun `glasses events resolve the canonical owner and ignore removed actions or invalid closes`() {
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))
        val action = BusEnvelope(
            path = BusPaths.ACTIVITY_ACTION,
            payload = JSONObject().put("activityId", "maps:activity").put("id", "stop").put("pluginId", "forged"),
        )
        router.handleGlassesAction(action)
        assertEquals("maps", sink.local.single().payload.getString("pluginId"))
        assertEquals("forged", action.payload.getString("pluginId"))
        router.handleLocal(updateEnvelope("maps").copy(payload = identity("maps").put("actions", JSONArray())), hudSender("maps"))
        router.handleGlassesAction(action)
        assertEquals(1, sink.local.size)
        router.handleGlassesClosed(BusEnvelope(path = BusPaths.ACTIVITY_CLOSED, payload = JSONObject().put("activityId", "maps:activity").put("reason", "unknown")))
        assertEquals(setOf("maps"), router.ownerPluginIds())
        router.handleGlassesClosed(BusEnvelope(path = BusPaths.ACTIVITY_CLOSED, payload = JSONObject().put("activityId", "maps:activity").put("reason", "owner")))
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals(BusPaths.ACTIVITY_CLOSED, sink.local.last().path)
    }

    @Test
    fun `hub stop closes residents once and clears the whole remote tier`() {
        router.handleLocal(startEnvelope("maps", 60_000L), hudSender("maps"))
        router.handleLocal(startEnvelope("timer"), hudSender("timer"))
        sink.remote.clear()
        router.clearAllForHubStop()
        router.clearAllForHubStop()
        assertNull(postedDelay)
        assertTrue(router.ownerPluginIds().isEmpty())
        assertEquals(setOf("maps", "timer"), sink.local.map { it.payload.getString("pluginId") }.toSet())
        assertTrue(sink.local.all { it.payload.getString("reason") == "disconnect" })
        assertEquals(ActivitySurfaceContract.EMPTY_ASSERT_OWNER_PLUGIN_ID, sink.remote.single().payload.getString("ownerPluginId"))
    }

    @Test
    fun `reconnect clear and resident sends cannot interleave with a local update`() {
        val clearEntered = CountDownLatch(1)
        val releaseClear = CountDownLatch(1)
        val updateEntered = CountDownLatch(1)
        val updateDone = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val blockingSink = object : PhoneHudRouteSink by sink {
            override fun sendRemote(envelope: BusEnvelope): String? {
                if (envelope.path == BusPaths.ACTIVITY_END) {
                    clearEntered.countDown()
                    check(releaseClear.await(5, TimeUnit.SECONDS))
                }
                return sink.sendRemote(envelope)
            }
        }
        val concurrentRouter = newRouter(blockingSink)
        sink.linkUp = false
        concurrentRouter.handleLocal(startEnvelope("maps"), hudSender("maps"))
        concurrentRouter.handleLocal(startEnvelope("timer"), hudSender("timer"))
        sink.linkUp = true
        val reconnect = thread(name = "activity-reconnect") {
            try { concurrentRouter.resendIfAvailable() } catch (error: Throwable) { failure.set(error) }
        }
        var update: Thread? = null
        try {
            assertTrue(clearEntered.await(5, TimeUnit.SECONDS))
            update = thread(name = "activity-update") {
                updateEntered.countDown()
                try { concurrentRouter.handleLocal(updateEnvelope("maps"), hudSender("maps")) }
                catch (error: Throwable) { failure.set(error) }
                finally { updateDone.countDown() }
            }
            assertTrue(updateEntered.await(5, TimeUnit.SECONDS))
            assertFalse(updateDone.await(100, TimeUnit.MILLISECONDS))
        } finally {
            releaseClear.countDown()
            reconnect.join(5_000L)
            update?.join(5_000L)
        }
        assertNull(failure.get())
        assertFalse(reconnect.isAlive)
        assertFalse(update?.isAlive == true)
        assertEquals(listOf(BusPaths.ACTIVITY_END, BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_START, BusPaths.ACTIVITY_UPDATE), sink.remote.map { it.path })
        assertTrue(sink.remote.zipWithNext().all { (left, right) -> left.payload.getLong("seq") < right.payload.getLong("seq") })
    }

    @Test
    fun `prevalidation rejects binary and malformed local activity envelopes`() {
        val local = startEnvelope("maps").copy(payload = JSONObject().put("surfaceId", "activity").put("kind", "activity").put("glyph", "straight").put("primary", "300 m"))
        assertNull(router.invalidLocalReason(local))
        assertEquals(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY, router.invalidLocalReason(startEnvelope("maps")))
        assertEquals(ActivitySurfaceContract.ERROR_INVALID_ACTIVITY, router.invalidLocalReason(local.copy(binary = byteArrayOf(1))))
        sink.capabilityBits = 0
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))
        assertEquals(ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, sink.errors.single().second)
        assertTrue(router.ownerPluginIds().isEmpty())
    }

    private fun startEnvelope(pluginId: String, maxDurationMs: Long? = null) = BusEnvelope(
        path = BusPaths.ACTIVITY_START,
        payload = identity(pluginId)
            .put("kind", ActivitySurfaceContract.KIND)
            .put("glyph", "straight")
            .put("primary", "300 m")
            .put("actions", JSONArray().put(JSONObject().put("id", "stop").put("glyph", "stop").put("label", "Stop")))
            .apply { maxDurationMs?.let { put("maxDurationMs", it) } },
    )

    private fun updateEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.ACTIVITY_UPDATE,
        payload = identity(pluginId).put("primary", "200 m"),
    )

    private fun identity(pluginId: String) = JSONObject()
        .put("surfaceId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", pluginId)
}
