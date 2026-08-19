package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Test

class RelayDisplayTimeTest {
    @Test
    fun `notice ttl combines a base with optional per-character scaling`() {
        assertEquals(3_000L, RelayNoticeRuntime.noticeTtlMs(3, false, 500))
        assertEquals(3_000L, RelayNoticeRuntime.noticeTtlMs(3, true, 0))
        assertEquals(7_500L, RelayNoticeRuntime.noticeTtlMs(3, true, 100))
        assertEquals(45_000L, RelayNoticeRuntime.noticeTtlMs(45, true, 10_000))
        assertEquals(2_000L, RelayNoticeRuntime.noticeTtlMs(2, false, 0))
        // Below the floor is clamped up, not sent short.
        assertEquals(2_000L, RelayNoticeRuntime.noticeTtlMs(1, false, 0))
    }

    @Test
    fun `display seconds coerce into the 2 to 45 range`() {
        assertEquals(2, RelaySettings.coerceNoticeDisplaySeconds(1))
        assertEquals(3, RelaySettings.coerceNoticeDisplaySeconds(3))
        assertEquals(45, RelaySettings.coerceNoticeDisplaySeconds(46))
    }

    @Test
    fun `legacy display time maps auto to scaling and fixed values to held`() {
        assertEquals(RelaySettings.LegacyDisplayTime(3, true), RelaySettings.legacyDisplayTime(0))
        assertEquals(RelaySettings.LegacyDisplayTime(10, false), RelaySettings.legacyDisplayTime(10))
        assertEquals(RelaySettings.LegacyDisplayTime(45, false), RelaySettings.legacyDisplayTime(50))
    }
}
