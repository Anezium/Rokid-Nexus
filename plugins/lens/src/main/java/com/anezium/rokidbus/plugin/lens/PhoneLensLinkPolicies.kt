package com.anezium.rokidbus.plugin.lens

internal object PhoneLensOfferUpdatePolicy {
    fun shouldStart(
        current: PhoneLensLinkOffer?,
        incoming: PhoneLensLinkOffer,
        joinActive: Boolean,
    ): Boolean = current == null || !sameLink(current, incoming) || !joinActive

    private fun sameLink(first: PhoneLensLinkOffer, second: PhoneLensLinkOffer): Boolean =
        first.sessionId == second.sessionId && first.token == second.token &&
            first.ssid == second.ssid && first.passphrase == second.passphrase &&
            first.port == second.port
}

internal data class PhoneLensDiscoveryPrimingDecision(
    val shouldPrime: Boolean,
    val discoveryWaitMs: Long,
    val stopCallbackFallbackMs: Long,
)

/** Pure per-join-cycle discovery decision; Android callbacks remain in PhoneLensImageLink. */
internal class PhoneLensDiscoveryPrimingPolicy(
    private val discoveryWaitMs: Long,
    private val stopCallbackFallbackMs: Long,
) {
    init {
        require(discoveryWaitMs > 0L)
        require(stopCallbackFallbackMs > 0L)
    }

    fun decision(alreadyPrimedForJoinCycle: Boolean): PhoneLensDiscoveryPrimingDecision =
        PhoneLensDiscoveryPrimingDecision(
            shouldPrime = !alreadyPrimedForJoinCycle,
            discoveryWaitMs = discoveryWaitMs,
            stopCallbackFallbackMs = stopCallbackFallbackMs,
        )
}

internal enum class PhoneLensFrequencyBand {
    TWO_GHZ,
    FIVE_GHZ,
    SIX_GHZ,
    UNKNOWN,
    ;

    companion object {
        fun fromFrequencyMhz(frequencyMhz: Int): PhoneLensFrequencyBand = when (frequencyMhz) {
            in 2_400..2_500 -> TWO_GHZ
            in 4_900..5_900 -> FIVE_GHZ
            in 5_925..7_125 -> SIX_GHZ
            else -> UNKNOWN
        }
    }
}

internal data class PhoneLensSuccessfulJoin(
    val ssid: String,
    val frequencyMhz: Int,
    val recordedAtEpochMs: Long,
)

internal enum class PhoneLensJoinIdentityRecency {
    UNSEEN,
    KNOWN_RECENT,
}

internal data class PhoneLensJoinWatchdogDecision(
    val identityRecency: PhoneLensJoinIdentityRecency,
    val timeoutMs: Long,
)

/** Pure identity-aware join window; persistence and Android timing remain in PhoneLensImageLink. */
internal class PhoneLensJoinWatchdogPolicy(
    private val unseenFirstAttemptMs: Long,
    private val knownRecentFirstAttemptMs: Long,
    private val retryAttemptMs: Long,
    private val recentWindowMs: Long,
) {
    init {
        require(unseenFirstAttemptMs > 0L)
        require(knownRecentFirstAttemptMs > 0L)
        require(retryAttemptMs > 0L)
        require(recentWindowMs > 0L)
    }

    fun decision(
        attempt: Int?,
        targetSsid: String,
        targetBand: PhoneLensFrequencyBand,
        lastSuccessfulJoin: PhoneLensSuccessfulJoin?,
        nowEpochMs: Long,
    ): PhoneLensJoinWatchdogDecision {
        val knownRecent = lastSuccessfulJoin?.let { success ->
            val ageMs = nowEpochMs - success.recordedAtEpochMs
            success.ssid == targetSsid &&
                targetBand != PhoneLensFrequencyBand.UNKNOWN &&
                PhoneLensFrequencyBand.fromFrequencyMhz(success.frequencyMhz) == targetBand &&
                ageMs in 0..recentWindowMs
        } == true
        val identityRecency = if (knownRecent) {
            PhoneLensJoinIdentityRecency.KNOWN_RECENT
        } else {
            PhoneLensJoinIdentityRecency.UNSEEN
        }
        val timeoutMs = when {
            attempt != 1 -> retryAttemptMs
            knownRecent -> knownRecentFirstAttemptMs
            else -> unseenFirstAttemptMs
        }
        return PhoneLensJoinWatchdogDecision(identityRecency, timeoutMs)
    }
}

/** Pure bounded backoff; Android operation cleanup remains in PhoneLensImageLink. */
internal class PhoneLensJoinRetryPolicy(
    private val initialDelayMs: Long,
    private val delayStepMs: Long,
    private val maxDelayMs: Long,
    private val maxAttempts: Int,
) {
    init {
        require(initialDelayMs > 0L)
        require(delayStepMs >= 0L)
        require(maxDelayMs >= initialDelayMs)
        require(maxAttempts > 0)
    }

    private var attemptsStarted = 0

    fun startAttempt(): Int? {
        if (attemptsStarted >= maxAttempts) return null
        attemptsStarted += 1
        return attemptsStarted
    }

    fun retryDelayAfter(attempt: Int): Long? {
        if (attempt <= 0 || attempt >= maxAttempts) return null
        return (initialDelayMs + (attempt - 1L) * delayStepMs).coerceAtMost(maxDelayMs)
    }

    fun reset() {
        attemptsStarted = 0
    }
}

internal enum class PhoneLensJoinRecoveryAction {
    NONE,
    REMOVE_GROUP,
    RESET_CHANNEL,
}

/** Pure escalation ladder; WifiP2pManager operations remain in PhoneLensImageLink. */
internal class PhoneLensJoinRecoveryPolicy(
    private val removeGroupAfterFailures: Int = 2,
    private val resetChannelAfterFailures: Int = 4,
) {
    init {
        require(removeGroupAfterFailures > 0)
        require(resetChannelAfterFailures > removeGroupAfterFailures)
    }

    fun actionAfter(consecutiveFailures: Int): PhoneLensJoinRecoveryAction = when {
        consecutiveFailures == removeGroupAfterFailures -> PhoneLensJoinRecoveryAction.REMOVE_GROUP
        consecutiveFailures == resetChannelAfterFailures -> PhoneLensJoinRecoveryAction.RESET_CHANNEL
        else -> PhoneLensJoinRecoveryAction.NONE
    }
}
