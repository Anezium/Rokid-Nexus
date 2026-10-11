package com.anezium.rokidbus.plugin.relay

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSurfaceContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayConversationTest {
    private val mika = RelayMessage(RelayMessageRole.RECEIVED, "on est devant", "Mika")
    private val ours = RelayMessage(RelayMessageRole.OURS, "j'arrive")
    private val missed = RelayMessage(RelayMessageRole.UNKNOWN, "Missed call")

    private fun handoff(serial: Long, text: String, after: List<RelayMessage>, status: RelayHandoffStatus = RelayHandoffStatus.HANDED_OFF) =
        RelayHandoff(serial, text, status, RelayTranscript.messageIds(after).lastOrNull())

    private fun entry(
        messages: List<RelayMessage>,
        handoffs: List<RelayHandoff> = emptyList(),
        live: Boolean = true,
        sender: String = "Mika",
    ) = RelayInboxEntry(
        RelayInboxSnapshot(
            id = "k1",
            sender = sender,
            appLabel = "WhatsApp",
            renderedText = messages.joinToString("\n") { it.text },
            capturedAtMs = 5_000,
            messages = messages,
            handoffs = handoffs,
        ),
        if (live) RelayReplyAvailability.REPLIABLE else RelayReplyAvailability.READ_ONLY,
    )

    private fun page(entry: RelayInboxEntry, conversation: Boolean = true) =
        RelayPages.page(RelayPages.threadPageId("k1"), 3, listOf(entry), false, conversation) { "12:34" } as NexusPage.Content

    @Test
    fun `a thread is a conversation keyed by its thread id, captured once, with each side as captured`() {
        val messages = listOf(mika, ours, missed)
        val page = page(entry(messages, listOf(handoff(0, "On my way", messages))))
        val body = page.body as PageBody.Conversation
        assertEquals("k1", body.threadKey)
        assertEquals("Messages · captured 12:34", body.caption)
        assertEquals(
            listOf(ConversationRole.RECEIVED, ConversationRole.OURS, ConversationRole.UNKNOWN, ConversationRole.OURS),
            body.messages.map { it.role },
        )
        // Newest last: our handoff reads after what it answered, with what is honestly known of it.
        assertEquals("On my way", body.messages.last().text)
        assertEquals(ConversationMessageStatus.HANDED_OFF, body.messages.last().status)
        assertTrue(body.messages.dropLast(1).all { it.status == null })
        // One-to-one, the title names the other person; no message repeats it.
        assertTrue(body.messages.all { it.sender == null })
        val composer = body.composer!!
        assertEquals("Mika", composer.recipient)
        assertEquals(RelayPages.SEND_ACTION_ID, composer.sendActionId)
        assertTrue(composer.maxChars in 1..PageSurfaceContract.MAX_COMPOSE_TEXT_CHARS)
        assertTrue(composer.replyable)
        assertNull(composer.unavailableText)
        // The send is the composer's only: typed compose.send, and no row targets it.
        assertEquals(listOf(RelayPages.SEND_ACTION_ID), page.actions.map { it.id })
        assertEquals(NexusActionType.ComposeSend, page.actions.single().typed)
        assertTrue(body.items.isEmpty())
        assertEquals(body.messages.map { it.id }.distinct().size, body.messages.size)
    }

    @Test
    fun `a thread whose reply authority is gone keeps its text and withholds Send`() {
        val body = page(entry(listOf(mika, ours), live = false)).body as PageBody.Conversation
        assertEquals(listOf("on est devant", "j'arrive"), body.messages.map { it.text })
        val composer = body.composer!!
        assertFalse(composer.replyable)
        assertEquals(RelayPages.UNAVAILABLE_TEXT, composer.unavailableText)
        assertEquals(RelayPages.UNAVAILABLE_SUB, composer.unavailableSub)
    }

    @Test
    fun `in a group each received message names its sender`() {
        val ana = RelayMessage(RelayMessageRole.RECEIVED, "moi aussi", "Ana")
        val body = page(entry(listOf(mika, ana, ours), sender = "Friends")).body as PageBody.Conversation
        assertEquals(listOf("Mika", "Ana", null), body.messages.map { it.sender })
    }

    @Test
    fun `without the launcher bundle a thread stays a document with no action`() {
        val page = page(entry(listOf(mika, ours)), conversation = false)
        assertTrue(page.body is PageBody.Document)
        assertTrue(page.actions.isEmpty())
    }

    @Test
    fun `a handoff stays until a later capture shows the app's own copy after what it answered`() {
        val before = listOf(mika)
        val log = listOf(handoff(0, "On my way", before))
        assertEquals(listOf("on est devant", "On my way"), RelayTranscript.merge(before, log).map { it.text })
        // The app reposts the thread without our reply yet: the handoff still reads.
        assertTrue(RelayTranscript.superseded(before, log).isEmpty())
        // Its own copy, as ours and after the message we answered, replaces Relay's.
        val after = listOf(mika, RelayMessage(RelayMessageRole.OURS, "On  my way"), RelayMessage(RelayMessageRole.RECEIVED, "ok", "Mika"))
        assertEquals(setOf(0L), RelayTranscript.superseded(after, log))
        // The same words from the other side are not our copy.
        val echo = listOf(mika, RelayMessage(RelayMessageRole.RECEIVED, "On my way", "Mika"))
        assertTrue(RelayTranscript.superseded(echo, log).isEmpty())
    }

    @Test
    fun `message ids stay the same when older messages fall off the start`() {
        val first = RelayTranscript.messageIds(listOf(mika, ours, missed))
        val second = RelayTranscript.messageIds(listOf(ours, missed, mika))
        assertEquals(first[1], second[0])
        assertEquals(first[2], second[1])
    }

    @Test
    fun `the handoff log keeps the newest within its count and characters`() {
        val log = (0L until 30L).map { RelayHandoff(it, "reply $it", RelayHandoffStatus.HANDED_OFF, null) }
        val kept = RelayTranscript.bounded(log, RelaySettings.DEFAULT_MESSAGES_PER_THREAD, 10_000)
        assertEquals((10L until 30L).toList(), kept.map { it.serial })
        val long = listOf(
            RelayHandoff(0, "a".repeat(600), RelayHandoffStatus.HANDED_OFF, null),
            RelayHandoff(1, "b".repeat(600), RelayHandoffStatus.UNCONFIRMED, null),
        )
        assertEquals(listOf(1L), RelayTranscript.bounded(long, 20, 1_000).map { it.serial })
        // The newest one always stays, even alone over the budget.
        assertEquals(listOf(1L), RelayTranscript.bounded(long, 20, 10).map { it.serial })
    }
}
