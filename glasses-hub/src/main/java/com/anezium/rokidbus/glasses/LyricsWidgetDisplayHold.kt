package com.anezium.rokidbus.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import com.anezium.rokidbus.shared.WidgetAnchor

/**
 * The glasses-side karaoke display hold: a renewable wake-lock episode driven by the ambient
 * widget's visibility and anchor state, with the widget contract's bounds (short renewable
 * holds, released shortly after pause/hide, hard 10-minute per-track ceiling). It must never
 * run in Glance mode and must never touch [DisplayWakePolicy]'s global wake budget — this is a
 * separate, deliberate episode exactly like [AssistantDisplayEpisode]'s.
 *
 * All calls must happen on the main thread (the renderer already runs there).
 */
internal object LyricsWidgetDisplayHold {
    private const val OWNER = "rokidbus:lyrics-karaoke-hold"

    private val main = Handler(Looper.getMainLooper())
    private val policy = KaraokeHoldPolicy(now = SystemClock::elapsedRealtime)
    private var wakeLock: PowerManager.WakeLock? = null
    private var renewTask: Runnable? = null
    private var runningContext: Context? = null
    private var lastAcceptedAnchor: WidgetAnchor? = null
    private var lastAcceptedContentKey: String? = null
    private var lastAnchorAcceptedAt = 0L

    fun isHeld(): Boolean = policy.isHeld

    /**
     * Re-evaluate with current widget state. [display] is whether the widget is on screen,
     * [anchor] and [contentKey] come from the active widget (null when hidden).
     */
    fun update(display: Boolean, anchor: WidgetAnchor?, contentKey: String?) {
        main.removeCallbacksAndMessages(null)
        renewTask = null
        if (anchor == null || contentKey == null) {
            clearAnchorTracking()
            apply(policy.forceRelease())
            return
        }
        if (anchor != lastAcceptedAnchor || contentKey != lastAcceptedContentKey) {
            lastAcceptedAnchor = anchor
            lastAcceptedContentKey = contentKey
            lastAnchorAcceptedAt = SystemClock.elapsedRealtime()
        }
        apply(
            policy.update(
                display = display,
                playing = anchor.playing,
                holdDisplay = WidgetStateMachine.holdDisplay,
                contentKey = contentKey,
                anchorAcceptedAt = lastAnchorAcceptedAt,
            ),
        )
        if (policy.isHeld) {
            val task = Runnable { update(display, anchor, contentKey) }
            renewTask = task
            main.postDelayed(task, KaraokeHoldPolicy.DEFAULT_HOLD_MS / 2)
        }
    }

    fun forceStop() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post(::forceStop)
            return
        }
        main.removeCallbacksAndMessages(null)
        renewTask = null
        apply(policy.forceRelease())
        clearAnchorTracking()
    }

    @Suppress("DEPRECATION")
    private fun apply(action: KaraokeHoldPolicy.Action) {
        when (action) {
            KaraokeHoldPolicy.Action.Nothing -> Unit
            is KaraokeHoldPolicy.Action.Acquire -> acquire(action.holdMs)
            KaraokeHoldPolicy.Action.Release -> release()
        }
    }

    @Suppress("DEPRECATION")
    private fun acquire(holdMs: Long) {
        val context = runningContext ?: return
        val power = context.getSystemService(PowerManager::class.java) ?: return
        // Renewals replace the prior timed lock. Keeping the old instance alive while assigning
        // a new one leaks an unreachable lock until its timeout and can stack multiple live holds.
        release()
        val lock = power.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
            OWNER,
        ).apply { setReferenceCounted(false) }
        lock.acquire(holdMs)
        wakeLock = lock
        log("lyrics karaoke hold acquired holdMs=$holdMs")
    }

    private fun release() {
        wakeLock?.let { runCatching { if (it.isHeld) it.release() } }
        wakeLock = null
    }

    /** The renderer provides its context so this object stays Android-coupled only at the edge. */
    fun setContext(context: Context?) {
        runningContext = context
    }

    private fun clearAnchorTracking() {
        lastAcceptedAnchor = null
        lastAcceptedContentKey = null
        lastAnchorAcceptedAt = 0L
    }
}
