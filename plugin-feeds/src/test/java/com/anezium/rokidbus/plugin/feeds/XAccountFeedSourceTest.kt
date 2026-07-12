package com.anezium.rokidbus.plugin.feeds

import com.anezium.rokidbus.plugin.feeds.xtransaction.TIME_EPOCH_SECONDS
import com.anezium.rokidbus.plugin.feeds.xtransaction.XClientTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class XAccountFeedSourceTest {
    @Test
    fun parsePage_extractsTweetsLongTextMediaAndBottomCursorWhileSkippingPromotion() {
        val page = XAccountFeedSource.parsePage(fixture(), fallbackNow = Instant.EPOCH)

        assertEquals(listOf("101", "202"), page.posts.map(FeedPost::id))
        assertEquals(listOf("Alice", "Bob"), page.posts.map(FeedPost::authorName))
        assertEquals(listOf("alice", "bob"), page.posts.map(FeedPost::authorHandle))
        assertEquals(
            "This is the complete Note Tweet long text, not the truncated legacy text.",
            page.posts[1].text,
        )
        assertFalse(page.posts[0].hasMedia)
        assertTrue(page.posts[1].hasMedia)
        assertEquals(listOf(FeedMediaType.PHOTO, FeedMediaType.GIF, FeedMediaType.VIDEO), page.posts[1].media.map(FeedMedia::type))
        assertEquals("https://pbs.twimg.com/media/photo.jpg?name=large", page.posts[1].media[0].url)
        assertEquals("https://pbs.twimg.com/media/photo.jpg?name=small", page.posts[1].media[0].previewUrl)
        assertEquals("A cat sleeping on a chair", page.posts[1].media[0].altText)
        assertEquals("https://video.twimg.com/gif.mp4", page.posts[1].media[1].url)
        assertEquals(4_200L, page.posts[1].media[1].durationMs)
        assertEquals("https://video.twimg.com/high.mp4", page.posts[1].media[2].url)
        assertEquals(12_500L, page.posts[1].media[2].durationMs)
        assertEquals(Instant.parse("2018-10-10T20:19:24Z"), page.posts[0].createdAt)
        assertTrue(page.posts.all { it.source == "x" })
        assertEquals("bottom-cursor-2", page.nextCursor)
    }

    @Test
    fun parsePage_stopsForUnchangedAbsentAndEmptyPagination() {
        assertNull(XAccountFeedSource.parsePage(fixture(), "bottom-cursor-2").nextCursor)
        assertNull(XAccountFeedSource.parsePage(emptyTimeline()).nextCursor)
        assertNull(XAccountFeedSource.parsePage("{}", "old-cursor").nextCursor)
    }

    @Test
    fun mediaExtraction_fallsBackToLegacyEntitiesPhoto() {
        val legacy = org.json.JSONObject(
            """{"entities":{"media":[{"type":"photo","media_url_https":"https://pbs.twimg.com/entities.jpg","ext_alt_text":"Fallback photo"}]}}""",
        )

        val media = XGraphQlParser.extractMedia(legacy).single()

        assertEquals(FeedMediaType.PHOTO, media.type)
        assertEquals("https://pbs.twimg.com/entities.jpg?name=large", media.url)
        assertEquals("Fallback photo", media.altText)
    }

    @Test
    fun missingCookies_returnsConnectPostWithoutNetworkOrTransactionInitialization() {
        var networkCalled = false
        val source = XAccountFeedSource(
            cookies = XAccountCookies(),
            httpClient = FeedHttpClient { _, _ ->
                networkCalled = true
                error("Network must not be called")
            },
            now = { Instant.EPOCH },
            transactionFactory = { error("Transaction must not be initialized") },
        )

        val page = source.fetchPage(null)

        assertFalse(networkCalled)
        assertEquals("X: connect your account in phone settings", page.posts.single().text)
        assertEquals("x", page.posts.single().source)
        assertNull(page.nextCursor)
    }

    @Test
    fun accountHeaders_matchQuaXAccountPathAndExcludeGuestHeaders() {
        val headers = XAccountFeedSource.accountHeaders(
            cookies = XAccountCookies(authToken = "auth", ct0 = "csrf", guestId = "guest", gt = "guest-token"),
            transactionId = "transaction",
        )

        assertEquals("Bearer ${XAccountFeedSource.AUTHENTICATED_BEARER}", headers["authorization"])
        assertEquals("guest_id=guest;gt=guest-token;auth_token=auth;ct0=csrf", headers["Cookie"])
        assertEquals("csrf", headers["x-csrf-token"])
        assertEquals("transaction", headers["x-client-transaction-id"])
        assertFalse(headers.containsKey("x-twitter-auth-type"))
        assertFalse(headers.containsKey("x-guest-token"))
    }

    @Test
    fun notFound_surfacesReconnectPostInsteadOfThrowing() {
        val source = sourceWithResponse(FeedHttpResponse(statusCode = 404, body = "not found"))

        val page = source.fetchPage(null)

        assertEquals("X: reconnect your account in phone settings", page.posts.single().text)
        assertNull(page.nextCursor)
    }

    @Test
    fun rateLimit_usesFallbackCooldownAndDoesNotRepeatNetworkRequest() {
        var requests = 0
        var currentTime = Instant.EPOCH
        val source = XAccountFeedSource(
            cookies = connectedCookies(),
            httpClient = responseClient {
                requests++
                FeedHttpResponse(statusCode = 429, body = "rate limited")
            },
            now = { currentTime },
            transactionFactory = { fixedTransaction() },
        )

        assertEquals("X: rate limited; try again later", source.fetchPage(null).posts.single().text)
        currentTime = currentTime.plusSeconds(60)
        assertEquals("X: rate limited; try again later", source.fetchPage(null).posts.single().text)
        assertEquals(1, requests)
    }

    @Test
    fun fetchPage_usesHomeTimelineEndpointCursorAndTransactionHeader() {
        var requestedUrl = ""
        var requestedHeaders = emptyMap<String, String>()
        val source = XAccountFeedSource(
            cookies = connectedCookies(),
            httpClient = responseClient { url, headers ->
                requestedUrl = url
                requestedHeaders = headers
                FeedHttpResponse(statusCode = 200, body = fixture())
            },
            now = { Instant.EPOCH },
            transactionFactory = { fixedTransaction() },
        )

        source.fetchPage("cursor-1")

        assertTrue(requestedUrl.startsWith("${XAccountFeedSource.API_URL}?variables="))
        assertTrue(java.net.URLDecoder.decode(requestedUrl, Charsets.UTF_8.name()).contains("\"cursor\":\"cursor-1\""))
        assertTrue(requestedUrl.contains("&features="))
        assertTrue(requestedUrl.contains("&fieldToggles="))
        assertTrue(requestedHeaders["x-client-transaction-id"].orEmpty().isNotBlank())
    }

    @Test
    fun tweetDetailParser_preservesTopToBottomOrderAndFindsFocal() {
        val thread = XGraphQlParser.parseThread(
            json = threadFixture(),
            focalTweetId = "300",
            source = FeedSourceKind.X_ACCOUNT.tag,
            fallbackNow = Instant.EPOCH,
        )

        assertEquals(listOf("100", "200", "300", "401", "402"), thread.posts.map(FeedPost::id))
        assertEquals(2, thread.focusIndex)
        assertEquals("https://video.twimg.com/focal-high.mp4", thread.posts[2].media.single().url)
        assertEquals("Reply Two", thread.posts.last().authorName)
    }

    @Test
    fun fetchThread_usesTweetDetailEndpointVariablesAndTransactionHeader() {
        var requestedUrl = ""
        var requestedHeaders = emptyMap<String, String>()
        val source = XAccountFeedSource(
            cookies = connectedCookies(),
            httpClient = responseClient { url, headers ->
                requestedUrl = url
                requestedHeaders = headers
                FeedHttpResponse(statusCode = 200, body = threadFixture())
            },
            now = { Instant.EPOCH },
            transactionFactory = { fixedTransaction() },
        )
        val focal = XGraphQlParser.parseThread(
            threadFixture(),
            "300",
            FeedSourceKind.X_ACCOUNT.tag,
            Instant.EPOCH,
        ).posts[2]

        val thread = source.fetchThread(focal)

        assertEquals(2, thread.focusIndex)
        assertTrue(requestedUrl.startsWith("${XAccountFeedSource.THREAD_API_URL}?variables="))
        assertTrue(java.net.URLDecoder.decode(requestedUrl, Charsets.UTF_8.name()).contains("\"focalTweetId\":\"300\""))
        assertTrue(requestedUrl.contains("&features="))
        assertTrue(requestedUrl.contains("&fieldToggles="))
        assertTrue(requestedHeaders["x-client-transaction-id"].orEmpty().isNotBlank())
    }

    @Test
    fun cookieCapture_keepsOnlyQuaXAccountCookiesAndRequiresAuthAndCsrf() {
        val cookies = XAccountCookies.fromCookieHeader(
            "other=discard; auth_token=auth=value; ct0=csrf; guest_id=guest; gt=token; att=attestation",
        )

        assertTrue(cookies.isConnected)
        assertEquals("auth=value", cookies.authToken)
        assertEquals("guest_id=guest;gt=token;att=attestation;auth_token=auth=value;ct0=csrf", cookies.asCookieHeader())
        assertFalse(XAccountCookies(authToken = "auth").isConnected)
    }

    private fun fixture(): String =
        checkNotNull(javaClass.classLoader?.getResource("x_home_timeline.json")).readText()

    private fun threadFixture(): String =
        checkNotNull(javaClass.classLoader?.getResource("x_tweet_detail.json")).readText()

    private fun emptyTimeline(): String =
        """{"data":{"home":{"home_timeline_urt":{"instructions":[{"type":"TimelineAddEntries","entries":[{"entryId":"cursor-bottom-0","content":{"cursorType":"Bottom","value":"next"}}]}]}}}}"""

    private fun sourceWithResponse(response: FeedHttpResponse): XAccountFeedSource = XAccountFeedSource(
        cookies = connectedCookies(),
        httpClient = responseClient { response },
        now = { Instant.EPOCH },
        transactionFactory = { fixedTransaction() },
    )

    private fun connectedCookies(): XAccountCookies = XAccountCookies(authToken = "auth", ct0 = "csrf")

    private fun fixedTransaction(): XClientTransaction = XClientTransaction.create(
        keyBytes = listOf(1, 2, 3),
        animationKey = "animation",
        epochSeconds = { TIME_EPOCH_SECONDS + 1 },
        randomByte = { 7 },
    )

    private fun responseClient(
        response: (String, Map<String, String>) -> FeedHttpResponse,
    ): FeedHttpClient = object : FeedHttpClient {
        override fun get(url: String, headers: Map<String, String>): String =
            error("String-only request was not expected")

        override fun getResponse(url: String, headers: Map<String, String>): FeedHttpResponse =
            response(url, headers)
    }

    private fun responseClient(response: () -> FeedHttpResponse): FeedHttpClient =
        responseClient { _, _ -> response() }
}
