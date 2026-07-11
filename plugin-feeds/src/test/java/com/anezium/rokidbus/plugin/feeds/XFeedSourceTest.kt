package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class XFeedSourceTest {
    @Test
    fun missingToken_returnsExplanatoryPostWithoutNetwork() {
        var networkCalled = false
        val source = XFeedSource(
            bearerToken = "",
            userId = "12345",
            httpClient = FeedHttpClient { _, _ ->
                networkCalled = true
                error("Network must not be called")
            },
            now = { Instant.EPOCH },
        )

        val page = source.fetchPage(null)

        assertFalse(networkCalled)
        assertEquals(1, page.posts.size)
        assertEquals("X: add API token in phone settings", page.posts.single().text)
        assertEquals("x-api", page.posts.single().source)
        assertNull(page.nextCursor)
    }
}
