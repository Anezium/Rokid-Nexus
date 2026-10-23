package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class GlassesFilesHotspotTest {
    private val id = "transfer_123"
    private val credentials = GlassesFilesHotspotCredentials("Nexus test", "password123", "wpa2")
    private val endpoint = GlassesFilesLaneEndpoint("192.168.4.2", 1234, GlassesFilesLane.encodeKey(GlassesFilesLane.newKey()))

    @Test fun requestsRoundTripAndRejectMalformedIds() {
        listOf(
            GlassesFilesRequest.HotspotOffer(id, "batch_123", credentials),
            GlassesFilesRequest.HotspotOffer(id, "batch_123", credentials, GlassesFilesClientNetwork("192.168.1.2", 24)),
            GlassesFilesRequest.HotspotRelease("batch_123"),
            GlassesFilesRequest.LaneSwitch(id),
            GlassesFilesRequest.LaneResume(id, 65536),
        ).forEach { request ->
            val json = GlassesFilesContract.encode(request)
            assertEquals(request, GlassesFilesContract.parseRequest(json))
            json.put(if (request is GlassesFilesRequest.HotspotRelease) "batchId" else "transferId", 5)
            assertNull(GlassesFilesContract.parseRequest(json))
        }
    }

    @Test fun resultsRoundTripAndRejectMalformedIds() {
        listOf(
            GlassesFilesResult.HotspotReady(id, endpoint, "192.168.4.1"),
            GlassesFilesResult.HotspotFailed(id),
            GlassesFilesResult.LaneResume(id, 65536),
        ).forEach { result ->
            val json = GlassesFilesContract.encode(result)
            assertEquals(result, GlassesFilesContract.parseResult(json))
            json.put("transferId", JSONObject.NULL)
            assertNull(GlassesFilesContract.parseResult(json))
        }
    }

    @Test fun credentialsAreStrictAndRedacted() {
        val offer = GlassesFilesRequest.HotspotOffer(id, "batch_123", credentials)
        assertFalse(offer.toString().contains(credentials.ssid))
        assertFalse(offer.toString().contains(credentials.passphrase))
        listOf("ssid" to "", "ssid" to "x".repeat(33), "ssid" to "\n", "ssid" to 123,
            "passphrase" to "short", "passphrase" to "x".repeat(129), "security" to "unknown",
            "batchId" to "invalid").forEach { (field, value) ->
            assertNull(GlassesFilesContract.parseRequest(GlassesFilesContract.encode(offer).put(field, value)))
        }
        assertTrue(credentials.copy(ssid = "é".repeat(16)).isValid())
        assertFalse(credentials.copy(ssid = "é".repeat(17)).isValid())
        assertTrue(credentials.copy(passphrase = "", security = "open").isValid())
        assertFalse(credentials.copy(security = "open").isValid())
        assertTrue(credentials.copy(security = "wpa3").isValid())
        listOf(-1, 1.5, "12", GlassesFilesContract.MAX_FILE_BYTES + 1).forEach { value ->
            assertNull(GlassesFilesContract.parseRequest(GlassesFilesContract.encode(GlassesFilesRequest.LaneResume(id, 0)).put("staged", value)))
            assertNull(GlassesFilesContract.parseResult(GlassesFilesContract.encode(GlassesFilesResult.LaneResume(id, 0)).put("staged", value)))
        }
        listOf("gateway" to "example.com", "lane" to JSONObject.NULL).forEach { (field, value) ->
            assertNull(GlassesFilesContract.parseResult(GlassesFilesContract.encode(GlassesFilesResult.HotspotReady(id, endpoint, "192.168.4.1")).put(field, value)))
        }
    }

    @Test fun policyVetoesSharedSmallCameraPermissionAndOldPeer() {
        fun start(bytes: Long = 20L * 1024 * 1024, shared: Boolean = false, camera: Boolean = false,
            permission: Boolean = true, peer: Boolean = true) =
            GlassesFilesHotspotPolicy.shouldStart(bytes, shared, camera, permission, peer)
        assertTrue(start())
        assertFalse(start(shared = true))
        assertFalse(start(bytes = 500_000))
        assertFalse(start(camera = true))
        assertFalse(start(permission = false))
        assertFalse(start(peer = false))
        assertFalse(start(bytes = GlassesFilesHotspotPolicy.MIN_BYTES - 1))
    }

    @Test fun clientSubnetProtectsSharedNetworkEvenAfterLaneDecline() {
        val network = GlassesFilesClientNetwork("192.168.1.5", 24)
        assertTrue(network.contains("192.168.1.6"))
        assertFalse(network.contains("192.168.2.6"))
        val offer = GlassesFilesRequest.HotspotOffer(id, "batch_123", credentials, network)
        listOf(JSONObject.NULL, JSONObject().put("host", "host").put("prefix", 24),
            JSONObject().put("host", "192.168.1.2").put("prefix", 33),
            JSONObject().put("host", "192.168.1.2").put("prefix", 24.5)).forEach {
            assertNull(GlassesFilesContract.parseRequest(GlassesFilesContract.encode(offer).put("clientNetwork", it)))
        }
    }

    @Test fun upgradeFreezesOneReceiverConfirmedOffset() {
        val upgrade = GlassesFilesLaneUpgrade()
        assertFalse(upgrade.snapshot(-1, 10))
        assertFalse(upgrade.snapshot(11, 10))
        assertTrue(upgrade.snapshot(7, 10))
        assertTrue(upgrade.snapshot(7, 10))
        assertFalse(upgrade.snapshot(8, 10))
        assertEquals(7L, upgrade.offset)
    }
}
