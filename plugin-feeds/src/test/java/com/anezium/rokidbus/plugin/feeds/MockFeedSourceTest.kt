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

    @Test
    fun bundledDemoThread_hasAncestorsFocalAndReplies() {
        val source = MockFeedSource { Instant.EPOCH }
        val focal = source.fetchPage(null).posts[3]

        val thread = source.fetchThread(focal)

        assertEquals(6, thread.posts.size)
        assertEquals(2, thread.focusIndex)
        assertEquals(focal, thread.posts[thread.focusIndex])
        assertTrue(thread.posts.drop(thread.focusIndex + 1).any(FeedPost::hasMedia))
    }
}
