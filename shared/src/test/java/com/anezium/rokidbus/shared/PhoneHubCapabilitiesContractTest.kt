package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneHubCapabilitiesContractTest {
    @Test
    fun `approved camera consumer name labels the generic launcher`() {
        val capabilities = PhoneHubCapabilitiesContract.create(
            BusCapabilityBits.CAMERA_CONSUMER_READY,
            " Lens ",
        )
        val payload = PhoneHubCapabilitiesContract.toJson(capabilities)
        val parsed = PhoneHubCapabilitiesContract.parse(payload)

        assertEquals("Lens", parsed.cameraConsumerName)
        assertEquals("Lens", PhoneHubCapabilitiesContract.cameraLauncherLabel(parsed))
        assertEquals("Lens", payload.getString("cameraConsumerName"))
    }

    @Test
    fun `legacy and unavailable consumers fall back to Camera`() {
        val legacy = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))
        val unready = PhoneHubCapabilitiesContract.create(0, "Lens")

        assertNull(legacy.cameraConsumerName)
        assertNull(unready.cameraConsumerName)
        assertEquals("Camera", PhoneHubCapabilitiesContract.cameraLauncherLabel(legacy))
        assertFalse(PhoneHubCapabilitiesContract.toJson(unready).has("cameraConsumerName"))
    }

    @Test
    fun `legacy capabilities key remains accepted`() {
        val parsed = PhoneHubCapabilitiesContract.parse(
            JSONObject()
                .put("capabilities", BusCapabilityBits.CAMERA_CONSUMER_READY)
                .put("cameraConsumerName", "Lens"),
        )

        assertEquals(BusCapabilityBits.CAMERA_CONSUMER_READY, parsed.features)
        assertEquals("Lens", parsed.cameraConsumerName)
    }
}
