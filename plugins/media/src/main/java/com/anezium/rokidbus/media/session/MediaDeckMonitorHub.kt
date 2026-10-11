package com.anezium.rokidbus.media.session

import android.content.Context

/** The active-session watcher, behind a seam so the hub and its subscribers run without Android. */
internal interface MediaDeckMonitor {
    /** Starts watching and reports what it sees at once, on the main thread. */
    fun start()

    fun stop()

    /** Looks again now; reports only through the listener. */
    fun refresh()

    /**
     * Hands [command] to the session observed as [expected] and to no other, after checking that
     * session's live state: a change whose callback has not arrived yet answers [MediaDeckSendResult.MOVED].
     */
    fun send(expected: MediaDeckObservation.Session, command: MediaDeckCommand): MediaDeckSendResult
}

internal enum class MediaDeckSendResult {
    SENT,

    /** The expected session is no longer the observed one, or its state moved; nothing was sent. */
    MOVED,
    ACCESS_REQUIRED,

    /** The session threw; nothing is known to have happened. */
    FAILED,
}

internal fun interface MediaDeckMonitorListener {
    fun onObserved(observation: MediaDeckObservation, snapshot: MediaDeckSnapshot?)
}

/** Who needs the active session watched. */
internal enum class MediaDeckSubscriber {
    /** The full surface, while it is open. */
    IMMERSION,

    /** The "Show in activities" entry, while the user enables it. */
    ACTIVITY,

    /** A media page, while it is on the glasses. */
    PAGE,
}

internal fun interface MediaDeckStateListener {
    /** [changed] when [state] carries a new revision; otherwise only the snapshot's position moved. */
    fun onState(state: MediaDeckState, snapshot: MediaDeckSnapshot?, changed: Boolean)
}

/**
 * Media Deck's one session monitor per process. It runs while at least one subscriber needs it
 * and stops with the last, so closing the full surface leaves an enabled activity watching, and
 * turning the activity off leaves an open surface watching. Every observation goes through one
 * state machine, so all subscribers see the same revisions, and every command is checked against
 * that state before it reaches exactly the session it was decided on. Main thread only.
 */
internal class MediaDeckMonitorHub(monitorFactory: (MediaDeckMonitorListener) -> MediaDeckMonitor) {
    private val machine = MediaDeckStateMachine()
    private val listeners = linkedMapOf<MediaDeckSubscriber, MediaDeckStateListener>()
    private val monitor = monitorFactory(MediaDeckMonitorListener(::onObserved))

    var snapshot: MediaDeckSnapshot? = null
        private set

    val state: MediaDeckState get() = machine.state

    val subscribers: Set<MediaDeckSubscriber> get() = listeners.keys.toSet()

    val isMonitoring: Boolean get() = listeners.isNotEmpty()

    /** Adds or replaces [subscriber]'s listener, which hears the current state at once. */
    fun subscribe(subscriber: MediaDeckSubscriber, listener: MediaDeckStateListener) {
        val first = listeners.isEmpty()
        listeners[subscriber] = listener
        if (first) {
            monitor.start()
        } else {
            listener.onState(machine.state, snapshot, changed = true)
            monitor.refresh()
        }
    }

    fun unsubscribe(subscriber: MediaDeckSubscriber) {
        if (listeners.remove(subscriber) == null || listeners.isNotEmpty()) return
        monitor.stop()
        machine.forget()
        snapshot = null
    }

    /**
     * Runs [command] decided on [target] once. It reaches the session only when that session is
     * still the observed one at the same revision and its live state agrees; the state that
     * follows is observed afterwards, never assumed.
     */
    fun control(target: MediaDeckTarget?, command: MediaDeckCommand): MediaDeckControlOutcome {
        val session = when (val decision = MediaDeckCommandPolicy.decide(machine.state, target, command)) {
            is MediaDeckCommandPolicy.Decision.Refuse -> return decision.outcome
            is MediaDeckCommandPolicy.Decision.Send -> decision.session
        }
        return when (monitor.send(session.observation, command)) {
            MediaDeckSendResult.SENT -> MediaDeckControlOutcome.DONE
            MediaDeckSendResult.MOVED -> MediaDeckControlOutcome.STALE_REVISION
            MediaDeckSendResult.ACCESS_REQUIRED -> MediaDeckControlOutcome.ACCESS_REQUIRED
            MediaDeckSendResult.FAILED -> MediaDeckControlOutcome.FAILED
        }
    }

    /** Looks again, for a subscriber that has a reason to doubt what it holds. */
    fun refresh() {
        if (listeners.isNotEmpty()) monitor.refresh()
    }

    private fun onObserved(observation: MediaDeckObservation, observed: MediaDeckSnapshot?) {
        // A late callback after the last subscriber left must not bring a state back.
        if (listeners.isEmpty()) return
        val changed = machine.observe(observation)
        snapshot = observed
        listeners.values.toList().forEach { it.onState(machine.state, observed, changed) }
    }

    companion object {
        @Volatile
        private var instance: MediaDeckMonitorHub? = null

        fun get(context: Context): MediaDeckMonitorHub = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                MediaDeckMonitorHub { listener -> MediaSessionMonitor(appContext, listener) }.also { instance = it }
            }
        }
    }
}
