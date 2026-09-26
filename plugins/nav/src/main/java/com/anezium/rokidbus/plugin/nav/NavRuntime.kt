package com.anezium.rokidbus.plugin.nav

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.anezium.rokidbus.client.PluginRegistrationResult
import com.anezium.rokidbus.client.plugin.NexusActivity
import com.anezium.rokidbus.client.plugin.NexusActivityProgress
import com.anezium.rokidbus.client.plugin.NexusActivityTrack
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.surfaceSession

/**
 * Keeps the live route as one activity for as long as a navigation app posts
 * guidance. It runs in the notification listener's process, which the system
 * keeps bound while Notification Access is granted.
 *
 * An activity ends when its owner disconnects, and the hub serves one
 * registration per plugin, so the owner is [NavPluginService]'s client: the
 * runtime keeps that service bound while guidance is live and releases it a
 * moment after the route ends. Nothing polls: each posted notification is one
 * chance to update.
 */
internal class NavRuntime(context: Context) {
    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val planner = NavActivityPlanner()
    private var pending: NavGuidance? = null
    private var deferred: NavGuidance? = null
    private var started = false
    private var startedGeneration = NO_GENERATION
    private var lastSentAtMs = 0L
    private val sendDeferred = Runnable { flushDeferred() }
    private val releaseIdle = Runnable { NavControl.releaseBus(appContext) }

    private val client get() = NavControl.serviceClient

    fun onGuidance(guidance: NavGuidance) = onMain {
        NavState.guidance = guidance
        main.removeCallbacks(releaseIdle)
        pending = guidance
        NavControl.holdBus(appContext)
        flush()
        refreshCard()
    }

    /** The app withdrew its guidance, or it became unreadable: the route is over. */
    fun onRouteEnded(source: NavSource) = onMain {
        if (NavState.guidance?.source != source && pending?.source != source) return@onMain
        NavState.guidance = null
        pending = null
        deferred = null
        main.removeCallbacks(sendDeferred)
        planner.reset()
        if (started) {
            log("end result=${client?.endActivity()}")
            started = false
        }
        refreshCard()
        // Give the end a moment on the bus before the registration that owns it goes.
        main.removeCallbacks(releaseIdle)
        main.postDelayed(releaseIdle, RELEASE_AFTER_END_MS)
    }

    fun shutdown() = onMain {
        NavState.guidance = null
        pending = null
        deferred = null
        planner.reset()
        if (started) client?.endActivity()
        started = false
        main.removeCallbacks(releaseIdle)
        NavControl.releaseBus(appContext)
    }

    /** The plugin service exists again, with a client to send through. */
    fun onBusReady() = onMain {
        started = false
        startedGeneration = NO_GENERATION
        planner.reset()
        pending = pending ?: NavState.guidance
        flush()
    }

    /** The plugin service went away; its registration took the activity with it. */
    fun onBusLost() = onMain {
        started = false
        startedGeneration = NO_GENERATION
        planner.reset()
        pending = pending ?: NavState.guidance
        // Still guiding: bind again, which recreates the service and its client.
        if (pending != null) NavControl.holdBus(appContext)
    }

    fun onLinkState(state: Int) = onMain { flush() }

    fun onRegistrationState(result: Int) = onMain {
        NavState.registration = result
        if (result != PluginRegistrationResult.APPROVED) {
            log("registration result=$result")
            return@onMain
        }
        // Approval comes twice per registration; only a new registration has
        // lost the activity (the old one ended with its connection).
        if (client?.registrationGeneration != startedGeneration) {
            started = false
            planner.reset()
            pending = pending ?: NavState.guidance
        }
        flush()
    }

    fun onActivityClosed(reason: String) = onMain {
        log("activity closed reason=$reason")
        started = false
        planner.reset()
    }

    private fun flush() {
        val current = client ?: return
        // Capabilities can arrive with the link before approval does; nothing
        // is sent until the registration it would belong to exists.
        if (!current.isApproved || !current.supportsActivitySurface) return
        val next = pending ?: return
        pending = null
        if (!started) planner.reset()
        when (val plan = planner.plan(next)) {
            is NavPlan.Start -> send(start = true, guidance = plan.guidance)
            is NavPlan.Update -> {
                val sinceLast = SystemClock.elapsedRealtime() - lastSentAtMs
                if (!plan.significant && sinceLast < MIN_QUIET_INTERVAL_MS) {
                    // Maps rewrites its notification every few metres. A quiet
                    // change waits for the next slot; the newest one wins.
                    deferred = plan.guidance
                    main.removeCallbacks(sendDeferred)
                    main.postDelayed(sendDeferred, MIN_QUIET_INTERVAL_MS - sinceLast)
                } else {
                    send(guidance = plan.guidance, significant = plan.significant, urgent = plan.urgent)
                }
            }
            NavPlan.Unchanged -> Unit
        }
    }

    private fun flushDeferred() {
        val guidance = deferred ?: return
        deferred = null
        if (started) send(guidance = guidance)
    }

    private fun send(guidance: NavGuidance, start: Boolean = false, significant: Boolean = false, urgent: Boolean = false) {
        val current = client ?: return
        if (significant || start) {
            deferred = null
            main.removeCallbacks(sendDeferred)
        }
        val activity = guidance.toActivity()
        val result = if (start) {
            current.startActivity(activity)
        } else {
            current.updateActivity(activity, significant = significant, urgent = urgent && current.supportsActivityExtras)
        }
        lastSentAtMs = SystemClock.elapsedRealtime()
        if (result == NexusSdkResult.SENT) {
            started = true
            if (start) startedGeneration = current.registrationGeneration
        } else {
            // Whatever did not arrive is sent whole next time.
            started = false
            planner.reset()
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
        private const val MAX_ROUTE_MS = 12L * 60L * 60L * 1000L
    }

    private fun NavGuidance.toActivity() = NexusActivity(
        glyph = glyph,
        primary = primary,
        secondary = secondary,
        progress = progressPercent?.let { NexusActivityProgress.Percent(it.coerceIn(0, 100)) },
        eta = eta,
        detail = detail,
        maxDurationMs = MAX_ROUTE_MS,
        // Guidance is worth lighting a sleeping display for; the platform
        // still caps how often any wake happens.
        wakeDisplay = true,
        badge = badge,
        track = track?.let { NexusActivityTrack(it.count, it.at, it.target, it.label) },
        measure = measure,
    )
}
