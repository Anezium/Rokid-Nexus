package com.anezium.rokidbus.lens

internal data class OcrCadenceState(
    val lastStartMs: Long? = null,
)

internal data class OcrCadenceDecision(
    val shouldStart: Boolean,
    val nextState: OcrCadenceState,
)

internal object OcrCadencePolicy {
    fun evaluate(
        state: OcrCadenceState,
        nowMs: Long,
        minIntervalMs: Long,
    ): OcrCadenceDecision {
        require(minIntervalMs > 0L) { "minIntervalMs must be positive" }

        val lastStartMs = state.lastStartMs
        val shouldStart = lastStartMs == null ||
            nowMs < lastStartMs ||
            nowMs - lastStartMs >= minIntervalMs
        return if (shouldStart) {
            OcrCadenceDecision(
                shouldStart = true,
                nextState = OcrCadenceState(lastStartMs = nowMs),
            )
        } else {
            OcrCadenceDecision(
                shouldStart = false,
                nextState = state,
            )
        }
    }
}
