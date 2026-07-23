package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RingSurfaceInputPolicyTest {
    @Test
    fun `forward swipe maps to one complete temple right pair`() {
        val policy = RingSurfaceInputPolicy()

        assertEquals(
            forwardPair(RingSurfaceInputPolicy.KEYCODE_DPAD_RIGHT),
            policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_FORWARD, 1_000L),
        )
    }

    @Test
    fun `backward swipe maps to one complete temple left pair`() {
        val policy = RingSurfaceInputPolicy()

        assertEquals(
            forwardPair(RingSurfaceInputPolicy.KEYCODE_DPAD_LEFT),
            policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_BACKWARD, 1_000L),
        )
    }

    @Test
    fun `single tap maps to one complete temple enter pair after the tap window`() {
        val policy = RingSurfaceInputPolicy()

        assertNull(policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 2_000L))
        assertNull(policy.resolveExpired(2_350L))
        assertEquals(
            forwardPair(RingSurfaceInputPolicy.KEYCODE_ENTER),
            policy.resolveExpired(2_351L),
        )
    }

    @Test
    fun `double tap maps to back after the final tap window`() {
        val policy = RingSurfaceInputPolicy()

        policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 3_000L)
        policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 3_200L)

        assertNull(policy.resolveExpired(3_550L))
        assertEquals(RingSurfaceInputPolicy.Resolution.Back, policy.resolveExpired(3_551L))
    }

    @Test
    fun `reset prevents a pending tap from reaching a later surface`() {
        val policy = RingSurfaceInputPolicy()

        policy.onKeyDown(RingSurfaceInputPolicy.RING_KEYCODE_TAP, 4_000L)
        policy.reset()

        assertNull(policy.resolveExpired(4_351L))
    }

    @Test
    fun `unknown ring key is not translated`() {
        assertNull(RingSurfaceInputPolicy().onKeyDown(999, 5_000L))
    }

    private fun forwardPair(keyCode: Int) =
        RingSurfaceInputPolicy.Resolution.Forward(
            listOf(
                RingSurfaceInputPolicy.MappedKeyEvent(
                    keyCode,
                    RingSurfaceInputPolicy.ACTION_DOWN,
                ),
                RingSurfaceInputPolicy.MappedKeyEvent(
                    keyCode,
                    RingSurfaceInputPolicy.ACTION_UP,
                ),
            ),
        )
}
