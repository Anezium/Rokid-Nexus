package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ActivitySurfaceContract
import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneActivityRouterTest {
    private var now = 10_000L
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.ACTIVITY_SURFACE,
        linkUp = true,
    )
    private val router = PhoneActivityRouter(
        state = PhoneActivityState(nowMs = { now }, initialSequence = 40L),
        sink = sink,
        nowMs = { now },
        postExpiry = {},
        cancelExpiry = {},
    )

    @Test
    fun `start journals OK and sends remote when the link is up`() {
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))

        val route = sink.localRoutes.single()
        assertEquals(BusPaths.ACTIVITY_START, route.path)
        assertEquals("maps", route.pluginId)
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals(null, route.reason)
        assertEquals(BusPaths.ACTIVITY_START, sink.remote.single().path)
    }

    @Test
    fun `start journals OK and holds when the link is down`() {
        sink.linkUp = false
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertTrue(sink.remote.isEmpty())
        assertTrue(sink.logs.any { it.contains("link_down") })
    }

    @Test
    fun `end with no session journals OK ignored`() {
        router.handleLocal(endEnvelope("maps"), hudSender("maps"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals("ACTIVITY_END_IGNORED_NO_SESSION", route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `missing activity capability is rejected`() {
        sink.capabilityBits = 0
        router.handleLocal(startEnvelope("maps"), hudSender("maps"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals(ActivitySurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, route.reason)
    }

    private fun startEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.ACTIVITY_START,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("kind", ActivitySurfaceContract.KIND)
            .put("glyph", "straight")
            .put("primary", "300 m"),
    )

    private fun endEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.ACTIVITY_END,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${ActivitySurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", ActivitySurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId),
    )
}
