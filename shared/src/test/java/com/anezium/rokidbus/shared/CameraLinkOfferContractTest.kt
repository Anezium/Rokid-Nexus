package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLinkOfferContractTest {
    @Test
    fun `reverse offer encode decode round trip preserves phone AP role`() {
        val offer = CameraLinkEndpointOffer(
            sessionId = "camera-session",
            ssid = "AndroidShare_1234",
            passphrase = "abcdefgh1234",
            port = 38_401,
            token = "0123456789abcdef",
            mode = CameraLinkMode.LOHS_REVERSE,
            security = CameraLinkSecurity.WPA3_SAE,
        )

        val encoded = CameraLinkOfferContract.encode(offer)

        assertEquals("lohs_reverse", encoded.getString("mode"))
        assertEquals("wpa3_sae", encoded.getString("security"))
        assertFalse(encoded.has("goIp"))
        assertEquals(offer, CameraLinkOfferContract.decode(encoded))
    }

    @Test
    fun `offer without mode remains the legacy glasses GO role`() {
        val legacy = JSONObject()
            .put("sessionId", "camera-session")
            .put("ssid", "DIRECT-NEXUS")
            .put("passphrase", "abcdefgh")
            .put("port", 38_401)
            .put("token", "0123456789abcdef")
            .put("goIp", "192.168.49.1")

        assertEquals(CameraLinkMode.P2P, CameraLinkOfferContract.decode(legacy)?.mode)
        assertEquals(CameraLinkSecurity.WPA2_PSK, CameraLinkOfferContract.decode(legacy)?.security)
        assertEquals(
            CameraLinkSecurity.WPA2_PSK,
            CameraLinkOfferContract.decode(
                JSONObject(legacy.toString()).put("security", "future_security"),
            )?.security,
        )
        assertNull(
            CameraLinkOfferContract.decode(
                JSONObject(legacy.toString()).put("mode", "future_transport"),
            ),
        )
    }

    @Test
    fun `reverse offer without security defaults to WPA2`() {
        val legacyReverse = JSONObject()
            .put("sessionId", "camera-session")
            .put("ssid", "AndroidShare_1234")
            .put("passphrase", "abcdefgh1234")
            .put("port", 38_401)
            .put("token", "0123456789abcdef")
            .put("mode", "lohs_reverse")

        assertEquals(
            CameraLinkSecurity.WPA2_PSK,
            CameraLinkOfferContract.decode(legacyReverse)?.security,
        )
        assertNull(
            CameraLinkOfferContract.decode(
                JSONObject(legacyReverse.toString()).put("security", "future_security"),
            ),
        )
    }

    @Test
    fun `open reverse offer permits an empty passphrase`() {
        val offer = CameraLinkEndpointOffer(
            sessionId = "camera-session",
            ssid = "AndroidShare_Open",
            passphrase = "",
            port = 38_401,
            token = "0123456789abcdef",
            mode = CameraLinkMode.LOHS_REVERSE,
            security = CameraLinkSecurity.OPEN,
        )

        assertEquals(offer, CameraLinkOfferContract.decode(CameraLinkOfferContract.encode(offer)))
    }
}
