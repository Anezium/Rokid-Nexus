package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Test

class ForegroundSurfaceEpochTest {
    @Test
    fun `same owner keeps the epoch across show and update`() {
        val epochs = ForegroundSurfaceEpoch()
        assertEquals(1L, epochs.assign("assistant"))
        assertEquals(1L, epochs.assign("assistant"))
    }

    @Test
    fun `owner change increments even when the new occupant starts a lower seq`() {
        val epochs = ForegroundSurfaceEpoch()
        assertEquals(1L, epochs.assign("assistant"))
        assertEquals(2L, epochs.assign("lyrics"))
        assertEquals(2L, epochs.assign("lyrics"))
    }

    @Test
    fun `release then the same plugin is a new occupancy`() {
        val epochs = ForegroundSurfaceEpoch()
        assertEquals(1L, epochs.assign("assistant"))
        epochs.release("assistant")
        epochs.release("lyrics")
        assertEquals(2L, epochs.assign("assistant"))
    }
}
