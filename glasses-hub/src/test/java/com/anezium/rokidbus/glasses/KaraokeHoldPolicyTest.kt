package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KaraokeHoldPolicyTest {
    private class Harness {
        var now = 0L
        var display = true
        var playing = false
        var holdDisplay = false
        var contentKey = "track-1"
        val actions = mutableListOf<KaraokeHoldPolicy.Action>()
        val policy = KaraokeHoldPolicy(
            now = { now },
            holdMs = 8_000,
            ceilingMs = 600_000,
            releaseGraceMs = 5_000,
        )
        fun drive() {
            val action = policy.update(display, playing, holdDisplay, contentKey)
            if (action !is KaraokeHoldPolicy.Action.Nothing) actions += action
        }
    }

    @Test
    fun `hold acquired only when playing visible and karaoke mode`() {
        val h = Harness()
        h.drive() // nothing / glance
        assertTrue(h.actions.isEmpty())

        h.holdDisplay = true
        h.drive() // still not playing
        assertTrue(h.actions.isEmpty())

        h.playing = true
        h.drive()
        assertTrue(h.actions.any { it is KaraokeHoldPolicy.Action.Acquire })
        assertTrue(h.policy.isHeld)
    }

    @Test
    fun `glance mode never holds even while playing and visible`() {
        val h = Harness()
        h.playing = true
        h.display = true
        h.holdDisplay = false
        h.drive()
        assertTrue(h.actions.isEmpty())
        assertFalse(h.policy.isHeld)
    }

    @Test
    fun `pause releases the hold within the release grace`() {
        val h = Harness()
        h.playing = true
        h.holdDisplay = true
        h.now = 0
        h.drive()
        assertTrue(h.policy.isHeld)

        h.playing = false
        h.now = 1_000
        h.drive() // within 5s grace: still held
        assertTrue(h.policy.isHeld)

        h.now = 6_000
        h.drive() // past grace: released
        assertTrue(h.actions.any { it is KaraokeHoldPolicy.Action.Release })
        assertFalse(h.policy.isHeld)
    }

    @Test
    fun `hiding the widget releases the hold`() {
        val h = Harness()
        h.playing = true
        h.holdDisplay = true
        h.drive()
        h.display = false
        h.now += 6_000
        h.drive()
        assertFalse(h.policy.isHeld)
    }

    @Test
    fun `per track ceiling forces a release then resets on a new track`() {
        val h = Harness()
        h.playing = true
        h.holdDisplay = true
        h.now = 0
        h.drive()
        h.now = 599_999
        h.drive()
        assertTrue(h.policy.isHeld)

        // Past the 10-minute ceiling the hold is released even though playing continues.
        h.now = 601_000
        h.drive()
        assertFalse(h.policy.isHeld)

        // A new track resets the ceiling and re-holds.
        h.contentKey = "track-2"
        h.now = 0
        h.drive()
        assertTrue(h.policy.isHeld)
    }

    @Test
    fun `force release clears the hold capacity`() {
        val h = Harness()
        h.playing = true
        h.holdDisplay = true
        h.drive()
        assertTrue(h.policy.isHeld)
        h.policy.forceRelease()
        assertFalse(h.policy.isHeld)
    }

    @Test
    fun `off mode with no anchor never holds`() {
        val h = Harness()
        h.holdDisplay = false
        h.playing = true
        h.drive()
        assertFalse(h.policy.isHeld)
    }
}