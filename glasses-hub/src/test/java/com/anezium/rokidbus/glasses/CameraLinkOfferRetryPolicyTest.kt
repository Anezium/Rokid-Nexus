package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraLinkOfferRetryPolicyTest {
    private val policy = CameraLinkOfferRetryPolicy(
        coldGroupSettleMs = 350L,
        intervalMs = 2_500L,
        maxOffers = 9,
        offersBeforeGroupRecreate = 5,
        maxGroupRecreates = 1,
    )

    @Test
    fun `created groups retain the cold settle delay`() {
        assertEquals(350L, policy.initialDelayMs(groupCreated = true))
        assertEquals(0L, policy.initialDelayMs(groupCreated = false))
    }

    @Test
    fun `fifth unanswered offer queues one recreate ten seconds after offer one`() {
        assertOffer(policy.nextAction(offersSent = 0, groupRecreatesDone = 0), 1, 2_500L)
        assertOffer(policy.nextAction(offersSent = 1, groupRecreatesDone = 0), 2, 2_500L)
        assertOffer(policy.nextAction(offersSent = 2, groupRecreatesDone = 0), 3, 2_500L)
        assertOffer(policy.nextAction(offersSent = 3, groupRecreatesDone = 0), 4, 2_500L)
        assertOffer(policy.nextAction(offersSent = 4, groupRecreatesDone = 0), 5, 0L)

        val recreate = policy.nextAction(offersSent = 5, groupRecreatesDone = 0)
        assertEquals(CameraLinkOfferRetryActionType.RECREATE_GROUP, recreate.type)
        assertNull(recreate.offerNumber)
        assertEquals(0L, recreate.nextDelayMs)
    }

    @Test
    fun `remaining total offer budget continues after recreate`() {
        for (offersSent in 5 until 9) {
            assertOffer(
                policy.nextAction(offersSent = offersSent, groupRecreatesDone = 1),
                offerNumber = offersSent + 1,
                nextDelayMs = 2_500L,
            )
        }

        val timeout = policy.nextAction(offersSent = 9, groupRecreatesDone = 1)
        assertEquals(CameraLinkOfferRetryActionType.TIMEOUT, timeout.type)
        assertNull(timeout.offerNumber)
        assertEquals(0L, timeout.nextDelayMs)
    }

    @Test
    fun `same counters always produce the same action`() {
        val first = policy.nextAction(offersSent = 5, groupRecreatesDone = 1)
        val second = policy.nextAction(offersSent = 5, groupRecreatesDone = 1)

        assertEquals(first, second)
    }

    @Test
    fun `associated client gets one bounded grace before the second poll removes`() {
        val gracePolicy = CameraLinkGroupRemovalGracePolicy(
            clientGraceMs = 2_500L,
            maxPolls = 2,
        )

        assertEquals(
            CameraLinkGroupRemovalAction(
                type = CameraLinkGroupRemovalActionType.POLL_GROUP,
                nextPollNumber = 2,
                delayMs = 2_500L,
            ),
            gracePolicy.nextAction(associatedClientPresent = true, pollNumber = 1),
        )
        assertEquals(
            CameraLinkGroupRemovalAction(
                type = CameraLinkGroupRemovalActionType.REMOVE_GROUP,
                nextPollNumber = null,
                delayMs = 0L,
            ),
            gracePolicy.nextAction(associatedClientPresent = true, pollNumber = 2),
        )
    }

    @Test
    fun `empty client list removes without adding grace`() {
        val gracePolicy = CameraLinkGroupRemovalGracePolicy(
            clientGraceMs = 2_500L,
            maxPolls = 2,
        )

        assertEquals(
            CameraLinkGroupRemovalActionType.REMOVE_GROUP,
            gracePolicy.nextAction(
                associatedClientPresent = false,
                pollNumber = 1,
            ).type,
        )
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
