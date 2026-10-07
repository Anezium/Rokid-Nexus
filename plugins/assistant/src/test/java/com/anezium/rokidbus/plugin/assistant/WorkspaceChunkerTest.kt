package com.anezium.rokidbus.plugin.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceChunkerTest {
    @Test
    fun `short paragraphs merge but heading boundaries keep their own ancestry`() {
        val chunks = WorkspaceChunker.chunk("# Contract\r\n\r\nFirst paragraph.\r\n\r\nSecond paragraph.\r\n" +
            "## Notice\r\nTwo months.\r\n## Leave\r\nTwenty days.", markdown = true)
        assertEquals(listOf("Contract", "Contract › Notice", "Contract › Leave"), chunks.map { it.headingPath })
        assertEquals("First paragraph.\n\nSecond paragraph.", chunks.first().text)
        assertEquals(listOf(0, 1, 2), chunks.map { it.ordinal })
        assertEquals(listOf(0, 2, 3), chunks.map { it.paragraph })
    }

    @Test
    fun `Setext headings work while fenced content does not create headings`() {
        val chunks = WorkspaceChunker.chunk("Contract\n========\nBody.\n\n```md\n# Fake\n```\n\n" +
            "Notice\n------\nTwo months.", markdown = true)
        assertEquals(listOf("Contract", "Contract › Notice"), chunks.map { it.headingPath })
        assertTrue(chunks.first().text.contains("# Fake"))
    }

    @Test
    fun `long paragraphs split at words without losing their order`() {
        val text = (0..600).joinToString(" ") { "word$it" }
        val chunks = WorkspaceChunker.chunk(text)
        assertTrue(chunks.size > 4)
        assertTrue(chunks.all { it.text.length <= 800 })
        assertEquals(text, chunks.joinToString(" ") { it.text.replace("\n\n", " ") })
        assertTrue(chunks.all { it.headingPath.isEmpty() })
    }

    @Test
    fun `empty paragraphs and overlong tokens never produce unbounded chunks`() {
        assertEquals(emptyList<WorkspaceChunk>(), WorkspaceChunker.chunk("\n\r\n  \n"))
        val chunks = WorkspaceChunker.chunk("x".repeat(900) + " short remaining words")
        assertTrue(chunks.all { it.text.length <= 800 })
        assertFalse(chunks.joinToString { it.text }.contains("x"))
        assertEquals("short remaining words", chunks.single().text)
    }
}
