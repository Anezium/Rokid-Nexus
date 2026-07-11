package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BlueskyFeedSourceTest {
    @Test
    fun parsePage_filtersRepliesRepostsAndLoggedOutOptOuts() {
        val json = checkNotNull(javaClass.classLoader?.getResource("bluesky_feed.json")).readText()

        val page = BlueskyFeedSource.parsePage(json)

        assertEquals("next-page-token", page.nextCursor)
        assertEquals(listOf("Alice", "Bob"), page.posts.map(FeedPost::authorName))
        assertTrue(page.posts[0].hasMedia)
        assertFalse(page.posts[1].hasMedia)
        assertTrue(page.posts.all { it.source == "bsky" })
    }

    @Test
    fun parsePage_treatsAbsentCursorAsEndOfFeed() {
        val page = BlueskyFeedSource.parsePage("""{"feed": []}""")

        assertEquals(emptyList<FeedPost>(), page.posts)
        assertEquals(null, page.nextCursor)
    }
}
