package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import com.anezium.rokidbus.lyrics.settings.LyricsWidgetMode

/**
 * Pure decision logic for the plugin-side auto show/hide of the ambient home widget. Android-free
 * so acceptance #6 drives it directly with an injected clock.
 *
 * Rules (from the widget contract):
 *  - Widget shows only when the plugin is in [LyricsWidgetMode.KARAOKE] or [LyricsWidgetMode.GLANCE]
 *    and a snapshot carries synced lyrics while playing.
 *  - It hides when playback pauses/stops for more than the 5 s grace, when the media session
 *    disappears, or when the track has no synced lyrics.
 *  - The full-screen lyrics surface supersedes the widget: when [fullScreenVisible] the widget hides
 *    and reappears when the surface closes (music still playing).
 */
class LyricsWidgetDriver(
    private val now: () -> Long,
    private val graceMs: Long = 5_000L,
) {
    private var showing = false
    private var pausedSince: Long? = null

    data class Decision(
        val show: Boolean,
        val hide: Boolean,
    )

    /**
     * @param mode the ambient mode; Off never shows the widget.
     * @param fullScreenVisible whether the plugin's full-screen lyrics surface is up.
     * @param snapshot current lyrics snapshot (or null when the media session is gone).
     */
    fun decide(
        mode: LyricsWidgetMode,
        fullScreenVisible: Boolean,
        snapshot: LyricsSnapshot?,
    ): Decision {
        if (mode == LyricsWidgetMode.OFF) {
            return settle(hide = true)
        }
        if (fullScreenVisible) {
            return settle(hide = true)
        }
        if (snapshot == null) {
            return settle(hide = true)
        }
        val playing = snapshot.sessionState == LyricsSessionState.PLAYING
        val hasSyncedLyrics = snapshot.synced && snapshot.lines.isNotEmpty()
        if (!hasSyncedLyrics) {
            return settle(hide = true)
        }
        if (playing) {
            pausedSince = null
            return settle(show = true)
        }
        // Paused/stopped: hide once the grace has fully elapsed.
        val pausedAt = pausedSince ?: now()
        pausedSince = pausedAt
        if (now() - pausedAt >= graceMs) {
            pausedSince = null
            return settle(hide = true)
        }
        return Decision(show = false, hide = false)
    }

    private fun settle(show: Boolean = false, hide: Boolean = false): Decision {
        val shouldShow = show && !showing
        val shouldHide = hide && showing
        if (shouldShow) showing = true
        if (shouldHide) showing = false
        return Decision(show = shouldShow, hide = shouldHide)
    }

    val isShowing: Boolean
        get() = showing

    /** Clear driver state (e.g. on plugin close) without emitting a hide decision. */
    fun reset() {
        showing = false
        pausedSince = null
    }
}