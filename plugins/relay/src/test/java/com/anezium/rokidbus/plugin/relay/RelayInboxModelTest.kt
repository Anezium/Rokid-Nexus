package com.anezium.rokidbus.plugin.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayInboxModelTest {
    @Test
    fun `inbox is newest first and capped to the card contract`() {
        val snapshots = (0 until RelayInboxCatalog.MAX_ENTRIES + 6).map { index ->
            snapshot(id = "thread-$index", capturedAtMs = index.toLong())
        }

        val entries = RelayInboxCatalog.entries(
            snapshots = snapshots,
            liveReplyIds = snapshots.map(RelayInboxSnapshot::id).toSet(),
        )

        assertEquals(RelayInboxCatalog.MAX_ENTRIES, entries.size)
        assertEquals("thread-69", entries.first().id)
        assertEquals("thread-6", entries.last().id)
    }

    @Test
    fun `newest duplicate wins before the requested cap is applied`() {
        val entries = RelayInboxCatalog.entries(
            snapshots = listOf(
                snapshot(id = "same", capturedAtMs = 1L, text = "old"),
                snapshot(id = "other", capturedAtMs = 2L),
                snapshot(id = "same", capturedAtMs = 3L, text = "new"),
            ),
            liveReplyIds = emptySet(),
            limit = 2,
        )

        assertEquals(listOf("same", "other"), entries.map(RelayInboxEntry::id))
        assertEquals("new", entries.first().snapshot.renderedText)
    }

    @Test
    fun `a list row is the sender, marked when it can no longer be answered`() {
        val entry = RelayInboxEntry(
            snapshot = snapshot(
                id = "signal-alice",
                sender = "Alice",
                appLabel = "Signal",
                text = "Alice: older\nAlice: newest message",
            ),
            availability = RelayReplyAvailability.REPLIABLE,
        )

        val line = RelayInboxCatalog.lineFor(entry, selected = true)

        // The row is the name; the HUD draws the selection, not a caret we
        // spell out, and the newest message travels as the row's sub line.
        assertTrue(line.startsWith("Alice"))
        assertEquals("Alice: newest message", RelayInboxCatalog.previewFor(entry))
        assertFalse(line.endsWith(" ·"))
        assertTrue(line.length <= RelayInboxCatalog.LIST_LINE_CHARS)
    }

    @Test
    fun `a hidden preview names the message and keeps no thread text`() {
        val entry = RelayInboxEntry(
            snapshot = snapshot(
                id = "signal-alice",
                sender = "Alice",
                appLabel = "Signal",
                text = "Alice: older\nAlice: newest message",
            ),
            availability = RelayReplyAvailability.REPLIABLE,
        )

        // Hidden, the row shows the placeholder and not the conversation.
        assertEquals(RelayPrivacy.HIDDEN_BODY, RelayInboxCatalog.previewFor(entry, hidden = true))
        assertFalse(RelayInboxCatalog.previewFor(entry, hidden = true).contains("newest message"))

        // Not hidden, it behaves as before.
        assertEquals("Alice: newest message", RelayInboxCatalog.previewFor(entry, hidden = false))
    }

    @Test
    fun `dead thread row remains readable and is labelled read only`() {
        val entry = RelayInboxEntry(
            snapshot = snapshot(id = "dead", sender = "Bob", appLabel = "Bob", text = "Still readable"),
            availability = RelayReplyAvailability.READ_ONLY,
        )

        val line = RelayInboxCatalog.lineFor(entry, selected = false)

        // A thread that can no longer be answered is marked, not hidden.
        assertTrue(line.startsWith("Bob"))
        assertTrue(line.endsWith(" ·"))
    }

    @Test
    fun `live ids classify otherwise identical snapshots as repliable or read only`() {
        val entries = RelayInboxCatalog.entries(
            snapshots = listOf(snapshot("live"), snapshot("dead")),
            liveReplyIds = setOf("live"),
        ).associateBy(RelayInboxEntry::id)

        assertEquals(RelayReplyAvailability.REPLIABLE, entries.getValue("live").availability)
        assertEquals(RelayReplyAvailability.READ_ONLY, entries.getValue("dead").availability)
    }

    @Test
    fun `thread card lines preserve all text within wire field caps`() {
        val longMessage = "x".repeat(RelayInboxCatalog.MAX_CARD_LINE_CHARS * 2 + 7)

        val lines = RelayInboxCatalog.cardLines("first\n$longMessage\nlast")

        assertEquals("first", lines.first())
        assertEquals("last", lines.last())
        assertEquals(longMessage, lines.drop(1).dropLast(1).joinToString(""))
        assertTrue(lines.all { it.length <= RelayInboxCatalog.MAX_CARD_LINE_CHARS })
    }

    @Test
    fun `selection moves wraps opens and backs through the two views`() {
        val selection = RelayInboxSelection()
        selection.reset(listOf("a", "b", "c"))

        assertEquals(0, selection.selectedIndex)
        assertTrue(selection.move(-1))
        assertEquals(2, selection.selectedIndex)
        assertTrue(selection.move(1))
        assertEquals(0, selection.selectedIndex)

        assertEquals("a", selection.openSelected())
        assertEquals(RelayInboxView.THREAD, selection.view)
        assertFalse(selection.move(1))
        assertEquals(RelayInboxBackResult.SHOW_LIST, selection.back())
        assertEquals(RelayInboxView.LIST, selection.view)
        assertNull(selection.openedThreadId)
        assertEquals(RelayInboxBackResult.CLOSE_SURFACE, selection.back())
    }

    @Test
    fun `live capture refreshes an opened thread only while it is being read`() {
        val selection = RelayInboxSelection()
        selection.reset(listOf("thread"))

        assertEquals(
            RelayInboxRefreshTarget.LIST,
            selection.liveRefreshTarget(
                changedItemId = "other",
                canRefreshOpenedThread = false,
            ),
        )

        selection.openSelected()

        assertEquals(
            RelayInboxRefreshTarget.THREAD,
            selection.liveRefreshTarget(
                changedItemId = "thread",
                canRefreshOpenedThread = true,
            ),
        )
        assertEquals(
            RelayInboxRefreshTarget.NONE,
            selection.liveRefreshTarget(
                changedItemId = "thread",
                canRefreshOpenedThread = false,
            ),
        )
        assertEquals(
            RelayInboxRefreshTarget.NONE,
            selection.liveRefreshTarget(
                changedItemId = "other",
                canRefreshOpenedThread = true,
            ),
        )
    }

    private fun snapshot(
        id: String,
        sender: String = "Sender",
        appLabel: String = "App",
        text: String = "message",
        capturedAtMs: Long = 0L,
    ) = RelayInboxSnapshot(
        id = id,
        sender = sender,
        appLabel = appLabel,
        renderedText = text,
        capturedAtMs = capturedAtMs,
    )
}
