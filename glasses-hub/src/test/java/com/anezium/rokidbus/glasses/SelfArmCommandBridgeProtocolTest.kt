package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmCommandBridgeProtocolTest {
    @Test
    fun prefixKeyedSha256MatchesKnownVectorAndVerifies() {
        val token = SelfArmCommandBridgeProtocol.token(SECRET, "wifi_enable", NONCE)

        assertEquals("0b36b28df70f60410219e9ae87cd6f420051528a3d26b16d6b01fe5b9f728113", token)
        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Accepted("wifi_enable", NONCE),
            SelfArmCommandBridgeProtocol.verify("wifi_enable:$NONCE:$token\n", SECRET, emptySet()),
        )
        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Rejected("auth"),
            SelfArmCommandBridgeProtocol.verify(
                "wifi_enable:$NONCE:${token.dropLast(1)}0\n",
                SECRET,
                emptySet(),
            ),
        )
    }

    @Test
    fun rejectsCommandsOutsideTheTwoLiteralWhitelist() {
        val result = SelfArmCommandBridgeProtocol.verify(
            "reboot:$NONCE:${"0".repeat(64)}\n",
            SECRET,
            emptySet(),
        )

        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("command"), result)
    }

    @Test
    fun rejectsAPreviouslySeenNonceBeforeExecution() {
        val request = SelfArmCommandBridgeProtocol.request(SECRET, "wifi_disable", NONCE)

        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Rejected("replay"),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, setOf(NONCE)),
        )
    }

    @Test
    fun rejectsMalformedOversizedAndInjectionInputs() {
        val invalidRequests = listOf(
            "wifi_enable;svc wifi disable:$NONCE:${"0".repeat(64)}\n",
            "wifi_enable:$NONCE:${"0".repeat(64)}:extra\n",
            "wifi_enable:not-hex:${"0".repeat(64)}\n",
            "wifi_enable:$NONCE:${"0".repeat(64)}\nsecond-line\n",
            "x".repeat(SelfArmCommandBridgeProtocol.MAX_REQUEST_BYTES + 1),
        )

        invalidRequests.forEach { request ->
            assertTrue(
                "Expected rejection for $request",
                SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()) is
                    SelfArmCommandBridgeProtocol.Verification.Rejected,
            )
        }
    }

    private companion object {
        const val SECRET = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        const val NONCE = "0123456789abcdef0123456789abcdef"
    }
}
