package com.anezium.rokidbus.plugin.feeds

import android.view.KeyEvent
import com.anezium.rokidbus.shared.plugin.NexusInputEvent
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

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

    private fun runtime(source: FeedSource, host: FakeHost) = FeedsRuntime(
        context = null,
        host = host,
        settings = { settings() },
        sourceFactory = { source },
        ioDispatcher = Dispatchers.Unconfined,
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

    private class FakeHost : FeedsRuntimeHost {
        val cards = mutableListOf<Pair<FeedCardContent, Boolean>>()
        val logs = mutableListOf<String>()
        var hidden = false
        val last: FeedCardContent get() = cards.last().first

        override fun sendCard(card: FeedCardContent, show: Boolean) {
            cards += card to show
        }

        override fun hideSurface() {
            hidden = true
        }

        override fun post(action: () -> Unit) = action()

        override fun log(message: String) {
            logs += message
        }
    }
}
