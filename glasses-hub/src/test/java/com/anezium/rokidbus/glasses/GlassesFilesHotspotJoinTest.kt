package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class GlassesFilesHotspotJoinTest {
    private val home = ipv4("192.168.1.25")
    private val hotspot = lease("10.218.114.98", "10.218.114.54")

    @Test fun `redacted SSID does not prevent a new IPv4 lease from joining`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(home, hotspot, null))
    }

    @Test fun `redacted SSID cannot accept the unchanged pre-join address`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, lease("192.168.1.25", "192.168.1.1"), null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, lease("192.168.1.25", "192.168.1.2"), null))
    }

    @Test fun `a lease inside the phone client subnet never joins`() {
        val client = GlassesFilesClientNetwork("10.218.114.2", 24)
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, hotspot, client))
        assertFalse(GlassesFilesHotspotJoin.isJoined(hotspot.address, hotspot, client, hotspot))
        assertTrue(GlassesFilesHotspotJoin.isJoined(home, hotspot, GlassesFilesClientNetwork("192.168.1.2", 24)))
    }

    @Test fun `a radio without a previous address can acquire a hotspot lease`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(null, hotspot, null))
    }

    @Test fun `association without an IPv4 address or gateway is not ready`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, null, null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, hotspot.copy(gateway = null), null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(home, hotspot.copy(gateway = hotspot.address), null))
    }

    @Test fun `unusable interface addresses and gateways are rejected`() {
        listOf("0.0.0.0", "127.0.0.1", "169.254.1.2", "224.0.0.1").forEach { invalid ->
            assertFalse(GlassesFilesHotspotJoin.isJoined(home, hotspot.copy(address = ipv4(invalid)), null))
            assertFalse(GlassesFilesHotspotJoin.isJoined(home, hotspot.copy(gateway = ipv4(invalid)), null))
        }
    }

    @Test fun `later transfers may reuse the same batch lease without reassociation`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(hotspot.address, hotspot, null, hotspot))
    }

    @Test fun `a new batch cannot reuse the previous address without its retained lease`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(hotspot.address, hotspot, null))
    }

    @Test fun `a changed gateway cannot reuse an unchanged batch address`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(hotspot.address,
            hotspot.copy(gateway = ipv4("10.218.114.1")), null, hotspot))
    }

    private fun lease(address: String, gateway: String) = GlassesFilesHotspotJoin.Lease(ipv4(address), ipv4(gateway))
    private fun ipv4(address: String) = InetAddress.getByName(address) as Inet4Address
}
