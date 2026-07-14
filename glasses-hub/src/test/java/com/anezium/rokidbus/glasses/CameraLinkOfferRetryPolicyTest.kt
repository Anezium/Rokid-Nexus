package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLinkOfferRetryPolicyTest {
    private val policy = CameraLinkOfferRetryPolicy(
        coldGroupSettleMs = 350L,
        intervalMs = 2_500L,
        maxOffers = 8,
        offersBeforeGroupRecreate = 3,
        maxGroupRecreates = 1,
    )

    @Test
    fun `created groups retain the cold settle delay`() {
        assertEquals(350L, policy.initialDelayMs(groupCreated = true))
        assertEquals(0L, policy.initialDelayMs(groupCreated = false))
    }

    @Test
    fun `third unanswered offer immediately queues one group recreate`() {
        assertOffer(policy.nextAction(offersSent = 0, groupRecreatesDone = 0), 1, 2_500L)
        assertOffer(policy.nextAction(offersSent = 1, groupRecreatesDone = 0), 2, 2_500L)
        assertOffer(policy.nextAction(offersSent = 2, groupRecreatesDone = 0), 3, 0L)

        val recreate = policy.nextAction(offersSent = 3, groupRecreatesDone = 0)
        assertEquals(CameraLinkOfferRetryActionType.RECREATE_GROUP, recreate.type)
        assertNull(recreate.offerNumber)
        assertEquals(0L, recreate.nextDelayMs)
    }

    @Test
    fun `remaining total offer budget continues after recreate`() {
        for (offersSent in 3 until 8) {
            assertOffer(
                policy.nextAction(offersSent = offersSent, groupRecreatesDone = 1),
                offerNumber = offersSent + 1,
                nextDelayMs = 2_500L,
            )
        }

        val timeout = policy.nextAction(offersSent = 8, groupRecreatesDone = 1)
        assertEquals(CameraLinkOfferRetryActionType.TIMEOUT, timeout.type)
        assertNull(timeout.offerNumber)
        assertEquals(0L, timeout.nextDelayMs)
    }

    @Test
    fun `same counters always produce the same action`() {
        val first = policy.nextAction(offersSent = 3, groupRecreatesDone = 1)
        val second = policy.nextAction(offersSent = 3, groupRecreatesDone = 1)

        assertEquals(first, second)
    }

    private fun assertOffer(
        action: CameraLinkOfferRetryAction,
        offerNumber: Int,
        nextDelayMs: Long,
    ) {
        assertEquals(CameraLinkOfferRetryActionType.OFFER, action.type)
        assertEquals(offerNumber, action.offerNumber)
        assertEquals(nextDelayMs, action.nextDelayMs)
    }
}
