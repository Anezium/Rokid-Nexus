package com.anezium.rokidbus.shared

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfaceEpochContractTest {
    @Test
    fun `stamp overwrites a plugin-supplied epoch`() {
        val spoofed = JSONObject().put("surfaceId", "main").put(SurfaceEpochContract.FIELD, 99)
        val stamped = SurfaceEpochContract.stamp(spoofed, 3L)
        assertEquals(3L, stamped.getLong(SurfaceEpochContract.FIELD))
        assertEquals(99, spoofed.getInt(SurfaceEpochContract.FIELD))
    }

    @Test
    fun `strip removes a client epoch so the hub can stamp later`() {
        val stripped = SurfaceEpochContract.strip(
            JSONObject().put("surfaceId", "main").put(SurfaceEpochContract.FIELD, 7),
        )
        assertFalse(stripped.has(SurfaceEpochContract.FIELD))
        assertEquals("main", stripped.getString("surfaceId"))
    }

    @Test
    fun `missing epoch reads as zero so older hubs keep seq-only ordering`() {
        assertEquals(0L, SurfaceEpochContract.read(JSONObject().put("seq", 12)))
    }

    @Test
    fun `a frame is stale only when its epoch is older than the live slot`() {
        assertTrue(SurfaceEpochContract.isStale(incomingEpoch = 1L, liveEpoch = 2L))
        assertFalse(SurfaceEpochContract.isStale(incomingEpoch = 2L, liveEpoch = 2L))
        assertFalse(SurfaceEpochContract.isStale(incomingEpoch = 3L, liveEpoch = 2L))
        assertFalse(SurfaceEpochContract.isStale(incomingEpoch = 0L, liveEpoch = 0L))
    }
}
