package com.anezium.rokidbus.phone

import android.net.wifi.SoftApConfiguration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesFilesHotspotCompatibilityTest {
    @Test fun `only public configurable LOHS APIs select a compatibility band`() {
        assertNull(GlassesFilesHotspotCompatibility.configuredBand(35, true))
        assertNull(GlassesFilesHotspotCompatibility.configuredBand(30, false))
        assertEquals(SoftApConfiguration.BAND_5GHZ, GlassesFilesHotspotCompatibility.configuredBand(36, true))
        assertEquals(SoftApConfiguration.BAND_2GHZ, GlassesFilesHotspotCompatibility.configuredBand(36, false))
        assertEquals(SoftApConfiguration.BAND_5GHZ, GlassesFilesHotspotCompatibility.configuredBand(37, true))
    }
}
