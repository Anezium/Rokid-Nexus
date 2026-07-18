package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginUpdateCheckerTest {
    @Test
    fun `a check is stale when it has never succeeded or reached max age`() {
        assertTrue(PluginUpdateThrottle.shouldRefresh(null, nowEpochMillis = 10_000L, maxAgeMs = 1_000L))
        assertTrue(PluginUpdateThrottle.shouldRefresh(9_000L, nowEpochMillis = 10_000L, maxAgeMs = 1_000L))
    }

    @Test
    fun `a successful check younger than max age is throttled`() {
        assertFalse(PluginUpdateThrottle.shouldRefresh(9_001L, nowEpochMillis = 10_000L, maxAgeMs = 1_000L))
    }

    @Test
    fun `clock rollback and non-positive max age force a refresh`() {
        assertTrue(PluginUpdateThrottle.shouldRefresh(10_001L, nowEpochMillis = 10_000L, maxAgeMs = 1_000L))
        assertTrue(PluginUpdateThrottle.shouldRefresh(10_000L, nowEpochMillis = 10_000L, maxAgeMs = 0L))
    }
}
