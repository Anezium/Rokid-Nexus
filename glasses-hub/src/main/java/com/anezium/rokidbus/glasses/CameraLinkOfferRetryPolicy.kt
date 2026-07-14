package com.anezium.rokidbus.glasses

internal data class CameraLinkOfferRetryAction(
    val offerNumber: Int?,
    val nextDelayMs: Long,
    val timedOut: Boolean,
)

/** Pure retry budget used by CameraLink's Android callback/scheduler shell. */
internal class CameraLinkOfferRetryPolicy(
    private val coldGroupSettleMs: Long,
    private val intervalMs: Long,
    private val maxOffers: Int,
) {
    init {
        require(coldGroupSettleMs >= 0L)
        require(intervalMs > 0L)
        require(maxOffers > 0)
    }

    private var offersSent = 0

    fun initialDelayMs(groupCreated: Boolean): Long =
        if (groupCreated) coldGroupSettleMs else 0L

    fun nextAction(): CameraLinkOfferRetryAction {
        if (offersSent >= maxOffers) {
            return CameraLinkOfferRetryAction(
                offerNumber = null,
                nextDelayMs = 0L,
                timedOut = true,
            )
        }
        offersSent += 1
        return CameraLinkOfferRetryAction(
            offerNumber = offersSent,
            nextDelayMs = intervalMs,
            timedOut = false,
        )
    }

    fun reset() {
        offersSent = 0
    }
}
