package com.anezium.rokidbus.glasses

internal enum class SelfArmWatchdogEnsureResult {
    READY,
    SESSION_UNREACHABLE,
    FAILED,
}

/**
 * Keeps reachability signals edge-driven: one signal can launch at most one retry, and a failed
 * retry needs a newer signal before it may run again.
 */
internal class SelfArmWatchdogRetryPolicy {
    private var retryNeeded = false
    private var reachabilitySignalPending = false
    private var retryScheduled = false
    private var retryRunning = false

    /** Returns true when the caller should (re)schedule the single debounced retry callback. */
    @Synchronized
    fun onReachabilitySignal(): Boolean {
        reachabilitySignalPending = true
        if (!retryNeeded || retryRunning) return false
        retryScheduled = true
        return true
    }

    /** Returns true when an already-observed reachability signal should now schedule a retry. */
    @Synchronized
    fun onEnsureFinished(result: SelfArmWatchdogEnsureResult): Boolean {
        retryRunning = false
        retryScheduled = false
        if (result != SelfArmWatchdogEnsureResult.SESSION_UNREACHABLE) {
            retryNeeded = false
            reachabilitySignalPending = false
            return false
        }

        retryNeeded = true
        if (!reachabilitySignalPending) return false
        retryScheduled = true
        return true
    }

    /** Claims the scheduled retry. Only one caller can receive true. */
    @Synchronized
    fun onRetryDelayElapsed(): Boolean {
        if (!retryNeeded || !retryScheduled || retryRunning) return false
        retryScheduled = false
        retryRunning = true
        reachabilitySignalPending = false
        return true
    }

    /** Restores the consumed signal when the shared self-arm single-flight rejected this start. */
    @Synchronized
    fun onRetryStartRejected() {
        retryRunning = false
        reachabilitySignalPending = true
    }
}
