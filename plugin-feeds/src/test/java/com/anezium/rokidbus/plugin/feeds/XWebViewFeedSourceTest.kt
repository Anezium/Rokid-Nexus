package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class XWebViewFeedSourceTest {
    @Test
    fun homeTimelineUrlMatch_acceptsHomeOperationsAndRejectsOtherTraffic() {
        assertTrue(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://x.com/i/api/graphql/query-id/HomeTimeline?variables=%7B%7D",
            ),
        )
        assertTrue(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://x.com/i/api/graphql/query-id/HomeLatestTimeline",
            ),
        )
        assertTrue(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://x.com/i/api/graphql/query-id%2FHomeTimeline",
            ),
        )
        assertFalse(XWebViewInterception.isHomeTimelineGraphQlUrl("https://x.com/home"))
        assertFalse(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://x.com/i/api/graphql/query-id/UserTweets",
            ),
        )
        assertFalse(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://evil.example/graphql/query-id/HomeTimeline",
            ),
        )
        assertFalse(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://x.com/i/api/graphql/query-id/UserTweets?next=/HomeTimeline",
            ),
        )
        assertFalse(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "https://not-x.com/graphql/query-id/HomeTimeline",
            ),
        )
        assertFalse(
            XWebViewInterception.isHomeTimelineGraphQlUrl(
                "http://x.com/i/api/graphql/query-id/HomeTimeline",
            ),
        )
        assertTrue(
            XWebViewInterception.isTweetDetailGraphQlUrl(
                "https://x.com/i/api/graphql/query-id/TweetDetail?variables=%7B%7D",
            ),
        )
        assertFalse(
            XWebViewInterception.isTweetDetailGraphQlUrl(
                "https://evil.example/i/api/graphql/query-id/TweetDetail",
            ),
        )
        val variables = java.net.URLEncoder.encode("{\"focalTweetId\":\"300\"}", Charsets.UTF_8.name())
        assertEquals(
            "300",
            XWebViewInterception.tweetDetailFocalTweetId(
                "https://x.com/i/api/graphql/query-id/TweetDetail?variables=$variables&features=%7B%7D",
            ),
        )
        assertNull(
            XWebViewInterception.tweetDetailFocalTweetId(
                "https://x.com/i/api/graphql/query-id/TweetDetail?variables=%7B%7D",
            ),
        )
    }

    @Test
    fun interceptionPayload_wrapsFetchAndXhrOnceWithoutDomScraping() {
        val javascript = XWebViewInterception.javascript()

        assertTrue(javascript.contains("window.__nexusXHomeTimelineInterceptor"))
        assertTrue(javascript.contains("window.fetch"))
        assertTrue(javascript.contains("response.clone().text()"))
        assertTrue(javascript.contains("XMLHttpRequest"))
        assertTrue(javascript.contains("xhr.responseText"))
        assertTrue(javascript.contains("tweetdetail"))
        assertTrue(javascript.contains("onGraphQlResponse"))
        assertFalse(javascript.contains("querySelector"))
    }

    @Test
    fun duplicateSuppression_appliesToHomePaginationButNotThreadReopen() {
        assertTrue(XWebViewInterception.shouldSuppressDuplicate("same", "same", threadPostId = null))
        assertFalse(XWebViewInterception.shouldSuppressDuplicate("same", "same", threadPostId = "300"))
        assertFalse(XWebViewInterception.shouldSuppressDuplicate("old", "new", threadPostId = null))
    }

    @Test
    fun interceptedFixture_usesAccountParserAndReturnsWebViewPosts() {
        val captureClient = FakeCaptureClient(response = XWebViewCapturedResponse(fixture()))
        val source = XWebViewFeedSource(
            cookies = connectedCookies(),
            captureClient = captureClient,
            now = { Instant.EPOCH },
        )

        val page = source.fetchPage(null)

        assertEquals(listOf("101", "202"), page.posts.map(FeedPost::id))
        assertEquals("This is the complete Note Tweet long text, not the truncated legacy text.", page.posts[1].text)
        assertTrue(page.posts[1].hasMedia)
        assertTrue(page.posts.all { it.source == "x-web" })
        assertEquals("bottom-cursor-2", page.nextCursor)
        assertTrue(captureClient.requests.single().initialPage)
    }

    @Test
    fun missingSession_returnsConnectPostWithoutStartingCapture() {
        val captureClient = FakeCaptureClient(response = null)
        val source = XWebViewFeedSource(
            cookies = XAccountCookies(),
            captureClient = captureClient,
            now = { Instant.EPOCH },
        )

        val page = source.fetchPage(null)

        assertEquals("X: connect your account in phone settings", page.posts.single().text)
        assertEquals("x-web", page.posts.single().source)
        assertTrue(captureClient.requests.isEmpty())
        assertNull(page.nextCursor)
    }

    @Test
    fun captureTimeout_returnsReconnectHint() {
        val source = XWebViewFeedSource(
            cookies = connectedCookies(),
            captureClient = FakeCaptureClient(response = null),
            now = { Instant.EPOCH },
            timeoutMillis = 1L,
        )

        val page = source.fetchPage(null)

        assertEquals("X webview: no feed captured — reconnect?", page.posts.single().text)
        assertEquals("x-web", page.posts.single().source)
        assertNull(page.nextCursor)
    }

    @Test
    fun paginationStopsForSameResponseAndSameCursor() {
        val response = XWebViewCapturedResponse(fixture())
        val captureClient = FakeCaptureClient(response)
        val source = XWebViewFeedSource(
            cookies = connectedCookies(),
            captureClient = captureClient,
            now = { Instant.EPOCH },
        )

        assertEquals("bottom-cursor-2", source.fetchPage(null).nextCursor)
        val unchangedPage = source.fetchPage("bottom-cursor-2")

        assertTrue(unchangedPage.posts.isEmpty())
        assertNull(unchangedPage.nextCursor)
        assertNull(XWebViewFeedSource.parseCapturedPage(fixture(), "bottom-cursor-2").nextCursor)
        assertFalse(captureClient.requests.last().initialPage)
    }

    @Test
    fun fetchThread_requestsTweetDetailAndUsesSharedParser() {
        val captureClient = FakeCaptureClient(XWebViewCapturedResponse(threadFixture()))
        val source = XWebViewFeedSource(
            cookies = connectedCookies(),
            captureClient = captureClient,
            now = { Instant.EPOCH },
        )
        val focal = FeedPost(
            id = "300",
            authorName = "Focal",
            authorHandle = "focal",
            text = "Focal tweet",
            createdAt = Instant.EPOCH,
            source = FeedSourceKind.X_WEBVIEW.tag,
        )

        val thread = source.fetchThread(focal)

        assertEquals(listOf("100", "200", "300", "401", "402"), thread.posts.map(FeedPost::id))
        assertEquals(2, thread.focusIndex)
        assertEquals("300", captureClient.requests.single().threadPostId)
        assertFalse(captureClient.requests.single().initialPage)
        assertTrue(thread.posts.all { it.source == FeedSourceKind.X_WEBVIEW.tag })
    }

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResource("x_home_timeline.json")).readText()

    private fun threadFixture(): String =
        checkNotNull(javaClass.classLoader?.getResource("x_tweet_detail.json")).readText()

    private fun connectedCookies(): XAccountCookies = XAccountCookies(authToken = "auth", ct0 = "csrf")

    private class FakeCaptureClient(
        private val response: XWebViewCapturedResponse?,
        override val isOverlayGranted: Boolean = true,
    ) : XWebViewCaptureClient {
        val requests = mutableListOf<XWebViewCaptureRequest>()

        override fun capture(
            request: XWebViewCaptureRequest,
            timeoutMillis: Long,
        ): XWebViewCapturedResponse? {
            requests += request
            return response
        }
    }
}
