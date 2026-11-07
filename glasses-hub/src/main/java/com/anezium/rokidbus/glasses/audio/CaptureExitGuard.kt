package com.anezium.rokidbus.glasses.audio

import java.util.concurrent.atomic.AtomicBoolean

/** Arm on an independent thread before touching framework teardown, which may itself block. */
internal class CaptureExitGuard(
    private val stopCapture: () -> Unit,
    private val scheduleHalt: (Long, () -> Unit) -> Unit,
    private val halt: () -> Unit,
) {
    private val stopping = AtomicBoolean()

    fun stop() {
        if (!stopping.compareAndSet(false, true)) return
        scheduleHalt(1000, halt)
        stopCapture()
    }

    /** Runs [block] with the guard armed on every exit, before any enclosing resource cleanup. */
    inline fun <T> armedOnExit(block: () -> T): T = try { block() } finally { stop() }
}
