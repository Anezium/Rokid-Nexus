package com.anezium.rokidbus.phone

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
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
    private var lastPublishedCoordinator: MediaTriggerCoordinator? = null
    private val watchedControllers =
        LinkedHashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()

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
        MediaTriggerSensorStore.coordinator?.onListenerLost()
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionChangeListener)
        mediaSessionManager = null
        clearControllerCallbacks()
        lastPublishedCoordinator = null
        handler.removeCallbacksAndMessages(null)
    }

    override fun onDestroy() {
        onListenerDisconnected()
        super.onDestroy()
    }

    private fun listenerComponent(): ComponentName =
        ComponentName(this, MediaTriggerNotificationListenerService::class.java)

    private fun resubscribe() {
        mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionChangeListener)
        // The component is our NLS identity (notification-access grant), not a package filter.
        mediaSessionManager?.addOnActiveSessionsChangedListener(
            sessionChangeListener,
            listenerComponent(),
            Handler(Looper.getMainLooper()),
        )
    }

    private val sessionChangeListener = MediaSessionManager.OnActiveSessionsChangedListener {
        pushPlayingState()
    }

    /** Resolve whether any active session is currently playing and forward the edge. */
    private fun pushPlayingState() {
        val controllers = runCatching {
            mediaSessionManager?.getActiveSessions(listenerComponent()).orEmpty()
        }.getOrDefault(emptyList())
        syncControllerCallbacks(controllers)
        val coordinator = MediaTriggerSensorStore.coordinator ?: return
        val anyPlaying = controllers.any { controller ->
            runCatching { isMediaPlaybackPlaying(controller.playbackState) }.getOrDefault(false)
        }
        coordinator.onPlaybackChanged(anyPlaying)
        lastPublishedCoordinator = coordinator
    }

    private fun syncControllerCallbacks(controllers: List<MediaController>) {
        val live = LinkedHashMap<MediaSession.Token, MediaController>()
        for (controller in controllers) {
            live[controller.sessionToken] = controller
        }
        val stale = watchedControllers.keys.filter { it !in live.keys }
        for (token in stale) {
            val (controller, callback) = watchedControllers.remove(token) ?: continue
            runCatching { controller.unregisterCallback(callback) }
        }
        for ((token, controller) in live) {
            if (token in watchedControllers) continue
            val callback = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    pushPlayingState()
                }

                override fun onSessionDestroyed() {
                    pushPlayingState()
                }
            }
            val registered = runCatching {
                controller.registerCallback(callback, handler)
            }.isSuccess
            if (registered) {
                watchedControllers[token] = controller to callback
            }
        }
    }

    private fun clearControllerCallbacks() {
        for ((controller, callback) in watchedControllers.values) {
            runCatching { controller.unregisterCallback(callback) }
        }
        watchedControllers.clear()
    }

    private fun scheduleGraceTick() {
        val tick = Runnable {
            val coordinator = MediaTriggerSensorStore.coordinator
            if (
                shouldResyncMediaTriggerListener(
                    listenerConnected = watching,
                    coordinatorAvailable = coordinator != null,
                    watchedControllersEmpty = watchedControllers.isEmpty(),
                    coordinatorChanged = coordinator !== lastPublishedCoordinator,
                )
            ) {
                pushPlayingState()
            }
            coordinator?.tickGrace()
            if (watching) scheduleGraceTick()
        }
        handler.postDelayed(tick, GRACE_TICK_PERIOD_MS)
    }

    companion object {
        private const val GRACE_TICK_PERIOD_MS = 5_000L
    }
}

internal fun shouldResyncMediaTriggerListener(
    listenerConnected: Boolean,
    coordinatorAvailable: Boolean,
    watchedControllersEmpty: Boolean,
    coordinatorChanged: Boolean,
): Boolean =
    listenerConnected &&
        coordinatorAvailable &&
        (watchedControllersEmpty || coordinatorChanged)

/**
 * API-30-safe stand-in for [PlaybackState.isActive] (API 31+), minus transient
 * connecting / skip states that do not mean music is playing.
 */
internal fun isMediaPlaybackPlaying(state: PlaybackState?): Boolean {
    val code = state?.state ?: return false
    return code == PlaybackState.STATE_PLAYING ||
        code == PlaybackState.STATE_BUFFERING ||
        code == PlaybackState.STATE_FAST_FORWARDING ||
        code == PlaybackState.STATE_REWINDING
}

/**
 * The hub drops its [MediaTriggerCoordinator] here for the NLS to read. The NLS is a
 * long-lived platform component with its own instance lifetime, so a static holder is the
 * reliable bridge; the hub resets it on every start.
 */
object MediaTriggerSensorStore {
    @Volatile var coordinator: MediaTriggerCoordinator? = null
}
