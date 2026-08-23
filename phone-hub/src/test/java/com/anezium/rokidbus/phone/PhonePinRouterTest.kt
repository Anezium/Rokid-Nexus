package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.PinSurfaceContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhonePinRouterTest {
    private var now = 10_000L
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.PIN_SURFACE,
        linkUp = true,
    )
    private var postedDelay: Long? = null
    private val router = PhonePinRouter(
        state = PhonePinState(nowMs = { now }, initialSequence = 40L),
        sink = sink,
        nowMs = { now },
        postExpiry = { delay -> postedDelay = delay },
        cancelExpiry = { postedDelay = null },
    )

    @Test
    fun `show journals OK and sends remote when the link is up`() {
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))

        val route = sink.localRoutes.single()
        assertEquals(BusPaths.PIN_SHOW, route.path)
        assertEquals("alpha", route.pluginId)
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals(null, route.reason)
        assertEquals(BusPaths.PIN_SHOW, sink.remote.single().path)
        assertTrue(postedDelay != null)
    }

    @Test
    fun `show journals OK and holds when the link is down`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.logs.any { it.contains("link_down") })
    }

    @Test
    fun `missing pin capability is rejected`() {
        sink.capabilityBits = 0
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals(PinSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, route.reason)
        assertEquals(PinSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, sink.errors.single().second)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `hide by a non-owner journals OK with ignored reason`() {
        router.handleLocal(showEnvelope("alpha"), hudSender("alpha"))
        sink.localRoutes.clear()
        sink.remote.clear()

        router.handleLocal(hideEnvelope("beta"), hudSender("beta"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals("PIN_HIDE_IGNORED_NOT_OWNER", route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    private fun showEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.PIN_SHOW,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${PinSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("kind", PinSurfaceContract.KIND)
            .put("title", "NEXUS PIN")
            .put("lines", JSONArray().put("sample overlay")),
    )

    private fun hideEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.PIN_HIDE,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${PinSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", PinSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId),
    )
}
