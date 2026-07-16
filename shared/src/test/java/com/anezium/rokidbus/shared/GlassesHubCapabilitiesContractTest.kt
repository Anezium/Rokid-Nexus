package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesHubCapabilitiesContractTest {
    @Test
    fun `capabilities carry the optional glasses version name`() {
        val capabilities = GlassesHubCapabilitiesContract.create(
            features = BusCapabilityBits.IMAGE_SURFACE,
            imageSurfaceVersion = ImageSurfaceContract.VERSION,
            maxImageBytes = ImageSurfaceContract.MAX_IMAGE_BYTES,
            versionName = " 1.0.1 ",
        )
        val payload = GlassesHubCapabilitiesContract.toJson(capabilities)
            .put("futureField", true)
        val parsed = GlassesHubCapabilitiesContract.parse(payload)

        assertEquals("1.0.1", payload.getString("versionName"))
        assertEquals("1.0.1", parsed.versionName)
        assertEquals(BusCapabilityBits.IMAGE_SURFACE, parsed.features)
    }

    @Test
    fun `legacy capabilities without a glasses version remain valid`() {
        val legacyPayload = JSONObject()
            .put("version", GlassesHubCapabilitiesContract.VERSION)
            .put("features", BusCapabilityBits.IMAGE_SURFACE)
            .put("imageSurfaceVersion", ImageSurfaceContract.VERSION)
            .put("maxImageBytes", ImageSurfaceContract.MAX_IMAGE_BYTES)
        val parsed = GlassesHubCapabilitiesContract.parse(legacyPayload)
        val versionlessPayload = GlassesHubCapabilitiesContract.toJson(
            GlassesHubCapabilitiesContract.create(
                features = parsed.features,
                imageSurfaceVersion = parsed.imageSurfaceVersion,
                maxImageBytes = parsed.maxImageBytes,
                versionName = null,
            ),
        )

        assertNull(parsed.versionName)
        assertFalse(versionlessPayload.has("versionName"))
    }
}
