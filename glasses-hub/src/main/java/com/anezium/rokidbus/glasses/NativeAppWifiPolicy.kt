package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.YoutubeSetupContract

/**
 * Native apps that are useless without the network. The glasses ROM boots with Wi-Fi off, so
 * opening one of these from Nexus brings the radio up first.
 */
internal object NativeAppWifiPolicy {
    fun needsWifi(packageName: String): Boolean = packageName in YoutubeSetupContract.PACKAGES
}
