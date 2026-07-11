package com.anezium.rokidbus.plugin.feeds

import android.content.Context
import java.time.Instant

internal data class XWebViewCaptureRequest(
    val cookies: XAccountCookies,
    val initialPage: Boolean,
    val previousFingerprint: String?,
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
                    hasMedia = false,
                ),
            ),
            nextCursor = null,
        )
    }
}
