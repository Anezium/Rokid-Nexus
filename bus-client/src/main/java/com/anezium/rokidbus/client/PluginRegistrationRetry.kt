package com.anezium.rokidbus.client

internal fun interface RetryCancellation {
    fun cancel()
}

internal fun interface RetryScheduler {
    fun schedule(delayMs: Long, action: () -> Unit): RetryCancellation
}

internal class PluginRegistrationRetry(
    private val scheduler: RetryScheduler,
    private val retry: () -> Unit,
    /**
     * Ceiling of the backoff. Plugins wait on a wearer who may approve
     * minutes later, so they keep knocking at this cadence forever; generic
     * clients pass the historical flat delay instead so released devices
     * keep their reconnect behavior.
     */
    private val maxDelayMs: Long = MAX_RETRY_DELAY_MS,
    private val onRetryScheduled: (attempt: Int, delayMs: Long, reason: String) -> Unit = { _, _, _ -> },
) {
    private var cancellation: RetryCancellation? = null
    private var retryAttempt = 0
    private var nextDelayMs = INITIAL_RETRY_DELAY_MS
    private var closed = false

    @Synchronized
    fun onRegistrationResult(result: Int) {
        when (result) {
            PluginRegistrationResult.PENDING_USER_APPROVAL,
            PluginRegistrationResult.REGISTRATION_FAILED -> schedule("registration result=$result")

            else -> resetLocked()
        }
    }

    @Synchronized
    fun onConnectionFailure(reason: String) {
        schedule(reason)
    }

    @Synchronized
    fun reset() {
        resetLocked()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        resetLocked()
    }

    private fun resetLocked() {
        cancellation?.cancel()
        cancellation = null
        retryAttempt = 0
        nextDelayMs = INITIAL_RETRY_DELAY_MS
    }

    /** Callers hold the monitor: sends race in from arbitrary binder threads. */
    private fun schedule(reason: String) {
        if (closed || cancellation != null) return
        val delayMs = nextDelayMs.coerceAtMost(maxDelayMs)
        val attempt = ++retryAttempt
        onRetryScheduled(attempt, delayMs, reason)
        cancellation = scheduler.schedule(delayMs) {
            if (onRetryFired()) retry()
        }
        nextDelayMs = (delayMs * 2).coerceAtMost(maxDelayMs)
    }

    @Synchronized
    private fun onRetryFired(): Boolean {
        cancellation = null
        return !closed
    }

    private companion object {
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 15_000L
    }
}
