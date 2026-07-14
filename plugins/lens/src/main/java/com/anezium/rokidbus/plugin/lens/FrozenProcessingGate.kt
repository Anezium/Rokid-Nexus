package com.anezium.rokidbus.plugin.lens

import java.util.concurrent.atomic.AtomicBoolean

/** Reference-counted boundary that keeps live work paused across asynchronous frozen jobs. */
internal class FrozenProcessingGate(
    private val pauseLive: () -> Unit,
    private val cancelLiveTranslations: () -> Unit,
    private val resumeLive: () -> Unit,
) : AutoCloseable {
    private val lock = Any()
    private var activeJobs = 0
    private var closed = false

    val isActive: Boolean
        get() = synchronized(lock) { activeJobs > 0 }

    fun begin(): Lease? {
        return synchronized(lock) {
            if (closed) return@synchronized null
            activeJobs += 1
            if (activeJobs == 1) {
                try {
                    pauseLive()
                    cancelLiveTranslations()
                } catch (failure: Throwable) {
                    activeJobs = 0
                    runCatching(resumeLive)
                    throw failure
                }
            }
            Lease(::finish)
        }
    }

    fun runIfLive(action: () -> Unit): Boolean {
        return synchronized(lock) {
            if (closed || activeJobs > 0) return@synchronized false
            action()
            true
        }
    }

    private fun finish() {
        synchronized(lock) {
            if (activeJobs <= 0) return@synchronized
            activeJobs -= 1
            if (activeJobs == 0 && !closed) resumeLive()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            activeJobs = 0
        }
    }

    internal class Lease(private val finish: () -> Unit) : AutoCloseable {
        private val finished = AtomicBoolean(false)

        override fun close() {
            if (finished.compareAndSet(false, true)) finish()
        }
    }
}
