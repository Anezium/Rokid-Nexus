package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import com.anezium.rokidbus.shared.plugin.PluginCapability
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class HeadTiltContractTest {
    @Test fun `default is off and configuration round trips`() {
        assertFalse(HeadTiltSettings().enabled)
        val settings = HeadTiltSettings(true, 25, 800, "relay")
        assertEquals(settings, HeadTiltContract.parse(HeadTiltContract.toJson(settings)))
    }

    @Test fun `invalid or incompatible settings fail closed`() {
        assertNull(HeadTiltContract.parse(JSONObject()))
        for ((field, value) in listOf("v" to 2, "enabled" to "true", "angleDegrees" to 9,
            "angleDegrees" to 40.5, "holdMillis" to 0, "pluginId" to "bad\nentry")) {
            assertNull(field, HeadTiltContract.parse(HeadTiltContract.toJson(HeadTiltSettings()).put(field, value)))
        }
    }

    @Test fun `plugins cannot send or subscribe to head tilt controls`() {
        for (path in listOf(HeadTiltContract.CONFIG_PATH, HeadTiltContract.REQUEST_PATH, HeadTiltContract.STATUS_PATH)) {
            assertTrue(PathRules.isReserved(path))
            assertNull(PathRules.requiredCapability(path))
            assertFalse(PathRules.isAllowedReceivePrefix(path, "sample", PluginCapability.entries.toSet()))
        }
    }
}
