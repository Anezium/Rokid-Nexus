package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneHubCapabilitiesContractTest {
    @Test
    fun `approved camera consumer name survives round trip`() {
        val capabilities = PhoneHubCapabilitiesContract.create(
            BusCapabilityBits.CAMERA_CONSUMER_READY,
            " Lens ",
        )
        val payload = PhoneHubCapabilitiesContract.toJson(capabilities)
        val parsed = PhoneHubCapabilitiesContract.parse(payload)

        assertEquals("Lens", parsed.cameraConsumerName)
        assertEquals("Lens", payload.getString("cameraConsumerName"))
    }

    @Test
    fun `legacy and unavailable consumers omit camera consumer name`() {
        val legacy = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))
        val unready = PhoneHubCapabilitiesContract.create(0, "Lens")

        assertNull(legacy.cameraConsumerName)
        assertNull(unready.cameraConsumerName)
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

    @Test
    fun `activity form-factor preference is additive and defaults collapsed`() {
        val legacy = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))
        assertFalse(legacy.activityAlwaysExpanded)

        val payload = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = 0,
                cameraConsumerName = null,
                activityAlwaysExpanded = true,
            ),
        )
        assertEquals(true, payload.getBoolean("activityAlwaysExpanded"))
        assertEquals(
            true,
            PhoneHubCapabilitiesContract.parse(payload).activityAlwaysExpanded,
        )
    }

    @Test
    fun `hud top inset survives round trip`() {
        val payload = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = 0,
                cameraConsumerName = null,
                hudTopInsetDp = 100,
            ),
        )

        assertEquals(100, payload.getInt("hudTopInsetDp"))
        assertEquals(100, PhoneHubCapabilitiesContract.parse(payload).hudTopInsetDp)
    }

    @Test
    fun `hud top inset defaults to zero`() {
        val parsed = PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0))

        assertEquals(0, parsed.hudTopInsetDp)
    }

    @Test
    fun `hud top inset clamps into the supported range`() {
        assertEquals(
            0,
            PhoneHubCapabilitiesContract.parse(
                JSONObject().put("hudTopInsetDp", -1),
            ).hudTopInsetDp,
        )
        assertEquals(
            120,
            PhoneHubCapabilitiesContract.parse(
                JSONObject().put("hudTopInsetDp", 121),
            ).hudTopInsetDp,
        )
    }

    @Test
    fun `hud top inset rejects garbage wire values`() {
        listOf("100", 100.5, true, JSONObject.NULL).forEach { garbage ->
            assertEquals(
                0,
                PhoneHubCapabilitiesContract.parse(
                    JSONObject().put("hudTopInsetDp", garbage),
                ).hudTopInsetDp,
            )
        }
    }

    @Test
    fun `hud position auto survives round trip`() {
        val payload = PhoneHubCapabilitiesContract.toJson(
            PhoneHubCapabilitiesContract.create(
                features = 0,
                cameraConsumerName = null,
                hudPositionAuto = false,
            ),
        )

        assertFalse(payload.getBoolean("hudPositionAuto"))
        assertFalse(PhoneHubCapabilitiesContract.parse(payload).hudPositionAuto)
    }

    @Test
    fun `hud position auto defaults on for legacy and garbage payloads`() {
        assertTrue(
            PhoneHubCapabilitiesContract.parse(
                JSONObject().put("features", 0),
            ).hudPositionAuto,
        )
        listOf("garbage", 0, 1.5, JSONObject.NULL).forEach { garbage ->
            assertTrue(
                PhoneHubCapabilitiesContract.parse(
                    JSONObject().put("hudPositionAuto", garbage),
                ).hudPositionAuto,
            )
        }
    }

    @Test
    fun `page session version defaults to zero and round trips when announced`() {
        assertEquals(0, PhoneHubCapabilitiesContract.parse(JSONObject().put("features", 0)).pageSessionVersion)
        val announced = PhoneHubCapabilitiesContract.create(0, null, pageSessionVersion = 1)
        val payload = PhoneHubCapabilitiesContract.toJson(announced)
        assertEquals(1, payload.getInt("pageSessionVersion"))
        assertEquals(1, PhoneHubCapabilitiesContract.parse(payload).pageSessionVersion)
        listOf("1", -2, 1.5, JSONObject.NULL).forEach {
            assertEquals(0, PhoneHubCapabilitiesContract.parse(JSONObject(payload.toString()).put("pageSessionVersion", it)).pageSessionVersion)
        }
        // A future version is carried as announced and simply never negotiates.
        assertEquals(2, PhoneHubCapabilitiesContract.parse(JSONObject().put("pageSessionVersion", 2)).pageSessionVersion)
    }
}
