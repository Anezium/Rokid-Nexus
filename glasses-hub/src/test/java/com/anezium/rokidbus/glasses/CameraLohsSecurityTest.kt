package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.CameraLinkSecurity
import org.junit.Assert.assertEquals
import org.junit.Test

class CameraLohsSecurityTest {
    @Test
    fun `reverse join timeout covers asynchronous OS network handoff`() {
        assertEquals(15_000L, GlassesHub.LOHS_REVERSE_JOIN_TIMEOUT_MS)
    }

    @Test
    fun `maps offer security to wifi connect keyword`() {
        assertEquals(WifiConnectSecurity.OPEN, CameraLinkSecurity.OPEN.toWifiConnectSecurity())
        assertEquals(WifiConnectSecurity.WPA2, CameraLinkSecurity.WPA2_PSK.toWifiConnectSecurity())
        assertEquals(WifiConnectSecurity.WPA3, CameraLinkSecurity.WPA3_SAE.toWifiConnectSecurity())
    }
}
