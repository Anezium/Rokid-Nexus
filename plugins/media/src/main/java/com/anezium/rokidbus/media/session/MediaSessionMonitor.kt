package com.anezium.rokidbus.media.session

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings

/** One look at the observed session, with what only the full surface needs: position and artwork. */
internal data class MediaDeckSnapshot(
    val identity: MediaSessionIdentity,
    val track: MediaTrack,
    val phase: MediaPlaybackPhase,
    val positionMs: Long,
    /** Whether the position moves by itself; see [MediaPlayback.advances]. */
    val advancing: Boolean,
    val playbackSpeed: Float,
    val artwork: Bitmap?,
    val artworkUri: String,
) {
    val isPlaying: Boolean get() = phase == MediaPlaybackPhase.PLAYING
    val packageName: String get() = track.packageName
    val sourceLabel: String get() = track.sourceLabel
    val title: String get() = track.title
    val artist: String get() = track.artist
    val album: String get() = track.album
    val durationMs: Long? get() = track.durationMs

    val observation: MediaDeckObservation.Session get() = MediaDeckObservation.Session(identity, track, phase)
}

/**
 * Watches the active media sessions through Media Deck's notification listener. Nothing polls:
 * the session list and the observed controller call back, and a command schedules one look once
 * the player has had a moment to act on it.
 */
internal class MediaSessionMonitor(
    context: Context,
    private val listener: MediaDeckMonitorListener,
) : MediaDeckMonitor {
    private val appContext = context.applicationContext
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent =
        ComponentName(appContext, MediaDeckNotificationListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val identities = MediaSessionIdentities()

    private var currentController: MediaController? = null
    private var sessionsListenerRegistered = false
    private var accessSubscription: (() -> Unit)? = null
    private var started = false

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emitCurrentState()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emitCurrentState()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            attachToController(selectController(controllers.orEmpty()))
        }

    private val refreshAfterControl = Runnable { refreshOnMain() }

    override fun start() = runOnMain {
        if (started) {
            refreshOnMain()
            return@runOnMain
        }
        started = true
        accessSubscription = MediaDeckAccessSignal.subscribe { refresh() }
        refreshOnMain()
    }

    override fun stop() = runOnMain {
        if (!started) return@runOnMain
        started = false
        mainHandler.removeCallbacks(refreshAfterControl)
        accessSubscription?.invoke()
        accessSubscription = null
        removeSessionsListener()
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    override fun refresh() = runOnMain { refreshOnMain() }

    override fun send(expected: MediaDeckObservation.Session, command: MediaDeckCommand): MediaDeckSendResult {
        if (!started) return MediaDeckSendResult.MOVED
        if (!isNotificationAccessEnabled()) {
            refreshOnMain()
            return MediaDeckSendResult.ACCESS_REQUIRED
        }
        // Only the session the wearer saw, never another active one picked now.
        val controller = currentController
        val live = controller?.let { runCatching { buildSnapshot(it) }.getOrNull() }
        if (controller == null || live == null || live.observation != expected) {
            mainHandler.post { refreshOnMain() }
            return MediaDeckSendResult.MOVED
        }
        return runCatching {
            val controls = controller.transportControls
            when (command) {
                MediaDeckCommand.PREVIOUS -> controls.skipToPrevious()
                MediaDeckCommand.PLAY -> controls.play()
                MediaDeckCommand.PAUSE -> controls.pause()
                MediaDeckCommand.NEXT -> controls.skipToNext()
            }
        }.fold(
            onSuccess = {
                scheduleRefresh()
                MediaDeckSendResult.SENT
            },
            onFailure = { failure ->
                mainHandler.post { refreshOnMain() }
                if (failure is SecurityException) MediaDeckSendResult.ACCESS_REQUIRED else MediaDeckSendResult.FAILED
            },
        )
    }

    private fun refreshOnMain() {
        if (!started) return
        if (!isNotificationAccessEnabled()) {
            detachController()
            removeSessionsListener()
            listener.onObserved(MediaDeckObservation.AccessRequired, null)
            return
        }
        ensureSessionsListener()
        val controllers = try {
            mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty()
        } catch (_: SecurityException) {
            detachController()
            removeSessionsListener()
            listener.onObserved(MediaDeckObservation.AccessRequired, null)
            return
        } catch (_: RuntimeException) {
            detachController()
            listener.onObserved(MediaDeckObservation.Unavailable, null)
            return
        }
        attachToController(selectController(controllers))
    }

    private fun ensureSessionsListener() {
        if (!started || sessionsListenerRegistered) return
        // A failure is retried on the next look; the controller callbacks still report meanwhile.
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler,
            )
        }.onSuccess {
            sessionsListenerRegistered = true
        }
    }

    private fun removeSessionsListener() {
        if (!sessionsListenerRegistered) return
        runCatching {
            mediaSessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        }
        sessionsListenerRegistered = false
    }

    private fun attachToController(controller: MediaController?) {
        if (currentController?.sessionToken == controller?.sessionToken) {
            emitCurrentState()
            return
        }
        currentController?.unregisterCallback(controllerCallback)
        currentController = controller
        controller?.registerCallback(controllerCallback, mainHandler)
        emitCurrentState()
    }

    private fun detachController() {
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    private fun emitCurrentState() {
        if (!started) return
        val snapshot = currentController?.let { controller ->
            try {
                buildSnapshot(controller)
            } catch (_: RuntimeException) {
                // A controller that cannot be read is not shown as if nothing played.
                detachController()
                listener.onObserved(MediaDeckObservation.Unavailable, null)
                return
            }
        }
        if (snapshot == null) {
            listener.onObserved(MediaDeckObservation.NoSession, null)
        } else {
            listener.onObserved(snapshot.observation, snapshot)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildSnapshot(controller: MediaController): MediaDeckSnapshot? {
        val metadata = controller.metadata ?: return null
        val title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().trim()
            .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty().trim() }
        if (title.isBlank()) return null
        val artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST).orEmpty().trim()
            .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST).orEmpty().trim() }
            .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty().trim() }
        val state = controller.playbackState
        val duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0L }
        val position = estimatePosition(state).let { value ->
            duration?.let { value.coerceAtMost(it) } ?: value
        }
        val artwork = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: metadata.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val packageName = controller.packageName
        return MediaDeckSnapshot(
            identity = identities.identityOf(packageName, controller.sessionToken),
            track = MediaTrack(
                packageName = packageName,
                sourceLabel = sourceLabel(packageName),
                mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
                title = title,
                artist = artist,
                album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim(),
                durationMs = duration,
            ),
            phase = MediaPlayback.phaseOf(state?.state),
            positionMs = position,
            advancing = MediaPlayback.advances(state?.state, state?.playbackSpeed ?: 0f),
            playbackSpeed = state?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
            artwork = artwork,
            artworkUri = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI).orEmpty()
                .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_ART_URI).orEmpty() }
                .ifBlank { metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI).orEmpty() },
        )
    }

    private fun selectController(controllers: List<MediaController>): MediaController? =
        controllers
            .filter(::hasUsableMetadata)
            .sortedWith(
                compareByDescending<MediaController> { playbackPriority(it.playbackState?.state) }
                    .thenByDescending { it.playbackState?.lastPositionUpdateTime ?: Long.MIN_VALUE },
            )
            .firstOrNull()

    private fun hasUsableMetadata(controller: MediaController): Boolean {
        val metadata = controller.metadata ?: return false
        return metadata.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty().isNotBlank() ||
            metadata.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).orEmpty().isNotBlank()
    }

    private fun estimatePosition(state: PlaybackState?): Long {
        state ?: return 0L
        val base = state.position.coerceAtLeast(0L)
        if (state.state != PlaybackState.STATE_PLAYING || state.lastPositionUpdateTime <= 0L) return base
        val elapsed = (SystemClock.elapsedRealtime() - state.lastPositionUpdateTime).coerceAtLeast(0L)
        return (base + elapsed * state.playbackSpeed).toLong().coerceAtLeast(0L)
    }

    @Suppress("DEPRECATION")
    private fun sourceLabel(packageName: String): String = runCatching {
        val info = appContext.packageManager.getApplicationInfo(packageName, 0)
        appContext.packageManager.getApplicationLabel(info).toString().trim()
    }.getOrDefault(packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() })

    private fun isNotificationAccessEnabled(): Boolean = isMediaDeckListenerEnabled(appContext)

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshAfterControl)
        mainHandler.postDelayed(refreshAfterControl, CONTROL_REFRESH_MS)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val CONTROL_REFRESH_MS = 350L

        fun playbackPriority(state: Int?): Int = when (MediaPlayback.phaseOf(state)) {
            MediaPlaybackPhase.PLAYING -> 2
            MediaPlaybackPhase.PAUSED -> 1
            MediaPlaybackPhase.STOPPED -> 0
        }
    }
}

/** Whether Media Deck's notification listener is enabled: what every session read requires. */
internal fun isMediaDeckListenerEnabled(context: Context): Boolean {
    val target = ComponentName(context, MediaDeckNotificationListenerService::class.java)
    val enabled = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty()
    return enabled.split(':').any { flattened -> ComponentName.unflattenFromString(flattened) == target }
}
