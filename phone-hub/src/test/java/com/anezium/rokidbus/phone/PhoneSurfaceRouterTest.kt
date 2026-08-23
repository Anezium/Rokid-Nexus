package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.SurfaceEpochContract
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSurfaceRouterTest {
    private val sink = RecordingPhoneHudRouteSink()
    private val epoch = ForegroundSurfaceEpoch(seedMs = 1_000L)
    private val hid = mutableListOf<String>()
    private val router = PhoneSurfaceRouter(
        sink = sink,
        surfaceEpoch = epoch,
        onPluginSelfHid = { hid += it },
    )

    @Test
    fun `show stamps seq and a hub-owned epoch`() {
        val stamped = router.withMetadata(showEnvelope("assistant", "assistant:main"), "assistant", closeOnHide = true)

        assertTrue(stamped.payload.getLong("seq") > 0L)
        assertEquals(1_001L, stamped.payload.getLong(SurfaceEpochContract.FIELD))
        val again = router.withMetadata(
            showEnvelope("assistant", "assistant:main").copy(path = BusPaths.SURFACE_UPDATE),
            "assistant",
            closeOnHide = true,
        )
        assertEquals(1_001L, again.payload.getLong(SurfaceEpochContract.FIELD))
        assertTrue(again.payload.getLong("seq") > stamped.payload.getLong("seq"))
    }

    @Test
    fun `hide of the last surface releases the epoch and reports self-hid`() {
        router.withMetadata(showEnvelope("assistant", "assistant:main"), "assistant", closeOnHide = true)
        router.withMetadata(
            BusEnvelope(
                path = BusPaths.SURFACE_HIDE,
                payload = JSONObject()
                    .put("surfaceId", "assistant:main")
                    .put("ownerPluginId", "assistant"),
            ),
            "assistant",
            closeOnHide = true,
        )

        assertEquals(listOf("assistant"), hid)
        assertEquals(1_002L, epoch.assign("assistant"))
    }

    @Test
    fun `hidePlugin sends a hide for each occupied surface`() {
        router.withMetadata(showEnvelope("lyrics", "lyrics:now"), "lyrics", closeOnHide = true)
        val hidden = router.hidePlugin("lyrics")

        assertEquals(listOf("lyrics:now"), hidden)
        assertEquals(BusPaths.SURFACE_HIDE, sink.remote.single().path)
        assertEquals("lyrics:now", sink.remote.single().payload.getString("surfaceId"))
    }

    private fun showEnvelope(pluginId: String, surfaceId: String) = BusEnvelope(
        path = BusPaths.SURFACE_SHOW,
        payload = JSONObject()
            .put("surfaceId", surfaceId)
            .put("ownerPluginId", pluginId),
    )
}
