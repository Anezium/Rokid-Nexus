package com.anezium.rokidbus.plugin.agents

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentsFreezeWatchTest {
    private val interval = 60_000L

    @Test
    fun `ordinary lateness is not evidence of a freeze`() {
        // A tick that is early, exact, or merely late still ran.
        assertFalse(AgentsFreezeWatch.wasSuspended(interval, interval / 2))
        assertFalse(AgentsFreezeWatch.wasSuspended(interval, interval))
        assertFalse(AgentsFreezeWatch.wasSuspended(interval, interval * 2))
        // The boundary itself is still explainable as a slow device.
        assertFalse(
            AgentsFreezeWatch.wasSuspended(interval, interval * AgentsFreezeWatch.TOLERANCE_FACTOR),
        )
    }

    @Test
    fun `a gap past the tolerance is reported`() {
        assertTrue(
            AgentsFreezeWatch.wasSuspended(
                interval,
                interval * AgentsFreezeWatch.TOLERANCE_FACTOR + 1,
            ),
        )
        // What the frozen device actually produced: a minute's tick, twenty
        // minutes late.
        assertTrue(AgentsFreezeWatch.wasSuspended(interval, 20 * 60_000L))
    }

    @Test
    fun `a gap that cannot be measured accuses nobody`() {
        // No previous tick, a clock that went backwards, or a nonsense interval
        // are all absence of evidence rather than evidence of a freeze.
        assertFalse(AgentsFreezeWatch.wasSuspended(interval, 0L))
        assertFalse(AgentsFreezeWatch.wasSuspended(interval, -1L))
        assertFalse(AgentsFreezeWatch.wasSuspended(0L, 20 * 60_000L))
        assertFalse(AgentsFreezeWatch.wasSuspended(-1L, 20 * 60_000L))
    }
}
