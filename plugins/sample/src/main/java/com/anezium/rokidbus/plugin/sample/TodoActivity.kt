package com.anezium.rokidbus.plugin.sample

import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityActivation
import com.anezium.rokidbus.client.plugin.NexusActivityPersistence
import com.anezium.rokidbus.client.plugin.NexusActivityProgress
import com.anezium.rokidbus.client.plugin.NexusPageDestination
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.shared.ActivityCloseReason

/**
 * The list as one activity: how many tasks are left, the list's name, and the list itself as
 * the destination a tap on the card opens. Built from the list alone, so the card says exactly
 * what the store holds.
 */
internal object TodoActivitySummary {
    /** Registered by the manifest's `GLYPHS`; glasses that do not know it draw their fallback dot. */
    const val GLYPH = "checklist"

    private const val MAX_PRIMARY = 12
    private const val MAX_SECONDARY = 28
    private const val MAX_DETAIL = 32
    private const val DETAIL_LINES = 2

    fun primary(list: TodoList): String = when {
        list.tasks.isEmpty() -> "No tasks"
        list.remaining == 0 -> "All done"
        else -> "${list.remaining} left"
    }

    fun build(list: TodoList): NexusActivity = NexusActivity(
        glyph = GLYPH,
        primary = clip(primary(list), MAX_PRIMARY),
        secondary = clip(list.title, MAX_SECONDARY),
        progress = list.tasks.size.takeIf { it > 0 }?.let { total ->
            NexusActivityProgress.Percent((total - list.remaining) * 100 / total)
        },
        // The expanded panel names what is still to do, in the list's own order.
        detail = list.tasks.filterNot(TodoTask::checked).take(DETAIL_LINES).map { clip(it.title, MAX_DETAIL) },
        destination = NexusPageDestination(TodoPages.TODO),
        activation = NexusActivityActivation.DESTINATION,
        persistence = NexusActivityPersistence.ONGOING,
    )

    private fun clip(value: String, limit: Int): String =
        if (value.length <= limit) value else value.take(limit - 1).trimEnd() + "…"
}

/** Where the "Show in activities" setting stands, as the settings screen reports it. */
internal enum class TodoActivityStatus {
    OFF,

    /** On, and waiting for this plugin's registration to come through. */
    WAITING,

    NOT_APPROVED,

    /** Handed to Nexus, which keeps it while this plugin sleeps. */
    SHOWN,

    /** No activity surface: the glasses never announced one, or `surfaces` is not granted. */
    UNAVAILABLE,

    /** The hubs did not negotiate the launcher bundle, so nothing could outlive this plugin. */
    NEEDS_NEWER_NEXUS,

    /** The user turned this plugin's activities off in Rokid Nexus. */
    DISABLED_IN_NEXUS,

    /** Nexus made room for another plugin's activity. */
    REPLACED,

    /** The adb-scripted demo route holds this plugin's one activity. */
    PAUSED_FOR_DEMO,
}

/** What one [TodoActivityPublisher.flush] did. */
internal enum class TodoFlush {
    SENT,

    /** Nothing to send: already current, off, or not possible on this registration. */
    IDLE,

    /** Could not send yet; the next registration tries again. */
    WAITING,

    /** Sent too recently; a later flush is scheduled. */
    DEFERRED,
}

/** The plugin's registration, as far as publishing the activity needs it. */
internal interface TodoActivitySink {
    /** The hub answered at least one registration of this client, whatever it said. */
    val registered: Boolean
    val approved: Boolean
    val activitiesAvailable: Boolean
    val launcherInteractive: Boolean
    val registrationGeneration: Int

    fun start(activity: NexusActivity): NexusSdkResult
    fun update(activity: NexusActivity): NexusSdkResult
    fun end(): NexusSdkResult
}

/**
 * The plugin's own "Show in activities" preference, whether an end still has to reach the hub,
 * and which list revision the summary last handed to Nexus reflects (null when none stands).
 */
internal interface TodoActivityPrefs {
    val showInActivities: Boolean
    var endPending: Boolean
    var publishedRevision: Long?
}

/**
 * Publishes the list as an ongoing activity while the user has "Show in activities" on, and
 * ends it once when they turn it off. It only reacts: a change of the list or the setting, a
 * new registration. A new registration starts the entry afresh, since that is the only way
 * Nexus revives an offline one; within a registration, only a different summary is sent, at
 * most a few times a second.
 *
 * It never needs the plugin to stay alive: once published, Nexus keeps the entry as an offline
 * snapshot when the plugin goes away. Without the launcher bundle nothing is published, since
 * an ordinary activity would vanish the moment the plugin went back to sleep.
 *
 * When Nexus refuses the entry (the user turned it off there) or evicts it for another plugin's,
 * this registration leaves it at that; a change of the list, the user turning the setting on
 * again, or the next registration offers it again.
 *
 * Main thread only.
 */
internal class TodoActivityPublisher(
    private val prefs: TodoActivityPrefs,
    private val list: () -> TodoList,
    private val sink: () -> TodoActivitySink?,
    private val now: () -> Long,
    private val schedule: (delayMs: Long, task: () -> Unit) -> Unit,
    private val flushed: (TodoFlush) -> Unit = {},
) {
    private var startedGeneration: Int? = null
    private var refusedGeneration: Int? = null
    private var yieldedGeneration: Int? = null
    private var lastSent: NexusActivity? = null
    private var lastSentAt: Long? = null
    private var deferred = false

    var status: TodoActivityStatus = TodoActivityStatus.OFF
        private set

    /** While true the demo route owns the activity, and nothing of the list is sent. */
    var pausedForDemo: Boolean = false
        set(value) {
            field = value
            // Whatever was on the glasses was replaced by the demo, or ended with it.
            reset()
        }

    fun flush(): TodoFlush = flushNow().also(flushed)

    /** A new client: its registrations hold nothing, and count their generations afresh. */
    fun newClient() {
        reset()
        refusedGeneration = null
        yieldedGeneration = null
    }

    /** The list changed: an entry Nexus evicted for another plugin's is worth offering again. */
    fun listChanged() {
        yieldedGeneration = null
    }

    /** The user turned "Show in activities" on: an earlier refusal or eviction no longer stands. */
    fun enabledAgain() {
        refusedGeneration = null
        yieldedGeneration = null
    }

    /**
     * The hub refused a start with `ACTIVITY_DISABLED`: the user turned this plugin's activities
     * off in Nexus, and the hub ended whatever entry stood.
     */
    fun refused() {
        reset()
        refusedGeneration = sink()?.registrationGeneration
        prefs.publishedRevision = null
        status = TodoActivityStatus.DISABLED_IN_NEXUS
    }

    /** The hub closed the entry; whatever this registration sent is gone. */
    fun closed(reason: String) {
        reset()
        if (reason == ActivityCloseReason.REPLACED.wireValue) {
            yieldedGeneration = sink()?.registrationGeneration
            prefs.publishedRevision = null
            if (prefs.showInActivities) status = TodoActivityStatus.REPLACED
        }
    }

    private fun reset() {
        startedGeneration = null
        lastSent = null
    }

    private fun flushNow(): TodoFlush {
        if (pausedForDemo) {
            status = TodoActivityStatus.PAUSED_FOR_DEMO
            return TodoFlush.IDLE
        }
        val enabled = prefs.showInActivities
        if (!enabled && !prefs.endPending) {
            status = TodoActivityStatus.OFF
            return TodoFlush.IDLE
        }
        val snapshot = list()
        val current = sink()
        if (current == null || !current.registered) {
            status = when {
                !enabled -> TodoActivityStatus.OFF
                // Nexus keeps what was handed over while this plugin sleeps.
                prefs.publishedRevision == snapshot.revision -> TodoActivityStatus.SHOWN
                else -> TodoActivityStatus.WAITING
            }
            return TodoFlush.WAITING
        }
        // Neither changes while this registration stands; an end still owed waits for one that
        // can carry it.
        if (!current.approved) {
            status = if (enabled) TodoActivityStatus.NOT_APPROVED else TodoActivityStatus.OFF
            return TodoFlush.IDLE
        }
        if (!current.activitiesAvailable) {
            status = if (enabled) TodoActivityStatus.UNAVAILABLE else TodoActivityStatus.OFF
            return TodoFlush.IDLE
        }
        if (!enabled) {
            if (throttled()) return TodoFlush.DEFERRED
            val result = current.end()
            lastSentAt = now()
            status = TodoActivityStatus.OFF
            if (result != NexusSdkResult.SENT) return TodoFlush.WAITING
            prefs.endPending = false
            prefs.publishedRevision = null
            reset()
            return TodoFlush.SENT
        }
        if (!current.launcherInteractive) {
            status = TodoActivityStatus.NEEDS_NEWER_NEXUS
            return TodoFlush.IDLE
        }
        val generation = current.registrationGeneration
        val fresh = startedGeneration != generation
        if (fresh && refusedGeneration == generation) {
            status = TodoActivityStatus.DISABLED_IN_NEXUS
            return TodoFlush.IDLE
        }
        if (fresh && yieldedGeneration == generation) {
            status = TodoActivityStatus.REPLACED
            return TodoFlush.IDLE
        }
        val activity = TodoActivitySummary.build(snapshot)
        if (!fresh && activity == lastSent) {
            // The summary already sent still describes the list as it is now.
            if (prefs.publishedRevision != snapshot.revision) prefs.publishedRevision = snapshot.revision
            status = TodoActivityStatus.SHOWN
            return TodoFlush.IDLE
        }
        if (throttled()) return TodoFlush.DEFERRED
        val result = if (fresh) current.start(activity) else current.update(activity)
        lastSentAt = now()
        if (result != NexusSdkResult.SENT) {
            // Whatever did not arrive is started whole next time.
            reset()
            status = TodoActivityStatus.WAITING
            return TodoFlush.WAITING
        }
        startedGeneration = generation
        lastSent = activity
        prefs.publishedRevision = snapshot.revision
        status = TodoActivityStatus.SHOWN
        return TodoFlush.SENT
    }

    private fun throttled(): Boolean {
        val last = lastSentAt ?: return false
        val wait = last + MIN_INTERVAL_MS - now()
        if (wait <= 0) return false
        if (!deferred) {
            deferred = true
            schedule(wait) {
                deferred = false
                flush()
            }
        }
        return true
    }

    companion object {
        /** Under the hub's four activity updates a second. */
        const val MIN_INTERVAL_MS = 300L
    }
}
