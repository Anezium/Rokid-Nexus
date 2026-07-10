package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrCadencePolicyTest {
    @Test
    fun firstFrameStartsImmediately() {
        val decision = evaluate(OcrCadenceState(), nowMs = 1_000L)

        assertTrue(decision.shouldStart)
        assertEquals(1_000L, decision.nextState.lastStartMs)
    }

    @Test
    fun frameOneMillisecondBeforeBoundaryIsRejected() {
        val state = OcrCadenceState(lastStartMs = 1_000L)

        val decision = evaluate(state, nowMs = 1_299L)

        assertFalse(decision.shouldStart)
        assertEquals(state, decision.nextState)
    }

    @Test
    fun frameExactlyAtBoundaryStarts() {
        val decision = evaluate(
            state = OcrCadenceState(lastStartMs = 1_000L),
            nowMs = 1_300L,
        )

        assertTrue(decision.shouldStart)
        assertEquals(1_300L, decision.nextState.lastStartMs)
    }

    @Test
    fun rejectedFramesDoNotPostponeNextStart() {
        val firstRejection = evaluate(
            state = OcrCadenceState(lastStartMs = 1_000L),
            nowMs = 1_200L,
        )
        val secondRejection = evaluate(firstRejection.nextState, nowMs = 1_299L)
        val boundary = evaluate(secondRejection.nextState, nowMs = 1_300L)

        assertFalse(firstRejection.shouldStart)
        assertFalse(secondRejection.shouldStart)
        assertTrue(boundary.shouldStart)
    }

    @Test
    fun resetStateMakesNextFrameImmediate() {
        val started = evaluate(OcrCadenceState(), nowMs = 1_000L)
        val reset = OcrCadenceState()
        val afterReset = evaluate(reset, nowMs = 1_001L)

        assertTrue(started.shouldStart)
        assertTrue(afterReset.shouldStart)
        assertEquals(1_001L, afterReset.nextState.lastStartMs)
    }

    @Test
    fun monotonicClockResetRecoversImmediately() {
        val decision = evaluate(
            state = OcrCadenceState(lastStartMs = 1_000L),
            nowMs = 900L,
        )

        assertTrue(decision.shouldStart)
        assertEquals(900L, decision.nextState.lastStartMs)
    }

    private fun evaluate(state: OcrCadenceState, nowMs: Long): OcrCadenceDecision =
        OcrCadencePolicy.evaluate(
            state = state,
            nowMs = nowMs,
            minIntervalMs = 300L,
        )
}
