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
        assertEquals(2, page.posts[0].media.size)
        assertEquals(FeedMediaType.PHOTO, page.posts[0].media[0].type)
        assertEquals("https://cdn.bsky.app/full-1.jpg", page.posts[0].media[0].url)
        assertEquals("A cat sleeping on a blue chair", page.posts[0].media[0].altText)
        assertFalse(page.posts[1].hasMedia)
        assertTrue(page.posts.all { it.source == "bsky" })
    }

    @Test
    fun parsePage_treatsAbsentCursorAsEndOfFeed() {
        val page = BlueskyFeedSource.parsePage("""{"feed": []}""")

        assertEquals(emptyList<FeedPost>(), page.posts)
        assertEquals(null, page.nextCursor)
    }

    @Test
    fun extractMedia_handlesRecordWithMediaVideoAndExternalThumb() {
        val wrappedVideo = org.json.JSONObject(
            """{"${'$'}type":"app.bsky.embed.recordWithMedia#view","media":{"${'$'}type":"app.bsky.embed.video#view","playlist":"https://video.example/stream.m3u8","thumbnail":"https://video.example/poster.jpg","alt":"A short clip"}}""",
        )
        val external = org.json.JSONObject(
            """{"${'$'}type":"app.bsky.embed.external#view","external":{"thumb":"https://example.com/thumb.jpg","description":"Link preview"}}""",
        )

        val video = BlueskyFeedSource.extractMedia(wrappedVideo).single()
        val thumb = BlueskyFeedSource.extractMedia(external).single()

        assertEquals(FeedMediaType.VIDEO, video.type)
        assertEquals("https://video.example/stream.m3u8", video.url)
        assertEquals("https://video.example/poster.jpg", video.previewUrl)
        assertEquals("A short clip", video.altText)
        assertEquals(FeedMediaType.PHOTO, thumb.type)
        assertEquals("https://example.com/thumb.jpg", thumb.url)
    }

    @Test
    fun parseThread_ordersAncestorsFocalAndFirstLevelReplies() {
        val json = checkNotNull(javaClass.classLoader?.getResource("bluesky_thread.json")).readText()

        val thread = BlueskyFeedSource.parseThread(json)

        assertEquals(listOf("Root", "Parent", "Focal", "Reply One", "Reply Two"), thread.posts.map(FeedPost::authorName))
        assertEquals(2, thread.focusIndex)
        assertEquals(FeedMediaType.VIDEO, thread.posts[thread.focusIndex].media.single().type)
        assertEquals("at://did:plc:focal/app.bsky.feed.post/focal", thread.posts[thread.focusIndex].threadRef)
    }
}
