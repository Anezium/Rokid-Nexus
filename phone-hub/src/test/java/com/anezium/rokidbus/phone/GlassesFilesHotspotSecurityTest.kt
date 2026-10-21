package com.anezium.rokidbus.phone

import android.net.wifi.SoftApConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesFilesHotspotSecurityTest {
    @Test fun `mixed security uses the advertised PSK mode to avoid Rokid SAE stalls`() {
        assertEquals("wpa2", GlassesFilesHotspotHost.security(SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION))
        assertEquals("wpa2", GlassesFilesHotspotHost.security(SoftApConfiguration.SECURITY_TYPE_WPA2_PSK))
    }

    @Test fun `SAE only and open reservations preserve their security and unknown modes fail closed`() {
        assertEquals("wpa3", GlassesFilesHotspotHost.security(SoftApConfiguration.SECURITY_TYPE_WPA3_SAE))
        assertEquals("open", GlassesFilesHotspotHost.security(SoftApConfiguration.SECURITY_TYPE_OPEN))
        assertNull(GlassesFilesHotspotHost.security(-1))
    }
}
