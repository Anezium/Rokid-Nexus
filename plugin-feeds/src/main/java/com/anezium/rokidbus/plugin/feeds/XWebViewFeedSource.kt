package com.anezium.rokidbus.plugin.feeds

import android.content.Context
import java.io.IOException
import java.time.Instant

internal data class XWebViewCaptureRequest(
    val cookies: XAccountCookies,
    val initialPage: Boolean,
    val previousFingerprint: String?,
    val threadPostId: String? = null,
)

internal data class XWebViewCapturedResponse(
    val body: String,
    val fingerprint: String = XWebViewInterception.responseFingerprint(body),
)

internal interface XWebViewCaptureClient : AutoCloseable {
    val isOverlayGranted: Boolean
    fun capture(request: XWebViewCaptureRequest, timeoutMillis: Long): XWebViewCapturedResponse?
    override fun close() = Unit
}

class XWebViewFeedSource internal constructor(
    private val cookies: XAccountCookies,
    private val captureClient: XWebViewCaptureClient,
    private val now: () -> Instant = Instant::now,
    private val timeoutMillis: Long = CAPTURE_TIMEOUT_MILLIS,
) : FeedSource, AutoCloseable {
    constructor(context: Context, cookies: XAccountCookies) : this(
        cookies = cookies,
        captureClient = ServiceXWebViewCaptureClient(context.applicationContext),
    )

    private var previousFingerprint: String? = null
    private var previousThreadFingerprint: String? = null

    override fun fetchPage(cursor: String?): FeedPage {
        if (!cookies.isConnected) return missingCookiesPage(now())
        if (!captureClient.isOverlayGranted) return missingOverlayPage(now())

        val response = captureClient.capture(
            request = XWebViewCaptureRequest(
                cookies = cookies,
                initialPage = cursor == null,
                previousFingerprint = previousFingerprint,
            ),
            timeoutMillis = timeoutMillis,
        ) ?: return timeoutPage(now()).also { captureClient.close() }
        if (response.fingerprint == previousFingerprint) return FeedPage(emptyList(), null)
        previousFingerprint = response.fingerprint
        return parseCapturedPage(response.body, cursor, now())
    }

    override fun fetchThread(post: FeedPost): FeedThread {
        if (!cookies.isConnected) return FeedThread(listOf(post), 0)
        if (!captureClient.isOverlayGranted) throw IOException("X WebView overlay permission is missing")
        val response = captureClient.capture(
            request = XWebViewCaptureRequest(
                cookies = cookies,
                initialPage = false,
                previousFingerprint = previousThreadFingerprint,
                threadPostId = post.threadRef.ifBlank { post.id },
            ),
            timeoutMillis = timeoutMillis,
        ) ?: throw IOException("X WebView TweetDetail capture timed out")
        previousThreadFingerprint = response.fingerprint
        val parsed = XGraphQlParser.parseThread(
            json = response.body,
            focalTweetId = post.id,
            source = FeedSourceKind.X_WEBVIEW.tag,
            fallbackNow = now(),
        )
        return if (parsed.posts.isEmpty()) FeedThread(listOf(post), 0) else parsed
    }

    override fun close() {
        captureClient.close()
    }

    companion object {
        internal const val CAPTURE_TIMEOUT_MILLIS = 15_000L

        internal fun parseCapturedPage(
            json: String,
            requestedCursor: String? = null,
            fallbackNow: Instant = Instant.now(),
        ): FeedPage {
            val parsed = XAccountFeedSource.parsePage(json, requestedCursor, fallbackNow)
            return parsed.copy(
                posts = parsed.posts.map { it.copy(source = FeedSourceKind.X_WEBVIEW.tag) },
            )
        }

        internal fun missingCookiesPage(now: Instant = Instant.now()): FeedPage = messagePage(
            id = "x-webview-connect",
            text = "X: connect your account in phone settings",
            now = now,
        )

        internal fun timeoutPage(now: Instant = Instant.now()): FeedPage = messagePage(
            id = "x-webview-timeout",
            text = "X webview: no feed captured — reconnect?",
            now = now,
        )

        private fun missingOverlayPage(now: Instant): FeedPage = messagePage(
            id = "x-webview-overlay",
            text = "X webview: grant Display over other apps in phone settings",
            now = now,
        )

        private fun messagePage(id: String, text: String, now: Instant): FeedPage = FeedPage(
            posts = listOf(
                FeedPost(
                    id = id,
                    authorName = "X",
                    authorHandle = "x",
                    text = text,
                    createdAt = now,
                    source = FeedSourceKind.X_WEBVIEW.tag,
                ),
            ),
            nextCursor = null,
        )
    }
}
