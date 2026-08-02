package com.anezium.rokidbus.plugin.t3code

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class T3BoardTest {
    @Test
    fun `snapshot filters archives sorts newest first and assigns row tones`() {
        val snapshot = fixture() as T3ShellEvent.Snapshot

        val board = T3Board.build(snapshot.threads, Instant.parse("2026-07-29T12:00:00Z"))

        assertEquals(3, board.totalThreads)
        assertEquals(listOf("thread-recent", "thread-alert", "thread-old"), board.rows.map(T3BoardRow::threadId))
        assertEquals(T3BoardTone.NORMAL, board.rows[0].tone)
        assertEquals(T3BoardTone.ALERT, board.rows[1].tone)
        assertEquals(T3BoardTone.DIM, board.rows[2].tone)
        assertEquals("CX", board.rows[0].badge)
        assertEquals("gpt-5.6-codex · 2m", board.rows[0].sub)
        assertFalse(board.rows.any { it.threadId == "thread-archived" })
    }

    @Test
    fun `board exposes total active count but caps rendered threads at forty`() {
        val snapshot = fixture() as T3ShellEvent.Snapshot
        val template = snapshot.threads.first { it.id == "thread-recent" }
        val many = (0 until 45).map { index ->
            template.copy(id = "extra-$index", title = "Extra $index", updatedAt = "2026-07-29T11:00:00Z")
        }

        val board = T3Board.build(snapshot.threads + many, Instant.parse("2026-07-29T12:00:00Z"))

        assertEquals(48, board.totalThreads)
        assertEquals(40, board.rows.size)
        assertTrue(board.rows.all { it.text.length <= 80 })
    }

    private fun fixture(): T3ShellEvent {
        val text = javaClass.getResource("/shell_snapshot.json")!!.readText()
        return T3Parsers.shellItem(JSONObject(text))!!
    }
}
