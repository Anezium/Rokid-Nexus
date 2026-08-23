package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.NoticeSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNoticeRouterTest {
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.NOTICE_SURFACE,
        linkUp = true,
    )
    private val router = PhoneNoticeRouter(
        state = PhoneNoticeState(nowMs = { 0L }, initialSequence = 0L),
        sink = sink,
    )

    @Test
    fun `show journals OK and sends remote when the link is up`() {
        router.handleLocal(showEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(BusPaths.NOTICE_SHOW, route.path)
        assertEquals("relay", route.pluginId)
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals(null, route.reason)
        assertEquals(BusPaths.NOTICE_SHOW, sink.remote.single().path)
    }

    @Test
    fun `show is rejected when the glasses link is down`() {
        sink.linkUp = false
        router.handleLocal(showEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals(NoticeSurfaceContract.ERROR_CAPABILITY_NOT_AVAILABLE, route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `update with no session journals OK ignored`() {
        router.handleLocal(updateEnvelope("relay"), hudSender("relay"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
        assertEquals("NOTICE_UPDATE_IGNORED", route.reason)
        assertTrue(sink.remote.isEmpty())
    }

    @Test
    fun `glasses input without a current notice is dropped locally`() {
        router.handleGlassesInput(
            BusEnvelope(
                path = BusPaths.NOTICE_INPUT,
                payload = JSONObject().put("noticeId", "relay:notice"),
            ),
        )

        assertTrue(sink.remoteRoutes.isEmpty())
        assertTrue(sink.local.isEmpty())
        assertTrue(sink.logs.any { it.contains("not_current") })
    }

    private fun showEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_SHOW,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("kind", NoticeSurfaceContract.KIND)
            .put("title", "Marie")
            .put("body", "On my way"),
    )

    private fun updateEnvelope(pluginId: String) = BusEnvelope(
        path = BusPaths.NOTICE_UPDATE,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:${NoticeSurfaceContract.LOCAL_SURFACE_ID}")
            .put("localSurfaceId", NoticeSurfaceContract.LOCAL_SURFACE_ID)
            .put("ownerPluginId", pluginId)
            .put("body", "Updated"),
    )
}
