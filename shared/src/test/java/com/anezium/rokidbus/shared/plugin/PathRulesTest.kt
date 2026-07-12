package com.anezium.rokidbus.shared.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PathRulesTest {
    @Test
    fun `prefix matching respects segment boundaries`() {
        assertTrue(PathRules.matchesPrefix("/plugin/hello", "/plugin/hello"))
        assertTrue(PathRules.matchesPrefix("/plugin/hello/reply", "/plugin/hello"))
        assertFalse(PathRules.matchesPrefix("/plugin/hello-world", "/plugin/hello"))
        assertFalse(PathRules.matchesPrefix("/foobar", "/foo"))
    }

    @Test
    fun `invalid and root paths are rejected`() {
        assertNull(PathRules.normalizeAbsolute(""))
        assertNull(PathRules.normalizeAbsolute("relative"))
        assertNull(PathRules.normalizeAbsolute("/"))
        assertNull(PathRules.normalizeAbsolute("/a//b"))
        assertNull(PathRules.normalizeAbsolute("/a/../b"))
        assertEquals("/a/b", PathRules.normalizeAbsolute("  /a/b  "))
    }

    @Test
    fun `reserved routes use segment-aware ownership`() {
        assertTrue(PathRules.isReserved("/launcher"))
        assertTrue(PathRules.isReserved("/launcher/open"))
        assertTrue(PathRules.isReserved("/surface/input"))
        assertTrue(PathRules.isReserved("/system/plugin/open"))
        assertTrue(PathRules.isReserved("/security/revoke"))
        assertTrue(PathRules.isReserved("/error"))
        assertFalse(PathRules.isReserved("/launcherish"))
    }

    @Test
    fun `camera receive routes are capability conditioned and narrow`() {
        val camera = setOf(PluginCapability.CAMERA)
        assertTrue(PathRules.isAllowedReceivePrefix("/camera/session/state", "lens", camera))
        assertTrue(PathRules.isAllowedReceivePrefix("/camera/link/offer", "lens", camera))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera/session/state", "lens", emptySet()))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera", "lens", camera))
        assertFalse(PathRules.isAllowedReceivePrefix("/camera/overlay", "lens", camera))
        assertEquals(
            PluginCapability.CAMERA,
            PathRules.requiredCapabilityForReceivePrefix("/camera/link/offer"),
        )
    }
}
