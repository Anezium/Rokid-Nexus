// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
package com.anezium.rokidbus.glasses.session

/** The single deadline the reducer asks for, on the uptime clock. */
internal interface SessionTimer {
    /** Replaces any pending task. */
    fun schedule(atUptimeMs: Long, task: () -> Unit)

    fun cancel()
}

/** Where the effects that touch Android end up. Deadlines never reach it. */
internal interface SessionEffectSink {
    fun execute(effect: SessionEffect)

    /** Called after all effects of one event ran, with the model they led to. */
    fun settled(model: SessionModel)
}

/**
 * Feeds events to the [SessionReducer], one at a time, and runs what comes out. Events raised
 * while effects run (a failed send, a window that could not be added) are queued and handled
 * after the current event, so the reducer never sees two events interleaved and the effects of
 * one transition are never split by another.
 *
 * The host window follows the state: [SessionEffect.AttachHost] goes out before the first
 * effect of a session and [SessionEffect.DetachHost] after its last. One timer is kept at
 * [SessionModel.nextDeadlineMs] and answers with a `Tick`; nothing is scheduled while closed.
 * The clock is the uptime clock, never `elapsedRealtime`: a deep sleep must not expire a gate
 * or a pending open the wearer never left.
 *
 * Single-threaded: call from the main thread, the same one the timer fires on.
 */
internal class SessionRunner(
    private val reducer: SessionReducer,
    private val clock: () -> Long,
    private val timer: SessionTimer,
    private val sink: SessionEffectSink,
    private val onError: (Throwable) -> Unit = { throw it },
) {
    /** Written on the main thread only; volatile because other components read it from their own. */
    @Volatile
    var model: SessionModel = SessionModel()
        private set

    val state: SessionState get() = model.state

    private val queue = ArrayDeque<SessionEvent>()
    private var draining = false
    private var scheduledAtMs: Long? = null

    fun dispatch(event: SessionEvent) {
        queue.addLast(event)
        if (draining) return
        draining = true
        try {
            while (queue.isNotEmpty()) handle(queue.removeFirst())
        } finally {
            draining = false
        }
    }

    private fun handle(event: SessionEvent) {
        val before = model
        val transition = reducer.reduce(before, event)
        model = transition.model
        val opened = before.state == SessionState.Closed && model.state != SessionState.Closed
        val closed = before.state != SessionState.Closed && model.state == SessionState.Closed
        if (opened) guarded { sink.execute(SessionEffect.AttachHost) }
        for (effect in transition.effects) {
            when (effect) {
                // The timer follows nextDeadlineMs below, so these two only document the transition.
                is SessionEffect.ScheduleDeadline, SessionEffect.CancelDeadline, SessionEffect.None -> Unit
                else -> guarded { sink.execute(effect) }
            }
        }
        if (closed) guarded { sink.execute(SessionEffect.DetachHost) }
        reschedule()
        guarded { sink.settled(model) }
    }

    private fun reschedule() {
        val at = model.nextDeadlineMs()
        if (at == scheduledAtMs) return
        scheduledAtMs = at
        if (at == null) {
            timer.cancel()
        } else {
            timer.schedule(at) {
                scheduledAtMs = null
                dispatch(SessionEvent.Tick(clock()))
            }
        }
    }

    private inline fun guarded(block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            onError(t)
        }
    }
}
