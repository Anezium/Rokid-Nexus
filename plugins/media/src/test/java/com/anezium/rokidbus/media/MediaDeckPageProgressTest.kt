package com.anezium.rokidbus.media

import com.anezium.rokidbus.media.session.observed
import com.anezium.rokidbus.media.session.snapshotOf
import org.junit.Assert.*
import org.junit.Test

class MediaDeckPageProgressTest {
    @Test fun `ordinary advancement is local while seeking and speed changes revise the page`() {
        val progress = MediaDeckPageProgress()
        val first = snapshotOf(observed(), 10_000)
        assertTrue(progress.observe(first, 1000))
        assertFalse(progress.observe(first, 1500))
        assertEquals(11_000L, progress.position(2000))
        assertFalse(progress.observe(first.copy(positionMs = 11_000), 2000))
        assertEquals(12_000L, progress.position(3000))
        assertTrue(progress.observe(first.copy(positionMs = 60_000), 3000))
        assertEquals(60_000L, progress.position(3000))
        assertTrue(progress.observe(first.copy(positionMs = 60_000, playbackSpeed = 2f), 3000))
        assertEquals(62_000L, progress.position(4000))
        assertTrue(progress.observe(first.copy(positionMs = 62_000, advancing = false), 4000))
        assertEquals(62_000L, progress.position(10000))
    }
}
