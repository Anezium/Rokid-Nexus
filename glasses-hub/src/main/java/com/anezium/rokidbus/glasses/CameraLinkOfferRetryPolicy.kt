package com.anezium.rokidbus.glasses

internal enum class CameraLinkOfferRetryActionType {
    OFFER,
    RECREATE_GROUP,
    TIMEOUT,
}

internal data class CameraLinkOfferRetryAction(
    val type: CameraLinkOfferRetryActionType,
    val offerNumber: Int?,
    val nextDelayMs: Long,
)

/** Pure retry decision used by CameraLink's Android callback/scheduler shell. */
internal class CameraLinkOfferRetryPolicy(
    private val coldGroupSettleMs: Long,
    private val intervalMs: Long,
    private val maxOffers: Int,
    private val offersBeforeGroupRecreate: Int,
    private val maxGroupRecreates: Int,
) {
    init {
        require(coldGroupSettleMs >= 0L)
        require(intervalMs > 0L)
        require(maxOffers > 0)
        require(offersBeforeGroupRecreate in 1..maxOffers)
        require(maxGroupRecreates >= 0)
    }

    fun initialDelayMs(groupCreated: Boolean): Long =
        if (groupCreated) coldGroupSettleMs else 0L

    fun nextAction(offersSent: Int, groupRecreatesDone: Int): CameraLinkOfferRetryAction {
        require(offersSent >= 0)
        require(groupRecreatesDone >= 0)

        return when {
            offersSent >= maxOffers -> CameraLinkOfferRetryAction(
                type = CameraLinkOfferRetryActionType.TIMEOUT,
                offerNumber = null,
                nextDelayMs = 0L,
            )
            groupRecreatesDone < maxGroupRecreates &&
                offersSent >= offersBeforeGroupRecreate -> CameraLinkOfferRetryAction(
                type = CameraLinkOfferRetryActionType.RECREATE_GROUP,
                offerNumber = null,
                nextDelayMs = 0L,
            )
            else -> {
                val offerNumber = offersSent + 1
                val recreateAfterOffer = groupRecreatesDone < maxGroupRecreates &&
                    offerNumber >= offersBeforeGroupRecreate
                CameraLinkOfferRetryAction(
                    type = CameraLinkOfferRetryActionType.OFFER,
                    offerNumber = offerNumber,
                    nextDelayMs = if (recreateAfterOffer) 0L else intervalMs,
                )
            }
        }
    }
}
