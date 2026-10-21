package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import java.net.Inet4Address

internal object GlassesFilesHotspotJoin {
    const val RETRY_MS = 5_000L

    fun shouldRetry(now: Long, deadline: Long, lastAttempt: Long?): Boolean =
        now < deadline && (lastAttempt == null || now - lastAttempt >= RETRY_MS)

    data class Lease(val address: Inet4Address, val gateway: Inet4Address?)

    fun isJoined(
        targetConfirmed: Boolean,
        previousAddress: Inet4Address?,
        current: Lease?,
        clientNetwork: GlassesFilesClientNetwork?,
        retainedLease: Lease? = null,
    ): Boolean {
        if (!targetConfirmed || current == null || !usable(current.address) || !usable(current.gateway) ||
            current.address == current.gateway || clientNetwork?.contains(current.address.hostAddress.orEmpty()) == true
        ) return false
        // Radio-on can auto-join a saved network. A new lease alone cannot identify the hotspot;
        // require the bridge's exact target confirmation before the sealed lane handshake.
        return current.address != previousAddress || current == retainedLease
    }

    private fun usable(address: Inet4Address?): Boolean = address != null &&
        !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isMulticastAddress
}
