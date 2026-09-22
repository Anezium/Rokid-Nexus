package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PinSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePinRouterTest {
    private var now = 10_000L
    private var postedDelay: Long? = null
    private val sink = RecordingPhoneHudRouteSink(capabilityBits = BusCapabilityBits.PIN_SURFACE)
    private val router = PhonePinRouter(
        state = PhonePinState(nowMs = { now }, initialSequence = 40L),
        sink = sink,
        nowMs = { now },
        postExpiry = { postedDelay = it },
        cancelExpiry = { postedDelay = null },
    )

    @Test
    fun `offline pin retains its deadline and resends only the remaining lifetime`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("alpha", ttlMs = 10_000L), hudSender("alpha"))
        assertEquals("alpha", router.ownerPluginId())
        assertEquals(10_000L, postedDelay)
        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.errors.isEmpty())
        assertEquals(PluginBusJournal.Verdict.OK, sink.localRoutes.single().verdict)

        now += 4_000L
        sink.linkUp = true
        router.resendIfAvailable()

        val resent = sink.remote.single()
        assertEquals(BusPaths.PIN_SHOW, resent.path)
        assertEquals("alpha", resent.payload.getString("ownerPluginId"))
        assertEquals(6_000L, resent.payload.getLong("ttlMs"))
        assertEquals(6_000L, postedDelay)
    }

    @Test
    fun `a pin expired offline reconnects as an empty slot instead of a late show`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("alpha", ttlMs = 1_000L), hudSender("alpha"))
        now += 1_000L
        router.expireCanonical()
        assertNull(router.ownerPluginId())
        assertNull(postedDelay)
        assertTrue(sink.remote.isEmpty())

        sink.linkUp = true
        router.resendIfAvailable()
        assertEquals(BusPaths.PIN_HIDE, sink.remote.single().path)
        assertEquals(HUB_OWNER_ID, sink.remote.single().payload.getString("ownerPluginId"))
    }

    @Test
    fun `offline owner hide cancels expiry and asserts an empty slot on reconnect`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("alpha", ttlMs = 10_000L), hudSender("alpha"))
        router.handleLocal(hideEnvelope("alpha"), hudSender("alpha"))
        assertNull(postedDelay)
        assertNull(router.ownerPluginId())
        assertTrue(sink.remote.isEmpty())
        sink.linkUp = true
        router.resendIfAvailable()
        assertEquals(BusPaths.PIN_HIDE, sink.remote.single().path)
    }

    @Test
    fun `revoking another plugin leaves the pin while revoking its owner clears it`() {
        router.handleLocal(showEnvelope("alpha", ttlMs = 10_000L), hudSender("alpha"))
        sink.remote.clear()
        router.clearForRevokedOwner("beta", "revoked")
        assertEquals("alpha", router.ownerPluginId())
        assertEquals(10_000L, postedDelay)
        assertTrue(sink.remote.isEmpty())

        router.clearForRevokedOwner("alpha", "revoked")
        assertNull(router.ownerPluginId())
        assertNull(postedDelay)
        assertEquals(BusPaths.PIN_HIDE, sink.remote.single().path)
        assertEquals("alpha", sink.remote.single().payload.getString("ownerPluginId"))
        assertTrue(sink.local.isEmpty())
    }

    @Test
    fun `pins without an explicit TTL use the shared default lifetime`() {
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))
        assertEquals(PinSurfaceContract.DEFAULT_TTL_MS, postedDelay)
        now += PinSurfaceContract.DEFAULT_TTL_MS - 1L
        router.expireCanonical()
        assertEquals("alpha", router.ownerPluginId())
        assertEquals(1L, postedDelay)
        assertEquals(BusPaths.PIN_SHOW, sink.remote.single().path)
        now += 1L
        router.expireCanonical()
        assertNull(router.ownerPluginId())
        assertNull(postedDelay)
        assertEquals(BusPaths.PIN_HIDE, sink.remote.last().path)
    }

    @Test
    fun `remote send failure reports the original request while retaining canonical pin`() {
        sink.sendRemoteError = "NO_DATA_PLANE"
        val envelope = showEnvelope("alpha")
        router.handleLocal(envelope, hudSender("alpha"))
        assertEquals(listOf(envelope.id to "NO_DATA_PLANE"), sink.errors)
        assertEquals("alpha", router.ownerPluginId())
        assertEquals(PluginBusJournal.Verdict.OK, sink.localRoutes.single().verdict)
    }

    @Test
    fun `capability rejection and a foreign hide cannot replace canonical ownership`() {
        sink.capabilityBits = 0
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))
        assertEquals(PinSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, sink.errors.single().second)
        assertNull(router.ownerPluginId())
        sink.capabilityBits = BusCapabilityBits.PIN_SURFACE
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))
        sink.remote.clear()
        router.handleLocal(hideEnvelope("beta"), hudSender("beta"))
        assertEquals("alpha", router.ownerPluginId())
        assertEquals("PIN_HIDE_IGNORED_NOT_OWNER", sink.localRoutes.last().reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `local validation runs before owner namespacing and rejects binary pins`() {
        val local = showEnvelope("alpha").copy(
            payload = JSONObject().put("surfaceId", "pin").put("kind", "pin").put("title", "Gate"),
        )
        assertNull(router.invalidLocalReason(local))
        assertEquals(PinSurfaceContract.ERROR_INVALID_PIN, router.invalidLocalReason(showEnvelope("alpha")))
        assertEquals(PinSurfaceContract.ERROR_INVALID_PIN, router.invalidLocalReason(local.copy(binary = byteArrayOf(1))))
    }

    private fun showEnvelope(pluginId: String, ttlMs: Long? = null) = BusEnvelope(
        path = BusPaths.PIN_SHOW,
        payload = identity(pluginId)
            .put("kind", PinSurfaceContract.KIND)
            .put("title", "Gate")
            .put("lines", JSONArray().put("A12"))
            .apply { ttlMs?.let { put("ttlMs", it) } },
    )

    private fun hideEnvelope(pluginId: String) = BusEnvelope(path = BusPaths.PIN_HIDE, payload = identity(pluginId))

    private fun identity(pluginId: String) = JSONObject()
        .put("surfaceId", "$pluginId:${PinSurfaceContract.LOCAL_SURFACE_ID}")
        .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
        .put("ownerPluginId", pluginId)
}
