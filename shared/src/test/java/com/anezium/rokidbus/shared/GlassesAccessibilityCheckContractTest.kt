package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesAccessibilityCheckContractTest {
    @Test
    fun `round trip keeps the foreign list and nexusEnabled apart`() {
        val payload = GlassesAccessibilityCheckContract.replyToJson(
            foreignServices = listOf("com.example/.LegacyService"),
            nexusEnabled = true,
        )

        val reply = GlassesAccessibilityCheckContract.fromReply(payload)

        assertEquals(listOf("com.example/.LegacyService"), reply?.foreignServices)
        assertTrue(reply?.nexusEnabled == true)
    }

    @Test
    fun `an empty foreign list with Nexus disabled is not the same as only Nexus running`() {
        val payload = GlassesAccessibilityCheckContract.replyToJson(
            foreignServices = emptyList(),
            nexusEnabled = false,
        )

        val reply = GlassesAccessibilityCheckContract.fromReply(payload)

        assertEquals(emptyList<String>(), reply?.foreignServices)
        assertEquals(false, reply?.nexusEnabled)
    }

    @Test
    fun `a reply from before nexusEnabled existed is read as Nexus running`() {
        val payload = JSONObject()
            .put("version", GlassesAccessibilityCheckContract.VERSION)
            .put("foreignServices", org.json.JSONArray())

        val reply = GlassesAccessibilityCheckContract.fromReply(payload)

        assertEquals(true, reply?.nexusEnabled)
    }

    @Test
    fun `a reply this build cannot parse is null, not a false positive`() {
        assertNull(GlassesAccessibilityCheckContract.fromReply(null))
        assertNull(GlassesAccessibilityCheckContract.fromReply(JSONObject().put("version", 0)))
    }
}
