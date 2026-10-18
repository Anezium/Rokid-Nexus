package com.anezium.rokidbus.phone

import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.RadioButton
import com.anezium.rokidbus.shared.AmbientSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * The ambient choice on the display and glasses-apps pages. Four options across one phone width
 * gave each a quarter of it and wrapped "Automatic" mid-word on the device, so the row now lays
 * out two per line; the buttons then span several lines and keep their exclusivity by hand.
 * Robolectric measures text with a stand-in, so this checks the widths and the selection
 * behaviour, not glyph fitting.
 */
@RunWith(RobolectricTestRunner::class)
class AmbientChoiceRowTest {
    private val context = RuntimeEnvironment.getApplication()
    private val sizes = listOf(
        null to "Automatic",
        AmbientSize.TEXT to "Text",
        AmbientSize.MINIMAP to "Minimap",
        AmbientSize.PANEL to "Panel",
    )

    private fun layout(view: View, widthPx: Int) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        view.layout(0, 0, view.measuredWidth, view.measuredHeight)
    }

    private fun buttons(row: ViewGroup): List<RadioButton> =
        (0 until row.childCount).flatMap { line ->
            val group = row.getChildAt(line) as ViewGroup
            (0 until group.childCount).mapNotNull { group.getChildAt(it) as? RadioButton }
        }

    @Test
    fun `four options take two lines of two, each option half the width`() {
        val row = ambientChoiceRow(context, sizes, selected = null) {}
        layout(row, widthPx = 900)

        assertEquals(2, row.childCount)
        val all = buttons(row)
        assertEquals(listOf("Automatic", "Text", "Minimap", "Panel"), all.map { it.text.toString() })
        all.forEach { assertEquals(it.text.toString(), 450, it.width) }
    }

    @Test
    fun `an odd count keeps the last option at the same width as the others`() {
        val row = ambientChoiceRow(context, sizes.take(3), selected = null) {}
        layout(row, widthPx = 900)

        assertEquals(2, row.childCount)
        buttons(row).forEach { assertEquals(it.text.toString(), 450, it.width) }
    }

    @Test
    fun `only the chosen option is checked and the choice is reported once`() {
        val chosen = mutableListOf<AmbientSize?>()
        val row = ambientChoiceRow(context, sizes, selected = null) { chosen += it }
        val all = buttons(row)
        assertEquals(listOf(true, false, false, false), all.map { it.isChecked })
        assertTrue("the initial state is not a choice", chosen.isEmpty())

        all[3].performClick()
        assertEquals(listOf(false, false, false, true), all.map { it.isChecked })
        assertEquals(listOf(AmbientSize.PANEL), chosen)

        all[3].performClick()
        assertTrue("tapping the chosen option again stays checked", all[3].isChecked)
        assertEquals("and is not reported again", listOf(AmbientSize.PANEL), chosen)

        all[0].performClick()
        assertEquals(listOf(true, false, false, false), all.map { it.isChecked })
        assertEquals(listOf(AmbientSize.PANEL, null), chosen)
        assertFalse(all[3].isChecked)
    }

    @Test
    fun `the row fills its parent width`() {
        val row = ambientChoiceRow(context, sizes, selected = AmbientSize.TEXT) {}
        val params = row.layoutParams as LinearLayout.LayoutParams
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, params.width)
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, params.height)
        assertEquals(listOf(false, true, false, false), buttons(row).map { it.isChecked })
    }
}
