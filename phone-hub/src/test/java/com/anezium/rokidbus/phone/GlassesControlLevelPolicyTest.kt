package com.anezium.rokidbus.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GlassesControlLevelPolicyTest {
    @Test
    fun `integer level is clamped to conservative percentage range`() {
        assertEquals(0, GlassesControlLevelPolicy.parseAndClamp(-1))
        assertEquals(0, GlassesControlLevelPolicy.parseAndClamp(0))
        assertEquals(42, GlassesControlLevelPolicy.parseAndClamp(42))
        assertEquals(100, GlassesControlLevelPolicy.parseAndClamp(100))
        assertEquals(100, GlassesControlLevelPolicy.parseAndClamp(101))
    }

    @Test
    fun `missing or non-integer level is rejected`() {
        assertNull(GlassesControlLevelPolicy.parseAndClamp(null))
        assertNull(GlassesControlLevelPolicy.parseAndClamp("42"))
        assertNull(GlassesControlLevelPolicy.parseAndClamp(42L))
        assertNull(GlassesControlLevelPolicy.parseAndClamp(42.0))
    }
}
