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

internal data class MediaDeckSnapshot(
    val packageName: String,
    val sourceLabel: String,
    val mediaId: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long?,
    val positionMs: Long,
    val isPlaying: Boolean,
    val playbackSpeed: Float,
    val artwork: Bitmap?,
    val artworkUri: String,
)

internal enum class MediaDeckMonitorStatus {
    STARTING,
    ACCESS_REQUIRED,
    NO_SESSION,
    UNAVAILABLE,
}

internal class MediaSessionMonitor(
    context: Context,
    private val onSnapshot: (MediaDeckSnapshot?) -> Unit,
    private val onStatus: (MediaDeckMonitorStatus) -> Unit,
) {
    private val appContext = context.applicationContext
    private val mediaSessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent =
        ComponentName(appContext, MediaDeckNotificationListenerService::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())

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

    fun start() = runOnMain {
        if (started) {
            refreshOnMain()
            return@runOnMain
        }
        started = true
        onStatus(MediaDeckMonitorStatus.STARTING)
        accessSubscription = MediaDeckAccessSignal.subscribe { refresh() }
        refreshOnMain()
    }

    fun stop() = runOnMain {
        if (!started) return@runOnMain
        started = false
        mainHandler.removeCallbacks(refreshAfterControl)
        accessSubscription?.invoke()
        accessSubscription = null
        removeSessionsListener()
        currentController?.unregisterCallback(controllerCallback)
        currentController = null
    }

    fun refresh() = runOnMain { refreshOnMain() }

    fun togglePlayback() = runOnMain {
        val controller = resolveControllerForControl() ?: return@runOnMain
        val isPlaying = controller.playbackState?.state in PLAYING_STATES
        runCatching {
            if (isPlaying) controller.transportControls.pause() else controller.transportControls.play()
        }.onSuccess { scheduleRefresh() }.onFailure {
            onStatus(MediaDeckMonitorStatus.UNAVAILABLE)
        }
    }

    fun skipToNext() = sendTransportCommand { skipToNext() }

    fun skipToPrevious() = sendTransportCommand { skipToPrevious() }

    private fun sendTransportCommand(command: MediaController.TransportControls.() -> Unit) = runOnMain {
        val controller = resolveControllerForControl() ?: return@runOnMain
        runCatching { controller.transportControls.command() }
            .onSuccess { scheduleRefresh() }
            .onFailure { onStatus(MediaDeckMonitorStatus.UNAVAILABLE) }
    }

    private fun refreshOnMain() {
        if (!started) return
        if (!isNotificationAccessEnabled()) {
            detachController()
            removeSessionsListener()
            onStatus(MediaDeckMonitorStatus.ACCESS_REQUIRED)
            onSnapshot(null)
            return
        }
        ensureSessionsListener()
        val controllers = try {
            mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty()
        } catch (_: SecurityException) {
            detachController()
            removeSessionsListener()
            onStatus(MediaDeckMonitorStatus.ACCESS_REQUIRED)
            onSnapshot(null)
            return
        } catch (_: RuntimeException) {
            detachController()
            onStatus(MediaDeckMonitorStatus.UNAVAILABLE)
            onSnapshot(null)
            return
        }
        attachToController(selectController(controllers))
    }

    private fun ensureSessionsListener() {
        if (!started || sessionsListenerRegistered) return
        runCatching {
            mediaSessionManager?.addOnActiveSessionsChangedListener(
                sessionsChangedListener,
                listenerComponent,
                mainHandler,
            )
        }.onSuccess {
            sessionsListenerRegistered = true
        }.onFailure {
            onStatus(MediaDeckMonitorStatus.UNAVAILABLE)
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
        val snapshot = buildSnapshot(currentController)
        if (snapshot == null) {
            onStatus(MediaDeckMonitorStatus.NO_SESSION)
            onSnapshot(null)
            return
        }
        onSnapshot(snapshot)
    }

    private fun resolveControllerForControl(): MediaController? {
        currentController?.let { return it }
        if (!isNotificationAccessEnabled()) {
            onStatus(MediaDeckMonitorStatus.ACCESS_REQUIRED)
            return null
        }
        val controller = runCatching {
            selectController(mediaSessionManager?.getActiveSessions(listenerComponent).orEmpty())
        }.getOrNull()
        if (controller == null) {
            onStatus(MediaDeckMonitorStatus.NO_SESSION)
            return null
        }
        attachToController(controller)
        return controller
    }

    @Suppress("DEPRECATION")
    private fun buildSnapshot(controller: MediaController?): MediaDeckSnapshot? {
        controller ?: return null
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
        return MediaDeckSnapshot(
            packageName = controller.packageName,
            sourceLabel = sourceLabel(controller.packageName),
            mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID).orEmpty(),
            title = title,
            artist = artist,
            album = metadata.getString(MediaMetadata.METADATA_KEY_ALBUM).orEmpty().trim(),
            durationMs = duration,
            positionMs = position,
            isPlaying = state?.let {
                it.state == PlaybackState.STATE_PLAYING && it.playbackSpeed > 0f
            } == true,
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

    private fun isNotificationAccessEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            appContext.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        return enabled.split(':').any { flattened ->
            ComponentName.unflattenFromString(flattened) == listenerComponent
        }
    }

    private fun scheduleRefresh() {
        mainHandler.removeCallbacks(refreshAfterControl)
        mainHandler.postDelayed(refreshAfterControl, CONTROL_REFRESH_MS)
    }

    private fun runOnMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private companion object {
        const val CONTROL_REFRESH_MS = 350L

        val PLAYING_STATES = setOf(
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_BUFFERING,
        )

        fun playbackPriority(state: Int?): Int = when (state) {
            PlaybackState.STATE_PLAYING,
            PlaybackState.STATE_FAST_FORWARDING,
            PlaybackState.STATE_REWINDING,
            PlaybackState.STATE_BUFFERING,
            -> 3
            PlaybackState.STATE_PAUSED -> 2
            PlaybackState.STATE_CONNECTING -> 1
            else -> 0
        }
    }
}
