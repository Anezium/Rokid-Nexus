package com.anezium.rokidbus.plugin.lens

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLensLinkOfferTest {
    @Test
    fun `parses shipped camera offer including session id`() {
        val offer = PhoneLensLinkOffer.parse(
            JSONObject()
                .put("sessionId", "camera-session")
                .put("ssid", "DIRECT-NEXUS")
                .put("passphrase", "abcdefgh")
                .put("port", 24861)
                .put("token", "0123456789abcdef")
                .put("goIp", "192.168.49.1"),
        )
        assertEquals("camera-session", offer?.sessionId)
        assertEquals(24861, offer?.port)
    }

    @Test
    fun `rejects legacy offer without session id and malformed credentials`() {
        val legacy = JSONObject()
            .put("ssid", "DIRECT-NEXUS")
            .put("passphrase", "abcdefgh")
            .put("port", 24861)
            .put("token", "0123456789abcdef")
            .put("goIp", "192.168.49.1")
        assertNull(PhoneLensLinkOffer.parse(legacy))
        assertNull(PhoneLensLinkOffer.parse(JSONObject(legacy.toString()).put("sessionId", "s").put("port", 0)))
    }
}
