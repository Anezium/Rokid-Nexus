package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.GlassesFilesClientNetwork
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class GlassesFilesHotspotJoinTest {
    @Test fun `a radio on autojoin lease cannot prove the hotspot before bridge confirmation`() {
        val homeLease = lease("192.168.1.25", "192.168.1.1")
        assertFalse(GlassesFilesHotspotJoin.isJoined(false, null, homeLease, null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(false, null, hotspot, null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(false, hotspot.address, hotspot, null, hotspot))
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, null, hotspot, null))
    }

    private val home = ipv4("192.168.1.25")
    private val hotspot = lease("10.218.114.98", "10.218.114.54")

    @Test fun `an early failed connect is retried within the original deadline`() {
        val attempts = mutableListOf<Long>()
        var lastAttempt: Long? = null
        for (now in 0L..20_000L step 100L) {
            if (GlassesFilesHotspotJoin.shouldRetry(now, 20_000L, lastAttempt)) {
                attempts += now
                lastAttempt = now
            }
        }
        org.junit.Assert.assertEquals(listOf(0L, 5_000L, 10_000L, 15_000L), attempts)
        assertFalse(GlassesFilesHotspotJoin.shouldRetry(20_001L, 20_000L, null))
    }

    @Test fun `a lease acquired during an attempt can finish before its retry window`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, null, null))
        assertFalse(GlassesFilesHotspotJoin.shouldRetry(1_500L, 20_000L, 0L))
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, home, hotspot, null))
    }

    @Test fun `redacted SSID does not prevent a new IPv4 lease from joining`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, home, hotspot, null))
    }

    @Test fun `redacted SSID cannot accept the unchanged pre-join address`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, lease("192.168.1.25", "192.168.1.1"), null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, lease("192.168.1.25", "192.168.1.2"), null))
    }

    @Test fun `a lease inside the phone client subnet never joins`() {
        val client = GlassesFilesClientNetwork("10.218.114.2", 24)
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, hotspot, client))
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, hotspot.address, hotspot, client, hotspot))
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, home, hotspot, GlassesFilesClientNetwork("192.168.1.2", 24)))
    }

    @Test fun `a radio without a previous address can acquire a hotspot lease`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, null, hotspot, null))
    }

    @Test fun `association without an IPv4 address or gateway is not ready`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, null, null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, hotspot.copy(gateway = null), null))
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, hotspot.copy(gateway = hotspot.address), null))
    }

    @Test fun `unusable interface addresses and gateways are rejected`() {
        listOf("0.0.0.0", "127.0.0.1", "169.254.1.2", "224.0.0.1").forEach { invalid ->
            assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, hotspot.copy(address = ipv4(invalid)), null))
            assertFalse(GlassesFilesHotspotJoin.isJoined(true, home, hotspot.copy(gateway = ipv4(invalid)), null))
        }
    }

    @Test fun `later transfers may reuse the same batch lease without reassociation`() {
        assertTrue(GlassesFilesHotspotJoin.isJoined(true, hotspot.address, hotspot, null, hotspot))
    }

    @Test fun `a new batch cannot reuse the previous address without its retained lease`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, hotspot.address, hotspot, null))
    }

    @Test fun `a changed gateway cannot reuse an unchanged batch address`() {
        assertFalse(GlassesFilesHotspotJoin.isJoined(true, hotspot.address,
            hotspot.copy(gateway = ipv4("10.218.114.1")), null, hotspot))
    }

    private fun lease(address: String, gateway: String) = GlassesFilesHotspotJoin.Lease(ipv4(address), ipv4(gateway))
    private fun ipv4(address: String) = InetAddress.getByName(address) as Inet4Address
}
