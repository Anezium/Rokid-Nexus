package com.anezium.rokidbus.phone

import com.anezium.rokidbus.shared.ImageSurfaceContract

internal class ImageSurfaceRateLimiter(
    private val nowMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    private val lastAcceptedBySurface = mutableMapOf<String, Long>()

    @Synchronized
    fun tryAcquire(surfaceId: String): Boolean {
        val now = nowMs()
        val previous = lastAcceptedBySurface[surfaceId]
        if (previous != null && now - previous < ImageSurfaceContract.MIN_FRAME_INTERVAL_MS) return false
        lastAcceptedBySurface[surfaceId] = now
        return true
    }

    @Synchronized
    fun clear() = lastAcceptedBySurface.clear()
}
