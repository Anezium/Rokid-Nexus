package com.anezium.rokidbus.plugin.feeds

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class PostCardLayoutTest {
    private val now = Instant.parse("2026-07-11T12:00:00Z")

    @Test
    fun shortPost_formatsHeaderBodyAndFooter() {
        val card = PostCardLayout.layout(
            post(text = "Hello HUD", createdAt = Instant.parse("2026-07-11T11:42:00Z")),
            now = now,
            position = 2,
            total = 25,
        )

        assertEquals("Ada Lovelace \u00b7 18m", card.lines[0])
        assertEquals("Hello HUD", card.lines[1])
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

        assertEquals(listOf("first line", "second line", "third line"), card.lines.drop(1))
    }

    @Test
    fun twoHundredEightyCharacterPost_fitsTheDocumentedCardBounds() {
        val text = ("0123456789".repeat(28)).also { assertEquals(280, it.length) }
        val card = PostCardLayout.layout(post(text), now, 0, 1)

        assertFalse(card.truncated)
        assertEquals(1, card.pageCount)
        assertEquals(PostCardLayout.CARD_ROWS, card.lines.size)
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
    fun mediaPost_appendsMarkerBeforeWrapping() {
        val card = PostCardLayout.layout(post("Photo", hasMedia = true), now, 0, 1)

        assertEquals("Photo [+media]", card.lines[1])
    }

    private fun post(
        text: String,
        createdAt: Instant = Instant.parse("2026-07-11T11:00:00Z"),
        hasMedia: Boolean = false,
    ) = FeedPost(
        id = "post",
        authorName = "Ada Lovelace",
        authorHandle = "ada",
        text = text,
        createdAt = createdAt,
        source = "bsky",
        hasMedia = hasMedia,
    )
}
