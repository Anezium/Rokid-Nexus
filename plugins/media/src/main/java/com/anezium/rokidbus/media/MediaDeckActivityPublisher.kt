package com.anezium.rokidbus.media

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckMonitorHub
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckStateListener
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.MediaDeckTarget
import com.anezium.rokidbus.media.session.MediaSessionIdentity
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract

/** The plugin service's registration, as the activity sees it. */
internal interface MediaDeckActivityPort {
    val approved: Boolean
    fun start(activity: NexusActivity): NexusSdkResult
    fun update(activity: NexusActivity, significant: Boolean): NexusSdkResult
    fun end(): NexusSdkResult

    /** Keeps the plugin service, and so its registration, alive; false when it could not. */
    fun hold(): Boolean
    fun release()
}

/**
 * The "Show in activities" subscriber. While the user enables it, it watches the media sessions
 * through the shared monitor and publishes the now-playing activity, even with the full surface
 * closed. It keeps the plugin service bound only while there is something to publish, ends the
 * activity and lets the service go when there is not (no session, no access, sessions that cannot
 * be read), and never polls: it publishes on a new revision only, which position never makes.
 *
 * A command is bound to the revision it was published at (see [MediaDeckActionIds]) and is run at
 * most once, against that exact session; it is never retried, here or after a reconnect.
 * Main thread only.
 */
internal class MediaDeckActivityPublisher(
    private val hub: MediaDeckMonitorHub,
    private val port: MediaDeckActivityPort,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> () -> Unit,
) {
    private data class Published(val revision: Long, val identity: MediaSessionIdentity, val trackKey: String)

    var enabled = false
        private set

    /** Why the activity stays off although enabled, until a new registration or the user's switch. */
    var suspendedBy: String? = null
        private set

    private var suspendedGeneration = NO_GENERATION
    private var linkUsable = true
    private var started = false

    /** The approved registration the publication belongs to; another one holds nothing yet. */
    private var currentGeneration = NO_GENERATION
    private var published: Published? = null
    private var holding = false
    private var lastSentAt: Long? = null
    private var cancelFlush: (() -> Unit)? = null
    private var cancelRelease: (() -> Unit)? = null
    private val shownTargets = LinkedHashMap<Long, MediaDeckTarget>()
    private val handledInvocations = ArrayDeque<String>()
    private val listener = MediaDeckStateListener { _, _, changed -> if (changed) plan() }

    val status: MediaDeckActivityStatus
        get() = MediaDeckActivityModel.status(enabled, hub.state, bus)

    val isHolding: Boolean get() = holding

    private val bus: MediaDeckBus
        get() = when {
            !port.approved -> MediaDeckBus.OWNER_GONE
            !linkUsable -> MediaDeckBus.LINK_LOST
            else -> MediaDeckBus.READY
        }

    /** The user's switch. Off ends the activity and stops watching; the preference is the caller's. */
    fun setEnabled(value: Boolean) {
        if (value == enabled) {
            if (value) plan()
            return
        }
        enabled = value
        suspendedBy = null
        if (value) {
            hub.subscribe(MediaDeckSubscriber.ACTIVITY, listener)
            plan()
        } else {
            plan()
            hub.unsubscribe(MediaDeckSubscriber.ACTIVITY)
        }
    }

    /** The plugin service exists again, with a client that holds nothing on the hub yet. */
    fun onBusReady() {
        forgetPublication()
        // A new client counts its registrations from scratch.
        currentGeneration = NO_GENERATION
        suspendedGeneration = NO_GENERATION
        plan()
    }

    /** The plugin service went away; its registration took the live activity with it. */
    fun onBusLost() {
        forgetPublication()
        currentGeneration = NO_GENERATION
    }

    fun onRegistration(approved: Boolean, generation: Int) {
        if (!approved) {
            // Pending, denied or revoked: nothing can be published, so nothing is held for it.
            // The hub binds the plugin again when the user opens it; that registration resumes.
            forgetPublication()
            currentGeneration = NO_GENERATION
            if (enabled) suspend(REASON_NOT_APPROVED)
            stopPublishing(releaseNow = true)
            return
        }
        if (generation != currentGeneration) {
            // A new registration holds nothing yet; approval is also reported again for the same one.
            forgetPublication()
            currentGeneration = generation
        }
        // A new registration may try again what an older one gave up; a repeated report may not.
        if (suspendedBy == REASON_NOT_APPROVED || (suspendedBy != null && generation != suspendedGeneration)) {
            suspendedBy = null
        }
        plan()
    }

    fun onLinkState(usable: Boolean) {
        linkUsable = usable
        // The hub resends the canonical activity on reconnect; only an unsent revision goes now.
        if (usable) plan()
    }

    /** The hub ended the activity. Only the user brings back one the hub replaced or refused. */
    fun onActivityClosed(reason: String) {
        forgetPublication()
        if (reason == ActivityCloseReason.OWNER.wireValue) return
        // Starting again on the next change would evict whichever entry took this one's place.
        suspend(reason)
        stopPublishing(releaseNow = false)
    }

    /** The phone refused the activity because the user turned it off there. */
    fun onError(code: String) {
        if (code != ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED) return
        forgetPublication()
        suspend(code)
        stopPublishing(releaseNow = false)
    }

    /**
     * Runs one activity command at most once, on the exact session and revision it was published
     * for. Null for an invocation already handled: its one answer was already given.
     */
    fun onCommand(invocationId: String, actionId: String, typed: NexusActionType?): MediaDeckControlOutcome? {
        if (invocationId in handledInvocations) return null
        handledInvocations.addLast(invocationId)
        while (handledInvocations.size > MAX_HANDLED_INVOCATIONS) handledInvocations.removeFirst()
        val (command, revision) = MediaDeckActionIds.parse(actionId) ?: return MediaDeckControlOutcome.NOT_OFFERED
        // The hub's copy of the declared type must say the same thing as the id.
        if (typed != null && MediaDeckCommand.of(typed) != command) return MediaDeckControlOutcome.NOT_OFFERED
        if (!enabled) return MediaDeckControlOutcome.NOT_OFFERED
        val target = shownTargets[revision] ?: return MediaDeckControlOutcome.STALE_REVISION
        return hub.control(target, command)
    }

    private fun plan() {
        val activity = if (enabled && suspendedBy == null) MediaDeckActivityModel.activity(hub.state) else null
        if (activity == null) {
            stopPublishing(releaseNow = false)
            return
        }
        cancelRelease?.invoke()
        cancelRelease = null
        if (!holding) holding = port.hold()
        flush()
    }

    private fun flush() {
        if (!enabled || suspendedBy != null || !port.approved) return
        val state = hub.state as? MediaDeckState.Active ?: return
        val activity = MediaDeckActivityModel.activity(state) ?: return
        val previous = published
        if (started && previous?.revision == state.revision) return
        if (started) {
            val wait = lastSentAt?.let { it + MIN_UPDATE_INTERVAL_MS - now() } ?: 0L
            if (wait > 0L) {
                // Bursts of player callbacks collapse into one update; the newest revision wins.
                if (cancelFlush == null) {
                    cancelFlush = schedule(wait) {
                        cancelFlush = null
                        flush()
                    }
                }
                return
            }
        }
        val significant = previous == null || previous.identity != state.identity || previous.trackKey != state.track.key
        val result = if (started) port.update(activity, significant) else port.start(activity)
        lastSentAt = now()
        if (result != NexusSdkResult.SENT) {
            // Whatever did not arrive is sent whole at the next chance.
            started = false
            published = null
            return
        }
        started = true
        published = Published(state.revision, state.identity, state.track.key)
        shownTargets[state.revision] = MediaDeckTarget(state.identity, state.revision)
        while (shownTargets.size > MAX_SHOWN_REVISIONS) shownTargets.remove(shownTargets.keys.first())
    }

    private fun stopPublishing(releaseNow: Boolean) {
        cancelFlush?.invoke()
        cancelFlush = null
        if (started) port.end()
        started = false
        published = null
        if (!holding) return
        if (releaseNow) {
            cancelRelease?.invoke()
            cancelRelease = null
            release()
        } else if (cancelRelease == null) {
            // Give the end a moment on the bus before the registration that owns it goes.
            cancelRelease = schedule(RELEASE_AFTER_END_MS) {
                cancelRelease = null
                release()
            }
        }
    }

    private fun release() {
        if (!holding) return
        holding = false
        port.release()
    }

    private fun forgetPublication() {
        cancelFlush?.invoke()
        cancelFlush = null
        started = false
        published = null
    }

    private fun suspend(reason: String) {
        suspendedBy = reason
        suspendedGeneration = currentGeneration
    }

    companion object {
        const val MIN_UPDATE_INTERVAL_MS = 300L
        const val RELEASE_AFTER_END_MS = 3_000L
        const val REASON_NOT_APPROVED = "not_approved"
        private const val NO_GENERATION = -1
        private const val MAX_SHOWN_REVISIONS = 8
        private const val MAX_HANDLED_INVOCATIONS = 64
    }
}
