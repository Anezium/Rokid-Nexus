package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfArmCommandBridgeProtocolTest {
    @Test fun fileCleanupAuthenticatesExactlyTheFixedNoArgumentCommand() {
        val command = SelfArmCommandBridgeProtocol.WIFI_CLEANUP_FILES
        val request = SelfArmCommandBridgeProtocol.request(SECRET, command, NONCE)
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Accepted(command, NONCE),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("replay"),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, setOf(NONCE)))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("auth"),
            SelfArmCommandBridgeProtocol.verify(request.replace(command, "wifi_disable"), SECRET, emptySet()))
        listOf(listOf("7"), listOf("QQ=="), listOf("7", "8")).forEach {
            assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
                SelfArmCommandBridgeProtocol.verify(
                    SelfArmCommandBridgeProtocol.request(SECRET, command, NONCE, it), SECRET, emptySet()))
        }
    }

    @Test fun fileConnectRequiresAuthenticatedCredentialsAndCannotBeReplayedAsCameraConnect() {
        val command = SelfArmCommandBridgeProtocol.WIFI_CONNECT_FILES
        val arguments = listOf("VGVzdA==", "YWJjZGVmZ2gxMjM0", "wpa2")
        val request = SelfArmCommandBridgeProtocol.request(SECRET, command, NONCE, arguments)
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Accepted(command, NONCE, arguments),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("auth"),
            SelfArmCommandBridgeProtocol.verify(request.replace(command, "wifi_connect"), SECRET, emptySet()))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("replay"),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, setOf(NONCE)))
        listOf(emptyList(), arguments + "extra", listOf("bad!", arguments[1], "wpa2"),
            listOf(arguments[0], arguments[1], "future")).forEach { malformed ->
            assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
                SelfArmCommandBridgeProtocol.verify(
                    SelfArmCommandBridgeProtocol.request(SECRET, command, NONCE, malformed), SECRET, emptySet()))
        }
    }

    @Test fun authenticatedForgetRequestBindsExactlyOneSsidArgument() {
        val arguments = listOf("QW5kcm9pZFNoYXJlXzEyMzQ=")
        val request = SelfArmCommandBridgeProtocol.request(SECRET, SelfArmCommandBridgeProtocol.WIFI_FORGET, NONCE, arguments)
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Accepted("wifi_forget", NONCE, arguments),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("auth"),
            SelfArmCommandBridgeProtocol.verify(request.replace(arguments[0], "QW5kcm9pZFNoYXJlXzU2Nzg="), SECRET, emptySet()))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("replay"),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, setOf(NONCE)))
    }

    @Test fun forgetRejectsMissingExtraMalformedAndOversizedArguments() {
        listOf(emptyList(), listOf(""), listOf("not-base64!"), listOf("A".repeat(45)),
            listOf("QQ==", "extra")).forEach { arguments ->
            val request = SelfArmCommandBridgeProtocol.request(SECRET, "wifi_forget", NONCE, arguments)
            assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
                SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()))
        }
        val maximum = listOf("A".repeat(44))
        assertEquals(SelfArmCommandBridgeProtocol.Verification.Accepted("wifi_forget", NONCE, maximum),
            SelfArmCommandBridgeProtocol.verify(
                SelfArmCommandBridgeProtocol.request(SECRET, "wifi_forget", NONCE, maximum), SECRET, emptySet()))
    }

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
    fun rejectsCommandsOutsideTheLiteralWhitelist() {
        val result = SelfArmCommandBridgeProtocol.verify(
            "reboot:$NONCE:${"0".repeat(64)}\n",
            SECRET,
            emptySet(),
        )

        assertEquals(SelfArmCommandBridgeProtocol.Verification.Rejected("command"), result)
    }

    @Test
    fun authenticatedConnectRequestCarriesEncodedCredentialsAndSecurity() {
        val arguments = listOf("QW5kcm9pZFNoYXJlXzEyMzQ=", "YWJjZGVmZ2gxMjM0", "wpa3")
        val request = SelfArmCommandBridgeProtocol.request(
            SECRET,
            SelfArmCommandBridgeProtocol.WIFI_CONNECT,
            NONCE,
            arguments,
        )

        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Accepted(
                SelfArmCommandBridgeProtocol.WIFI_CONNECT,
                NONCE,
                arguments,
            ),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()),
        )
        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Rejected("auth"),
            SelfArmCommandBridgeProtocol.verify(
                request.replace(":wpa3:", ":wpa2:"),
                SECRET,
                emptySet(),
            ),
        )
        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
            SelfArmCommandBridgeProtocol.verify(
                "wifi_connect:$NONCE:not-base64!:YWJjZGVmZ2g=:wpa3:${"0".repeat(64)}\n",
                SECRET,
                emptySet(),
            ),
        )
        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
            SelfArmCommandBridgeProtocol.verify(
                "wifi_connect:$NONCE:QW5kcm9pZFNoYXJlXzEyMzQ=:YWJjZGVmZ2g=:future:${"0".repeat(64)}\n",
                SECRET,
                emptySet(),
            ),
        )
    }

    @Test
    fun authenticatedOpenConnectRequestCarriesNoPassphrase() {
        val arguments = listOf("QW5kcm9pZFNoYXJlX09wZW4=", "", "open")
        val request = SelfArmCommandBridgeProtocol.request(
            SECRET,
            SelfArmCommandBridgeProtocol.WIFI_CONNECT,
            NONCE,
            arguments,
        )

        assertEquals(
            SelfArmCommandBridgeProtocol.Verification.Accepted(
                SelfArmCommandBridgeProtocol.WIFI_CONNECT,
                NONCE,
                arguments,
            ),
            SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()),
        )
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
    fun authenticatedWirelessAdbCommandsCarryNoArguments() {
        listOf(
            SelfArmCommandBridgeProtocol.ADB_WIFI_ENABLE,
            SelfArmCommandBridgeProtocol.ADB_WIFI_DISABLE,
        ).forEach { command ->
            val request = SelfArmCommandBridgeProtocol.request(SECRET, command, NONCE)
            assertEquals(
                SelfArmCommandBridgeProtocol.Verification.Accepted(command, NONCE),
                SelfArmCommandBridgeProtocol.verify(request, SECRET, emptySet()),
            )
            assertEquals(
                SelfArmCommandBridgeProtocol.Verification.Rejected("format"),
                SelfArmCommandBridgeProtocol.verify(
                    "$command:$NONCE:unexpected:${"0".repeat(64)}\n",
                    SECRET,
                    emptySet(),
                ),
            )
        }
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
