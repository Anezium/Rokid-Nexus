package com.anezium.rokidbus.plugin.agents.alleycat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PairingPayloadTest {
    @Test
    fun parsesCanonicalKittylitterPayload() {
        val payload = PairingPayload.parse(
            """{"v":1,"node_id":"node_abc","token":"$TEST_TOKEN","relay":null}""",
        )
        assertEquals("node_abc", payload.nodeId)
        assertEquals(TEST_TOKEN, payload.token)
        assertNull(payload.relay)
        assertNull(payload.displayName)
        assertTrue(!payload.toString().contains(TEST_TOKEN))
    }

    @Test
    fun acceptsHostNameAliasesAndPinnedRelay() {
        val fromHostName = PairingPayload.parse(
            JSONObject()
                .put("v", 1)
                .put("node_id", "n1")
                .put("token", TEST_TOKEN)
                .put("relay", "https://relay.example")
                .put("host_name", "desk"),
        )
        assertEquals("https://relay.example", fromHostName.relay)
        assertEquals("desk", fromHostName.displayName)

        val fromHostname = PairingPayload.parse(
            """{"v":1,"node_id":"n1","token":"$TEST_TOKEN","hostname":"laptop"}""",
        )
        assertEquals("laptop", fromHostname.displayName)

        val fromDisplayName = PairingPayload.parse(
            """{"v":1,"node_id":"n1","token":"$TEST_TOKEN","display_name":"Studio"}""",
        )
        assertEquals("Studio", fromDisplayName.displayName)
    }

    @Test
    fun rejectsVersionDriftAndMalformedToken() {
        try {
            PairingPayload.parse("""{"v":2,"node_id":"n1","token":"$TEST_TOKEN"}""")
            fail("expected version rejection")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("unsupported pairing version 2"))
            assertTrue(!e.message!!.contains(TEST_TOKEN))
        }

        try {
            PairingPayload.parse("""{"v":1,"node_id":"n1","token":"not-hex"}""")
            fail("expected token rejection")
        } catch (e: AlleycatException) {
            assertTrue(e.message!!.contains("32-byte hex"))
        }
    }

    @Test
    fun parseFlexibleAcceptsWrappedQrText() {
        val wrapped = "kittylitter pair\n{\"v\":1,\"node_id\":\"n1\",\"token\":\"$TEST_TOKEN\"}\n"
        val payload = PairingPayload.parseFlexible(wrapped)
        assertEquals("n1", payload.nodeId)
        assertEquals(TEST_TOKEN, payload.token)
        assertTrue(!payload.toString().contains(TEST_TOKEN))
    }
}
