package com.anezium.rokidbus.phone

/** A ready CXR identity wakes the single connection loop, including before it starts waiting. */
internal class SppReconnectBackoff(
    private val initialDelayMs: Long = 1_000L,
    private val maximumDelayMs: Long = 30_000L,
) {
    private val monitor = Object()
    private var revision = 0L
    private var delayMs = initialDelayMs

    fun beginAttempt(): Long = synchronized(monitor) { revision }

    fun retryDelayMs(): Long = synchronized(monitor) { delayMs }

    fun reset() = synchronized(monitor) {
        revision++
        delayMs = initialDelayMs
        monitor.notifyAll()
    }

    fun connected() = synchronized(monitor) { delayMs = initialDelayMs }

    fun awaitRetry(attempt: Long) = synchronized(monitor) {
        await(attempt, delayMs)
        if (revision == attempt) delayMs = (delayMs * 2).coerceAtMost(maximumDelayMs)
    }

    fun await(attempt: Long, millis: Long) = synchronized(monitor) {
        val deadline = System.nanoTime() + millis * 1_000_000L
        while (revision == attempt) {
            val remaining = deadline - System.nanoTime()
            if (remaining <= 0) break
            monitor.wait(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }
}
