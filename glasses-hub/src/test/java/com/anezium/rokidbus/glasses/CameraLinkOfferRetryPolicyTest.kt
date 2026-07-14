package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLinkOfferRetryPolicyTest {
    @Test
    fun `offers repeat at the configured cadence before timing out`() {
        val policy = CameraLinkOfferRetryPolicy(
            coldGroupSettleMs = 350L,
            intervalMs = 2_500L,
            maxOffers = 3,
        )

        assertEquals(350L, policy.initialDelayMs(groupCreated = true))
        assertEquals(0L, policy.initialDelayMs(groupCreated = false))

        repeat(3) { index ->
            val action = policy.nextAction()
            assertEquals(index + 1, action.offerNumber)
            assertEquals(2_500L, action.nextDelayMs)
            assertFalse(action.timedOut)
        }

        val timeout = policy.nextAction()
        assertNull(timeout.offerNumber)
        assertEquals(0L, timeout.nextDelayMs)
        assertTrue(timeout.timedOut)

        policy.reset()
        assertEquals(1, policy.nextAction().offerNumber)
    }
}
