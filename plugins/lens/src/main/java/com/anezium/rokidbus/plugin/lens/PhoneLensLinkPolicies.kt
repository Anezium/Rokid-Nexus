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
