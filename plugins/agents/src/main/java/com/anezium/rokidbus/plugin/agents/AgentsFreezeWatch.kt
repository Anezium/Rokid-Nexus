package com.anezium.rokidbus.plugin.agents

/**
 * Notices that Android stopped running this process.
 *
 * `isIgnoringBatteryOptimizations` is the documented question to ask, but it is
 * not a reliable answer everywhere: on the Vivo device this was measured on, the
 * system's own power manager holds the real decision and the platform flag stays
 * false no matter what the wearer allows. So rather than trust a flag, the
 * monitor watches whether its own heartbeat actually ran.
 *
 * A frozen process is invisible from the inside — it simply is not scheduled —
 * but it leaves a footprint: the tick that should have happened 60 seconds ago
 * happened 20 minutes ago instead. That gap is the evidence, and it is the same
 * thing the computer sees as a phone that accepts a connection and then never
 * answers the handshake.
 */
internal object AgentsFreezeWatch {
    /**
     * How far past the expected interval a tick must land before it counts.
     * Scheduling jitter, a slow doze maintenance window, and a busy device all
     * stretch a tick; being frozen stretches it by orders of magnitude. Three
     * intervals is comfortably past the former and far short of the latter.
     */
    const val TOLERANCE_FACTOR = 3

    /**
     * True when [observedGapMs] is too long to explain as ordinary lateness.
     *
     * A gap that is zero, negative, or missing a previous tick is not evidence
     * of anything: the first tick after a start has nothing to compare against,
     * and a clock that went backwards says more about the clock than the process.
     */
    fun wasSuspended(expectedIntervalMs: Long, observedGapMs: Long): Boolean {
        if (expectedIntervalMs <= 0L || observedGapMs <= 0L) return false
        return observedGapMs > expectedIntervalMs * TOLERANCE_FACTOR
    }
}
