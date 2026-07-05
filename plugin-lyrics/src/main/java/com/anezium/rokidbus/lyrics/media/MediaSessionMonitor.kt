package com.anezium.rokidbus.lyrics.media

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationManagerCompat

data class MediaPlaybackSnapshot(
    val packageName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
)

class MediaSessionMonitor(
    context: Context,
    private val onPlaybackSnapshot: (MediaPlaybackSnapshot?) -> Unit,
    private val onStatusChanged: (String) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mediaSessionManager =
        appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent =
        ComponentName(appContext, MediaNotificationListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var currentController: MediaController? = null
    private var activeSessionsListenerRegistered = false
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emitCurrentState()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emitCurrentState()
        override fun onSessionDestroyed() = refresh()
    }

    private val progressTicker = object : Runnable {
        override fun run() {
            emitCurrentState()
            if (shouldScheduleProgress(currentController?.playbackState)) {
                mainHandler.postDelayed(this, 300L)
            }
        }
    }

    private val activeSessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToController(selectController(controllers.orEmpty()))
        }

    fun start() {
        if (started) {
            ensureActiveSessionsListener()
            refresh()
            return
        }
        started = true
        if (!isNotificationAccessEnabled()) {
            onStatusChanged("Enable notification access so Lyrics can read media sessions.")
            onPlaybackSnapshot(null)
            return
        }
        ensureActiveSessionsListener()
        refresh()
    }

    fun stop() {
        started = false
        mainHandler.removeCallbacks(progressTicker)
        runCatching {
            if (activeSessionsListenerRegistered) {
                mediaSessionManager?.removeOnActiveSessionsChangedListener(activeSessionsChangedListener)
            }
        }
        activeSessionsListenerRegistered = false
        attachToController(null)
    }

    fun refresh() {
        if (!started) return
        if (!isNotificationAccessEnabled()) {
            attachToController(null)
            onStatusChanged("Notification access is disabled. Enable it to follow media playback.")
            onPlaybackSnapshot(null)
            return
        }
        ensureActiveSessionsListener()
        val controllers = runCatching {
            mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty()
        }.getOrElse {
            onStatusChanged("Unable to query active media sessions: ${it.message ?: "unknown error"}")
            emptyList()
        }
        attachToController(selectController(controllers))
    }

    fun togglePlayback() {
        if (!started) return
        val controller = resolveControllerForControl()
        if (controller == null) {
            onStatusChanged("No active media session to control. Start a media app on the phone.")
            return
        }

        val playbackState = controller.playbackState
        val transportControls = controller.transportControls
        val isPlaying = playbackState?.state in setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_BUFFERING,
        )

        runCatching {
            if (isPlaying) {
                transportControls.pause()
            } else {
                transportControls.play()
            }
        }.onFailure {
            onStatusChanged("Unable to control playback: ${it.message ?: "unknown error"}")
        }
    }

    fun skipToNext() {
        sendTransportCommand("next track") { skipToNext() }
    }

    fun skipToPrevious() {
        sendTransportCommand("previous track") { skipToPrevious() }
    }

    private fun sendTransportCommand(
        label: String,
        command: MediaController.TransportControls.() -> Unit,
    ) {
        if (!started) return
        val controller = resolveControllerForControl()
        if (controller == null) {
            onStatusChanged("No active media session to control. Start a media app on the phone.")
            return
        }
        runCatching {
            controller.transportControls.command()
        }.onFailure {
            onStatusChanged("Unable to send $label: ${it.message ?: "unknown error"}")
        }
    }

    private fun attachToController(controller: MediaController?) {
        val previous = currentController
        if (previous?.sessionToken == controller?.sessionToken) {
            emitCurrentState()
            return
        }
        previous?.unregisterCallback(controllerCallback)
        currentController = controller
        controller?.registerCallback(controllerCallback, mainHandler)
        emitCurrentState()
    }

    private fun emitCurrentState() {
        mainHandler.removeCallbacks(progressTicker)
        val snapshot = buildSnapshot(currentController)
        if (snapshot == null) {
            onStatusChanged("No active media session found. Start a media app on the phone.")
            onPlaybackSnapshot(null)
            return
        }
        onStatusChanged("Tracking ${sourceLabel(snapshot.packageName)} - ${snapshot.title} / ${snapshot.artist}")
        onPlaybackSnapshot(snapshot)
        if (shouldScheduleProgress(currentController?.playbackState)) {
            mainHandler.postDelayed(progressTicker, 300L)
        }
    }

    private fun ensureActiveSessionsListener() {
        if (!started || activeSessionsListenerRegistered) return
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                activeSessionsChangedListener,
                listenerComponent,
                mainHandler,
            )
        }.onSuccess {
            activeSessionsListenerRegistered = true
        }.onFailure {
            onStatusChanged("Media session listener failed: ${it.message ?: "unknown error"}")
        }
    }

    private fun resolveActiveController(): MediaController? {
        if (!isNotificationAccessEnabled()) return null
        val controllers = runCatching {
            mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty()
        }.getOrElse {
            emptyList()
        }
        val controller = selectController(controllers)
        if (controller != null) {
            attachToController(controller)
        }
        return controller
    }

    private fun resolveControllerForControl(): MediaController? =
        currentController ?: resolveActiveController()

    private fun buildSnapshot(controller: MediaController?): MediaPlaybackSnapshot? {
        controller ?: return null
        val metadata = controller.metadata ?: return null
        val playbackState = controller.playbackState
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            .orEmpty()
            .trim()
            .ifBlank {
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().trim()
            }
        if (title.isBlank() || artist.isBlank()) return null
        val album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim()
        val durationMs = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
        val isPlaying = playbackState?.state in setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_BUFFERING,
        )
        val speed = playbackState?.playbackSpeed ?: 1f
        val positionMs = estimatePosition(playbackState)
        return MediaPlaybackSnapshot(
            packageName = controller.packageName,
            title = title,
            artist = artist,
            album = album,
            durationMs = durationMs,
            positionMs = positionMs,
            isPlaying = isPlaying,
            playbackSpeed = speed,
        )
    }

    private fun estimatePosition(state: PlaybackState?): Long {
        state ?: return 0L
        val base = state.position.coerceAtLeast(0L)
        if (!shouldScheduleProgress(state)) return base
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (base + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }

    private fun selectController(controllers: List<MediaController>): MediaController? =
        controllers
            .filter(::hasUsableMetadata)
            .sortedWith(
                compareByDescending<MediaController> { controllerPlaybackPriority(it.playbackState?.state) }
                    .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: Long.MIN_VALUE }
            )
            .firstOrNull()

    private fun hasUsableMetadata(controller: MediaController): Boolean {
        val metadata = controller.metadata ?: return false
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST)
            .orEmpty()
            .trim()
            .ifBlank {
                metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().trim()
            }
        return title.isNotBlank() && artist.isNotBlank()
    }

    private fun shouldScheduleProgress(state: PlaybackState?): Boolean =
        state?.state == PlaybackState.STATE_PLAYING && state.lastPositionUpdateTime > 0L

    private fun isNotificationAccessEnabled(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(appContext)
            .contains(appContext.packageName)

    private fun sourceLabel(packageName: String): String = when (packageName) {
        SPOTIFY_PACKAGE -> "Spotify"
        else -> packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }

    private companion object {
        private const val SPOTIFY_PACKAGE = "com.spotify.music"
    }
}

internal fun controllerPlaybackPriority(state: Int?): Int = when (state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_BUFFERING,
    -> 3
    PlaybackState.STATE_PAUSED -> 2
    PlaybackState.STATE_CONNECTING -> 1
    else -> 0
}
