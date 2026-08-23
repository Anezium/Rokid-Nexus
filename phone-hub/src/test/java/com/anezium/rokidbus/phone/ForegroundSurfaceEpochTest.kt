package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForegroundSurfaceEpochTest {
    @Test
    fun `same owner keeps the epoch across show and update`() {
        val epochs = ForegroundSurfaceEpoch(seedMs = 1_000L)
        assertEquals(1_001L, epochs.assign("assistant"))
        assertEquals(1_001L, epochs.assign("assistant"))
    }

    @Test
    fun `owner change increments even when the new occupant starts a lower seq`() {
        val epochs = ForegroundSurfaceEpoch(seedMs = 1_000L)
        assertEquals(1_001L, epochs.assign("assistant"))
        assertEquals(1_002L, epochs.assign("lyrics"))
        assertEquals(1_002L, epochs.assign("lyrics"))
    }

    @Test
    fun `release then the same plugin is a new occupancy`() {
        val epochs = ForegroundSurfaceEpoch(seedMs = 1_000L)
        assertEquals(1_001L, epochs.assign("assistant"))
        epochs.release("assistant")
        epochs.release("lyrics")
        assertEquals(1_002L, epochs.assign("assistant"))
    }

    @Test
    fun `a restarted hub stamps an epoch newer than any previous instance`() {
        val previous = ForegroundSurfaceEpoch(seedMs = 1_700_000_000_000L)
        previous.assign("assistant")
        previous.assign("lyrics")
        val lastFromPrevious = previous.assign("relay")
        val restarted = ForegroundSurfaceEpoch()
        assertTrue(restarted.assign("assistant") > lastFromPrevious)
    }
}
