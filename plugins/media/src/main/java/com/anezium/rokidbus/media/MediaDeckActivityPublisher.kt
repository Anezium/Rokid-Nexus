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
 * Nexus can stop it too: a registration that is not approved (pending, revoked, plugin disabled),
 * the user turning the activity off in Nexus, or the hub replacing it. It then ends what it
 * published, lets the service go and stops watching, while the user's switch stays on; a new
 * registration, or [rearm] on an event the plugin already receives, tries once more.
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
    onHubAtStart: Boolean = false,
    private val rememberOnHub: (Boolean) -> Unit = {},
) {
    private data class Published(val revision: Long, val identity: MediaSessionIdentity, val trackKey: String)

    var enabled = false
        private set

    /** Why Nexus stopped the activity although enabled, until a new registration or [rearm]. */
    var suspendedBy: String? = null
        private set

    private var suspendedGeneration = NO_GENERATION
    private var linkUsable = true
    private var started = false
    private var watching = false

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
        get() = MediaDeckActivityModel.status(enabled, suspendedBy != null, hub.state, bus)

    val isHolding: Boolean get() = holding

    /** Whether this subscriber keeps the shared monitor running. */
    val isWatching: Boolean get() = watching

    /** Whether an activity is live on the hub as far as this publisher knows. */
    val isStarted: Boolean get() = started

    /**
     * Whether an entry of this plugin may still be on the hub, live or as the offline snapshot an
     * earlier process left. Remembered across processes, so turning the activity off, or finding
     * nothing playing, ends that snapshot too instead of leaving it there for good.
     */
    var mayBeOnHub: Boolean = onHubAtStart
        private set

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
            syncWatch()
            plan()
        } else {
            plan()
            syncWatch()
        }
    }

    /**
     * Something the plugin already receives (its listener connecting, its settings opening, the
     * service starting) says to look again: whatever Nexus stopped is tried once more.
     */
    fun rearm() {
        if (enabled) {
            suspendedBy = null
            syncWatch()
        }
        plan()
    }

    /** The plugin service exists again, with a client that holds nothing on the hub yet. */
    fun onBusReady() {
        forgetPublication()
        // A new client counts its registrations from scratch.
        currentGeneration = NO_GENERATION
        suspendedGeneration = NO_GENERATION
        rearm()
    }

    /**
     * The plugin service went away; its registration took the live activity with it. A binding
     * this publisher still holds stays: it brings the service back, which reports [onBusReady].
     */
    fun onBusLost() {
        forgetPublication()
        currentGeneration = NO_GENERATION
    }

    fun onRegistration(approved: Boolean, generation: Int) {
        if (!approved) {
            // Pending, denied, revoked or disabled: nothing can be published, so nothing is held or
            // watched for it. The next registration (the hub binding the plugin again) resumes.
            forgetPublication()
            currentGeneration = NO_GENERATION
            if (enabled) suspend(REASON_NOT_APPROVED)
            stopPublishing(releaseNow = true)
            syncWatch()
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
            syncWatch()
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
        markOnHub(false)
        if (reason == ActivityCloseReason.OWNER.wireValue) return
        // Starting again on the next change would evict whichever entry took this one's place.
        suspend(reason)
        stopPublishing(releaseNow = false)
        syncWatch()
    }

    /** The phone refused the activity because the user turned it off there. */
    fun onError(code: String) {
        if (code != ActivitySurfaceContract.ERROR_ACTIVITY_DISABLED) return
        forgetPublication()
        // The phone ended the entry when the user turned it off there.
        markOnHub(false)
        suspend(code)
        stopPublishing(releaseNow = false)
        syncWatch()
    }

    /**
     * Runs one activity command at most once, on the exact session and revision it was published
     * for. Null for an invocation already handled: its one answer was already given.
     */
    fun onCommand(invocationId: String, actionId: String, typed: NexusActionType?): MediaDeckControlOutcome? {
        if (invocationId in handledInvocations) return null
        handledInvocations.addLast(invocationId)
        while (handledInvocations.size > MAX_HANDLED_INVOCATIONS) handledInvocations.removeFirst()
        return control(actionId, typed)
    }

    /**
     * An action from glasses without the launcher bundle: no invocation and no answer, but the
     * same check, so it too reaches only the session and revision it was shown for.
     */
    fun onLegacyAction(actionId: String): MediaDeckControlOutcome = control(actionId, typed = null)

    private fun control(actionId: String, typed: NexusActionType?): MediaDeckControlOutcome {
        val (command, revision) = MediaDeckActionIds.parse(actionId) ?: return MediaDeckControlOutcome.NOT_OFFERED
        // The hub's copy of the declared type must say the same thing as the id.
        if (typed != null && MediaDeckCommand.of(typed) != command) return MediaDeckControlOutcome.NOT_OFFERED
        if (!enabled || suspendedBy != null) return MediaDeckControlOutcome.NOT_OFFERED
        // A revision no longer held binds to nothing: stale while a session plays, else the
        // reason there is none (nothing playing, no access).
        return hub.control(shownTargets[revision], command)
    }

    private fun syncWatch() {
        val wanted = enabled && suspendedBy == null
        if (wanted == watching) return
        watching = wanted
        if (wanted) {
            hub.subscribe(MediaDeckSubscriber.ACTIVITY, listener)
        } else {
            hub.unsubscribe(MediaDeckSubscriber.ACTIVITY)
        }
    }

    private fun plan() {
        val activity = if (enabled && suspendedBy == null) MediaDeckActivityModel.activity(hub.state) else null
        if (activity == null) {
            stopPublishing(releaseNow = false)
            return
        }
        keepHolding()
        flush()
    }

    private fun keepHolding() {
        cancelRelease?.invoke()
        cancelRelease = null
        if (!holding) holding = port.hold()
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
            // Whatever did not arrive is sent whole at the next chance: approval with its
            // capabilities, a link coming back, or the next change.
            started = false
            published = null
            return
        }
        started = true
        markOnHub(true)
        published = Published(state.revision, state.identity, state.track.key)
        shownTargets[state.revision] = MediaDeckTarget(state.identity, state.revision)
        while (shownTargets.size > MAX_SHOWN_REVISIONS) shownTargets.remove(shownTargets.keys.first())
    }

    private fun stopPublishing(releaseNow: Boolean) {
        cancelFlush?.invoke()
        cancelFlush = null
        val wasStarted = started
        started = false
        published = null
        shownTargets.clear()
        if (wasStarted || mayBeOnHub) {
            if (port.approved) {
                // Without activity support the hub holds nothing of ours; only a lost send leaves doubt.
                if (port.end() != NexusSdkResult.NOT_REGISTERED) markOnHub(false)
            } else if (!releaseNow && suspendedBy != REASON_NOT_APPROVED) {
                // Only an approved registration can end it: hold the service until approval arrives.
                keepHolding()
                if (holding) return
            }
        }
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
        // Nothing published on a registration that is gone can be acted on any more.
        shownTargets.clear()
    }

    private fun markOnHub(value: Boolean) {
        if (mayBeOnHub == value) return
        mayBeOnHub = value
        rememberOnHub(value)
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
