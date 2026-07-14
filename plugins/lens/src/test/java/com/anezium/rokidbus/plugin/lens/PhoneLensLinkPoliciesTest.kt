package com.anezium.rokidbus.plugin.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneLensLinkPoliciesTest {
    private fun offer(
        token: String = "0123456789abcdef",
        goIp: String = "192.168.49.1",
        ssid: String = "DIRECT-NEXUS",
    ) =
        PhoneLensLinkOffer(
            sessionId = "camera-session",
            ssid = ssid,
            passphrase = "abcdefgh",
            port = 38_401,
            token = token,
            goIp = goIp,
        )

    @Test
    fun `active repeated offer is ignored by stable session and token identity`() {
        assertFalse(
            PhoneLensOfferUpdatePolicy.shouldStart(
                current = offer(),
                incoming = offer(goIp = "192.168.49.2"),
                joinActive = true,
            ),
        )
    }

    @Test
    fun `same offer restarts an idle link and a new token replaces an active link`() {
        assertTrue(
            PhoneLensOfferUpdatePolicy.shouldStart(
                current = offer(),
                incoming = offer(),
                joinActive = false,
            ),
        )
        assertTrue(
            PhoneLensOfferUpdatePolicy.shouldStart(
                current = offer(),
                incoming = offer(token = "fedcba9876543210"),
                joinActive = true,
            ),
        )
        assertTrue(
            PhoneLensOfferUpdatePolicy.shouldStart(
                current = offer(),
                incoming = offer(ssid = "DIRECT-REPLACEMENT"),
                joinActive = true,
            ),
        )
    }

    @Test
    fun `join retries use bounded one two three second backoff`() {
        val policy = PhoneLensJoinRetryPolicy(
            initialDelayMs = 1_000L,
            delayStepMs = 1_000L,
            maxDelayMs = 3_000L,
            maxAttempts = 6,
        )

        assertEquals(1, policy.startAttempt())
        assertEquals(1_000L, policy.retryDelayAfter(1))
        assertEquals(2, policy.startAttempt())
        assertEquals(2_000L, policy.retryDelayAfter(2))
        assertEquals(3, policy.startAttempt())
        assertEquals(3_000L, policy.retryDelayAfter(3))
        assertEquals(4, policy.startAttempt())
        assertEquals(3_000L, policy.retryDelayAfter(4))
        assertEquals(5, policy.startAttempt())
        assertEquals(3_000L, policy.retryDelayAfter(5))
        assertEquals(6, policy.startAttempt())
        assertNull(policy.retryDelayAfter(6))
        assertNull(policy.startAttempt())
    }
}
