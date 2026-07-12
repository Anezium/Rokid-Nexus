package com.anezium.rokidbus.lyrics

import android.os.SystemClock
import android.view.KeyEvent
import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusTimedLine
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import java.security.MessageDigest
import kotlin.math.abs

internal interface LyricsRuntimeHost {
    fun sendCard(card: NexusCard, show: Boolean)
    fun sendTimedLines(lines: NexusTimedLines, show: Boolean)
    fun updateTimedLinesAnchor(contentKey: String, anchor: NexusPlaybackAnchor)
    fun hideSurface()
}

internal class LyricsRuntime(
    private val host: LyricsRuntimeHost,
) {
    private var unsubscribeState: (() -> Unit)? = null
    private var active = false
    private var lastSent: SentSurface? = null

    fun register() {
        unsubscribeState?.invoke()
        unsubscribeState = LyricsRuntimeGraph.stateStore.subscribe { state ->
            handleState(state)
        }
    }

    fun open() {
        active = true
        pushState(LyricsRuntimeGraph.stateStore.current(), force = true)
    }

    fun close() {
        if (!active && lastSent == null) return
        active = false
        lastSent = null
        host.hideSurface()
    }

    fun input(event: NexusInputEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> close()
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

    fun registrationApproved() {
        if (active) pushState(LyricsRuntimeGraph.stateStore.current(), force = true)
    }

    fun unregister() {
        close()
        unsubscribeState?.invoke()
        unsubscribeState = null
    }

    private fun handleState(state: LyricsPhoneViewState) {
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

        val show = previous == null || force
        if (lyrics.synced && lyrics.lines.isNotEmpty()) {
            val anchor = playbackAnchor(lyrics, playing, now)
            if (!force && !contentChanged) {
                host.updateTimedLinesAnchor(contentKey, anchor)
            } else {
                host.sendTimedLines(timedLines(lyrics, contentKey, anchor), show)
            }
        } else {
            host.sendCard(card(lyrics, state.deviceStatus.statusLabel, contentKey), show)
        }
        lastSent = SentSurface(
            contentKey = contentKey,
            positionMs = lyrics.progressMs,
            playing = playing,
            sentAtElapsedRealtime = now,
        )
    }

    private fun timedLines(
        lyrics: LyricsSnapshot,
        contentKey: String,
        anchor: NexusPlaybackAnchor,
    ): NexusTimedLines = NexusTimedLines(
        title = titleFor(lyrics).take(MAX_TITLE_CHARS),
        contentKey = contentKey,
        lines = lyrics.lines.take(MAX_TIMED_LINES).map { line ->
            NexusTimedLine(line.startTimeMs.coerceAtLeast(0L), line.text.take(MAX_LINE_CHARS))
        },
        anchor = anchor,
        subtitle = subtitleFor(lyrics).take(MAX_LINE_CHARS).takeIf(String::isNotBlank),
        footer = footerFor(lyrics).take(MAX_LINE_CHARS).takeIf(String::isNotBlank),
    )

    private fun playbackAnchor(
        lyrics: LyricsSnapshot,
        playing: Boolean,
        sentAt: Long,
    ): NexusPlaybackAnchor = NexusPlaybackAnchor(
        positionMs = lyrics.progressMs.coerceAtLeast(0L),
        playing = playing,
        sentAtElapsedRealtime = sentAt,
    )

    private fun card(
        lyrics: LyricsSnapshot,
        statusLabel: String,
        contentKey: String,
    ): NexusCard = NexusCard(
        title = titleFor(lyrics).ifBlank { "Lyrics" }.take(MAX_TITLE_CHARS),
        lines = cardLines(lyrics, statusLabel).map { it.take(MAX_LINE_CHARS) },
        footer = footerFor(lyrics).ifBlank { "Rokid Nexus" }.take(MAX_LINE_CHARS),
        contentKey = contentKey,
        handlesBack = true,
    )

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
        val identity = if (lyrics.synced && lyrics.lines.isNotEmpty()) {
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
        return shortHash(identity)
    }

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
        private const val SEEK_RESYNC_MS = 1_500L
        private const val MAX_TITLE_CHARS = 120
        private const val MAX_LINE_CHARS = 240
        private const val MAX_TIMED_LINES = 2_000
    }
}
