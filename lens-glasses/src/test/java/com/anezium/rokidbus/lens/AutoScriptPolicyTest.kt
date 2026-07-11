package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScriptPolicyTest {
    @Test
    fun probeBecomesDueEveryThreeSecondsWhileLatin() {
        val first = AutoScriptPolicy.planLiveFrame(AutoScriptState(), nowMs = 1_000L)
        assertEquals(OcrScript.LATIN, first.script)
        assertFalse(first.isProbe)

        val early = AutoScriptPolicy.planLiveFrame(first.nextState, nowMs = 3_999L)
        assertFalse(early.isProbe)

        val due = AutoScriptPolicy.planLiveFrame(early.nextState, nowMs = 4_000L)
        assertTrue(due.isProbe)
        assertEquals(OcrScript.JAPANESE, due.script)

        val nextDue = AutoScriptPolicy.planLiveFrame(due.nextState, nowMs = 7_000L)
        assertTrue(nextDue.isProbe)
    }

    @Test
    fun cjkProbeSwitchesAtThirtyPercentWithEightCharacters() {
        val state = AutoScriptState(lastProbeAtMs = 0L)
        val result = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 3_000L,
            script = OcrScript.JAPANESE,
            isProbe = true,
            text = "日本語abcdefg",
        )

        assertTrue(result.acceptResult)
        assertTrue(result.switched)
        assertEquals(OcrScript.JAPANESE, result.effectiveScript)

        val tooShort = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 3_000L,
            script = OcrScript.JAPANESE,
            isProbe = true,
            text = "日本語abc",
        )
        assertFalse(tooShort.acceptResult)
    }

    @Test
    fun minimumDwellBlocksLatinToJapaneseSwitchAfterRecentRevert() {
        val state = AutoScriptState(
            effectiveScript = OcrScript.LATIN,
            lastProbeAtMs = 3_000L,
            lastSwitchAtMs = 1_000L,
        )
        val blocked = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 5_999L,
            script = OcrScript.JAPANESE,
            isProbe = true,
            text = "日本語abcdefg",
        )
        assertFalse(blocked.acceptResult)
        assertEquals(OcrScript.LATIN, blocked.effectiveScript)

        val allowed = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 6_000L,
            script = OcrScript.JAPANESE,
            isProbe = true,
            text = "日本語abcdefg",
        )
        assertTrue(allowed.acceptResult)
        assertEquals(OcrScript.JAPANESE, allowed.effectiveScript)
    }

    @Test
    fun japaneseRevertsAfterSixConsecutiveLowCjkFrames() {
        var state = AutoScriptState(
            effectiveScript = OcrScript.JAPANESE,
            lastSwitchAtMs = 0L,
        )
        repeat(AUTO_REVERT_CONSECUTIVE_FRAMES - 1) { index ->
            val observation = AutoScriptPolicy.observeLiveResult(
                state,
                nowMs = 5_000L + index,
                script = OcrScript.JAPANESE,
                isProbe = false,
                text = "plain latin words",
            )
            assertFalse(observation.switched)
            state = observation.nextState
        }
        val sixth = AutoScriptPolicy.observeLiveResult(
            state,
            nowMs = 5_100L,
            script = OcrScript.JAPANESE,
            isProbe = false,
            text = "still latin",
        )
        assertTrue(sixth.switched)
        assertEquals(OcrScript.LATIN, sixth.effectiveScript)
    }

    @Test
    fun dwellHysteresisBlocksRevertUntilFiveSeconds() {
        var state = AutoScriptState(
            effectiveScript = OcrScript.JAPANESE,
            lastSwitchAtMs = 1_000L,
        )
        repeat(AUTO_REVERT_CONSECUTIVE_FRAMES) {
            state = AutoScriptPolicy.observeLiveResult(
                state,
                nowMs = 2_000L + it,
                script = OcrScript.JAPANESE,
                isProbe = false,
                text = "latin only",
            ).nextState
        }
        assertEquals(OcrScript.JAPANESE, state.effectiveScript)

        val afterDwell = AutoScriptPolicy.observeLiveResult(
            state,
            nowMs = 6_000L,
            script = OcrScript.JAPANESE,
            isProbe = false,
            text = "latin only",
        )
        assertEquals(OcrScript.LATIN, afterDwell.effectiveScript)
    }

    @Test
    fun frozenLowTextRetriesOtherRecognizerOnlyOnceAndKeepsMoreText() {
        val initial = FrozenScriptState(primaryScript = OcrScript.LATIN)
        val primary = AutoScriptPolicy.observeFrozenResult(initial, OcrScript.LATIN, "short")
        assertEquals(OcrScript.JAPANESE, primary.retryScript)
        assertTrue(primary.nextState.otherRecognizerTried)

        val retry = AutoScriptPolicy.observeFrozenResult(
            primary.nextState,
            OcrScript.JAPANESE,
            "日本語の長い文章です",
        )
        assertNull(retry.retryScript)
        assertEquals(
            OcrScript.JAPANESE,
            AutoScriptPolicy.betterFrozenScript(
                OcrScript.LATIN,
                "short",
                OcrScript.JAPANESE,
                "日本語の長い文章です",
            ),
        )

        val laterLowResult = AutoScriptPolicy.observeFrozenResult(
            retry.nextState,
            OcrScript.LATIN,
            "tiny",
        )
        assertNull(laterLowResult.retryScript)
    }
}
