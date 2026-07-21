package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LohsGatewayResolverTest {
    @Test
    fun `parses a DHCP gateway ending in dot twenty one without assuming dot one`() {
        assertEquals("192.168.43.21", LohsGatewayResolver.fromDhcpGateway(0x152BA8C0))
    }

    @Test
    fun `rejects missing and non-routable DHCP gateway values`() {
        assertNull(LohsGatewayResolver.fromDhcpGateway(0))
        assertNull(LohsGatewayResolver.fromDhcpGateway(0x0100007F))
    }

    @Test
    fun `normalizes quoted framework SSIDs`() {
        assertEquals("AndroidShare_1234", LohsGatewayResolver.normalizeSsid("\"AndroidShare_1234\""))
    }
}
