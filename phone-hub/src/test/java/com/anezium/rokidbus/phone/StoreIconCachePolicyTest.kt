package com.anezium.rokidbus.phone

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StoreIconCachePolicyTest {
    @Test
    fun `cache filename is a stable sha256 of the url`() {
        val directory = File("ignored-cache-root")
        val url = "https://cdn.example.com/icons/shoplist.png"

        val file = StoreIconCachePolicy.cacheFile(directory, url)

        assertEquals(directory, file.parentFile)
        assertEquals(
            "d560f7344df2aad336186d660bdf60b21994c9248cbc7669c84a234da0cf2ee5.png",
            file.name,
        )
        assertEquals(file, StoreIconCachePolicy.cacheFile(directory, url))
    }

    @Test
    fun `cache freshness includes seven day boundary and rejects future mtimes`() {
        val now = 2_000_000_000L

        assertTrue(StoreIconCachePolicy.isFresh(now, now))
        assertTrue(
            StoreIconCachePolicy.isFresh(
                now - StoreIconCachePolicy.TTL_MILLIS,
                now,
            ),
        )
        assertFalse(
            StoreIconCachePolicy.isFresh(
                now - StoreIconCachePolicy.TTL_MILLIS - 1L,
                now,
            ),
        )
        assertFalse(StoreIconCachePolicy.isFresh(now + 1L, now))
        assertFalse(StoreIconCachePolicy.isFresh(0L, now))
    }
}
