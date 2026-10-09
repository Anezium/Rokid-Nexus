package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class HudMarkdownTest {
    @Test
    fun `bold emphasis around a tool confirmation is removed`() {
        assertEquals(
            "Reminder set for August 8, 2026 at 9:00 AM: call the dentist.",
            stripHudMarkdown(
                "Reminder set for **August 8, 2026 at 9:00 AM**: call the dentist.",
            ),
        )
    }

    @Test
    fun `italics underscores and inline code are removed`() {
        assertEquals("Note saved: Garage door code.", stripHudMarkdown("*Note* __saved__: `Garage door code`."))
    }

    @Test
    fun `bullet markers and arithmetic survive`() {
        assertEquals("- 3 * 4 = 12", stripHudMarkdown("- 3 * 4 = 12"))
        assertEquals("snake_case_name stays", stripHudMarkdown("snake_case_name stays"))
    }

    @Test
    fun `plain text is returned unchanged without allocating`() {
        val text = "Timer set for 1 minute."
        assertSame(text, stripHudMarkdown(text))
    }

    @Test
    fun `unpaired markers are left alone`() {
        assertEquals("2 * 3", stripHudMarkdown("2 * 3"))
        assertEquals("half **open", stripHudMarkdown("half **open"))
    }

    @Test
    fun `chatgpt citation tokens keep a cited file and never reach the HUD`() {
        assertEquals("2 regions exceed North. (sales.pdf)",
            stripCitationMarkup("2 regions exceed North. citesales.pdf"))
        assertEquals("Paris is sunny.", stripCitationMarkup("Paris is sunny. citeturn0search1"))
        assertEquals("Still streaming", stripCitationMarkup("Still streaming citesal"))
        assertEquals("Plain answer.", stripCitationMarkup("Plain answer."))
    }
}
