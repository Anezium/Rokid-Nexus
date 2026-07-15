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

internal enum class CameraLinkGroupRemovalActionType {
    REMOVE_GROUP,
    POLL_GROUP,
}

internal data class CameraLinkGroupRemovalAction(
    val type: CameraLinkGroupRemovalActionType,
    val nextPollNumber: Int?,
    val delayMs: Long,
)

/** Pure bounded grace for an associated P2P client to deliver its TCP HELLO. */
internal class CameraLinkGroupRemovalGracePolicy(
    private val clientGraceMs: Long,
    private val maxPolls: Int,
) {
    init {
        require(clientGraceMs > 0L)
        require(maxPolls > 1)
    }

    fun nextAction(
        associatedClientPresent: Boolean,
        pollNumber: Int,
    ): CameraLinkGroupRemovalAction {
        require(pollNumber in 1..maxPolls)
        return if (associatedClientPresent && pollNumber < maxPolls) {
            CameraLinkGroupRemovalAction(
                type = CameraLinkGroupRemovalActionType.POLL_GROUP,
                nextPollNumber = pollNumber + 1,
                delayMs = clientGraceMs,
            )
        } else {
            CameraLinkGroupRemovalAction(
                type = CameraLinkGroupRemovalActionType.REMOVE_GROUP,
                nextPollNumber = null,
                delayMs = 0L,
            )
        }
    }
}

/** Pure retry decision used by CameraLink's Android callback/scheduler shell. */
internal class CameraLinkOfferRetryPolicy(
    private val coldGroupSettleMs: Long,
    private val intervalMs: Long,
    private val maxOffers: Int,
    private val offersBeforeGroupRecreate: Int,
    private val offersBetweenGroupRecreates: Int,
    private val maxGroupRecreates: Int,
) {
    init {
        require(coldGroupSettleMs >= 0L)
        require(intervalMs > 0L)
        require(maxOffers > 0)
        require(offersBeforeGroupRecreate in 1..maxOffers)
        require(offersBetweenGroupRecreates > 0)
        require(maxGroupRecreates >= 0)
        if (maxGroupRecreates > 1) {
            require(
                offersBeforeGroupRecreate +
                    (maxGroupRecreates - 1) * offersBetweenGroupRecreates < maxOffers,
            )
        }
    }

    fun initialDelayMs(groupCreated: Boolean): Long =
        if (groupCreated) coldGroupSettleMs else 0L

    fun nextAction(offersSent: Int, groupRecreatesDone: Int): CameraLinkOfferRetryAction {
        require(offersSent >= 0)
        require(groupRecreatesDone >= 0)
        val nextRecreateAt = offersBeforeGroupRecreate +
            groupRecreatesDone * offersBetweenGroupRecreates

        return when {
            offersSent >= maxOffers -> CameraLinkOfferRetryAction(
                type = CameraLinkOfferRetryActionType.TIMEOUT,
                offerNumber = null,
                nextDelayMs = 0L,
            )
            groupRecreatesDone < maxGroupRecreates &&
                offersSent >= nextRecreateAt -> CameraLinkOfferRetryAction(
                type = CameraLinkOfferRetryActionType.RECREATE_GROUP,
                offerNumber = null,
                nextDelayMs = 0L,
            )
            else -> {
                val offerNumber = offersSent + 1
                val recreateAfterOffer = groupRecreatesDone < maxGroupRecreates &&
                    offerNumber >= nextRecreateAt
                CameraLinkOfferRetryAction(
                    type = CameraLinkOfferRetryActionType.OFFER,
                    offerNumber = offerNumber,
                    nextDelayMs = if (recreateAfterOffer) 0L else intervalMs,
                )
            }
        }
    }
}
