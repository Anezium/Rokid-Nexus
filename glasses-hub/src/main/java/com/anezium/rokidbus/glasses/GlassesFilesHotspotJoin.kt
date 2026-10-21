package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import java.net.Inet4Address

internal object GlassesFilesHotspotJoin {
    data class Lease(val address: Inet4Address, val gateway: Inet4Address?)

    fun isJoined(
        previousAddress: Inet4Address?,
        current: Lease?,
        clientNetwork: GlassesFilesClientNetwork?,
        retainedLease: Lease? = null,
    ): Boolean {
        if (current == null || !usable(current.address) || !usable(current.gateway) ||
            current.address == current.gateway || clientNetwork?.contains(current.address.hostAddress.orEmpty()) == true
        ) return false
        // SSID is redacted in the background. Only a changed lease or this batch's retained lease
        // can justify offering a lane; the phone still has to prove it with the sealed handshake.
        return current.address != previousAddress || current == retainedLease
    }

    private fun usable(address: Inet4Address?): Boolean = address != null &&
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress
}
