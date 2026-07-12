package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.BusCapabilityBits
import com.anezium.rokidbus.shared.BusEnvelope
import com.anezium.rokidbus.shared.BusPaths
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class PhonePluginRegistryTest {
    @Test
    fun sendBinary_routesBinaryEnvelopeWithSurfaceMetadata() {
        var routed: BusEnvelope? = null
        val registry = registry(sendEnvelope = { envelope ->
            routed = envelope
            null
        })
        val bytes = byteArrayOf(1, 2, 3)

        registry.sendBinary(
            BusPaths.SURFACE_UPDATE,
            "image-request",
            JSONObject().put("surfaceId", "feeds").put("kind", "image"),
            bytes,
        )

        val envelope = assertNotNull(routed).let { routed!! }
        assertEquals(BusPaths.SURFACE_UPDATE, envelope.path)
        assertEquals("image-request", envelope.id)
        assertEquals("feeds", envelope.payload.getString("surfaceId"))
        assertTrue(envelope.payload.getLong("seq") > 0L)
        assertArrayEquals(bytes, envelope.binary)
        registry.close()
    }

    @Test
    fun supportsImageSurface_readsCurrentCapabilityValue() {
        var capabilities = 0
        val registry = registry(capabilitiesProvider = { capabilities })

        assertFalse(registry.supportsImageSurface())
        capabilities = BusCapabilityBits.IMAGE_SURFACE
        assertTrue(registry.supportsImageSurface())
        capabilities = 0
        assertFalse(registry.supportsImageSurface())
        registry.close()
    }

    private fun registry(
        sendEnvelope: (BusEnvelope) -> String? = { null },
        capabilitiesProvider: () -> Int = { 0 },
    ) = PhonePluginRegistry(
        context = RuntimeEnvironment.getApplication(),
        plugins = emptyList(),
        sendEnvelope = sendEnvelope,
        capabilitiesProvider = capabilitiesProvider,
        logger = {},
    )
}
