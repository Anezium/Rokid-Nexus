package com.anezium.rokidbus.lyrics

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusLyricsWidget
import com.anezium.rokidbus.client.plugin.NexusPlaybackAnchor
import com.anezium.rokidbus.client.plugin.NexusTimedLines
import com.anezium.rokidbus.lyrics.contracts.LyricsLine
import com.anezium.rokidbus.lyrics.contracts.LyricsSessionState
import com.anezium.rokidbus.lyrics.contracts.LyricsSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricsRuntimeTest {
    private class RecordingHost : LyricsRuntimeHost {
        val widgetCommands = mutableListOf<String>()

        override fun sendCard(card: NexusCard, show: Boolean) = Unit
        override fun sendTimedLines(lines: NexusTimedLines, show: Boolean) = Unit
        override fun updateTimedLinesAnchor(contentKey: String, anchor: NexusPlaybackAnchor) = Unit
        override fun hideSurface() = Unit
        override fun showWidget(widget: NexusLyricsWidget) {
            widgetCommands += "show"
        }
        override fun updateWidgetAnchor(contentKey: String, anchor: NexusPlaybackAnchor) {
            widgetCommands += "update"
        }
        override fun hideWidget() {
            widgetCommands += "hide"
        }
    }

    @Test
    fun needsPlaybackAnchorUpdate_resyncsAtEachLineBoundary() {
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 3,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = 0L,
            )
        )
    }

    @Test
    fun needsPlaybackAnchorUpdate_skipsStablePlaybackWithinDriftTolerance() {
        assertFalse(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = 1_499L,
            )
        )
    }

    @Test
    fun needsPlaybackAnchorUpdate_keepsPlaybackAndSeekResyncs() {
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = false,
                positionDriftMs = 0L,
            )
        )
        assertTrue(
            needsPlaybackAnchorUpdate(
                previousLineIndex = 4,
                currentLineIndex = 4,
                previousPlaying = true,
                playing = true,
                positionDriftMs = -1_500L,
            )
        )
    }

    @Test
    fun widgetReappearsWithFullContentAfterTransientHideOnTheSameTrack() {
        var state = LyricsPhoneViewState(
            lyrics = LyricsSnapshot(sessionState = LyricsSessionState.LOADING),
        )
        var listener: ((LyricsPhoneViewState) -> Unit)? = null
        var now = 1_000L
        val host = RecordingHost()
        val runtime = LyricsRuntime(
            host = host,
            now = { now },
            currentState = { state },
            subscribeState = { nextListener ->
                listener = nextListener
                val unsubscribe = { listener = null }
                nextListener(state)
                unsubscribe
            },
        )
        runtime.setBackgroundOpen(true)
        runtime.register()
        runtime.open()
        host.widgetCommands.clear()

        val playable = LyricsSnapshot(
            sessionState = LyricsSessionState.PLAYING,
            trackTitle = "Blinding Lights",
            artistName = "The Weeknd",
            albumName = "After Hours",
            provider = "SPOTIFY",
            synced = true,
            progressMs = 10_000,
            currentLineIndex = 1,
            lines = listOf(
                LyricsLine(0, "one"),
                LyricsLine(8_000, "two"),
            ),
        )

        try {
            fun emit(snapshot: LyricsSnapshot) {
                now += 250
                state = state.copy(lyrics = snapshot)
                listener?.invoke(state)
            }

            emit(playable)
            emit(
                playable.copy(
                    sessionState = LyricsSessionState.LOADING,
                    synced = false,
                    lines = emptyList(),
                ),
            )
            emit(playable.copy(progressMs = 10_500))

            assertEquals(listOf("show", "hide", "show"), host.widgetCommands)
        } finally {
            runtime.unregister()
        }
    }
}
