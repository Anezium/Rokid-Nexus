package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSurfaceRouterTest {
    private val sink = RecordingPhoneHudRouteSink()
    private val released = mutableListOf<Pair<String, Boolean>>()
    private val router = PhoneSurfaceRouter(
        sink,
        onPluginSelfHid = { pluginId, detach -> released += pluginId to detach },
        nowMs = { 1_000L },
    )

    @Test
    fun `sequence survives a hide and reopening without changing the caller envelope`() {
        val original = envelope(BusPaths.SURFACE_SHOW, "media", "main")
        val shown = router.withMetadata(original, "media", closeOnHide = true)
        router.hidePlugin("media")
        val reopened = router.withMetadata(original, "media", closeOnHide = true)

        assertEquals(1_001L, shown.payload.getLong("seq"))
        assertEquals(1_002L, sink.remote.single().payload.getLong("seq"))
        assertEquals(1_003L, reopened.payload.getLong("seq"))
        assertFalse(original.payload.has("seq"))
        assertFalse(reopened.payload.has("epoch"))
        assertEquals(original.id, reopened.id)
    }

    @Test
    fun `detaching one of two surfaces does not close the plugin`() {
        occupy("sample", "first")
        occupy("sample", "last")

        router.withMetadata(
            envelope(BusPaths.SURFACE_HIDE, "sample", "first").also { it.payload.put("detach", true) },
            "sample",
            closeOnHide = true,
        )
        assertTrue(released.isEmpty())

        router.withMetadata(envelope(BusPaths.SURFACE_HIDE, "sample", "last"), "sample", closeOnHide = true)
        assertEquals(listOf("sample" to false), released)
    }

    @Test
    fun `last surface forwards its detach request to the audio lifecycle owner`() {
        occupy("sample", "main")
        val hidden = router.withMetadata(
            envelope(BusPaths.SURFACE_HIDE, "sample", "main").also { it.payload.put("detach", true) },
            "sample",
            closeOnHide = true,
        )

        assertTrue(hidden.payload.getBoolean("detach"))
        assertEquals(listOf("sample" to true), released)
        router.release("sample", "sample:main", detach = true)
        assertEquals(1, released.size)
    }

    @Test
    fun `Ink hide waits for glasses closed before releasing the plugin`() {
        occupy("assistant", "ink")
        router.withMetadata(
            envelope(BusPaths.SURFACE_HIDE, "assistant", "ink"),
            "assistant",
            closeOnHide = false,
        )
        assertTrue(released.isEmpty())

        router.release("assistant", "assistant:ink")
        assertEquals(listOf("assistant" to false), released)
        assertTrue(router.hidePlugin("assistant").isEmpty())
    }

    @Test
    fun `closing one plugin hides all its surfaces and leaves another plugin alone`() {
        occupy("sample", "first")
        occupy("sample", "second")
        occupy("media", "main")

        assertEquals(setOf("sample:first", "sample:second"), router.hidePlugin("sample").toSet())
        assertEquals(2, sink.remote.size)
        assertTrue(sink.remote.all {
            it.path == BusPaths.SURFACE_HIDE && it.payload.getString("ownerPluginId") == "sample"
        })
        assertTrue(released.isEmpty())
        assertEquals(listOf("media:main"), router.hidePlugin("media"))
        assertTrue(router.hidePlugin("sample").isEmpty())
    }

    @Test
    fun `forgetting a compiled surface does not report a user close`() {
        occupy("assistant", "old")
        occupy("assistant", "current")

        router.forget("assistant", "assistant:old")

        assertTrue(released.isEmpty())
        assertEquals(listOf("assistant:current"), router.hidePlugin("assistant"))
    }

    @Test
    fun `a hide for a just compiled surface is sequenced even before it was tracked`() {
        router.sendHide("assistant", "assistant:ink")
        router.sendHide("assistant", "assistant:ink")

        assertEquals(listOf(1_001L, 1_002L), sink.remote.map { it.payload.getLong("seq") })
        assertTrue(router.hidePlugin("assistant").isEmpty())
        assertTrue(released.isEmpty())
    }

    private fun occupy(pluginId: String, localId: String) {
        router.withMetadata(envelope(BusPaths.SURFACE_SHOW, pluginId, localId), pluginId, closeOnHide = true)
    }

    private fun envelope(path: String, pluginId: String, localId: String) = BusEnvelope(
        path = path,
        payload = JSONObject()
            .put("surfaceId", "$pluginId:$localId")
            .put("ownerPluginId", pluginId),
    )
}
