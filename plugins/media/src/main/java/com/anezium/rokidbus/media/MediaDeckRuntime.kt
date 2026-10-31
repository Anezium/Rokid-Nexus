package com.anezium.rokidbus.media

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusMedia
import com.anezium.rokidbus.client.plugin.NexusMediaAnchor
import com.anezium.rokidbus.client.plugin.NexusMediaImageArtwork
import com.anezium.rokidbus.client.plugin.NexusMonoArtwork
import com.anezium.rokidbus.media.session.MediaDeckCommand
import com.anezium.rokidbus.media.session.MediaDeckControlOutcome
import com.anezium.rokidbus.media.session.MediaDeckMonitorHub
import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import com.anezium.rokidbus.media.session.MediaDeckState
import com.anezium.rokidbus.media.session.MediaDeckStateListener
import com.anezium.rokidbus.media.session.MediaDeckSubscriber
import com.anezium.rokidbus.media.session.MediaDeckTarget
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlin.math.abs

internal interface MediaDeckRuntimeHost {
    fun supportsImage(): Boolean
    fun sendCard(card: NexusCard, show: Boolean)
    fun sendMedia(media: NexusMedia, imageBytes: ByteArray?, show: Boolean)
    fun updateMediaAnchor(contentKey: String, anchor: NexusMediaAnchor)
    fun hideSurface()
}

/**
 * The full surface: the immersion subscriber of the shared monitor. It watches only while the
 * surface is open; closing it leaves the monitor to whoever else still needs it (an enabled
 * activity). Everything it draws is the hub's state, so the status card shows whenever there is no
 * session to draw, and every control goes to the session drawn, at the revision drawn.
 * Main thread only.
 */
internal class MediaDeckRuntime(
    context: Context,
    private val host: MediaDeckRuntimeHost,
    private val hub: MediaDeckMonitorHub,
) {
    private val appContext = context.applicationContext
    private val listener = MediaDeckStateListener { state, snapshot, _ -> render(state, snapshot) }
    private var active = false
    private var surfaceShown = false
    private var latestSnapshot: MediaDeckSnapshot? = null

    /** The state on the surface now; what a tap or a swipe acts on. */
    private var shownState: MediaDeckState? = null
    private var lastRenderedKey: String? = null
    private var lastSentMedia: SentMedia? = null
    private var cachedArtworkTrackKey: String? = null
    private var cachedArtworkMode: MediaArtworkMode? = null
    private var cachedArtwork: EncodedMediaArtwork? = null
    private var cachedArtworkUriAttempt: String? = null

    val isOpen: Boolean get() = active

    fun open() {
        active = true
        surfaceShown = false
        lastRenderedKey = null
        lastSentMedia = null
        shownState = null
        showStatus(MediaDeckState.Unknown(0), force = true)
        // Hears the current state at once; a surface opened again just looks again.
        hub.subscribe(MediaDeckSubscriber.IMMERSION, listener)
    }

    fun close() {
        if (!active) return
        active = false
        // Only this surface's interest ends; an enabled activity keeps the monitor running.
        hub.unsubscribe(MediaDeckSubscriber.IMMERSION)
        latestSnapshot = null
        shownState = null
        lastSentMedia = null
        lastRenderedKey = null
        cachedArtworkTrackKey = null
        cachedArtworkMode = null
        cachedArtwork = null
        cachedArtworkUriAttempt = null
        if (surfaceShown) {
            host.hideSurface()
        }
        surfaceShown = false
    }

    fun input(event: NexusInputEvent) {
        if (!active || event.action != KeyEvent.ACTION_DOWN) return
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> {
                close()
                return
            }
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> {
                val state = shownState
                if (state is MediaDeckState.NoSession) {
                    hub.resumeIdle(state.revision)
                    return
                }
                (state as? MediaDeckState.Active)?.let { MediaDeckCommand.desiredFor(it.phase) } ?: return
            }
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> MediaDeckCommand.NEXT
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> MediaDeckCommand.PREVIOUS
            else -> return
        }
        control(command)
    }

    /**
     * One command on the session drawn, at the revision drawn: never another session, never
     * retried. What follows is drawn from the state observed afterwards.
     */
    fun control(command: MediaDeckCommand): MediaDeckControlOutcome =
        hub.control(shownState?.let { MediaDeckTarget.of(it) }, command)

    fun imageCapabilityChanged() {
        if (!active || cachedArtworkMode == artworkModeFor(host.supportsImage())) return
        latestSnapshot?.let { pushSnapshot(it, force = true) }
    }

    private fun render(state: MediaDeckState, snapshot: MediaDeckSnapshot?) {
        if (!active) return
        shownState = state
        if (state is MediaDeckState.Active && snapshot != null) {
            latestSnapshot = snapshot
            pushSnapshot(snapshot, force = false)
        } else {
            // Whatever is not a session to draw is said, even right after one was drawn.
            latestSnapshot = null
            showStatus(state, force = false)
        }
    }

    private fun showStatus(state: MediaDeckState, force: Boolean) {
        if (!active) return
        val (key, lines) = when (state) {
            is MediaDeckState.Unknown -> "checking" to listOf(
                "Checking active media...",
                "Playback stays on your phone.",
            )
            is MediaDeckState.AccessRequired -> "access" to listOf(
                if (state.revoked) "Media access was turned off." else "Media access is required.",
                "Open Media Deck on the phone",
                "and enable notification access.",
            )
            is MediaDeckState.NoSession, is MediaDeckState.Active -> "idle" to listOf(
                "Ready to play.",
                "Tap to resume your last player.",
                "If silent, open a player on the phone.",
            )
            is MediaDeckState.Unavailable -> "unavailable" to listOf(
                "Media control is unavailable.",
                "Check Media Deck access on the phone.",
            )
        }
        val renderedKey = "status:$key"
        if (!force && lastRenderedKey == renderedKey) return
        host.sendCard(
            NexusCard(
                title = "MEDIA DECK",
                lines = lines,
                footer = "BACK: CLOSE",
                contentKey = renderedKey,
                handlesBack = true,
            ),
            show = !surfaceShown,
        )
        surfaceShown = true
        lastRenderedKey = renderedKey
        lastSentMedia = null
    }

    private fun pushSnapshot(snapshot: MediaDeckSnapshot, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val contentKey = snapshot.track.key
        val artworkMode = artworkModeFor(host.supportsImage())
        val artworkWasMissing = cachedArtworkTrackKey == contentKey &&
            cachedArtworkMode == artworkMode && cachedArtwork == null
        val artwork = artworkFor(contentKey, snapshot, artworkMode)
        val artworkArrived = artworkWasMissing && artwork != null
        val previous = lastSentMedia
        val speed = clockSpeed(snapshot)
        val contentChanged = previous == null || previous.contentKey != contentKey
        val anchorChanged = previous == null ||
            previous.playing != snapshot.isPlaying ||
            previous.playbackSpeed != speed ||
            previous.durationMs != snapshot.durationMs ||
            abs(snapshot.positionMs - previous.predictedPosition(now)) >= SEEK_RESYNC_MS
        if (!force && !contentChanged && !artworkArrived && !anchorChanged) return

        val sendFullPayload = force || contentChanged || artworkArrived
        val anchor = anchor(snapshot, now)
        if (sendFullPayload) {
            val media = buildFullSurface(snapshot, contentKey, artwork, anchor)
            host.sendMedia(
                media,
                imageBytes = (artwork as? EncodedImageArtwork)?.bytes,
                show = !surfaceShown,
            )
        } else {
            host.updateMediaAnchor(contentKey, anchor)
        }
        surfaceShown = true
        lastRenderedKey = "media:$contentKey"
        lastSentMedia = SentMedia(
            contentKey = contentKey,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playing = snapshot.isPlaying,
            playbackSpeed = speed,
            sentAtElapsedRealtime = now,
        )
    }

    private fun buildFullSurface(
        snapshot: MediaDeckSnapshot,
        contentKey: String,
        artwork: EncodedMediaArtwork?,
        anchor: NexusMediaAnchor,
    ): NexusMedia = NexusMedia(
        title = "MEDIA DECK",
        contentKey = contentKey,
        subtitle = MediaText.clip(snapshot.sourceLabel, SOURCE_LIMIT).uppercase().takeIf(String::isNotBlank),
        mediaTitle = MediaText.clip(snapshot.title, TITLE_LIMIT),
        mediaArtist = MediaText.clip(snapshot.artist, ARTIST_LIMIT).takeIf(String::isNotBlank),
        mediaAlbum = MediaText.clip(snapshot.album, ALBUM_LIMIT).takeIf(String::isNotBlank),
        footer = "SWIPE: TRACK  TAP: PLAY/PAUSE",
        anchor = anchor,
        artwork = (artwork as? EncodedMonoArtwork)?.let {
            NexusMonoArtwork(
                width = it.width,
                height = it.height,
                bytes = it.bytes,
                hash = it.hash,
            )
        },
        imageArtwork = (artwork as? EncodedImageArtwork)?.let {
            NexusMediaImageArtwork(
                mimeType = it.mimeType,
                pixelWidth = it.width,
                pixelHeight = it.height,
            )
        },
    )

    /**
     * The glasses draw play or pause from [MediaDeckSnapshot.isPlaying], the same phase a tap acts
     * on; the clock moves only while the position really advances, so a buffering session shows
     * as playing on a standing clock.
     */
    private fun anchor(snapshot: MediaDeckSnapshot, now: Long): NexusMediaAnchor = NexusMediaAnchor(
        positionMs = snapshot.positionMs.coerceAtLeast(0L),
        playing = snapshot.isPlaying,
        playbackSpeed = clockSpeed(snapshot),
        sentAtElapsedRealtime = now,
        durationMs = snapshot.durationMs,
    )

    private fun clockSpeed(snapshot: MediaDeckSnapshot): Float =
        if (snapshot.advancing) snapshot.playbackSpeed.coerceAtLeast(0f) else 0f

    private fun artworkFor(
        contentKey: String,
        snapshot: MediaDeckSnapshot,
        mode: MediaArtworkMode,
    ): EncodedMediaArtwork? {
        if (cachedArtworkTrackKey != contentKey || cachedArtworkMode != mode) {
            cachedArtworkTrackKey = contentKey
            cachedArtworkMode = mode
            cachedArtworkUriAttempt = snapshot.artworkUri
            cachedArtwork = encodeArtwork(mode, snapshot)
        } else if (cachedArtwork == null && snapshot.artwork != null) {
            cachedArtwork = encodeArtwork(mode, snapshot)
        } else if (cachedArtwork == null &&
            snapshot.artworkUri.isNotBlank() &&
            snapshot.artworkUri != cachedArtworkUriAttempt
        ) {
            cachedArtworkUriAttempt = snapshot.artworkUri
            cachedArtwork = when (mode) {
                MediaArtworkMode.IMAGE -> ImageArtworkEncoder.encode(appContext, null, snapshot.artworkUri)
                MediaArtworkMode.MONO -> MonoArtworkEncoder.encode(appContext, null, snapshot.artworkUri)
            }
        }
        return cachedArtwork
    }

    private fun encodeArtwork(mode: MediaArtworkMode, snapshot: MediaDeckSnapshot): EncodedMediaArtwork? =
        when (mode) {
            MediaArtworkMode.IMAGE -> ImageArtworkEncoder.encode(
                appContext,
                snapshot.artwork,
                snapshot.artworkUri,
            )
            MediaArtworkMode.MONO -> MonoArtworkEncoder.encode(
                appContext,
                snapshot.artwork,
                snapshot.artworkUri,
            )
        }

    private data class SentMedia(
        val contentKey: String,
        val positionMs: Long,
        val durationMs: Long?,
        val playing: Boolean,
        val playbackSpeed: Float,
        val sentAtElapsedRealtime: Long,
    ) {
        fun predictedPosition(now: Long): Long {
            if (!playing) return positionMs
            val elapsed = (now - sentAtElapsedRealtime).coerceAtLeast(0L)
            val predicted = positionMs + (elapsed * playbackSpeed).toLong()
            return durationMs?.let { predicted.coerceAtMost(it) } ?: predicted
        }
    }

    private companion object {
        const val SEEK_RESYNC_MS = 1_250L
        const val TITLE_LIMIT = 96
        const val ARTIST_LIMIT = 80
        const val ALBUM_LIMIT = 80
        const val SOURCE_LIMIT = 32
    }
}
