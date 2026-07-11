package com.anezium.rokidbus.lens

internal const val AUTO_PROBE_INTERVAL_MS = 3_000L
internal const val AUTO_REVERT_CONSECUTIVE_FRAMES = 6
internal const val AUTO_MIN_DWELL_MS = 5_000L
internal const val AUTO_BURST_TRIGGER_FRAMES = 2
internal const val AUTO_BURST_COOLDOWN_MS = 10_000L

internal enum class OcrScript {
    LATIN,
    JAPANESE,
    CHINESE,
    KOREAN,
    DEVANAGARI,
}

internal val NON_LATIN_OCR_SCRIPTS = listOf(
    OcrScript.JAPANESE,
    OcrScript.CHINESE,
    OcrScript.KOREAN,
    OcrScript.DEVANAGARI,
)

private val OCR_SCRIPT_ROTATION = OcrScript.entries.toList()

internal data class AutoScriptState(
    val effectiveScript: OcrScript = OcrScript.LATIN,
    val nextProbeScript: OcrScript = OcrScript.JAPANESE,
    val lastProbeAtMs: Long? = null,
    val lastSwitchAtMs: Long? = null,
    val consecutiveLowScriptFrames: Int = 0,
    val consecutiveLowLatinFrames: Int = 0,
    val burstProbesRemaining: Int = 0,
    val burstCooldownUntilMs: Long? = null,
)

internal data class LiveScriptPlan(
    val script: OcrScript,
    val isProbe: Boolean,
    val isBurstProbe: Boolean = false,
    val nextState: AutoScriptState,
)

internal data class LiveScriptObservation(
    val effectiveScript: OcrScript,
    val acceptResult: Boolean,
    val switched: Boolean,
    val nextState: AutoScriptState,
)

internal data class ScriptTextStats(
    val nonSpaceCharacters: Int,
    val scriptCharacters: Int,
) {
    val scriptShare: Double
        get() = if (nonSpaceCharacters == 0) 0.0 else scriptCharacters.toDouble() / nonSpaceCharacters
}

internal data class FrozenScriptState(
    val primaryScript: OcrScript,
    val retryScript: OcrScript = if (primaryScript == OcrScript.LATIN) {
        OcrScript.JAPANESE
    } else {
        OcrScript.LATIN
    },
    val attemptedScripts: List<OcrScript> = emptyList(),
    val bestScript: OcrScript? = null,
    val bestNonSpaceCharacters: Int = -1,
    val winnerScript: OcrScript? = null,
)

internal data class FrozenScriptResolution(
    val winnerScript: OcrScript?,
    val nextState: FrozenScriptState,
)

/** Pure state machine for serial AUTO recognizer selection. */
internal object AutoScriptPolicy {
    fun planLiveFrame(state: AutoScriptState, nowMs: Long): LiveScriptPlan {
        require(nowMs >= 0L)
        if (state.effectiveScript != OcrScript.LATIN) {
            return LiveScriptPlan(
                script = state.effectiveScript,
                isProbe = false,
                nextState = state,
            )
        }

        val cooldownActive = state.burstCooldownUntilMs?.let { nowMs < it } == true
        if (state.burstProbesRemaining > 0 ||
            (!cooldownActive && state.consecutiveLowLatinFrames >= AUTO_BURST_TRIGGER_FRAMES)
        ) {
            val burstState = if (state.burstProbesRemaining > 0) {
                state
            } else {
                state.copy(
                    burstProbesRemaining = NON_LATIN_OCR_SCRIPTS.size,
                    burstCooldownUntilMs = null,
                )
            }
            return planBurstProbe(burstState, nowMs)
        }

        val lastProbeAtMs = state.lastProbeAtMs
        if (lastProbeAtMs == null) {
            return LiveScriptPlan(
                script = OcrScript.LATIN,
                isProbe = false,
                nextState = state.copy(lastProbeAtMs = nowMs),
            )
        }
        val probeDue = nowMs < lastProbeAtMs || nowMs - lastProbeAtMs >= AUTO_PROBE_INTERVAL_MS
        if (!probeDue) {
            return LiveScriptPlan(OcrScript.LATIN, isProbe = false, nextState = state)
        }

        val probeScript = validNonLatinProbe(state.nextProbeScript)
        return LiveScriptPlan(
            script = probeScript,
            isProbe = true,
            nextState = state.copy(
                nextProbeScript = nextNonLatinScript(probeScript),
                lastProbeAtMs = nowMs,
            ),
        )
    }

    fun observeLiveResult(
        state: AutoScriptState,
        nowMs: Long,
        script: OcrScript,
        isProbe: Boolean,
        isBurstProbe: Boolean = false,
        text: String,
    ): LiveScriptObservation {
        require(!isBurstProbe || isProbe)
        val stats = textStats(text, script)
        if (isProbe) {
            val shouldSwitch = state.effectiveScript == OcrScript.LATIN &&
                script != OcrScript.LATIN &&
                (isBurstProbe || dwellComplete(state, nowMs)) &&
                stats.nonSpaceCharacters >= 8 &&
                stats.scriptShare >= 0.30
            val nextState = if (shouldSwitch) {
                state.copy(
                    effectiveScript = script,
                    nextProbeScript = script,
                    lastSwitchAtMs = nowMs,
                    consecutiveLowScriptFrames = 0,
                    consecutiveLowLatinFrames = 0,
                    burstProbesRemaining = 0,
                    burstCooldownUntilMs = null,
                )
            } else {
                state
            }
            return LiveScriptObservation(
                effectiveScript = nextState.effectiveScript,
                acceptResult = shouldSwitch,
                switched = shouldSwitch,
                nextState = nextState,
            )
        }

        if (state.effectiveScript == OcrScript.LATIN) {
            val hasLatinText = stats.nonSpaceCharacters >= 8
            val nextState = state.copy(
                consecutiveLowLatinFrames = if (hasLatinText) {
                    0
                } else {
                    (state.consecutiveLowLatinFrames + 1).coerceAtMost(AUTO_BURST_TRIGGER_FRAMES)
                },
                burstCooldownUntilMs = if (hasLatinText) null else state.burstCooldownUntilMs,
            )
            return LiveScriptObservation(
                effectiveScript = OcrScript.LATIN,
                acceptResult = true,
                switched = false,
                nextState = nextState,
            )
        }

        if (script != state.effectiveScript) {
            return LiveScriptObservation(state.effectiveScript, acceptResult = true, switched = false, state)
        }

        val lowCount = if (stats.scriptShare < 0.05) state.consecutiveLowScriptFrames + 1 else 0
        val shouldRevert = lowCount >= AUTO_REVERT_CONSECUTIVE_FRAMES && dwellComplete(state, nowMs)
        val nextState = if (shouldRevert) {
            state.copy(
                effectiveScript = OcrScript.LATIN,
                nextProbeScript = state.effectiveScript,
                lastProbeAtMs = nowMs,
                lastSwitchAtMs = nowMs,
                consecutiveLowScriptFrames = 0,
                consecutiveLowLatinFrames = 0,
                burstProbesRemaining = 0,
                burstCooldownUntilMs = null,
            )
        } else {
            state.copy(consecutiveLowScriptFrames = lowCount.coerceAtMost(AUTO_REVERT_CONSECUTIVE_FRAMES))
        }
        return LiveScriptObservation(
            effectiveScript = nextState.effectiveScript,
            acceptResult = true,
            switched = shouldRevert,
            nextState = nextState,
        )
    }

    fun seedLiveState(state: AutoScriptState, script: OcrScript, nowMs: Long): AutoScriptState {
        require(nowMs >= 0L)
        return state.copy(
            effectiveScript = script,
            nextProbeScript = if (script == OcrScript.LATIN) state.nextProbeScript else script,
            lastSwitchAtMs = nowMs,
            consecutiveLowScriptFrames = 0,
            consecutiveLowLatinFrames = 0,
            burstProbesRemaining = 0,
            burstCooldownUntilMs = null,
        )
    }

    fun biasNextProbe(state: AutoScriptState, script: OcrScript?): AutoScriptState =
        if (script != null && script != OcrScript.LATIN) {
            state.copy(nextProbeScript = script)
        } else {
            state
        }

    fun planFrozenRecognition(state: FrozenScriptState): OcrScript? {
        if (state.winnerScript != null) return null
        if (state.attemptedScripts.isEmpty()) return state.primaryScript
        return frozenSweepOrder(state).firstOrNull { it !in state.attemptedScripts }
    }

    fun resolveFrozenResult(
        state: FrozenScriptState,
        script: OcrScript,
        text: String,
    ): FrozenScriptResolution {
        require(state.winnerScript == null)
        require(script == planFrozenRecognition(state))
        val stats = textStats(text, script)
        val isPrimary = state.attemptedScripts.isEmpty()
        val isBetter = state.bestScript == null || stats.nonSpaceCharacters > state.bestNonSpaceCharacters
        val bestScript = if (isBetter) script else state.bestScript
        val bestCharacters = if (isBetter) stats.nonSpaceCharacters else state.bestNonSpaceCharacters
        val attempted = state.attemptedScripts + script
        val primaryComplete = isPrimary && stats.nonSpaceCharacters >= 8
        val credibleSweepResult = !isPrimary &&
            stats.nonSpaceCharacters >= 8 &&
            stats.scriptShare >= 0.30
        val sweepComplete = attempted.size == OCR_SCRIPT_ROTATION.size
        val winner = when {
            primaryComplete || credibleSweepResult -> script
            sweepComplete -> bestScript
            else -> null
        }
        val nextState = state.copy(
            attemptedScripts = attempted,
            bestScript = bestScript,
            bestNonSpaceCharacters = bestCharacters,
            winnerScript = winner,
        )
        return FrozenScriptResolution(winnerScript = winner, nextState = nextState)
    }

    fun textStats(text: String, script: OcrScript): ScriptTextStats {
        var nonSpace = 0
        var scriptCharacters = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) continue
            nonSpace += 1
            if (matchesScript(Character.UnicodeScript.of(codePoint), script)) scriptCharacters += 1
        }
        return ScriptTextStats(nonSpaceCharacters = nonSpace, scriptCharacters = scriptCharacters)
    }

    private fun planBurstProbe(state: AutoScriptState, nowMs: Long): LiveScriptPlan {
        val probeScript = validNonLatinProbe(state.nextProbeScript)
        val remaining = (state.burstProbesRemaining - 1).coerceAtLeast(0)
        return LiveScriptPlan(
            script = probeScript,
            isProbe = true,
            isBurstProbe = true,
            nextState = state.copy(
                nextProbeScript = nextNonLatinScript(probeScript),
                lastProbeAtMs = nowMs,
                burstProbesRemaining = remaining,
                burstCooldownUntilMs = if (remaining == 0) {
                    nowMs + AUTO_BURST_COOLDOWN_MS
                } else {
                    state.burstCooldownUntilMs
                },
            ),
        )
    }

    private fun frozenSweepOrder(state: FrozenScriptState): List<OcrScript> {
        val start = OCR_SCRIPT_ROTATION.indexOf(state.retryScript).takeIf { it >= 0 } ?: 0
        return OCR_SCRIPT_ROTATION.indices
            .map { offset -> OCR_SCRIPT_ROTATION[(start + offset).mod(OCR_SCRIPT_ROTATION.size)] }
            .filter { it != state.primaryScript }
    }

    private fun matchesScript(unicodeScript: Character.UnicodeScript, script: OcrScript): Boolean =
        when (script) {
            OcrScript.LATIN -> unicodeScript == Character.UnicodeScript.LATIN
            OcrScript.JAPANESE -> unicodeScript == Character.UnicodeScript.HAN ||
                unicodeScript == Character.UnicodeScript.HIRAGANA ||
                unicodeScript == Character.UnicodeScript.KATAKANA
            OcrScript.CHINESE -> unicodeScript == Character.UnicodeScript.HAN
            OcrScript.KOREAN -> unicodeScript == Character.UnicodeScript.HANGUL
            OcrScript.DEVANAGARI -> unicodeScript == Character.UnicodeScript.DEVANAGARI
        }

    private fun validNonLatinProbe(script: OcrScript): OcrScript =
        script.takeIf { it in NON_LATIN_OCR_SCRIPTS } ?: OcrScript.JAPANESE

    private fun nextNonLatinScript(script: OcrScript): OcrScript {
        val index = NON_LATIN_OCR_SCRIPTS.indexOf(script)
        return NON_LATIN_OCR_SCRIPTS[(index + 1).mod(NON_LATIN_OCR_SCRIPTS.size)]
    }

    private fun dwellComplete(state: AutoScriptState, nowMs: Long): Boolean {
        val lastSwitchAtMs = state.lastSwitchAtMs ?: return true
        return nowMs < lastSwitchAtMs || nowMs - lastSwitchAtMs >= AUTO_MIN_DWELL_MS
    }
}
