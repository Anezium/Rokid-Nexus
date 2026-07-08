package com.anezium.rokidbus.lyrics

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.plugin.NexusPlugin
import com.anezium.rokidbus.shared.plugin.NexusPluginHost
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs

class LyricsPlugin : NexusPlugin {
    override val id: String = SURFACE_ID
    override val displayName: String = "Lyrics"

    private lateinit var host: NexusPluginHost
    private var unsubscribeState: (() -> Unit)? = null
    private var active = false
    private var lastSent: SentSurface? = null
    private var dismissedTrackKey: String? = null

    override fun onRegister(host: NexusPluginHost) {
        this.host = host
        LyricsRuntimeGraph.initialize(host.context)
        unsubscribeState?.invoke()
        unsubscribeState = LyricsRuntimeGraph.stateStore.subscribe { state ->
            handleState(state)
        }
        LyricsRuntimeGraph.start(host.context)
    }

    override fun onOpen() {
        active = true
        dismissedTrackKey = null
        LyricsRuntimeGraph.start(host.context)
        pushState(LyricsRuntimeGraph.stateStore.current(), force = true)
    }

    override fun onClose() {
        if (!active && lastSent == null) return
        dismissedTrackKey = trackKey(LyricsRuntimeGraph.stateStore.current().lyrics)
        active = false
        lastSent = null
        host.send(
            BusPaths.SURFACE_HIDE,
            JSONObject()
                .put("surfaceId", SURFACE_ID),
        )
    }

    override fun onInput(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> onClose()
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            -> LyricsRuntimeGraph.togglePlayback()
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            -> LyricsRuntimeGraph.skipToNext()
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            -> LyricsRuntimeGraph.skipToPrevious()
        }
    }

    private fun handleState(state: LyricsPhoneViewState) {
        val lyrics = state.lyrics
        val currentTrackKey = trackKey(lyrics)
        if (!active &&
            autoOpenEnabled() &&
            lyrics.sessionState == LyricsSessionState.PLAYING &&
            currentTrackKey != dismissedTrackKey
        ) {
            active = true
        }
        if (active) {
            pushState(state, force = false)
        }
    }

    private fun pushState(state: LyricsPhoneViewState, force: Boolean) {
        val lyrics = state.lyrics
        val now = SystemClock.elapsedRealtime()
        val contentKey = contentKey(lyrics, state.deviceStatus.statusLabel)
        val playing = lyrics.sessionState == LyricsSessionState.PLAYING
        val previous = lastSent
        val contentChanged = previous == null || previous.contentKey != contentKey
        val shouldSend = force ||
            contentChanged ||
            previous.playing != playing ||
            abs(lyrics.progressMs - previous.predictedPosition(now)) >= SEEK_RESYNC_MS
        if (!shouldSend) return

        val payload = if (lyrics.synced && lyrics.lines.isNotEmpty()) {
            if (!force && !contentChanged) {
                anchorOnlyPayload(lyrics, playing, now, contentKey)
            } else {
                timedLinesPayload(lyrics, playing, now, contentKey)
            }
        } else {
            cardPayload(lyrics, state.deviceStatus.statusLabel, contentKey)
        }
        val path = if (previous == null || force) BusPaths.SURFACE_SHOW else BusPaths.SURFACE_UPDATE
        host.send(path, payload)
        lastSent = SentSurface(
            contentKey = contentKey,
            positionMs = lyrics.progressMs,
            playing = playing,
            sentAtElapsedRealtime = now,
        )
    }

    private fun timedLinesPayload(
        lyrics: LyricsSnapshot,
        playing: Boolean,
        sentAt: Long,
        contentKey: String,
    ): JSONObject =
        basePayload("timed-lines", contentKey)
            .put("title", titleFor(lyrics))
            .put("subtitle", subtitleFor(lyrics))
            .put("footer", footerFor(lyrics))
            .put(
                "anchor",
                JSONObject()
                    .put("positionMs", lyrics.progressMs.coerceAtLeast(0L))
                    .put("playing", playing)
                    .put("sentAtElapsedRealtime", sentAt),
            )
            .put(
                "lines",
                JSONArray().also { array ->
                    lyrics.lines.forEach { line ->
                        array.put(
                            JSONObject()
                                .put("timeMs", line.startTimeMs)
                                .put("text", line.text),
                        )
                    }
                },
            )

    private fun anchorOnlyPayload(
        lyrics: LyricsSnapshot,
        playing: Boolean,
        sentAt: Long,
        contentKey: String,
    ): JSONObject =
        basePayload("timed-lines", contentKey)
            .put(
                "anchor",
                JSONObject()
                    .put("positionMs", lyrics.progressMs.coerceAtLeast(0L))
                    .put("playing", playing)
                    .put("sentAtElapsedRealtime", sentAt),
            )

    private fun cardPayload(
        lyrics: LyricsSnapshot,
        statusLabel: String,
        contentKey: String,
    ): JSONObject =
        basePayload("card", contentKey)
            .put("title", titleFor(lyrics).ifBlank { "Lyrics" })
            .put("lines", JSONArray(cardLines(lyrics, statusLabel)))
            .put("footer", footerFor(lyrics).ifBlank { "Rokid Nexus" })

    private fun basePayload(kind: String, contentKey: String): JSONObject =
        JSONObject()
            .put("surfaceId", SURFACE_ID)
            .put("kind", kind)
            .put("contentKey", contentKey)

    private fun cardLines(lyrics: LyricsSnapshot, statusLabel: String): List<String> {
        lyrics.errorMessage?.takeIf { it.isNotBlank() }?.let {
            return listOf(it, "Playback controls remain available.")
        }
        if (lyrics.sessionState == LyricsSessionState.LOADING) {
            return listOf("Loading lyrics...", lyrics.sourceSummary)
        }
        val plain = lyrics.plainLyrics.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
            .toList()
        if (plain.isNotEmpty()) return plain
        if (lyrics.trackTitle.isBlank()) {
            return listOf(statusLabel.ifBlank { "Start music on the phone." })
        }
        return listOf("No timed lyrics found.", lyrics.sourceSummary)
    }

    private fun titleFor(lyrics: LyricsSnapshot): String =
        lyrics.trackTitle.ifBlank { "Lyrics" }

    private fun subtitleFor(lyrics: LyricsSnapshot): String =
        buildString {
            append(lyrics.artistName)
            lyrics.albumName.takeIf { it.isNotBlank() }?.let { album ->
                if (isNotBlank()) append(" / ")
                append(album)
            }
        }

    private fun footerFor(lyrics: LyricsSnapshot): String =
        buildString {
            lyrics.provider.takeIf { it.isNotBlank() }?.let { append(it) }
            if (lyrics.synced) {
                if (isNotBlank()) append(" / ")
                append("synced")
            }
        }

    private fun contentKey(
        lyrics: LyricsSnapshot,
        statusLabel: String,
    ): String {
        val lineKey = if (lyrics.synced) {
            "${lyrics.lines.size}:${lyrics.lines.lastOrNull()?.startTimeMs ?: 0L}"
        } else {
            lyrics.plainLyrics.take(128)
        }
        return if (lyrics.synced && lyrics.lines.isNotEmpty()) {
            listOf(
                lyrics.trackTitle,
                lyrics.artistName,
                lyrics.albumName,
                lyrics.provider,
                lineKey,
            ).joinToString("|")
        } else {
            listOf(
                lyrics.sessionState.name,
                lyrics.trackTitle,
                lyrics.artistName,
                lyrics.albumName,
                lyrics.provider,
                lyrics.errorMessage.orEmpty(),
                statusLabel,
                lineKey,
            ).joinToString("|")
        }
    }

    private fun trackKey(lyrics: LyricsSnapshot): String =
        listOf(
            lyrics.trackTitle.trim().lowercase(),
            lyrics.artistName.trim().lowercase(),
            lyrics.albumName.trim().lowercase(),
            lyrics.durationSeconds?.toString().orEmpty(),
        ).joinToString("|")

    private fun autoOpenEnabled(): Boolean =
        host.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(PREF_AUTO_OPEN, true)

    private data class SentSurface(
        val contentKey: String,
        val positionMs: Long,
        val playing: Boolean,
        val sentAtElapsedRealtime: Long,
    ) {
        fun predictedPosition(now: Long): Long {
            if (!playing) return positionMs
            val elapsed = (now - sentAtElapsedRealtime).coerceAtLeast(0L)
            return positionMs + elapsed
        }
    }

    private companion object {
        private const val SURFACE_ID = "lyrics"
        private const val PREFS = "nexus_plugin_lyrics"
        private const val PREF_AUTO_OPEN = "auto_open"
        private const val SEEK_RESYNC_MS = 1_500L
    }
}
