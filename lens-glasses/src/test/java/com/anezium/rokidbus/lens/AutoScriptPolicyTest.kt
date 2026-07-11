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
            assertFalse(plan.isBurstProbe)
            assertEquals(script, plan.script)
            state = plan.nextState
        }
    }

    @Test
    fun burstStartsOnFrameAfterSecondAcceptedEmptyLatinFrame() {
        var state = AutoScriptState(lastProbeAtMs = 0L)
        repeat(AUTO_BURST_TRIGGER_FRAMES) { index ->
            val plan = AutoScriptPolicy.planLiveFrame(state, nowMs = 100L + index)
            assertEquals(OcrScript.LATIN, plan.script)
            assertFalse(plan.isProbe)
            state = AutoScriptPolicy.observeLiveResult(
                state = plan.nextState,
                nowMs = 100L + index,
                script = plan.script,
                isProbe = plan.isProbe,
                isBurstProbe = plan.isBurstProbe,
                text = "tiny",
            ).nextState
        }

        val burst = AutoScriptPolicy.planLiveFrame(state, nowMs = 102L)
        assertTrue(burst.isProbe)
        assertTrue(burst.isBurstProbe)
        assertEquals(OcrScript.JAPANESE, burst.script)
    }

    @Test
    fun burstProbeIgnoresDwellButOrdinaryProbeKeepsIt() {
        val recentRevert = AutoScriptState(
            lastProbeAtMs = 0L,
            lastSwitchAtMs = 1_000L,
            consecutiveLowLatinFrames = AUTO_BURST_TRIGGER_FRAMES,
        )
        val burst = AutoScriptPolicy.planLiveFrame(recentRevert, nowMs = 2_000L)
        assertTrue(burst.isBurstProbe)
        val burstWinner = AutoScriptPolicy.observeLiveResult(
            state = burst.nextState,
            nowMs = 2_000L,
            script = burst.script,
            isProbe = true,
            isBurstProbe = true,
            text = "\u65e5\u672c\u8a9e\u6587\u5b57\u5217abc",
        )
        assertTrue(burstWinner.switched)

        val ordinary = AutoScriptPolicy.observeLiveResult(
            state = recentRevert.copy(consecutiveLowLatinFrames = 0),
            nowMs = 2_000L,
            script = OcrScript.JAPANESE,
            isProbe = true,
            isBurstProbe = false,
            text = "\u65e5\u672c\u8a9e\u6587\u5b57\u5217abc",
        )
        assertFalse(ordinary.switched)
    }

    @Test
    fun fruitlessBurstWalksEveryNonLatinScriptOnceThenCoolsDown() {
        var state = AutoScriptState(
            nextProbeScript = OcrScript.KOREAN,
            lastProbeAtMs = 0L,
            consecutiveLowLatinFrames = AUTO_BURST_TRIGGER_FRAMES,
        )
        val scripts = mutableListOf<OcrScript>()
        repeat(NON_LATIN_OCR_SCRIPTS.size) { index ->
            val plan = AutoScriptPolicy.planLiveFrame(state, nowMs = 100L + index)
            assertTrue(plan.isBurstProbe)
            scripts += plan.script
            state = AutoScriptPolicy.observeLiveResult(
                state = plan.nextState,
                nowMs = 100L + index,
                script = plan.script,
                isProbe = true,
                isBurstProbe = true,
                text = "",
            ).nextState
        }
        assertEquals(
            listOf(OcrScript.KOREAN, OcrScript.DEVANAGARI, OcrScript.JAPANESE, OcrScript.CHINESE),
            scripts,
        )

        val cooldownFrame = AutoScriptPolicy.planLiveFrame(state, nowMs = 200L)
        assertFalse(cooldownFrame.isBurstProbe)
        assertEquals(OcrScript.LATIN, cooldownFrame.script)

        state = AutoScriptPolicy.observeLiveResult(
            state = cooldownFrame.nextState,
            nowMs = 200L,
            script = OcrScript.LATIN,
            isProbe = false,
            isBurstProbe = false,
            text = "readable latin text",
        ).nextState
        repeat(AUTO_BURST_TRIGGER_FRAMES) { index ->
            val latin = AutoScriptPolicy.planLiveFrame(state, nowMs = 300L + index)
            state = AutoScriptPolicy.observeLiveResult(
                state = latin.nextState,
                nowMs = 300L + index,
                script = OcrScript.LATIN,
                isProbe = false,
                isBurstProbe = false,
                text = "tiny",
            ).nextState
        }
        assertTrue(AutoScriptPolicy.planLiveFrame(state, nowMs = 400L).isBurstProbe)
    }

    @Test
    fun fruitlessBurstCanRunAgainAfterCooldownExpires() {
        var state = completeFruitlessBurst(nowMs = 1_000L)
        val beforeExpiry = AutoScriptPolicy.planLiveFrame(
            state.copy(lastProbeAtMs = 9_000L),
            nowMs = 10_999L,
        )
        assertFalse(beforeExpiry.isBurstProbe)

        state = beforeExpiry.nextState
        val afterExpiry = AutoScriptPolicy.planLiveFrame(state, nowMs = 11_003L)
        assertTrue(afterExpiry.isBurstProbe)
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
                isBurstProbe = false,
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
            isBurstProbe = false,
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
            isBurstProbe = false,
            text = "\ud55c\uad6d\ubb38\uc790\uc778\uc2dd\uc2dc\ud5d8",
        )
        assertFalse(blocked.acceptResult)
        assertEquals(OcrScript.LATIN, blocked.effectiveScript)

        val allowed = AutoScriptPolicy.observeLiveResult(
            state = state,
            nowMs = 6_000L,
            script = OcrScript.KOREAN,
            isProbe = true,
            isBurstProbe = false,
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
                    isBurstProbe = false,
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
                isBurstProbe = false,
                text = "still latin",
            )
            assertTrue(script.name, sixth.switched)
            assertEquals(OcrScript.LATIN, sixth.effectiveScript)
            assertEquals(script, sixth.nextState.nextProbeScript)
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
                isBurstProbe = false,
                text = "latin only",
            ).nextState
        }
        assertEquals(OcrScript.DEVANAGARI, state.effectiveScript)

        val afterDwell = AutoScriptPolicy.observeLiveResult(
            state,
            nowMs = 6_000L,
            script = OcrScript.DEVANAGARI,
            isProbe = false,
            isBurstProbe = false,
            text = "latin only",
        )
        assertEquals(OcrScript.LATIN, afterDwell.effectiveScript)
    }

    @Test
    fun frozenSweepEarlyExitsOnFirstCredibleScript() {
        var state = FrozenScriptState(
            primaryScript = OcrScript.LATIN,
            retryScript = OcrScript.KOREAN,
        )
        state = resolveFrozen(state, OcrScript.LATIN, "short").nextState
        state = resolveFrozen(state, OcrScript.KOREAN, "latin noise only").nextState

        val devanagari = resolveFrozen(state, OcrScript.DEVANAGARI, "\u0915\u0916\u0917\u0918\u0919\u091a\u091b\u091c")
        assertEquals(OcrScript.DEVANAGARI, devanagari.winnerScript)
        assertNull(AutoScriptPolicy.planFrozenRecognition(devanagari.nextState))
        assertEquals(listOf(OcrScript.LATIN, OcrScript.KOREAN, OcrScript.DEVANAGARI), devanagari.nextState.attemptedScripts)
    }

    @Test
    fun frozenPrimaryWithEnoughTextDoesNotStartSweep() {
        val initial = FrozenScriptState(
            primaryScript = OcrScript.KOREAN,
            retryScript = OcrScript.LATIN,
        )
        val result = resolveFrozen(initial, OcrScript.KOREAN, "readable primary text")
        assertEquals(OcrScript.KOREAN, result.winnerScript)
        assertNull(AutoScriptPolicy.planFrozenRecognition(result.nextState))
    }

    @Test
    fun frozenSweepFinishesAndKeepsTheLargestResult() {
        var state = FrozenScriptState(
            primaryScript = OcrScript.LATIN,
            retryScript = OcrScript.CHINESE,
        )
        val texts = mapOf(
            OcrScript.LATIN to "one",
            OcrScript.CHINESE to "latin-noise",
            OcrScript.KOREAN to "four",
            OcrScript.DEVANAGARI to "five5",
            OcrScript.JAPANESE to "six666",
        )
        while (true) {
            val script = AutoScriptPolicy.planFrozenRecognition(state) ?: break
            val resolution = AutoScriptPolicy.resolveFrozenResult(state, script, texts.getValue(script))
            state = resolution.nextState
            if (resolution.winnerScript != null) break
        }

        assertEquals(OcrScript.CHINESE, state.winnerScript)
        assertEquals(
            listOf(
                OcrScript.LATIN,
                OcrScript.CHINESE,
                OcrScript.KOREAN,
                OcrScript.DEVANAGARI,
                OcrScript.JAPANESE,
            ),
            state.attemptedScripts,
        )
    }

    @Test
    fun frozenSweepTieKeepsEarlierResult() {
        var state = FrozenScriptState(
            primaryScript = OcrScript.LATIN,
            retryScript = OcrScript.KOREAN,
        )
        val texts = mapOf(
            OcrScript.LATIN to "one",
            OcrScript.KOREAN to "1234567",
            OcrScript.DEVANAGARI to "7654321",
            OcrScript.JAPANESE to "12",
            OcrScript.CHINESE to "1",
        )
        while (true) {
            val script = AutoScriptPolicy.planFrozenRecognition(state) ?: break
            val resolution = AutoScriptPolicy.resolveFrozenResult(state, script, texts.getValue(script))
            state = resolution.nextState
            if (resolution.winnerScript != null) break
        }
        assertEquals(OcrScript.KOREAN, state.winnerScript)
    }

    @Test
    fun nonLatinFrozenWinnerSeedsLiveStateOnUnfreeze() {
        val seeded = AutoScriptPolicy.seedLiveState(
            state = AutoScriptState(
                effectiveScript = OcrScript.LATIN,
                burstProbesRemaining = 2,
                burstCooldownUntilMs = 20_000L,
            ),
            script = OcrScript.KOREAN,
            nowMs = 5_000L,
        )
        assertEquals(OcrScript.KOREAN, seeded.effectiveScript)
        assertEquals(OcrScript.KOREAN, seeded.nextProbeScript)
        assertEquals(5_000L, seeded.lastSwitchAtMs)
        assertEquals(0, seeded.burstProbesRemaining)
        assertNull(seeded.burstCooldownUntilMs)
    }

    @Test
    fun lastNonLatinScriptBiasesTheNextBurstFirst() {
        var state = AutoScriptPolicy.seedLiveState(
            state = AutoScriptState(),
            script = OcrScript.KOREAN,
            nowMs = 0L,
        )
        repeat(AUTO_REVERT_CONSECUTIVE_FRAMES) { index ->
            state = AutoScriptPolicy.observeLiveResult(
                state = state,
                nowMs = AUTO_MIN_DWELL_MS + index,
                script = OcrScript.KOREAN,
                isProbe = false,
                isBurstProbe = false,
                text = "plain latin",
            ).nextState
        }
        repeat(AUTO_BURST_TRIGGER_FRAMES) { index ->
            state = AutoScriptPolicy.observeLiveResult(
                state = state,
                nowMs = 6_000L + index,
                script = OcrScript.LATIN,
                isProbe = false,
                isBurstProbe = false,
                text = "tiny",
            ).nextState
        }
        val burst = AutoScriptPolicy.planLiveFrame(state, nowMs = 7_000L)
        assertTrue(burst.isBurstProbe)
        assertEquals(OcrScript.KOREAN, burst.script)
    }

    private fun completeFruitlessBurst(nowMs: Long): AutoScriptState {
        var state = AutoScriptState(
            lastProbeAtMs = 0L,
            consecutiveLowLatinFrames = AUTO_BURST_TRIGGER_FRAMES,
        )
        repeat(NON_LATIN_OCR_SCRIPTS.size) { index ->
            val time = nowMs + index
            val plan = AutoScriptPolicy.planLiveFrame(state, time)
            state = AutoScriptPolicy.observeLiveResult(
                state = plan.nextState,
                nowMs = time,
                script = plan.script,
                isProbe = true,
                isBurstProbe = true,
                text = "",
            ).nextState
        }
        return state
    }

    private fun resolveFrozen(
        state: FrozenScriptState,
        expectedScript: OcrScript,
        text: String,
    ): FrozenScriptResolution {
        val script = AutoScriptPolicy.planFrozenRecognition(state)
        assertEquals(expectedScript, script)
        return AutoScriptPolicy.resolveFrozenResult(state, expectedScript, text)
    }
}
