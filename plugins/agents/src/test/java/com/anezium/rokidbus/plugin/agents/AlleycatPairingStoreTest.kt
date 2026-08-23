package com.anezium.rokidbus.plugin.agents

import com.anezium.rokidbus.plugin.agents.alleycat.PairingPayload
import com.anezium.rokidbus.plugin.agents.alleycat.TEST_TOKEN
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlleycatPairingStoreTest {
    @Test
    fun pairingParseStoresPublicComputerAndKeepsTokenOnlyInSecretStore() {
        val payload = PairingPayload.parse(
            """{"v":1,"node_id":"node_abc","token":"$TEST_TOKEN","relay":"https://relay.example","host_name":"desk"}""",
        )
        val computer = AlleycatComputer.fromPayload(payload)
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, payload.token)

        assertEquals("desk", computer.name)
        assertEquals("https://relay.example", computer.relay)
        assertTrue(computer.computerId.startsWith(AlleycatComputer.ID_PREFIX))
        assertEquals(TEST_TOKEN, secrets.getToken(computer.computerId))

        val json = computer.toPublicJson()
        assertFalse(json.toString().contains(TEST_TOKEN))
        assertFalse(json.has("token"))
        assertEquals("node_abc", json.getString("nodeId"))

        val restored = AlleycatComputer.fromPublicJson(JSONObject(json.toString()))!!
        assertEquals(computer.computerId, restored.computerId)
        assertEquals(computer.nodeId, restored.nodeId)
        assertEquals(computer.name, restored.name)
        assertEquals(computer.relay, restored.relay)
        assertEquals(TEST_TOKEN, secrets.getToken(restored.computerId))

        assertFalse(computer.toString().contains(TEST_TOKEN))
        assertFalse(restored.toString().contains(TEST_TOKEN))
        assertFalse(payload.toString().contains(TEST_TOKEN))
        assertFalse(secrets.toString().contains(TEST_TOKEN))
    }

    @Test
    fun sameNodeIdRoundTripsToTheSameComputerId() {
        val payload = PairingPayload.parse(
            """{"v":1,"node_id":"n1","token":"$TEST_TOKEN"}""",
        )
        val a = AlleycatComputer.fromPayload(payload)
        val b = AlleycatComputer.fromPayload(payload)
        assertEquals(a.computerId, b.computerId)
        assertNull(a.selectedAgent)
        assertFalse(a.needsRePair)
    }

    @Test
    fun removingTheTokenLeavesThePublicComputerUntouched() {
        val payload = PairingPayload.parse(
            """{"v":1,"node_id":"n1","token":"$TEST_TOKEN"}""",
        )
        val computer = AlleycatComputer.fromPayload(payload)
        val secrets = MemoryAlleycatSecretStore()
        secrets.putToken(computer.computerId, payload.token)
        secrets.removeToken(computer.computerId)
        assertNull(secrets.getToken(computer.computerId))
        assertEquals("n1", computer.nodeId)
    }
}
