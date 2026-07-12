package com.anezium.rokidbus.plugin.feeds

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import com.anezium.rokidbus.shared.ImageSurfaceContract
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class FeedsRuntimeTest {
    private val now = Instant.parse("2026-07-12T12:00:00Z")

    @Test
    fun open_entersSourceMenuWithoutBuildingOrFetchingSource() {
        val source = FakeSource(FeedPage(listOf(post("feed", "Feed")), null), FeedThread(emptyList(), 0))
        val host = FakeHost()
        val requestedKinds = mutableListOf<FeedSourceKind>()
        val runtime = runtime(source, host, requestedKinds = requestedKinds)

        runtime.open()

        assertEquals("source 1/1 \u00b7 tap", host.last.footer)
        assertTrue(host.cards.single().second)
        assertTrue(requestedKinds.isEmpty())
        assertEquals(0, source.pageFetchCount)
    }

    @Test
    fun open_highlightsConfiguredDefaultAndFallsBackWhenUnavailable() {
        val source = FakeSource(FeedPage(emptyList(), null), FeedThread(emptyList(), 0))
        val configuredHost = FakeHost()
        val unavailableHost = FakeHost()

        runtime(
            source,
            configuredHost,
            runtimeSettings = settings(source = FeedSourceKind.X_OFFICIAL, xBearerToken = "configured"),
        ).open()
        runtime(
            source,
            unavailableHost,
            runtimeSettings = settings(source = FeedSourceKind.X_ACCOUNT),
        ).open()

        assertEquals("source 2/2 \u00b7 tap", configuredHost.last.footer)
        assertEquals("source 1/1 \u00b7 tap", unavailableHost.last.footer)
    }

    @Test
    fun menuMoveAndTap_buildsSelectedKindAndFetchesFirstPage() {
        val source = FakeSource(FeedPage(listOf(post("feed", "Feed")), null), FeedThread(emptyList(), 0))
        val host = FakeHost()
        val requestedKinds = mutableListOf<FeedSourceKind>()
        val runtime = runtime(
            source,
            host,
            runtimeSettings = settings(xBearerToken = "configured"),
            requestedKinds = requestedKinds,
        )

        runtime.open()
        runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("source 2/2 \u00b7 tap", host.last.footer)
        runtime.key(KeyEvent.KEYCODE_ENTER)

        assertEquals(listOf(FeedSourceKind.X_OFFICIAL), requestedKinds)
        assertEquals(1, source.pageFetchCount)
        assertEquals("demo 1/1", host.last.footer)
    }

    @Test
    fun availableSources_filtersCredentialsAndAlwaysHidesDemo() {
        val cookies = XAccountCookies(authToken = "auth", ct0 = "csrf")

        assertEquals(listOf(FeedSourceKind.BLUESKY), availableSources(settings()))
        assertEquals(
            listOf(FeedSourceKind.BLUESKY, FeedSourceKind.X_ACCOUNT, FeedSourceKind.X_WEBVIEW),
            availableSources(settings(xAccountCookies = cookies)),
        )
        assertEquals(
            listOf(FeedSourceKind.BLUESKY, FeedSourceKind.X_OFFICIAL),
            availableSources(settings(xBearerToken = "configured")),
        )
        assertEquals(
            listOf(
                FeedSourceKind.BLUESKY,
                FeedSourceKind.X_ACCOUNT,
                FeedSourceKind.X_WEBVIEW,
                FeedSourceKind.X_OFFICIAL,
            ),
            availableSources(settings(xAccountCookies = cookies, xBearerToken = "configured")),
        )
        assertFalse(INCLUDE_DEMO_SOURCE)
    }

    @Test
    fun feedThreadGalleryBackStack_restoresExactFeedPositionAndClosesOnlyAtRoot() {
        val feedPosts = listOf(post("feed-1", "First"), focalPost(), post("feed-3", "Third"))
        val threadPosts = listOf(post("root", "Root"), feedPosts[1], post("reply", "Reply"))
        val source = FakeSource(FeedPage(feedPosts, null), FeedThread(threadPosts, 1))
        val host = FakeHost()
        val runtime = runtime(source, host)

        runtime.openFeed()
        assertEquals("demo 1/3", host.last.footer)
        assertTrue(host.cards.first().second)

        runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("demo 2/3", host.last.footer)

        runtime.key(KeyEvent.KEYCODE_ENTER)
        assertTrue(host.cards.any { it.first.lines == listOf("Loading thread...") })
        assertEquals("demo thread 2/3", host.last.footer)
        assertEquals(0, host.last.pageIndex)

        runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("demo thread 2/3", host.last.footer)
        assertEquals(1, host.last.pageIndex)

        runtime.key(KeyEvent.KEYCODE_DPAD_CENTER)
        assertEquals("demo media 1/2", host.last.footer)
        assertTrue(host.last.lines.contains("Photo 1/2"))

        runtime.key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals("demo media 2/2", host.last.footer)
        val cardsBeforeGalleryTap = host.cards.size
        runtime.key(KeyEvent.KEYCODE_SPACE)
        assertEquals(cardsBeforeGalleryTap, host.cards.size)

        runtime.key(KeyEvent.KEYCODE_BACK)
        assertEquals("demo thread 2/3", host.last.footer)
        assertEquals(1, host.last.pageIndex)
        assertFalse(host.hidden)

        runtime.key(KeyEvent.KEYCODE_BACK)
        assertEquals("demo 2/3", host.last.footer)
        assertFalse(host.hidden)

        runtime.key(KeyEvent.KEYCODE_BACK)
        assertEquals("source 1/1 \u00b7 tap", host.last.footer)
        assertFalse(host.hidden)

        runtime.key(KeyEvent.KEYCODE_BACK)
        assertTrue(host.hidden)
    }

    @Test
    fun threadSwipes_pageCurrentPostBeforeAdvancingAndMoveBackwardToPageZero() {
        val focal = focalPost()
        val reply = post("reply", "Reply")
        val source = FakeSource(FeedPage(listOf(focal), null), FeedThread(listOf(focal, reply), 0))
        val host = FakeHost()
        val runtime = runtime(source, host)
        runtime.openFeed()
        runtime.key(KeyEvent.KEYCODE_ENTER)
        val pageCount = host.last.pageCount
        assertTrue(pageCount > 2)

        repeat(pageCount - 1) { runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT) }
        assertEquals(pageCount - 1, host.last.pageIndex)
        assertEquals("demo thread 1/2", host.last.footer)

        runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT)
        assertEquals("demo thread 2/2", host.last.footer)
        assertEquals(0, host.last.pageIndex)

        runtime.key(KeyEvent.KEYCODE_DPAD_LEFT)
        assertEquals("demo thread 1/2", host.last.footer)
        assertEquals(0, host.last.pageIndex)
    }

    @Test
    fun threadFailure_showsErrorAndBackReturnsToFeed() {
        val focal = post("focal", "Focal")
        val host = FakeHost()
        val runtime = runtime(FailingThreadSource(FeedPage(listOf(focal), null)), host)
        runtime.openFeed()

        runtime.key(KeyEvent.KEYCODE_ENTER)

        assertTrue(host.last.lines.joinToString(" ").startsWith("Thread fetch failed."))
        assertTrue(host.logs.single().startsWith("thread fetch failed source=bsky"))
        runtime.key(KeyEvent.KEYCODE_BACK)
        assertEquals("demo 1/1", host.last.footer)
        assertFalse(host.hidden)
    }

    @Test
    fun feedPrefetchStillRunsNearEnd() {
        val source = PagingSource()
        val host = FakeHost()
        val runtime = runtime(source, host)
        runtime.openFeed()

        runtime.key(KeyEvent.KEYCODE_DPAD_RIGHT)

        assertEquals(listOf(null, "next"), source.cursors)
        assertEquals("demo 2/7", host.last.footer)
    }

    @Test
    fun galleryWithoutImageCapability_keepsTextPlaceholder() {
        val host = FakeHost(supportsImage = false)
        val runtime = runtime(
            FakeSource(FeedPage(listOf(focalPost()), null), FeedThread(listOf(focalPost()), 0)),
            host,
            imageLoader = FeedImageLoader { error("must not load") },
        )

        runtime.openGallery()

        assertTrue(host.last.lines.contains("Photo 1/2"))
        assertTrue(host.images.isEmpty())
    }

    @Test
    fun galleryWithImageCapability_emitsImageAndBackReplacesItWithCard() {
        val host = FakeHost(supportsImage = true)
        val runtime = runtime(
            FakeSource(FeedPage(listOf(focalPost()), null), FeedThread(listOf(focalPost()), 0)),
            host,
            imageLoader = FeedImageLoader { validImageFrame() },
        )

        runtime.openGallery()

        assertEquals(1, host.images.size)
        assertEquals("image", host.events.last())
        assertEquals("demo photo 1/2", host.images.single().first.getString("footer"))

        runtime.key(KeyEvent.KEYCODE_BACK)

        assertEquals("card", host.events.last())
        assertEquals("demo thread 1/1", host.last.footer)
    }

    @Test
    fun galleryImageFailure_replacesLoadingCardWithTextFallback() {
        val host = FakeHost(supportsImage = true)
        val runtime = runtime(
            FakeSource(FeedPage(listOf(focalPost()), null), FeedThread(listOf(focalPost()), 0)),
            host,
            imageLoader = FeedImageLoader { null },
        )

        runtime.openGallery()

        assertTrue(host.images.isEmpty())
        assertTrue(host.last.lines.contains("Photo 1/2"))
    }

    @Test
    fun lateGalleryResultAfterIndexChange_doesNotReplaceNewerView() {
        val loader = DeferredImageLoader()
        val host = FakeHost(supportsImage = true)
        val runtime = runtime(
            FakeSource(FeedPage(listOf(focalPost()), null), FeedThread(listOf(focalPost()), 0)),
            host,
            imageLoader = loader,
        )
        runtime.openGallery()
        assertEquals(1, loader.requests.size)

        runtime.key(KeyEvent.KEYCODE_MEDIA_NEXT)
        assertEquals(2, loader.requests.size)
        assertTrue(host.last.lines.single().startsWith("Loading photo"))

        loader.complete(0, validImageFrame())
        assertTrue(host.images.isEmpty())

        loader.complete(1, validImageFrame())
        assertEquals(1, host.images.size)
        assertEquals("demo video 0:12 2/2", host.images.single().first.getString("footer"))
    }

    private fun runtime(
        source: FeedSource,
        host: FakeHost,
        imageLoader: FeedImageLoader = FeedImageLoader { null },
        runtimeSettings: FeedsSettings = settings(),
        requestedKinds: MutableList<FeedSourceKind>? = null,
    ) = FeedsRuntime(
        context = null,
        host = host,
        settings = { runtimeSettings },
        sourceFactory = { _, kind ->
            requestedKinds?.add(kind)
            source
        },
        ioDispatcher = Dispatchers.Unconfined,
        imageLoader = imageLoader,
        now = { now },
    )

    private fun settings(
        source: FeedSourceKind = FeedSourceKind.BLUESKY,
        xAccountCookies: XAccountCookies = XAccountCookies(),
        xBearerToken: String = "",
    ) = FeedsSettings(
        source = source,
        xAccountCookies = xAccountCookies,
        xBearerToken = xBearerToken,
        xUserId = "",
        blueskyFeedGeneratorUri = BlueskyFeedSource.DEFAULT_FEED_GENERATOR_URI,
    )

    private fun focalPost() = post(
        id = "focal",
        text = "Long focal post ${"word ".repeat(140)}",
        media = listOf(
            media(FeedMediaType.PHOTO, "First photo"),
            media(FeedMediaType.VIDEO, "Second item", 12_000L),
        ),
    )

    private fun post(
        id: String,
        text: String,
        media: List<FeedMedia> = emptyList(),
    ) = FeedPost(
        id = id,
        authorName = "Author $id",
        authorHandle = id,
        text = text,
        createdAt = now.minusSeconds(60),
        source = FeedSourceKind.DEMO.tag,
        media = media,
    )

    private fun media(type: FeedMediaType, alt: String, durationMs: Long? = null) = FeedMedia(
        type = type,
        url = "https://example.invalid/full",
        previewUrl = "https://example.invalid/preview",
        altText = alt,
        durationMs = durationMs,
    )

    private fun FeedsRuntime.key(keyCode: Int) {
        input(NexusInputEvent("feeds", keyCode, KeyEvent.ACTION_DOWN))
    }

    private fun FeedsRuntime.openGallery() {
        openFeed()
        key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
    }

    private fun FeedsRuntime.openFeed() {
        open()
        key(KeyEvent.KEYCODE_ENTER)
    }

    private fun validImageFrame(): FeedImageFrame {
        val bytes = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(),
            0xff.toByte(), 0xc0.toByte(), 0x00, 0x0b,
            0x08, 0x00, 0x01, 0x00, 0x01,
            0x01, 0x01, 0x11, 0x00,
            0xff.toByte(), 0xd9.toByte(),
        )
        return FeedImageFrame(
            contentKey = "feed-test",
            pixelWidth = 1,
            pixelHeight = 1,
            mimeType = ImageSurfaceContract.MIME_JPEG,
            sha256 = ImageSurfaceContract.sha256(bytes),
            bytes = bytes,
            jpegQuality = 85,
            contrastFactor = FeedImagePipeline.DEFAULT_CONTRAST_FACTOR,
        )
    }

    private class FakeSource(
        private val page: FeedPage,
        private val thread: FeedThread,
    ) : FeedSource {
        var pageFetchCount = 0

        override fun fetchPage(cursor: String?): FeedPage {
            pageFetchCount++
            return page
        }
        override fun fetchThread(post: FeedPost): FeedThread = thread
    }

    private class FailingThreadSource(private val page: FeedPage) : FeedSource {
        override fun fetchPage(cursor: String?): FeedPage = page
        override fun fetchThread(post: FeedPost): FeedThread = error("offline")
    }

    private class PagingSource : FeedSource {
        val cursors = mutableListOf<String?>()

        override fun fetchPage(cursor: String?): FeedPage {
            cursors += cursor
            return if (cursor == null) {
                FeedPage((1..6).map { postNumber -> staticPost("page-$postNumber") }, "next")
            } else {
                FeedPage(listOf(staticPost("page-7")), null)
            }
        }

        private fun staticPost(id: String) = FeedPost(
            id = id,
            authorName = "Author",
            authorHandle = "author",
            text = id,
            createdAt = Instant.EPOCH,
            source = FeedSourceKind.DEMO.tag,
        )
    }

    private class FakeHost(private val supportsImage: Boolean = false) : FeedsRuntimeHost {
        val cards = mutableListOf<Pair<FeedCardContent, Boolean>>()
        val images = mutableListOf<Pair<JSONObject, ByteArray>>()
        val events = mutableListOf<String>()
        val logs = mutableListOf<String>()
        var hidden = false
        val last: FeedCardContent get() = cards.last().first

        override fun sendCard(card: FeedCardContent, show: Boolean) {
            cards += card to show
            events += "card"
        }

        override fun sendImage(payload: JSONObject, bytes: ByteArray) {
            images += payload to bytes
            events += "image"
        }

        override fun supportsImage(): Boolean = supportsImage

        override fun hideSurface() {
            hidden = true
        }

        override fun post(action: () -> Unit) = action()

        override fun log(message: String) {
            logs += message
        }
    }

    private class DeferredImageLoader : FeedImageLoader {
        val requests = mutableListOf<Continuation<FeedImageFrame?>>()

        override suspend fun load(media: FeedMedia): FeedImageFrame? = suspendCoroutine { continuation ->
            requests += continuation
        }

        fun complete(index: Int, frame: FeedImageFrame?) {
            requests[index].resume(frame)
        }
    }
}
