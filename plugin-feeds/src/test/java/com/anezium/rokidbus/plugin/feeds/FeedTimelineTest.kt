package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class FeedTimelineTest {
    @Test
    fun cursorAndPrefetchThreshold_followPageState() {
        val timeline = FeedTimeline()
        assertFalse(timeline.shouldFetchNext(0))

        timeline.append(FeedPage(posts(0, 25), "cursor-2"))

        assertEquals("cursor-2", timeline.nextCursor)
        assertFalse(timeline.shouldFetchNext(19))
        assertTrue(timeline.shouldFetchNext(20))

        timeline.append(FeedPage(posts(25, 10), null))

        assertEquals(35, timeline.posts.size)
        assertNull(timeline.nextCursor)
        assertFalse(timeline.shouldFetchNext(34))
    }

    @Test
    fun append_deduplicatesAndNeverKeepsMoreThanTwoHundred() {
        val timeline = FeedTimeline()

        timeline.append(FeedPage(posts(0, 150), "cursor-2"))
        timeline.append(FeedPage(posts(140, 100), "cursor-3"))

        assertEquals(200, timeline.posts.size)
        assertEquals(200, timeline.posts.map(FeedPost::id).toSet().size)
        assertNull(timeline.nextCursor)
    }

    private fun posts(start: Int, count: Int): List<FeedPost> = (start until start + count).map { index ->
        FeedPost(
            id = "post-$index",
            authorName = "Author",
            authorHandle = "author",
            text = "Post $index",
            createdAt = Instant.EPOCH,
            source = "demo",
            hasMedia = false,
        )
    }
}
