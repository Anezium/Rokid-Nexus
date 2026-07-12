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
    fun feedThreadGalleryBackStack_restoresExactFeedPositionAndClosesOnlyAtRoot() {
        val feedPosts = listOf(post("feed-1", "First"), focalPost(), post("feed-3", "Third"))
        val threadPosts = listOf(post("root", "Root"), feedPosts[1], post("reply", "Reply"))
        val source = FakeSource(FeedPage(feedPosts, null), FeedThread(threadPosts, 1))
        val host = FakeHost()
        val runtime = runtime(source, host)

        runtime.open()
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
        assertTrue(host.hidden)
    }

    @Test
    fun threadSwipes_pageCurrentPostBeforeAdvancingAndMoveBackwardToPageZero() {
        val focal = focalPost()
        val reply = post("reply", "Reply")
        val source = FakeSource(FeedPage(listOf(focal), null), FeedThread(listOf(focal, reply), 0))
        val host = FakeHost()
        val runtime = runtime(source, host)
        runtime.open()
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
        runtime.open()

        runtime.key(KeyEvent.KEYCODE_ENTER)

        assertTrue(host.last.lines.joinToString(" ").startsWith("Thread fetch failed."))
        assertTrue(host.logs.single().startsWith("thread fetch failed source=demo"))
        runtime.key(KeyEvent.KEYCODE_BACK)
        assertEquals("demo 1/1", host.last.footer)
        assertFalse(host.hidden)
    }

    @Test
    fun feedPrefetchStillRunsNearEnd() {
        val source = PagingSource()
        val host = FakeHost()
        val runtime = runtime(source, host)
        runtime.open()

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
    ) = FeedsRuntime(
        context = null,
        host = host,
        settings = { settings() },
        sourceFactory = { source },
        ioDispatcher = Dispatchers.Unconfined,
        imageLoader = imageLoader,
        now = { now },
    )

    private fun settings() = FeedsSettings(
        source = FeedSourceKind.DEMO,
        xAccountCookies = XAccountCookies(),
        xBearerToken = "",
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
        open()
        key(KeyEvent.KEYCODE_ENTER)
        key(KeyEvent.KEYCODE_DPAD_CENTER)
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
        override fun fetchPage(cursor: String?): FeedPage = page
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
