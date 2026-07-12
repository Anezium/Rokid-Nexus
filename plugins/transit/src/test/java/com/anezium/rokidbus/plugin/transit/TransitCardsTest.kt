package com.anezium.rokidbus.plugin.transit

import org.junit.Assert.assertEquals
import org.junit.Test

class TransitCardsTest {
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
