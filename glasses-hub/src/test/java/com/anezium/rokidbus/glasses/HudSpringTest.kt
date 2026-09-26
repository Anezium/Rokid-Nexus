package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class HudSpringTest {
    private val frame = 1f / 60f

    /** Steps until settled; returns every value on the way and the time taken. */
    private fun settle(value: HudSpringValue, spring: HudSpring, maxSec: Float = 3f): Pair<List<Float>, Float> {
        val trace = mutableListOf(value.value)
        var elapsed = 0f
        while (elapsed < maxSec && !value.isSettled()) {
            value.step(frame, spring)
            trace += value.value
            elapsed += frame
        }
        return trace to elapsed
    }

    private fun overshoot(spring: HudSpring): Float {
        val value = HudSpringValue(0f).apply { target = 100f }
        val (trace, _) = settle(value, spring)
        return (trace.max() - 100f).coerceAtLeast(0f) / 100f
    }

    @Test
    fun `every spring settles on its target within about a second`() {
        listOf(HudSpring.STANDARD, HudSpring.BEAT, HudSpring.EXIT).forEach { spring ->
            val value = HudSpringValue(0f).apply { target = 300f }
            val (_, elapsed) = settle(value, spring)

            assertTrue("$spring took $elapsed s", elapsed < 1.2f)
            assertTrue(value.isSettled())
        }
    }

    @Test
    fun `the standard morph barely overshoots, a beat visibly wobbles, exits never do`() {
        assertTrue(overshoot(HudSpring.STANDARD) in 0.005f..0.04f)
        assertTrue(overshoot(HudSpring.BEAT) > 0.1f)
        assertEquals(0f, overshoot(HudSpring.EXIT), 0.001f)
    }

    @Test
    fun `retargeting mid-motion continues from where the value is, without a jump`() {
        val value = HudSpringValue(0f).apply { target = 200f }
        repeat(8) { value.step(frame, HudSpring.STANDARD) }
        val before = value.value
        val velocityBefore = value.velocity

        value.target = -50f
        value.step(frame, HudSpring.STANDARD)

        assertTrue(abs(value.value - before) < abs(velocityBefore) * frame * 1.5f + 1f)
        assertTrue("still moving the old way for a moment", value.value > before)
        settle(value, HudSpring.STANDARD)
        assertEquals(-50f, value.value, 0.5f)
    }

    @Test
    fun `a long dropped frame does not blow the spring up`() {
        val value = HudSpringValue(0f).apply { target = 100f }
        value.step(0.25f, HudSpring.BEAT)

        assertTrue(value.value.isFinite())
        assertTrue(value.value in 0f..140f)
    }
}
