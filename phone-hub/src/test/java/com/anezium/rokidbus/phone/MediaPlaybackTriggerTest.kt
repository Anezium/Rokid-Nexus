package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaPlaybackTriggerTest {
    private class Harness(graceMs: Long = MediaPlaybackTrigger.DEFAULT_GRACE_MS) {
        var now = 0L
        val opens = mutableListOf<Long>()
        val closes = mutableListOf<Long>()
        var visibleSurface = false
        val trigger = MediaPlaybackTrigger(
            now = { now },
            onOpen = { opens += now },
            onClose = { closes += now },
            ownsVisibleSurface = { visibleSurface },
            graceMs = graceMs,
        )

        fun tick() = trigger.tickGrace()
    }

    @Test
    fun `a playback start opens the plugin`() {
        val h = Harness()
        h.trigger.onPlaybackChanged(true)
        assertEquals(listOf(0L), h.opens)
        assertTrue(h.trigger.isHoldingOpen)
    }

    @Test
    fun `pause then grace elapses closes the plugin`() {
        val h = Harness(graceMs = 60_000)
        h.trigger.onPlaybackChanged(true)
        h.now = 1_000
        h.trigger.onPlaybackChanged(false)
        h.tick()
        assertTrue(h.closes.isEmpty())

        h.now = 1_000 + 60_000
        h.tick()
        assertEquals(listOf(61_000L), h.closes)
        assertFalse(h.trigger.isHoldingOpen)
    }

    @Test
    fun `close is deferred while the plugin owns a visible surface`() {
        val h = Harness(graceMs = 60_000)
        h.trigger.onPlaybackChanged(true)
        h.now = 1_000
        h.trigger.onPlaybackChanged(false)
        h.visibleSurface = true
        h.now = 1_000 + 60_000
        h.tick()
        assertTrue(h.closes.isEmpty())

        // The surface clears later; the next grace tick then closes.
        h.visibleSurface = false
        h.tick()
        assertEquals(listOf(61_000L), h.closes)
    }

    @Test
    fun `replaying before the grace resets the idle clock`() {
        val h = Harness(graceMs = 60_000)
        h.trigger.onPlaybackChanged(true)
        h.now = 1_000
        h.trigger.onPlaybackChanged(false)
        h.now = 30_000
        h.trigger.onPlaybackChanged(true)
        h.now = 31_000
        h.trigger.onPlaybackChanged(false)
        h.tick()

        h.now = 91_000 // 60s after the second stop
        h.tick()
        assertEquals(listOf(91_000L), h.closes)
    }
}