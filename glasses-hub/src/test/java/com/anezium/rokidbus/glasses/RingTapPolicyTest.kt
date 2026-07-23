package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingTapPolicyTest {
    @Test
    fun `single tap resolves after the window expires`() {
        val policy = RingTapPolicy()

        policy.onTap(1_000L)

        assertNull(policy.resolveExpired(1_350L))
        assertEquals(RingTapPolicy.Resolution.SINGLE, policy.resolveExpired(1_351L))
        assertNull(policy.resolveExpired(1_352L))
    }

    @Test
    fun `double tap resolves after the final tap window expires`() {
        val policy = RingTapPolicy()

        policy.onTap(2_000L)
        policy.onTap(2_200L)

        assertNull(policy.resolveExpired(2_550L))
        assertEquals(RingTapPolicy.Resolution.DOUBLE, policy.resolveExpired(2_551L))
    }

    @Test
    fun `three or more taps resolve to no launcher action`() {
        val policy = RingTapPolicy()

        policy.onTap(3_000L)
        policy.onTap(3_100L)
        policy.onTap(3_200L)

        assertEquals(RingTapPolicy.Resolution.IGNORE, policy.resolveExpired(3_551L))
        assertNull(policy.resolveExpired(3_552L))
    }

    @Test
    fun `a resolved window does not carry taps into the next window`() {
        val policy = RingTapPolicy()

        policy.onTap(4_000L)
        assertEquals(RingTapPolicy.Resolution.SINGLE, policy.resolveExpired(4_351L))

        policy.onTap(5_000L)
        policy.onTap(5_100L)
        assertEquals(RingTapPolicy.Resolution.DOUBLE, policy.resolveExpired(5_451L))
    }
}
