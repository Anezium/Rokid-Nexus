package com.anezium.rokidbus.glasses

/** Pure tap-count policy for the R08 ring while the launcher overlay owns navigation. */
internal class RingTapPolicy(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {
    enum class Resolution {
        SINGLE,
        DOUBLE,
        IGNORE,
    }

    private var tapCount = 0
    private var latestTapAtMs = Long.MIN_VALUE

    fun onTap(eventTimeMs: Long) {
        tapCount++
        latestTapAtMs = eventTimeMs
    }

    fun resolveExpired(eventTimeMs: Long): Resolution? {
        if (tapCount == 0 || eventTimeMs - latestTapAtMs <= windowMs) return null
        val resolution = when (tapCount) {
            1 -> Resolution.SINGLE
            2 -> Resolution.DOUBLE
            else -> Resolution.IGNORE
        }
        reset()
        return resolution
    }

    fun reset() {
        tapCount = 0
        latestTapAtMs = Long.MIN_VALUE
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 350L
    }
}
