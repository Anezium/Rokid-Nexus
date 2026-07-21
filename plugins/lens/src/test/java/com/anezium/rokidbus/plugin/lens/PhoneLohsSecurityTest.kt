package com.anezium.rokidbus.plugin.lens

import android.net.wifi.SoftApConfiguration
import com.anezium.rokidbus.shared.CameraLinkSecurity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneLohsSecurityTest {
    @Test
    fun `maps supported Soft AP security types to offer tokens`() {
        assertEquals(
            CameraLinkSecurity.OPEN,
            cameraLinkSecurityForSoftAp(SoftApConfiguration.SECURITY_TYPE_OPEN),
        )
        assertEquals(
            CameraLinkSecurity.WPA2_PSK,
            cameraLinkSecurityForSoftAp(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK),
        )
        // Transition mode joins as SAE: measured hardware rejects the WPA2 association.
        assertEquals(
            CameraLinkSecurity.WPA3_SAE,
            cameraLinkSecurityForSoftAp(SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION),
        )
        assertEquals(
            CameraLinkSecurity.WPA3_SAE,
            cameraLinkSecurityForSoftAp(SoftApConfiguration.SECURITY_TYPE_WPA3_SAE),
        )
        assertNull(cameraLinkSecurityForSoftAp(Int.MAX_VALUE))
    }
}
