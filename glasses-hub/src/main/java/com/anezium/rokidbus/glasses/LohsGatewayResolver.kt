package com.anezium.rokidbus.glasses

internal object LohsGatewayResolver {
    /** Android's legacy DHCP integer stores IPv4 octets in little-endian order. */
    fun fromDhcpGateway(value: Int): String? {
        if (value == 0) return null
        val octets = IntArray(4) { index -> value ushr (index * 8) and 0xff }
        val first = octets[0]
        if (first == 0 || first == 127 || first in 224..255 || octets.all { it == 255 }) return null
        return octets.joinToString(".")
    }

    fun normalizeSsid(value: String?): String = value.orEmpty()
        .removePrefix("\"")
        .removeSuffix("\"")
}
