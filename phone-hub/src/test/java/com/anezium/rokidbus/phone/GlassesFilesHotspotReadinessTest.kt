package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class GlassesFilesHotspotReadinessTest {
    private val client = address("wlan0", "192.168.1.2")
    private val gateway = address("swlan0", "192.168.231.1")

    @Test fun `onStarted without a new hotspot gateway is not ready`() {
        assertFalse(GlassesFilesHotspotReadiness.isReady(listOf(client), listOf(client), null))
        assertFalse(GlassesFilesHotspotReadiness.isReady(emptyList(), emptyList(), null))
    }

    @Test fun `a new hotspot interface gateway permits the offer`() {
        assertTrue(GlassesFilesHotspotReadiness.isReady(listOf(client), listOf(client, gateway), null))
        assertTrue(GlassesFilesHotspotReadiness.isReady(emptyList(), listOf(gateway.copy(name = "wlan0")), null))
        assertTrue(GlassesFilesHotspotReadiness.isReady(emptyList(), listOf(gateway.copy(name = "ap_br_swlan0")), null))
    }

    @Test fun `existing addresses and the phone client subnet cannot be the hotspot`() {
        assertFalse(GlassesFilesHotspotReadiness.isReady(listOf(gateway), listOf(gateway), null))
        assertFalse(GlassesFilesHotspotReadiness.isReady(emptyList(), listOf(client),
            GlassesFilesClientNetwork("192.168.1.2", 24)))
    }

    @Test fun `cellular VPN and unusable addresses do not establish readiness`() {
        listOf("rmnet0", "tun0", "lo").forEach { name ->
            assertFalse(GlassesFilesHotspotReadiness.isReady(emptyList(), listOf(gateway.copy(name = name)), null))
        }
        listOf("0.0.0.0", "127.0.0.1", "169.254.1.1", "224.0.0.1").forEach { ip ->
            assertFalse(GlassesFilesHotspotReadiness.isReady(emptyList(), listOf(address("wlan0", ip)), null))
        }
    }

    private fun address(name: String, ip: String) = GlassesFilesHotspotReadiness.Interface(
        name, InetAddress.getByName(ip) as Inet4Address)
}
