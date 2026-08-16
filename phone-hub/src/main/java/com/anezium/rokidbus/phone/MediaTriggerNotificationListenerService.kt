package com.anezium.rokidbus.phone

import android.media.session.MediaSessionManager
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService

/**
 * The phone hub's NotificationListenerService: it exists so
 * [MediaSessionManager.getActiveSessions] returns live sessions (that API requires a
 * notification-listener component; the hub had none before this). It watches sessions
 * passively and feeds playback edges to the hub's [MediaTriggerCoordinator].
 *
 * It stays inert without notification access: with no grant, [onListenerConnected] never
 * runs and no session is watched. The hub supplies its coordinator through
 * [MediaTriggerSensorStore] on a hub start; a hub restart simply re-attaches.
 */
class MediaTriggerNotificationListenerService : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaSessionManager: MediaSessionManager? = null
    private var watching = false

    override fun onListenerConnected() {
        watching = true
        val manager = getSystemService(MediaSessionManager::class.java)
        mediaSessionManager = manager
        resubscribe()
        pushPlayingState()
        scheduleGraceTick()
    }

    override fun onListenerDisconnected() {
        watching = false
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionChangeListener)
        mediaSessionManager = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }

    private fun resubscribe() {
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionChangeListener)
        mediaSessionManager?.addOnActiveSessionsChangedListener(
            sessionChangeListener,
            null, // no component filter: watch every package's sessions
            Handler(Looper.getMainLooper()),
        )
    }

    private val sessionChangeListener = MediaSessionManager.OnActiveSessionsChangedListener {
        pushPlayingState()
    }

    /** Resolve whether any active session is currently playing and forward the edge. */
    private fun pushPlayingState() {
        val coordinator = MediaTriggerSensorStore.coordinator ?: return
        val controllers = runCatching {
            mediaSessionManager?.getActiveSessions(null).orEmpty()
        }.getOrDefault(emptyList())
        val anyPlaying = controllers.any { controller ->
            runCatching { controller.playbackState?.isActive == true }.getOrDefault(false)
        }
        coordinator.onPlaybackChanged(anyPlaying)
    }

    private fun scheduleGraceTick() {
        val tick = Runnable {
            MediaTriggerSensorStore.coordinator?.tickGrace()
            if (watching) scheduleGraceTick()
        }
        handler.postDelayed(tick, GRACE_TICK_PERIOD_MS)
    }

    companion object {
        private const val GRACE_TICK_PERIOD_MS = 5_000L
    }
}

/**
 * The hub drops its [MediaTriggerCoordinator] here for the NLS to read. The NLS is a
 * long-lived platform component with its own instance lifetime, so a static holder is the
 * reliable bridge; the hub resets it on every start.
 */
object MediaTriggerSensorStore {
    @Volatile var coordinator: MediaTriggerCoordinator? = null
}