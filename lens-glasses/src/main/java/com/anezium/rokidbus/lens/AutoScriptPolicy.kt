package com.anezium.rokidbus.lens

internal const val AUTO_PROBE_INTERVAL_MS = 3_000L
internal const val AUTO_REVERT_CONSECUTIVE_FRAMES = 6
internal const val AUTO_MIN_DWELL_MS = 5_000L

internal enum class OcrScript {
    LATIN,
    JAPANESE;

    fun other(): OcrScript = if (this == LATIN) JAPANESE else LATIN
}

internal data class AutoScriptState(
    val effectiveScript: OcrScript = OcrScript.LATIN,
    val lastProbeAtMs: Long? = null,
    val lastSwitchAtMs: Long? = null,
    val consecutiveLowCjkFrames: Int = 0,
)

internal data class LiveScriptPlan(
    val script: OcrScript,
    val isProbe: Boolean,
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
    val cjkCharacters: Int,
) {
    val cjkShare: Double
        get() = if (nonSpaceCharacters == 0) 0.0 else cjkCharacters.toDouble() / nonSpaceCharacters
}

internal data class FrozenScriptState(
    val primaryScript: OcrScript,
    val otherRecognizerTried: Boolean = false,
)

internal data class FrozenScriptObservation(
    val retryScript: OcrScript?,
    val nextState: FrozenScriptState,
)

/** Pure state machine for serial AUTO recognizer selection. */
internal object AutoScriptPolicy {
    fun planLiveFrame(state: AutoScriptState, nowMs: Long): LiveScriptPlan {
        require(nowMs >= 0L)
        if (state.effectiveScript == OcrScript.JAPANESE) {
            return LiveScriptPlan(OcrScript.JAPANESE, isProbe = false, nextState = state)
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
        return LiveScriptPlan(
            script = if (probeDue) OcrScript.JAPANESE else OcrScript.LATIN,
            isProbe = probeDue,
            nextState = if (probeDue) state.copy(lastProbeAtMs = nowMs) else state,
        )
    }

    fun observeLiveResult(
        state: AutoScriptState,
        nowMs: Long,
        script: OcrScript,
        isProbe: Boolean,
        text: String,
    ): LiveScriptObservation {
        val stats = textStats(text)
        if (isProbe) {
            val dwellComplete = dwellComplete(state, nowMs)
            val shouldSwitch = state.effectiveScript == OcrScript.LATIN &&
                script == OcrScript.JAPANESE &&
                dwellComplete &&
                stats.nonSpaceCharacters >= 8 &&
                stats.cjkShare >= 0.30
            val nextState = if (shouldSwitch) {
                state.copy(
                    effectiveScript = OcrScript.JAPANESE,
                    lastSwitchAtMs = nowMs,
                    consecutiveLowCjkFrames = 0,
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

        if (state.effectiveScript != OcrScript.JAPANESE || script != OcrScript.JAPANESE) {
            return LiveScriptObservation(state.effectiveScript, acceptResult = true, switched = false, state)
        }

        val lowCount = if (stats.cjkShare < 0.05) state.consecutiveLowCjkFrames + 1 else 0
        val shouldRevert = lowCount >= AUTO_REVERT_CONSECUTIVE_FRAMES && dwellComplete(state, nowMs)
        val nextState = if (shouldRevert) {
            state.copy(
                effectiveScript = OcrScript.LATIN,
                lastProbeAtMs = nowMs,
                lastSwitchAtMs = nowMs,
                consecutiveLowCjkFrames = 0,
            )
        } else {
            state.copy(consecutiveLowCjkFrames = lowCount.coerceAtMost(AUTO_REVERT_CONSECUTIVE_FRAMES))
        }
        return LiveScriptObservation(
            effectiveScript = nextState.effectiveScript,
            acceptResult = true,
            switched = shouldRevert,
            nextState = nextState,
        )
    }

    fun observeFrozenResult(
        state: FrozenScriptState,
        script: OcrScript,
        text: String,
    ): FrozenScriptObservation {
        val triedOther = state.otherRecognizerTried || script != state.primaryScript
        val retry = if (!triedOther && textStats(text).nonSpaceCharacters < 8) {
            state.primaryScript.other()
        } else {
            null
        }
        return FrozenScriptObservation(
            retryScript = retry,
            nextState = state.copy(otherRecognizerTried = triedOther || retry != null),
        )
    }

    fun betterFrozenScript(
        primaryScript: OcrScript,
        primaryText: String,
        retryScript: OcrScript,
        retryText: String,
    ): OcrScript =
        if (textStats(retryText).nonSpaceCharacters > textStats(primaryText).nonSpaceCharacters) {
            retryScript
        } else {
            primaryScript
        }

    fun textStats(text: String): ScriptTextStats {
        var nonSpace = 0
        var cjk = 0
        var index = 0
        while (index < text.length) {
            val codePoint = Character.codePointAt(text, index)
            index += Character.charCount(codePoint)
            if (Character.isWhitespace(codePoint)) continue
            nonSpace += 1
            when (Character.UnicodeScript.of(codePoint)) {
                Character.UnicodeScript.HAN,
                Character.UnicodeScript.HIRAGANA,
                Character.UnicodeScript.KATAKANA,
                -> cjk += 1
                else -> Unit
            }
        }
        return ScriptTextStats(nonSpaceCharacters = nonSpace, cjkCharacters = cjk)
    }

    private fun dwellComplete(state: AutoScriptState, nowMs: Long): Boolean {
        val lastSwitchAtMs = state.lastSwitchAtMs ?: return true
        return nowMs < lastSwitchAtMs || nowMs - lastSwitchAtMs >= AUTO_MIN_DWELL_MS
    }
}
