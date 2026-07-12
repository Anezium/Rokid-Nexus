package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PostCardLayoutTest {
    private val now = Instant.parse("2026-07-11T12:00:00Z")

    @Test
    fun sourceMenu_listsSourcesMarksSelectionAndFitsCard() {
        val sources = listOf(
            FeedSourceKind.BLUESKY,
            FeedSourceKind.X_ACCOUNT,
            FeedSourceKind.X_WEBVIEW,
            FeedSourceKind.X_OFFICIAL,
        )

        val card = PostCardLayout.renderSourceMenu(sources, selectedIndex = 2)

        assertEquals("Feeds", card.title)
        assertEquals("source 3/4 \u00b7 tap", card.footer)
        assertEquals(
            listOf(
                "  Bluesky",
                "  X \u00b7 QuaX",
                "\u203a X \u00b7 WebView",
                "  X \u00b7 official API",
            ),
            card.lines,
        )
        assertTrue(card.lines.size <= PostCardLayout.CARD_ROWS)
        assertTrue(card.lines.all { it.length <= PostCardLayout.LINE_CHARS })
    }

    @Test
    fun shortPost_formatsHeaderBodyAndFooter() {
        val card = PostCardLayout.layout(
            post(text = "Hello HUD", createdAt = Instant.parse("2026-07-11T11:42:00Z")),
            now = now,
            position = 2,
            total = 25,
        )

        assertEquals("Ada Lovelace \u00b7 18m", card.lines[0])
        assertEquals("@ada", card.lines[1])
        assertEquals("Hello HUD", card.lines[2])
        assertEquals("bsky 3/25", card.footer)
        assertFalse(card.truncated)
    }

    @Test
    fun longPost_wrapsToWidthAndTruncatesCompactCard() {
        val card = PostCardLayout.layout(post("word ".repeat(100)), now, 0, 1)

        assertEquals(PostCardLayout.CARD_ROWS, card.lines.size)
        assertTrue(card.lines.all { it.length <= PostCardLayout.LINE_CHARS })
        assertTrue(card.lines.last().endsWith("\u2026"))
        assertTrue(card.truncated)
    }

    @Test
    fun multilinePost_preservesExplicitLineBreaks() {
        val card = PostCardLayout.layout(post("first line\nsecond line\nthird line"), now, 0, 1)

        assertEquals(listOf("first line", "second line", "third line"), card.lines.drop(2))
    }

    @Test
    fun twoHundredEightyCharacterPost_fitsTheExpandedCardBounds() {
        val text = ("0123456789".repeat(28)).also { assertEquals(280, it.length) }
        val card = PostCardLayout.layout(post(text), now, 0, 1)

        assertFalse(card.truncated)
        assertEquals(1, card.pageCount)
        assertEquals(13, card.lines.size)
        assertTrue(card.lines.all { it.length <= PostCardLayout.LINE_CHARS })
    }

    @Test
    fun expandedLongPost_pagesWithoutEllipsis() {
        val expanded = PostCardLayout.layout(post("word ".repeat(100)), now, 0, 1, expanded = true, requestedPage = 1)

        assertTrue(expanded.pageCount > 1)
        assertEquals(1, expanded.pageIndex)
        assertFalse(expanded.lines.last().endsWith("\u2026"))
        assertTrue(expanded.footer.startsWith("bsky 1/1 p2/"))
    }

    @Test
    fun mediaPost_appendsTypedMarkerAndAltBeforeWrapping() {
        val card = PostCardLayout.layout(
            post(
                "Photo",
                media = listOf(
                    media(FeedMediaType.PHOTO, "A cat sleeping on a chair"),
                    media(FeedMediaType.PHOTO),
                ),
            ),
            now,
            0,
            1,
        )

        assertEquals("Photo [2 photos] (A cat", card.lines[2])
        assertEquals("sleeping on a chair)", card.lines[3])
    }

    @Test
    fun header_usesTwoLinesTruncatesAndSkipsBlankHandle() {
        val withHandle = PostCardLayout.layout(
            post("Body").copy(authorName = "A name that is much too long", authorHandle = "a-handle-that-is-also-too-long"),
            now,
            0,
            1,
        )
        val blankHandle = PostCardLayout.layout(post("Body").copy(authorHandle = ""), now, 0, 1)

        assertEquals(27, withHandle.lines[0].length)
        assertEquals("@a-handle-that-is-also-too-", withHandle.lines[1])
        assertEquals("Body", withHandle.lines[2])
        assertEquals(listOf("Ada Lovelace \u00b7 1h", "Body"), blankHandle.lines)
    }

    @Test
    fun typedMarkers_coverGifAndTimedVideo() {
        assertEquals("[GIF]", PostCardLayout.mediaMarker(listOf(media(FeedMediaType.GIF))))
        assertEquals("[video 1:05]", PostCardLayout.mediaMarker(listOf(media(FeedMediaType.VIDEO, durationMs = 65_000L))))
    }

    @Test
    fun galleryPlaceholder_hasPostHeaderDescriptorAltAndFooter() {
        val post = post(
            "Ignored in gallery",
            media = listOf(media(FeedMediaType.PHOTO), media(FeedMediaType.PHOTO, "A second photo")),
        )

        val card = PostCardLayout.renderGalleryItem(post, 1, now)

        assertEquals(listOf("Ada Lovelace \u00b7 1h", "@ada", "Photo 2/2", "A second photo"), card.lines)
        assertEquals("bsky media 2/2", card.footer)
        assertEquals(1, card.pageIndex)
        assertEquals(2, card.pageCount)
    }

    @Test
    fun contentKey_staysWithinSurfaceLimitForMaximalCard() {
        val card = FeedCardContent(
            title = "T".repeat(120),
            lines = List(15) { "x".repeat(27) },
            footer = "f".repeat(27),
            truncated = true,
            pageIndex = 0,
            pageCount = 2,
        )

        assertTrue(card.contentKey().length <= 128)
    }

    private fun post(
        text: String,
        createdAt: Instant = Instant.parse("2026-07-11T11:00:00Z"),
        media: List<FeedMedia> = emptyList(),
    ) = FeedPost(
        id = "post",
        authorName = "Ada Lovelace",
        authorHandle = "ada",
        text = text,
        createdAt = createdAt,
        source = "bsky",
        media = media,
    )

    private fun media(
        type: FeedMediaType,
        altText: String = "",
        durationMs: Long? = null,
    ) = FeedMedia(type, "https://example.invalid/full", "https://example.invalid/preview", altText, durationMs)
}
