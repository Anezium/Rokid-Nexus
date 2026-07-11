package com.anezium.rokidbus.lens

internal const val AUTO_PROBE_INTERVAL_MS = 3_000L
internal const val AUTO_REVERT_CONSECUTIVE_FRAMES = 6
internal const val AUTO_MIN_DWELL_MS = 5_000L

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

internal data class AutoScriptState(
    val effectiveScript: OcrScript = OcrScript.LATIN,
    val nextProbeScript: OcrScript = OcrScript.JAPANESE,
    val lastProbeAtMs: Long? = null,
    val lastSwitchAtMs: Long? = null,
    val consecutiveLowScriptFrames: Int = 0,
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
        if (state.effectiveScript != OcrScript.LATIN) {
            return LiveScriptPlan(state.effectiveScript, isProbe = false, nextState = state)
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

        val probeScript = state.nextProbeScript.takeIf { it != OcrScript.LATIN } ?: OcrScript.JAPANESE
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
        text: String,
    ): LiveScriptObservation {
        val stats = textStats(text, script)
        if (isProbe) {
            val dwellComplete = dwellComplete(state, nowMs)
            val shouldSwitch = state.effectiveScript == OcrScript.LATIN &&
                script != OcrScript.LATIN &&
                dwellComplete &&
                stats.nonSpaceCharacters >= 8 &&
                stats.scriptShare >= 0.30
            val nextState = if (shouldSwitch) {
                state.copy(
                    effectiveScript = script,
                    lastSwitchAtMs = nowMs,
                    consecutiveLowScriptFrames = 0,
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

        if (state.effectiveScript == OcrScript.LATIN || script != state.effectiveScript) {
            return LiveScriptObservation(state.effectiveScript, acceptResult = true, switched = false, state)
        }

        val lowCount = if (stats.scriptShare < 0.05) state.consecutiveLowScriptFrames + 1 else 0
        val shouldRevert = lowCount >= AUTO_REVERT_CONSECUTIVE_FRAMES && dwellComplete(state, nowMs)
        val nextState = if (shouldRevert) {
            state.copy(
                effectiveScript = OcrScript.LATIN,
                lastProbeAtMs = nowMs,
                lastSwitchAtMs = nowMs,
                consecutiveLowScriptFrames = 0,
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

    fun observeFrozenResult(
        state: FrozenScriptState,
        script: OcrScript,
        text: String,
    ): FrozenScriptObservation {
        val triedOther = state.otherRecognizerTried || script != state.primaryScript
        val retry = if (!triedOther && textStats(text, script).nonSpaceCharacters < 8) {
            state.retryScript
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
        if (textStats(retryText, retryScript).nonSpaceCharacters >
            textStats(primaryText, primaryScript).nonSpaceCharacters
        ) {
            retryScript
        } else {
            primaryScript
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

    private fun nextNonLatinScript(script: OcrScript): OcrScript {
        val index = NON_LATIN_OCR_SCRIPTS.indexOf(script)
        return NON_LATIN_OCR_SCRIPTS[(index + 1).mod(NON_LATIN_OCR_SCRIPTS.size)]
    }

    private fun dwellComplete(state: AutoScriptState, nowMs: Long): Boolean {
        val lastSwitchAtMs = state.lastSwitchAtMs ?: return true
        return nowMs < lastSwitchAtMs || nowMs - lastSwitchAtMs >= AUTO_MIN_DWELL_MS
    }
}
