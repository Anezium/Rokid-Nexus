package com.anezium.rokidbus.phone

import android.net.wifi.SoftApConfiguration

internal object GlassesFilesHotspotCompatibility {
    fun configuredBand(apiLevel: Int, supports5Ghz: Boolean): Int? = when {
        apiLevel < 36 -> null
        supports5Ghz -> SoftApConfiguration.BAND_5GHZ
        else -> SoftApConfiguration.BAND_2GHZ
    }
}
