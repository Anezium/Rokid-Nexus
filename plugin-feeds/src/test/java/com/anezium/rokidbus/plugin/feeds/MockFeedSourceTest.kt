package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class MockFeedSourceTest {
    @Test
    fun bundledDemo_hasFifteenVariedOfflinePostsIncludingTwoHundredEightyCharacters() {
        val page = MockFeedSource { Instant.EPOCH }.fetchPage(null)

        assertEquals(15, page.posts.size)
        assertTrue(page.posts.any { it.text.length == 280 })
        assertTrue(page.posts.any(FeedPost::hasMedia))
        assertEquals(null, page.nextCursor)
    }
}
