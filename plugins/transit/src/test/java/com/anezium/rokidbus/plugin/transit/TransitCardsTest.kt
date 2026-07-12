package com.anezium.rokidbus.plugin.transit

import com.anezium.rokidbus.client.plugin.NexusCard
import com.anezium.rokidbus.client.plugin.NexusCardLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TransitCardsTest {
    @Test
    fun `content key of a dense departure board stays a valid NexusCard key`() {
        // Regression: real boards used to concatenate their full content into the
        // contentKey, blowing the SDK's 128-char cap and crashing the plugin.
        val card = TransitCardContent(
            title = "Gare de Lyon Part-Dieu Vivier-Merle",
            lines = (1..6).map { index ->
                CardLine(
                    text = "Charpennes Charles Hernu via Gare Part-Dieu $index",
                    badge = "T$index",
                    trail = listOf("2min", "8min", "14min"),
                )
            },
            footer = "swipe stop . tap page . stale 15:48",
        )
        val key = card.contentKey()
        assertTrue(key.length <= 128)
        // Must construct without throwing, exactly like TransitPluginService.sendCard.
        NexusCard(
            title = card.title,
            lines = emptyList(),
            footer = card.footer,
            contentKey = key,
            richLines = card.lines.map { line ->
                NexusCardLine(
                    text = line.text,
                    badge = line.badge.takeIf(String::isNotBlank),
                    trail = line.trail,
                )
            },
            handlesBack = true,
        )
        // Different content still yields a different key (dedupe stays correct).
        assertTrue(key != card.copy(footer = "swipe stop . tap page").contentKey())
    }

    @Test
    fun chooserCardMarksSelectedMode() {
        val nearMe = TransitCards.chooser(TransitMode.NEAR_ME)
        assertEquals("Transit", nearMe.title)
        assertEquals(listOf(CardLine("> Near Me"), CardLine("  Favorites")), nearMe.lines)
        assertEquals("swipe . tap opens", nearMe.footer)

        val favorites = TransitCards.chooser(TransitMode.FAVORITES)
        assertEquals(listOf(CardLine("  Near Me"), CardLine("> Favorites")), favorites.lines)
    }
}
