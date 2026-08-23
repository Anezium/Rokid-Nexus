package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.InkSurfaceContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PhoneInkRouterTest {
    private val sink = RecordingPhoneHudRouteSink(
        capabilityBits = BusCapabilityBits.INK_SURFACE,
        linkUp = true,
    )
    private val surfaceRouter = PhoneSurfaceRouter(
        sink = sink,
        surfaceEpoch = ForegroundSurfaceEpoch(seedMs = 1_000L),
        onPluginSelfHid = {},
    )
    private val coordinator = PhoneInkSurfaceCoordinator()
    private val router = PhoneInkRouter(
        sink = sink,
        coordinator = coordinator,
        surfaceRouter = surfaceRouter,
    )

    @Test
    fun `invalid owner journals REJECTED INVALID_SURFACE_ID`() {
        router.handleLocal(
            BusEnvelope(
                path = BusPaths.INK_SHOW,
                payload = JSONObject()
                    .put("ownerPluginId", "hello")
                    .put("localSurfaceId", "main")
                    .put("surfaceId", "hello:other")
                    .put("page", PAGE),
            ),
            hudSender("hello"),
        )

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals("INVALID_SURFACE_ID", route.reason)
        assertEquals("INVALID_SURFACE_ID", sink.errors.single().second)
    }

    @Test
    fun `missing ink capability is rejected except on hide`() {
        sink.capabilityBits = 0
        router.handleLocal(inkEnvelope(BusPaths.INK_SHOW), hudSender("hello"))

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals("CAPABILITY_NOT_AVAILABLE", route.reason)
    }

    @Test
    fun `binary ink command is rejected as a wire-type problem`() {
        router.handleLocal(
            inkEnvelope(BusPaths.INK_SHOW).copy(binary = byteArrayOf(1)),
            hudSender("hello"),
        )

        val route = sink.localRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals("INK_WIRE_TYPE", route.reason)
    }

    @Test
    fun `show journals OK and publishes a remote surface show`() {
        val published = CountDownLatch(1)
        val publishingSink = RecordingPhoneHudRouteSink(
            capabilityBits = BusCapabilityBits.INK_SURFACE,
        )
        val publishingSurface = PhoneSurfaceRouter(
            sink = publishingSink,
            surfaceEpoch = ForegroundSurfaceEpoch(seedMs = 1_000L),
            onPluginSelfHid = {},
        )
        val latchCoordinator = PhoneInkSurfaceCoordinator(
            postResult = { action ->
                action()
                published.countDown()
            },
        )
        val latchRouter = PhoneInkRouter(
            sink = publishingSink,
            coordinator = latchCoordinator,
            surfaceRouter = publishingSurface,
        )
        try {
            latchRouter.handleLocal(inkEnvelope(BusPaths.INK_SHOW), hudSender("hello"))
            assertTrue(published.await(5, TimeUnit.SECONDS))
            val route = publishingSink.localRoutes.single()
            assertEquals(PluginBusJournal.Verdict.OK, route.verdict)
            assertEquals(BusPaths.SURFACE_SHOW, publishingSink.remote.single().path)
            assertTrue(publishingSink.remote.single().payload.has("seq"))
            assertTrue(publishingSink.remote.single().payload.getLong("epoch") > 0L)
        } finally {
            latchCoordinator.close()
        }
    }

    @Test
    fun `glasses event without a type is rejected`() {
        router.handleGlassesEvent(
            BusEnvelope(
                path = BusPaths.INK_EVENT,
                payload = JSONObject().put("surfaceId", "hello:main"),
            ),
        )

        val route = sink.remoteRoutes.single()
        assertEquals(PluginBusJournal.Verdict.REJECTED, route.verdict)
        assertEquals("INVALID_INK_EVENT", route.reason)
    }

    private fun inkEnvelope(path: String) = BusEnvelope(
        path = path,
        payload = JSONObject()
            .put("ownerPluginId", "hello")
            .put("localSurfaceId", "main")
            .put("surfaceId", "hello:main")
            .put("page", PAGE)
            .put("data", JSONObject().put("value", "one")),
    )

    companion object {
        private val PAGE = """
            <script type="application/json" def>{"data":{"value":"zero"}}</script>
            <page><view><text>{{ value }}</text></view></page>
            <style></style>
        """.trimIndent()
    }
}
