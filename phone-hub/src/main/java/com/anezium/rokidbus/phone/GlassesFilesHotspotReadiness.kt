package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import java.net.Inet4Address
import java.net.NetworkInterface

internal object GlassesFilesHotspotReadiness {
    const val TIMEOUT_MS = 5_000L

    data class Interface(val name: String, val address: Inet4Address)

    fun snapshot(): List<Interface> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback && !it.isPointToPoint }
            .flatMap { network -> network.inetAddresses.toList().filterIsInstance<Inet4Address>()
                .map { Interface(network.name, it) } }
    }.getOrDefault(emptyList())

    fun isReady(before: List<Interface>, current: List<Interface>, client: GlassesFilesClientNetwork?): Boolean =
        current.any { candidate ->
            listOf("wlan", "swlan", "ap", "wifi").any { candidate.name.startsWith(it) } &&
                candidate.address.isSiteLocalAddress &&
                before.none { it.address == candidate.address } &&
                client?.contains(candidate.address.hostAddress.orEmpty()) != true
        }
}
