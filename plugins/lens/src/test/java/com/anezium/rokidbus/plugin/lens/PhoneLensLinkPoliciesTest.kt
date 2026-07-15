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
    fun `discovery priming runs once per join cycle with bounded waits`() {
        val policy = PhoneLensDiscoveryPrimingPolicy(
            discoveryWaitMs = 2_000L,
            stopCallbackFallbackMs = 400L,
        )

        assertEquals(
            PhoneLensDiscoveryPrimingDecision(
                shouldPrime = true,
                discoveryWaitMs = 2_000L,
                stopCallbackFallbackMs = 400L,
            ),
            policy.decision(alreadyPrimedForJoinCycle = false),
        )
        assertEquals(
            PhoneLensDiscoveryPrimingDecision(
                shouldPrime = false,
                discoveryWaitMs = 2_000L,
                stopCallbackFallbackMs = 400L,
            ),
            policy.decision(alreadyPrimedForJoinCycle = true),
        )
    }

    @Test
    fun `unseen identity gets one uninterrupted seven and a half second first attempt`() {
        val policy = joinWatchdogPolicy()

        assertEquals(
            PhoneLensJoinWatchdogDecision(
                identityRecency = PhoneLensJoinIdentityRecency.UNSEEN,
                timeoutMs = 7_500L,
            ),
            policy.decision(
                attempt = 1,
                targetSsid = "DIRECT-RN-new",
                targetBand = PhoneLensFrequencyBand.TWO_GHZ,
                lastSuccessfulJoin = null,
                nowEpochMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `recent same ssid and band gets a three second first attempt`() {
        val policy = joinWatchdogPolicy()
        val success = PhoneLensSuccessfulJoin(
            ssid = "DIRECT-RN-stable",
            frequencyMhz = 2_462,
            recordedAtEpochMs = 900_000L,
        )

        assertEquals(
            PhoneLensJoinWatchdogDecision(
                identityRecency = PhoneLensJoinIdentityRecency.KNOWN_RECENT,
                timeoutMs = 3_000L,
            ),
            policy.decision(
                attempt = 1,
                targetSsid = "DIRECT-RN-stable",
                targetBand = PhoneLensFrequencyBand.TWO_GHZ,
                lastSuccessfulJoin = success,
                nowEpochMs = 1_000_000L,
            ),
        )
    }

    @Test
    fun `stale changed or cross-band joins remain unseen and retries stay bounded`() {
        val policy = joinWatchdogPolicy()
        val success = PhoneLensSuccessfulJoin(
            ssid = "DIRECT-RN-stable",
            frequencyMhz = 2_437,
            recordedAtEpochMs = 600_000L,
        )

        assertEquals(
            PhoneLensJoinIdentityRecency.UNSEEN,
            policy.decision(
                attempt = 1,
                targetSsid = "DIRECT-RN-stable",
                targetBand = PhoneLensFrequencyBand.TWO_GHZ,
                lastSuccessfulJoin = success,
                nowEpochMs = 1_000_000L,
            ).identityRecency,
        )
        assertEquals(
            PhoneLensJoinIdentityRecency.UNSEEN,
            policy.decision(
                attempt = 1,
                targetSsid = "DIRECT-RN-replaced",
                targetBand = PhoneLensFrequencyBand.TWO_GHZ,
                lastSuccessfulJoin = success.copy(recordedAtEpochMs = 900_000L),
                nowEpochMs = 1_000_000L,
            ).identityRecency,
        )
        assertEquals(
            PhoneLensJoinIdentityRecency.UNSEEN,
            policy.decision(
                attempt = 1,
                targetSsid = "DIRECT-RN-stable",
                targetBand = PhoneLensFrequencyBand.FIVE_GHZ,
                lastSuccessfulJoin = success.copy(recordedAtEpochMs = 900_000L),
                nowEpochMs = 1_000_000L,
            ).identityRecency,
        )
        assertEquals(
            4_500L,
            policy.decision(
                attempt = 2,
                targetSsid = "DIRECT-RN-stable",
                targetBand = PhoneLensFrequencyBand.TWO_GHZ,
                lastSuccessfulJoin = success.copy(recordedAtEpochMs = 900_000L),
                nowEpochMs = 1_000_000L,
            ).timeoutMs,
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

    @Test
    fun `join recovery removes group after two failures and resets channel after four`() {
        val policy = PhoneLensJoinRecoveryPolicy()

        assertEquals(PhoneLensJoinRecoveryAction.NONE, policy.actionAfter(0))
        assertEquals(PhoneLensJoinRecoveryAction.NONE, policy.actionAfter(1))
        assertEquals(PhoneLensJoinRecoveryAction.REMOVE_GROUP, policy.actionAfter(2))
        assertEquals(PhoneLensJoinRecoveryAction.NONE, policy.actionAfter(3))
        assertEquals(PhoneLensJoinRecoveryAction.RESET_CHANNEL, policy.actionAfter(4))
        assertEquals(PhoneLensJoinRecoveryAction.NONE, policy.actionAfter(5))
        assertEquals(PhoneLensJoinRecoveryAction.NONE, policy.actionAfter(6))
    }

    private fun joinWatchdogPolicy() = PhoneLensJoinWatchdogPolicy(
        unseenFirstAttemptMs = 7_500L,
        knownRecentFirstAttemptMs = 3_000L,
        retryAttemptMs = 4_500L,
        recentWindowMs = 300_000L,
    )
}
