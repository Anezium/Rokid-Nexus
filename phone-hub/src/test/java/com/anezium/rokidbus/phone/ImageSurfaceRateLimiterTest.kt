package com.anezium.rokidbus.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageSurfaceRateLimiterTest {
    @Test
    fun `enforces 150 ms independently per surface`() {
        var now = 1_000L
        val limiter = ImageSurfaceRateLimiter { now }

        assertTrue(limiter.tryAcquire("plugin:main"))
        assertTrue(limiter.tryAcquire("plugin:other"))
        now += 149
        assertFalse(limiter.tryAcquire("plugin:main"))
        now += 1
        assertTrue(limiter.tryAcquire("plugin:main"))
    }
}
