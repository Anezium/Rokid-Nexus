package com.anezium.rokidbus.media

import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.media.session.MediaDeckMonitorStatus
import com.anezium.rokidbus.media.session.MediaDeckSnapshot
import com.anezium.rokidbus.media.session.MediaSessionMonitor
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import kotlin.math.abs

class MediaDeckPlugin : NexusPlugin {
    override val id: String = SURFACE_ID
    override val displayName: String = "Media Deck"

    private lateinit var host: NexusPluginHost
    private lateinit var monitor: MediaSessionMonitor
    private var active = false
    private var surfaceShown = false
    private var latestSnapshot: MediaDeckSnapshot? = null
    private var monitorStatus = MediaDeckMonitorStatus.STARTING
    private var lastRenderedKey: String? = null
    private var lastSentMedia: SentMedia? = null
    private var cachedArtworkTrackKey: String? = null
    private var cachedArtwork: EncodedMonoArtwork? = null
    private var cachedArtworkUriAttempt: String? = null

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        monitor = MediaSessionMonitor(
            context = host.context,
            onSnapshot = { snapshot ->
                host.post { handleSnapshot(snapshot) }
            },
            onStatus = { status ->
                host.post { handleStatus(status) }
            },
        )
    }

    override fun onOpen() {
        active = true
        surfaceShown = false
        lastRenderedKey = null
        lastSentMedia = null
        monitorStatus = MediaDeckMonitorStatus.STARTING
        showStatus(force = true)
        monitor.start()
    }

    override fun onClose() {
        active = false
        monitor.stop()
        latestSnapshot = null
        lastSentMedia = null
        lastRenderedKey = null
        cachedArtworkTrackKey = null
        cachedArtwork = null
        cachedArtworkUriAttempt = null
        if (surfaceShown) {
            host.send(
                BusPaths.SURFACE_HIDE,
                JSONObject().put("surfaceId", SURFACE_ID),
            )
        }
        surfaceShown = false
    }

    override fun onInput(event: NexusInputEvent) {
        if (!active || event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> onClose()
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> monitor.togglePlayback()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> monitor.skipToNext()
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> monitor.skipToPrevious()
        }
    }

    private fun handleStatus(status: MediaDeckMonitorStatus) {
        monitorStatus = status
        if (active && latestSnapshot == null) showStatus(force = false)
    }

    private fun handleSnapshot(snapshot: MediaDeckSnapshot?) {
        if (!active) return
        latestSnapshot = snapshot
        if (snapshot == null) {
            showStatus(force = false)
        } else {
            pushSnapshot(snapshot, force = false)
        }
    }

    private fun showStatus(force: Boolean) {
        if (!active) return
        val (key, lines) = when (monitorStatus) {
            MediaDeckMonitorStatus.STARTING -> "checking" to listOf(
                "Checking active media...",
                "Playback stays on your phone.",
            )
            MediaDeckMonitorStatus.ACCESS_REQUIRED -> "access" to listOf(
                "Media access is required.",
                "Open Media Deck on the phone",
                "and enable notification access.",
            )
            MediaDeckMonitorStatus.NO_SESSION -> "idle" to listOf(
                "Nothing is playing.",
                "Start Spotify, VLC, a podcast,",
                "or another media app on the phone.",
            )
            MediaDeckMonitorStatus.UNAVAILABLE -> "unavailable" to listOf(
                "Media control is unavailable.",
                "Check Media Deck access on the phone.",
            )
        }
        val renderedKey = "status:$key"
        if (!force && lastRenderedKey == renderedKey) return
        sendSurface(
            JSONObject()
                .put("surfaceId", SURFACE_ID)
                .put("kind", "card")
                .put("contentKey", renderedKey)
                .put("title", "MEDIA DECK")
                .put("lines", JSONArray(lines))
                .put("footer", "BACK: CLOSE"),
        )
        lastRenderedKey = renderedKey
        lastSentMedia = null
    }

    private fun pushSnapshot(snapshot: MediaDeckSnapshot, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val contentKey = trackKey(snapshot)
        val artworkWasMissing = cachedArtworkTrackKey == contentKey && cachedArtwork == null
        val artwork = artworkFor(contentKey, snapshot)
        val artworkArrived = artworkWasMissing && artwork != null
        val previous = lastSentMedia
        val contentChanged = previous == null || previous.contentKey != contentKey
        val anchorChanged = previous == null ||
            previous.playing != snapshot.isPlaying ||
            previous.durationMs != snapshot.durationMs ||
            abs(snapshot.positionMs - previous.predictedPosition(now)) >= SEEK_RESYNC_MS
        if (!force && !contentChanged && !artworkArrived && !anchorChanged) return

        val sendFullPayload = force || contentChanged || artworkArrived
        val payload = if (sendFullPayload) {
            buildFullPayload(snapshot, contentKey, artwork, now)
        } else {
            anchorPayload(snapshot, contentKey, now)
        }
        sendSurface(payload)
        lastRenderedKey = "media:$contentKey"
        lastSentMedia = SentMedia(
            contentKey = contentKey,
            positionMs = snapshot.positionMs,
            durationMs = snapshot.durationMs,
            playing = snapshot.isPlaying,
            playbackSpeed = snapshot.playbackSpeed,
            sentAtElapsedRealtime = now,
        )
    }

    private fun buildFullPayload(
        snapshot: MediaDeckSnapshot,
        contentKey: String,
        artwork: EncodedMonoArtwork?,
        now: Long,
    ): JSONObject = JSONObject()
        .put("surfaceId", SURFACE_ID)
        .put("kind", "media")
        .put("mediaVersion", 1)
        .put("contentKey", contentKey)
        .put("title", "MEDIA DECK")
        .put("subtitle", clipped(snapshot.sourceLabel, SOURCE_LIMIT).uppercase())
        .put("mediaTitle", clipped(snapshot.title, TITLE_LIMIT))
        .put("mediaArtist", clipped(snapshot.artist, ARTIST_LIMIT))
        .put("mediaAlbum", clipped(snapshot.album, ALBUM_LIMIT))
        .put("footer", "SWIPE: TRACK  TAP: PLAY/PAUSE")
        .put("anchor", anchor(snapshot, now))
        .also { payload -> artwork?.let { payload.put("artwork", it.toJson()) } }

    private fun anchorPayload(
        snapshot: MediaDeckSnapshot,
        contentKey: String,
        now: Long,
    ): JSONObject = JSONObject()
        .put("surfaceId", SURFACE_ID)
        .put("kind", "media")
        .put("mediaVersion", 1)
        .put("contentKey", contentKey)
        .put("anchor", anchor(snapshot, now))

    private fun anchor(snapshot: MediaDeckSnapshot, now: Long): JSONObject = JSONObject()
        .put("positionMs", snapshot.positionMs.coerceAtLeast(0L))
        .put("playing", snapshot.isPlaying)
        .put("playbackSpeed", snapshot.playbackSpeed.toDouble())
        .put("sentAtElapsedRealtime", now)
        .also { value -> snapshot.durationMs?.let { value.put("durationMs", it) } }

    private fun artworkFor(contentKey: String, snapshot: MediaDeckSnapshot): EncodedMonoArtwork? {
        if (cachedArtworkTrackKey != contentKey) {
            cachedArtworkTrackKey = contentKey
            cachedArtworkUriAttempt = snapshot.artworkUri
            cachedArtwork = MonoArtworkEncoder.encode(host.context, snapshot.artwork, snapshot.artworkUri)
        } else if (cachedArtwork == null && snapshot.artwork != null) {
            cachedArtwork = MonoArtworkEncoder.encode(host.context, snapshot.artwork, snapshot.artworkUri)
        } else if (cachedArtwork == null &&
            snapshot.artworkUri.isNotBlank() &&
            snapshot.artworkUri != cachedArtworkUriAttempt
        ) {
            cachedArtworkUriAttempt = snapshot.artworkUri
            cachedArtwork = MonoArtworkEncoder.encode(host.context, null, snapshot.artworkUri)
        }
        return cachedArtwork
    }

    private fun sendSurface(payload: JSONObject) {
        host.send(
            if (surfaceShown) BusPaths.SURFACE_UPDATE else BusPaths.SURFACE_SHOW,
            payload,
        )
        surfaceShown = true
    }

    private fun trackKey(snapshot: MediaDeckSnapshot): String = shortHash(
        listOf(
            snapshot.packageName,
            snapshot.mediaId,
            snapshot.title,
            snapshot.artist,
            snapshot.album,
            snapshot.durationMs?.toString().orEmpty(),
        ).joinToString("\u0000"),
    )

    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(16) {
            for (index in 0 until 8) {
                val byte = digest[index].toInt() and 0xff
                append(hex[byte ushr 4])
                append(hex[byte and 0x0f])
            }
        }
    }

    private fun clipped(value: String, limit: Int): String =
        value.replace('\n', ' ').replace('\r', ' ').trim().take(limit)

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
        const val SURFACE_ID = "media"
        const val SEEK_RESYNC_MS = 1_250L
        const val TITLE_LIMIT = 96
        const val ARTIST_LIMIT = 80
        const val ALBUM_LIMIT = 80
        const val SOURCE_LIMIT = 32
    }
}
