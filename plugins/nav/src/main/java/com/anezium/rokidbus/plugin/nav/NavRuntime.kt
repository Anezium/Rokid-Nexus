package com.anezium.rokidbus.plugin.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityTrack
import com.anezium.rokidbus.client.plugin.NexusGuidancePlan
import com.anezium.rokidbus.client.plugin.NexusGuidancePlanner
import com.anezium.rokidbus.client.plugin.NexusGuidanceStep
import com.anezium.rokidbus.client.plugin.NexusPageDestination
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.surfaceSession
import com.anezium.rokidbus.shared.ActivityCloseReason
import com.anezium.rokidbus.shared.ActivitySurfaceContract

/**
 * Keeps the live route as one activity. Guidance comes from the notification listener, which
 * the system keeps bound while Notification Access is granted, or from a walking journey the
 * wearer started, in the same process; each source has its own runtime and only the one
 * [NavControl] selected may publish or end the activity, so notification callbacks cannot
 * replace a journey the wearer started.
 *
 * An activity ends when its owner disconnects, and the hub serves one registration per plugin,
 * so the owner is [NavPluginService]'s client: the runtime keeps that service bound while
 * guidance is live and releases it a moment after the route ends. Nothing polls: each posted
 * notification, or each fix of a journey, is one chance to update.
 *
 * With the launcher bundle the activity opens `nav:main` directly, and a journey's map and
 * heading follow it through [NavMapPublisher]; the text is always sent on its own first.
 */
internal class NavRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val planner = NexusGuidancePlanner()
    private var pending: NavGuidance? = null
    private var deferred: NavGuidance? = null
    private var started = false
    private var startedGeneration = NO_GENERATION
    private var lastSentAtMs = 0L
    private val sendDeferred = Runnable { flushDeferred() }
    private val sendMap = Runnable { if (NavControl.owns(this)) flushMap() }
    private val releaseIdle = Runnable { if (NavControl.owns(this)) NavControl.releaseBus(appContext) }
    private val map = NavMapPublisher(
        supported = { client?.supportsLauncherInteractive() == true },
        sendGeometry = { revision, geometry ->
            val delay = NavControl.mapPublicationDelayMs()
            if (delay != null) {
                main.removeCallbacks(sendMap)
                main.postDelayed(sendMap, delay)
                NexusSdkResult.CAPABILITY_NOT_AVAILABLE
            } else {
                client?.publishActivityGeometry(revision, geometry) ?: NexusSdkResult.NOT_REGISTERED
            }
        },
        sendHeading = { deg, accuracy -> client?.publishActivityHeading(deg, accuracy) ?: NexusSdkResult.NOT_REGISTERED },
        now = SystemClock::elapsedRealtime,
        streetsSupported = { client?.supportsRenderedMaps == true },
        onGeometrySent = { revision, snapshot -> NavControl.renderedFrames.remember(revision, snapshot) },
    )

    private val client get() = NavControl.serviceClient

    fun onGuidance(guidance: NavGuidance) = onMain {
        if (!NavControl.owns(this)) return@onMain
        NavState.guidance = guidance
        NavState.updatedAtMs = System.currentTimeMillis()
        NavControl.guidanceChanged()
        main.removeCallbacks(releaseIdle)
        pending = guidance
        NavControl.holdBus(appContext)
        flush()
        refreshCard()
    }

    /** A journey's new state: its text as guidance, then its map for whoever can draw it. */
    fun onJourney(snapshot: NavRouteSnapshot) = onMain {
        if (!NavControl.owns(this)) return@onMain
        map.update(snapshot, send = false)
        onGuidance(snapshot.guidance())
        flushMap()
    }

    fun onRenderedMapsChanged() = onMain {
        if (NavControl.owns(this)) flushMap()
    }

    private fun flushMap() {
        main.removeCallbacks(sendMap)
        val delay = map.geometryDelayMs()
        delay?.let { main.postDelayed(sendMap, it) }
        map.flush()
    }

    /** The app withdrew its guidance, or it stayed unreadable past the listener's grace. */
    fun onRouteEnded(source: NavSource) = onMain {
        if (!NavControl.owns(this)) return@onMain
        if (NavState.guidance?.source != source && pending?.source != source) return@onMain
        NavState.guidance = null
        NavState.updatedAtMs = System.currentTimeMillis()
        NavControl.guidanceChanged()
        pending = null
        deferred = null
        main.removeCallbacks(sendDeferred)
        planner.reset()
        map.clear()
        main.removeCallbacks(sendMap)
        if (started) {
            log("end result=${client?.endActivity()}")
            started = false
        }
        refreshCard()
        // Give the end a moment on the bus before the registration that owns it goes.
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, RELEASE_AFTER_END_MS)
    }

    /**
     * This source stops for good. Only the selected one ends the activity, and lets the service go
     * unless [releaseBus] is false because another source takes the route over at once.
     */
    fun shutdown(releaseBus: Boolean = true) = onMain {
        if (NavControl.owns(this)) {
            if (NavState.guidance != null) NavState.updatedAtMs = System.currentTimeMillis()
            NavState.guidance = null
            // A visible page says the source is gone instead of keeping the last turn.
            NavControl.guidanceChanged()
            if (started) client?.endActivity()
            if (releaseBus) NavControl.releaseBus(appContext)
        }
        pending = null
        deferred = null
        planner.reset()
        map.clear()
        main.removeCallbacks(sendMap)
        started = false
        main.removeCallbacks(releaseIdle)
        main.removeCallbacks(sendDeferred)
    }

    /** The plugin service exists again, with a client to send through. */
    fun onBusReady() = onMain {
        started = false
        startedGeneration = NO_GENERATION
        planner.reset()
        map.activityEnded()
        pending = pending ?: NavState.guidance
        flush()
    }

    /** The plugin service went away; its registration took the activity with it. */
    fun onBusLost() = onMain {
        started = false
        startedGeneration = NO_GENERATION
        planner.reset()
        map.activityEnded()
        pending = pending ?: NavState.guidance
        // Still guiding: bind again, which recreates the service and its client.
        if (pending != null) NavControl.holdBus(appContext)
    }

    fun onLinkState(state: Int) = onMain {
        flush()
        map.linkChanged()
    }

    fun onRegistrationState(result: Int) = onMain {
        NavState.registration = result
        if (result != PluginRegistrationResult.APPROVED) {
            log("registration result=$result")
            map.activityEnded()
            return@onMain
        }
        // Approval comes twice per registration; only a new registration has
        // lost the activity (the old one ended with its connection).
        if (client?.registrationGeneration != startedGeneration) {
            started = false
            planner.reset()
            map.activityEnded()
            pending = pending ?: NavState.guidance
        }
        flush()
    }

    fun onActivityClosed(reason: String) = onMain {
        log("activity closed reason=$reason")
        if (!NavControl.owns(this) || !started || client?.registrationGeneration != startedGeneration) return@onMain
        // Owner closes acknowledge an end we already handled locally. The stable
        // activity id may since have been reused by this route or another source.
        if (reason == ActivityCloseReason.OWNER.wireValue) return@onMain
        started = false
        planner.reset()
        map.activityEnded()
    }

    private fun flush() {
        if (!NavControl.owns(this)) return
        val current = client ?: return
        // Capabilities can arrive with the link before approval does; nothing
        // is sent until the registration it would belong to exists.
        if (!current.isApproved || !current.supportsActivitySurface) return
        val next = pending ?: return
        pending = null
        if (!started) planner.reset()
        when (val plan = planner.plan(next.toGuidanceStep())) {
            is NexusGuidancePlan.Start -> send(start = true, guidance = next)
            is NexusGuidancePlan.Update -> {
                val sinceLast = SystemClock.elapsedRealtime() - lastSentAtMs
                if (!plan.significant && sinceLast < MIN_QUIET_INTERVAL_MS) {
                    // Maps rewrites its notification every few metres. A quiet
                    // change waits for the next slot; the newest one wins.
                    deferred = next
                    main.removeCallbacks(sendDeferred)
                    main.postDelayed(sendDeferred, MIN_QUIET_INTERVAL_MS - sinceLast)
                } else {
                    send(guidance = next, significant = plan.significant, urgent = plan.urgent)
                }
            }
            NexusGuidancePlan.Unchanged -> Unit
        }
    }

    private fun flushDeferred() {
        val guidance = deferred ?: return
        deferred = null
        if (started) send(guidance = guidance)
    }

    private fun send(guidance: NavGuidance, start: Boolean = false, significant: Boolean = false, urgent: Boolean = false) {
        if (!NavControl.owns(this)) return
        val current = client ?: return
        if (significant || start) {
            deferred = null
            main.removeCallbacks(sendDeferred)
        }
        // Over the launcher bundle the card opens the route page itself, no hub summary in between.
        val activity = guidance.toActivity().let {
            if (current.supportsLauncherInteractive()) it.copy(destination = NexusPageDestination(NavPages.MAIN)) else it
        }
        val result = if (start) {
            current.startActivity(activity)
        } else {
            current.updateActivity(activity, significant = significant, urgent = urgent && current.supportsActivityExtras)
        }
        lastSentAtMs = SystemClock.elapsedRealtime()
        if (result == NexusSdkResult.SENT) {
            started = true
            if (start) {
                startedGeneration = current.registrationGeneration
                map.activityStarted()
            }
        } else {
            // Whatever did not arrive is sent whole next time.
            started = false
            planner.reset()
            map.activityEnded()
            pending = guidance
        }
        if (start || significant) log("send start=$start significant=$significant urgent=$urgent glyph=${guidance.glyph} result=$result")
    }

    private fun refreshCard() {
        if (!NavControl.cardOpen) return
        client?.surfaceSession(NavCard.SURFACE_ID)?.updateCard(NavCard.build(appContext))
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        private const val TAG = "NexusNav"
        private const val NO_GENERATION = -1
        private const val MIN_QUIET_INTERVAL_MS = 1_000L
        private const val RELEASE_AFTER_END_MS = 3_000L
    }
}

private const val MAX_ROUTE_MS = 12L * 60L * 60L * 1000L

/** This guidance in the SDK's shared guidance shape; everything the wearer sees carries over. */
internal fun NavGuidance.toGuidanceStep() = NexusGuidanceStep(
    glyph = glyph,
    primary = primary,
    secondary = secondary,
    eta = eta,
    detail = detail,
    badge = badge,
    track = track?.let { NexusActivityTrack(it.count, it.at, it.target, it.label) },
    measure = measure,
    progressPercent = progressPercent,
    stepKey = stepKey,
    imminent = imminent,
    arrived = arrived,
    // Only what the bounded lines lost: an older hub draws those, a newer one scrolls these.
    wholeSecondary = street?.let { NavText.fit(it, ActivitySurfaceContract.MAX_WHOLE_CHARS) }?.takeIf { it != secondary },
    wholeDetail = wholeDetail.map { NavText.fit(it, ActivitySurfaceContract.MAX_WHOLE_CHARS) }.takeIf { it != detail }.orEmpty(),
)

// Guidance is worth lighting a sleeping display for; the platform still caps
// how often any wake happens.
internal fun NexusGuidanceStep.toRouteActivity(): NexusActivity =
    toActivity(maxDurationMs = MAX_ROUTE_MS, wakeDisplay = true)

internal fun NavGuidance.toActivity(): NexusActivity = toGuidanceStep().toRouteActivity()
