package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextExtractorTest {
    @Test
    fun `messaging style keeps the newest messages in timestamp order`() {
        val input = NotificationTextInput(
            messages = listOf(
                message("one", "A", 100L),
                message("two", "B", 500L),
                message("three", "C", 200L),
                message("four", "D", 400L),
                message("five", "E", 300L),
                message("six", "F", 600L),
            ),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "E: five\nD: four\nB: two\nF: six",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `genuinely richer expanded lines beat messaging style`() {
        val input = NotificationTextInput(
            messages = listOf(message("Brief")),
            expandedLines = listOf("Brief with enough additional detail to win"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "Brief with enough additional detail to win",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `expanded lines do not replace a richer messaging style body`() {
        val input = NotificationTextInput(
            messages = listOf(message("Primary message")),
            expandedLines = listOf("Shorter"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "Primary message",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `expanded lines are used when messaging style is empty`() {
        val input = NotificationTextInput(
            expandedLines = listOf("first line", null, "second line"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "first line\nsecond line",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `big text is used after messages and expanded lines`() {
        val input = NotificationTextInput(
            messages = listOf(message("   ")),
            expandedLines = listOf("   ", null),
            bigText = "big text",
            text = "plain fallback",
        )

        assertEquals("big text", NotificationTextExtractor.extract(input, messageLimit = 4))
    }

    @Test
    fun `plain text is the final nonempty fallback`() {
        val input = NotificationTextInput(bigText = "   ", text = "plain text")

        assertEquals("plain text", NotificationTextExtractor.extract(input, messageLimit = 4))
    }

    @Test
    fun `empty text input produces an empty body`() {
        assertEquals(
            "",
            NotificationTextExtractor.extract(NotificationTextInput(), messageLimit = 4),
        )
    }

    @Test
    fun `a collapsed message summary always loses to expanded lines`() {
        assertTrue(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = "3 new messages",
                lines = "A\nB",
            ),
        )
    }

    @Test
    fun `expanded detail must exceed the normalized margin`() {
        val primary = "a\nb"
        val exactlyTwentyFourMore = "a\n${"x".repeat(25)}"
        val twentyFiveMore = "a\n${"x".repeat(26)}"

        assertFalse(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = primary,
                lines = exactlyTwentyFourMore,
            ),
        )
        assertTrue(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = primary,
                lines = twentyFiveMore,
            ),
        )
    }

    @Test
    fun `top trimming leaves text within the cap and preserves the newest end`() {
        assertEquals("short", NotificationTextExtractor.trimFromTop("short", maxChars = 8))
        assertEquals("def", NotificationTextExtractor.trimFromTop("abcdef", maxChars = 3))

        val trimmed = NotificationTextExtractor.trimFromTop(
            "oldest line\nmiddle line\nnewest line",
            maxChars = 11,
        )

        assertEquals("newest line", trimmed)
        assertTrue(trimmed.length <= 11)
    }

    @Test
    fun `top trimming handles zero cap and empty input`() {
        assertEquals("", NotificationTextExtractor.trimFromTop("text", maxChars = 0))
        assertEquals("", NotificationTextExtractor.trimFromTop("", maxChars = 8))
    }

    @Test
    fun `messaging style messages say who wrote them while the rendered string stays as it was`() {
        val input = NotificationTextInput(
            messages = listOf(
                message("on est devant", "Mika", 100L),
                ExtractedMessage(text = "j'arrive", sender = null, timestamp = 200L, fromSelf = true),
                ExtractedMessage(text = "moi", sender = "Me", timestamp = 300L, fromSelf = true),
            ),
        )
        val thread = NotificationTextExtractor.extractThread(input, messageLimit = 20)
        assertEquals("Mika: on est devant\nj'arrive\nMe: moi", thread.rendered)
        assertEquals(NotificationTextExtractor.extract(input, messageLimit = 20), thread.rendered)
        assertEquals(
            listOf(
                RelayMessage(RelayMessageRole.RECEIVED, "on est devant", "Mika"),
                RelayMessage(RelayMessageRole.OURS, "j'arrive"),
                RelayMessage(RelayMessageRole.OURS, "moi"),
            ),
            thread.messages,
        )
    }

    @Test
    fun `remote input history adds our replies the thread lacks, oldest first, and only to the messages`() {
        val input = NotificationTextInput(
            messages = listOf(
                message("on est devant", "Mika", 100L),
                ExtractedMessage(text = "deja vu", sender = null, timestamp = 200L, fromSelf = true),
            ),
            // Newest first, as Android keeps it.
            remoteInputHistory = listOf("second", "first", "deja vu", null, " "),
        )
        val thread = NotificationTextExtractor.extractThread(input, messageLimit = 20)
        assertEquals(
            listOf("on est devant", "deja vu", "first", "second"),
            thread.messages.map { it.text },
        )
        assertTrue(thread.messages.drop(1).all { it.role == RelayMessageRole.OURS })
        assertFalse("first" in thread.rendered)
    }

    @Test
    fun `plain text and expanded lines name no author`() {
        val plain = NotificationTextExtractor.extractThread(NotificationTextInput(text = "hello"), messageLimit = 20)
        assertEquals(listOf(RelayMessage(RelayMessageRole.UNKNOWN, "hello")), plain.messages)
        val lines = NotificationTextExtractor.extractThread(
            NotificationTextInput(expandedLines = listOf("Mika: one", "Ana: two")),
            messageLimit = 20,
        )
        assertEquals(listOf(RelayMessageRole.UNKNOWN, RelayMessageRole.UNKNOWN), lines.messages.map { it.role })
        assertEquals(listOf("Mika: one", "Ana: two"), lines.messages.map { it.text })
    }

    @Test
    fun `the messages keep the newest within the thread's count`() {
        val input = NotificationTextInput(
            messages = (1..10).map { message("m$it", "Mika", it.toLong()) },
            remoteInputHistory = listOf("r2", "r1"),
        )
        val thread = NotificationTextExtractor.extractThread(input, messageLimit = 4)
        assertEquals(listOf("m9", "m10", "r1", "r2"), thread.messages.map { it.text })
    }

    private fun message(text: String?, sender: String? = null, timestamp: Long = 0L) =
        ExtractedMessage(text = text, sender = sender, timestamp = timestamp)
}
