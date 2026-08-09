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
    private val onRetryScheduled: (attempt: Int, delayMs: Long, reason: String) -> Unit = { _, _, _ -> },
) {
    private var cancellation: RetryCancellation? = null
    private var retryAttempt = 0
    private var nextDelayMs = INITIAL_RETRY_DELAY_MS
    private var closed = false

    fun onRegistrationResult(result: Int) {
        when (result) {
            PluginRegistrationResult.PENDING_USER_APPROVAL,
            PluginRegistrationResult.REGISTRATION_FAILED -> schedule("registration result=$result")

            else -> reset()
        }
    }

    fun onConnectionFailure(reason: String) {
        schedule(reason)
    }

    fun reset() {
        cancellation?.cancel()
        cancellation = null
        retryAttempt = 0
        nextDelayMs = INITIAL_RETRY_DELAY_MS
    }

    fun close() {
        if (closed) return
        closed = true
        reset()
    }

    private fun schedule(reason: String) {
        if (closed || cancellation != null) return
        val delayMs = nextDelayMs
        val attempt = ++retryAttempt
        onRetryScheduled(attempt, delayMs, reason)
        cancellation = scheduler.schedule(delayMs) {
            cancellation = null
            if (!closed) retry()
        }
        nextDelayMs = (delayMs * 2).coerceAtMost(MAX_RETRY_DELAY_MS)
    }

    private companion object {
        const val INITIAL_RETRY_DELAY_MS = 1_000L
        const val MAX_RETRY_DELAY_MS = 32_000L
    }
}
