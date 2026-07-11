package com.anezium.rokidbus.lens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoScriptPolicyTest {
    @Test
    fun probeRotatesThroughOneNonLatinScriptEveryThreeSeconds() {
        var state = AutoScriptPolicy.planLiveFrame(AutoScriptState(), nowMs = 1_000L).also {
            assertEquals(OcrScript.LATIN, it.script)
            assertFalse(it.isProbe)
        }.nextState

        val early = AutoScriptPolicy.planLiveFrame(state, nowMs = 3_999L)
        assertFalse(early.isProbe)
        state = early.nextState

        val expected = listOf(
            OcrScript.JAPANESE,
            OcrScript.CHINESE,
            OcrScript.KOREAN,
            OcrScript.DEVANAGARI,
            OcrScript.JAPANESE,
        )
        expected.forEachIndexed { index, script ->
            val plan = AutoScriptPolicy.planLiveFrame(state, nowMs = 4_000L + index * 3_000L)
            assertTrue(plan.isProbe)
            assertEquals(script, plan.script)
            state = plan.nextState
        }
    }

    @Test
    fun everyNonLatinProbeCanWinTheSameCredibilityComparison() {
        val credibleText = mapOf(
            OcrScript.JAPANESE to "\u65e5\u672c\u8a9eabcdefg",
            OcrScript.CHINESE to "\u4e2d\u6587\u5b57abcdefg",
            OcrScript.KOREAN to "\ud55c\uad6d\uc5b4abcdefg",
            OcrScript.DEVANAGARI to "\u0915\u0916\u0917abcdefg",
        )

        credibleText.forEach { (script, text) ->
            val result = AutoScriptPolicy.observeLiveResult(
                state = AutoScriptState(lastProbeAtMs = 0L),
                nowMs = 3_000L,
                script = script,
                isProbe = true,
                text = text,
            )

            assertTrue(script.name, result.acceptResult)
            assertTrue(script.name, result.switched)
            assertEquals(script, result.effectiveScript)
        }

        val tooShort = AutoScriptPolicy.observeLiveResult(
            state = AutoScriptState(lastProbeAtMs = 0L),
            nowMs = 3_000L,
            script = OcrScript.CHINESE,
            isProbe = true,
            text = "\u4e2d\u6587\u5b57abc",
        )
        assertFalse(tooShort.acceptResult)
    }

    @Test
    fun minimumDwellBlocksLatinToNonLatinSwitchAfterRecentRevert() {
        val state = AutoScriptState(
            effectiveScript = OcrScript.LATIN,
            lastProbeAtMs = 3_000L,
            lastSwitchAtMs = 1_000L,
        )
        val blocked = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 5_999L,
            script = OcrScript.KOREAN,
            isProbe = true,
            text = "\ud55c\uad6d\ubb38\uc790\uc778\uc2dd\uc2dc\ud5d8",
        )
        assertFalse(blocked.acceptResult)
        assertEquals(OcrScript.LATIN, blocked.effectiveScript)

        val allowed = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 6_000L,
            script = OcrScript.KOREAN,
            isProbe = true,
            text = "\ud55c\uad6d\ubb38\uc790\uc778\uc2dd\uc2dc\ud5d8",
        )
        assertTrue(allowed.acceptResult)
        assertEquals(OcrScript.KOREAN, allowed.effectiveScript)
    }

    @Test
    fun everyNonLatinScriptFallsBackAfterSixLowScriptFrames() {
        NON_LATIN_OCR_SCRIPTS.forEach { script ->
            var state = AutoScriptState(
                effectiveScript = script,
                lastSwitchAtMs = 0L,
            )
            repeat(AUTO_REVERT_CONSECUTIVE_FRAMES - 1) { index ->
                val observation = AutoScriptPolicy.observeLiveResult(
                    state,
                    nowMs = 5_000L + index,
                    script = script,
                    isProbe = false,
                    text = "plain latin words",
                )
                assertFalse(script.name, observation.switched)
                state = observation.nextState
            }
            val sixth = AutoScriptPolicy.observeLiveResult(
                state,
                nowMs = 5_100L,
                script = script,
                isProbe = false,
                text = "still latin",
            )
            assertTrue(script.name, sixth.switched)
            assertEquals(OcrScript.LATIN, sixth.effectiveScript)
        }
    }

    @Test
    fun dwellHysteresisBlocksRevertUntilFiveSeconds() {
        var state = AutoScriptState(
            effectiveScript = OcrScript.DEVANAGARI,
            lastSwitchAtMs = 1_000L,
        )
        repeat(AUTO_REVERT_CONSECUTIVE_FRAMES) {
            state = AutoScriptPolicy.observeLiveResult(
                state,
                nowMs = 2_000L + it,
                script = OcrScript.DEVANAGARI,
                isProbe = false,
                text = "latin only",
            ).nextState
        }
        assertEquals(OcrScript.DEVANAGARI, state.effectiveScript)

        val afterDwell = AutoScriptPolicy.observeLiveResult(
            state,
            nowMs = 6_000L,
            script = OcrScript.DEVANAGARI,
            isProbe = false,
            text = "latin only",
        )
        assertEquals(OcrScript.LATIN, afterDwell.effectiveScript)
    }

    @Test
    fun frozenLatinPrimaryRetriesTheCurrentRotationScriptOnlyOnce() {
        NON_LATIN_OCR_SCRIPTS.forEach { retryScript ->
            val initial = FrozenScriptState(
                primaryScript = OcrScript.LATIN,
                retryScript = retryScript,
            )
            val primary = AutoScriptPolicy.observeFrozenResult(initial, OcrScript.LATIN, "short")
            assertEquals(retryScript, primary.retryScript)
            assertTrue(primary.nextState.otherRecognizerTried)

            val retry = AutoScriptPolicy.observeFrozenResult(
                primary.nextState,
                retryScript,
                "long enough retry text",
            )
            assertNull(retry.retryScript)

            val laterLowResult = AutoScriptPolicy.observeFrozenResult(
                retry.nextState,
                OcrScript.LATIN,
                "tiny",
            )
            assertNull(laterLowResult.retryScript)
        }
    }

    @Test
    fun frozenNonLatinPrimaryRetriesLatinAndKeepsTheResultWithMoreText() {
        NON_LATIN_OCR_SCRIPTS.forEach { primaryScript ->
            val initial = FrozenScriptState(primaryScript = primaryScript)
            val primary = AutoScriptPolicy.observeFrozenResult(initial, primaryScript, "short")
            assertEquals(OcrScript.LATIN, primary.retryScript)
            assertEquals(
                OcrScript.LATIN,
                AutoScriptPolicy.betterFrozenScript(
                    primaryScript,
                    "short",
                    OcrScript.LATIN,
                    "a much longer latin retry result",
                ),
            )
        }
    }
}
