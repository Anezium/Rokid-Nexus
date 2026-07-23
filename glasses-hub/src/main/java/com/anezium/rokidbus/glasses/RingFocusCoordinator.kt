package com.anezium.rokidbus.glasses

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper

/**
 * Edge-triggered union of the launcher and plugin-surface ring owners.
 *
 * A launcher-to-surface handoff temporarily retains focus after the launcher
 * source drops. The matching surface show completes it atomically; a bounded
 * timeout releases a failed handoff.
 */
internal class RingFocusCoordinator(
    private val publish: (Boolean) -> Unit,
) {
    private var launcherShown = false
    private var surfaceActive = false
    private var surfaceHandoffPending = false
    private var publishedFocused = false

    @Synchronized
    fun setLauncherShown(shown: Boolean) {
        launcherShown = shown
        if (shown) surfaceHandoffPending = false
        publishIfChanged()
    }

    @Synchronized
    fun setSurfaceActive(active: Boolean, completesHandoff: Boolean = false) {
        surfaceActive = active
        if (active && completesHandoff) surfaceHandoffPending = false
        publishIfChanged()
    }

    @Synchronized
    fun beginSurfaceHandoff() {
        if (launcherShown) surfaceHandoffPending = true
        publishIfChanged()
    }

    @Synchronized
    fun expireSurfaceHandoff() {
        surfaceHandoffPending = false
        publishIfChanged()
    }

    @Synchronized
    fun reset() {
        launcherShown = false
        surfaceActive = false
        surfaceHandoffPending = false
        publishIfChanged()
    }

    private fun publishIfChanged() {
        val focused = launcherShown || surfaceActive || surfaceHandoffPending
        if (focused == publishedFocused) return
        publishedFocused = focused
        publish(focused)
    }
}

/** Android broadcast adapter around the pure focus coordinator. */
internal object RingFocusBroadcastCoordinator {
    private const val RING_FOCUS_ACTION = "com.anezium.r08accessbridge.action.NEXUS_RING_FOCUS"
    private const val RING_BRIDGE_PACKAGE = "com.anezium.r08accessbridge"
    private const val SURFACE_HANDOFF_TIMEOUT_MS = 10_000L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var inputServiceConnected = false
    private val coordinator = RingFocusCoordinator(::sendFocusBroadcast)
    private val handoffExpiry = Runnable { coordinator.expireSurfaceHandoff() }

    fun onServiceConnected(context: Context, surfaceActive: Boolean) {
        appContext = context.applicationContext
        inputServiceConnected = true
        coordinator.setSurfaceActive(surfaceActive)
    }

    fun onServiceDestroyed(context: Context) {
        appContext = context.applicationContext
        main.removeCallbacks(handoffExpiry)
        coordinator.reset()
        inputServiceConnected = false
        appContext = null
    }

    fun setLauncherShown(context: Context, shown: Boolean) {
        if (!inputServiceConnected) return
        appContext = context.applicationContext
        if (shown) main.removeCallbacks(handoffExpiry)
        coordinator.setLauncherShown(shown)
    }

    fun beginSurfaceHandoff(context: Context) {
        if (!inputServiceConnected) return
        appContext = context.applicationContext
        coordinator.beginSurfaceHandoff()
        main.removeCallbacks(handoffExpiry)
        main.postDelayed(handoffExpiry, SURFACE_HANDOFF_TIMEOUT_MS)
    }

    fun setSurfaceActive(
        context: Context,
        active: Boolean,
        completesHandoff: Boolean = false,
    ) {
        if (!inputServiceConnected) return
        appContext = context.applicationContext
        if (active && completesHandoff) main.removeCallbacks(handoffExpiry)
        coordinator.setSurfaceActive(active, completesHandoff)
    }

    fun setSurfaceInactive() {
        if (!inputServiceConnected) return
        coordinator.setSurfaceActive(false)
    }

    private fun sendFocusBroadcast(focused: Boolean) {
        val context = appContext ?: return
        context.sendBroadcast(
            Intent(RING_FOCUS_ACTION)
                .setPackage(RING_BRIDGE_PACKAGE)
                .putExtra("focused", focused)
                .putExtra("ts", System.currentTimeMillis()),
        )
    }
}
