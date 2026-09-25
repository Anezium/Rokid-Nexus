package com.anezium.rokidbus.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class ActivityPrimaryFitTest {
    // Monospace: every character advances 0.6 em. At density 1 the panel's text
    // column is 250 - 24 padding - 48 glyph - 12 gap - 2 slack = 164 px.
    private val available = 164f
    private val eta = 5 * 0.6f * 13f + 8f

    private fun widthOf(text: String): (Float) -> Float = { sizeSp -> text.length * 0.6f * sizeSp }

    @Test
    fun `a short value keeps the full size next to its eta`() {
        assertEquals(
            ActivityPrimaryFit(sizeSp = 24f, etaBelow = false),
            fitActivityPrimary(available, eta, widthOf("3 stops")),
        )
    }

    @Test
    fun `a value that almost fits shrinks inline before anything moves`() {
        val fit = fitActivityPrimary(available, eta, widthOf("12:41 PM"))
        assertEquals(false, fit.etaBelow)
        assertEquals(24f, fit.sizeSp, 0f)

        val tighter = fitActivityPrimary(available, eta, widthOf("1 h 5 min"))
        assertEquals(false, tighter.etaBelow)
        assertEquals(21f, tighter.sizeSp, 0f)
    }

    @Test
    fun `a full twelve characters moves the eta down and stays large`() {
        assertEquals(
            ActivityPrimaryFit(sizeSp = 22f, etaBelow = true),
            fitActivityPrimary(available, eta, widthOf("Depart 3 min")),
        )
    }

    @Test
    fun `without an eta nothing moves and the value only shrinks as needed`() {
        assertEquals(
            ActivityPrimaryFit(sizeSp = 22f, etaBelow = false),
            fitActivityPrimary(available, null, widthOf("Depart 3 min")),
        )
        assertEquals(
            ActivityPrimaryFit(sizeSp = 24f, etaBelow = false),
            fitActivityPrimary(available, null, widthOf("300 m")),
        )
    }

    @Test
    fun `an impossible fit ends at the floor instead of looping`() {
        assertEquals(
            ActivityPrimaryFit(sizeSp = ACTIVITY_PRIMARY_MIN_SP, etaBelow = true),
            fitActivityPrimary(40f, eta, widthOf("Depart 3 min")),
        )
    }
}
