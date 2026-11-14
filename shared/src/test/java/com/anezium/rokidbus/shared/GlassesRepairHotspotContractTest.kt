package com.anezium.rokidbus.shared

import com.anezium.rokidbus.shared.plugin.PathRules
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlassesRepairHotspotContractTest {
    @Test fun `only a bounded correlation id can own a reservation`() {
        assertTrue(GlassesRepairHotspotContract.validId("0123456789abcdef0123456789abcdef"))
        listOf("", "../repair", "a".repeat(33), "A".repeat(32), "a\n".repeat(16)).forEach {
            assertFalse(GlassesRepairHotspotContract.validId(it))
        }
    }

    @Test fun `recovery credentials must be protected and valid`() {
        val credentials = GlassesFilesHotspotCredentials("temporary-network", "ephemeral-password", "wpa2")
        val offer = GlassesRepairHotspotContract.offer(credentials)
        assertEquals(credentials, GlassesRepairHotspotContract.credentials(offer))
        assertNull(GlassesRepairHotspotContract.credentials(JSONObject(offer.toString()).put("ssid", "")))
        assertNull(GlassesRepairHotspotContract.credentials(JSONObject(offer.toString()).put("security", "open").put("passphrase", "")))
        assertNull(GlassesRepairHotspotContract.credentials(JSONObject(offer.toString()).put("passphrase", 12345678)))
        assertFalse(credentials.toString().contains(credentials.passphrase))
    }

    @Test fun `unknown versions and kinds cannot activate recovery`() {
        assertNull(GlassesRepairHotspotContract.kind(JSONObject().put("kind", "request")))
        assertNull(GlassesRepairHotspotContract.kind(GlassesRepairHotspotContract.message("request").put("version", 2)))
        assertNull(GlassesRepairHotspotContract.kind(GlassesRepairHotspotContract.message("pair")))
    }

    @Test fun `plugins cannot request the recovery route`() {
        assertTrue(PathRules.isReserved(GlassesRepairHotspotContract.PATH))
    }
}
