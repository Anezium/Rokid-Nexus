package com.anezium.rokidbus.plugin.line

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.shared.PageBody
import org.junit.Assert.*
import org.junit.Test

class LinePagesTest {
    private val chat = LineChat("u0123456789abcdef0123456789abcdef", "Alice", false, emptyList())

    @Test fun pageIdsDoNotExposeConversationIdentity() {
        val id = LinePages.pageId(chat)
        assertFalse(id.contains(chat.id))
        assertFalse(id.contains(chat.title))
        assertEquals(id, LinePages.pageId(chat.copy(title = "Renamed")))
        assertNotEquals(id, LinePages.pageId(chat.copy(id = "different")))
    }

    @Test fun disconnectedConversationKeepsDraftAuthorityDisabled() {
        val state = LineSnapshot("offline", listOf(chat))
        val page = LinePages.render(LinePages.pageId(chat), 1, state, true)
        val composer = (page.body as PageBody.Conversation).composer!!
        assertFalse(composer.replyable)
        assertNotNull(composer.unavailableText)
    }

    @Test fun readyConversationSendsOnlyThroughTheHubComposer() {
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(chat)), true)
        val composer = (page.body as PageBody.Conversation).composer!!
        assertTrue(composer.replyable)
        assertEquals("Alice", composer.recipient)
        assertEquals(LinePages.SEND, composer.sendActionId)
        assertEquals(NexusActionType.ComposeSend, page.actions.single().typed)
    }

    @Test fun oldHubsHaveNoSendingActions() {
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(chat)), false)
        assertTrue(page.body is PageBody.Summary)
        assertTrue(page.actions.isEmpty())
    }

    @Test fun revokedAccountCannotRenderCachedConversations() {
        val page = LinePages.render(LinePages.pageId(chat), 2, LineSnapshot("signed_out"), true)
        assertTrue(page.body is PageBody.Summary)
        assertTrue(page.actions.isEmpty())
    }

    @Test fun escapedHistoryStaysWithinTheWireBudget() {
        val loaded = chat.copy(history = (0..63).map { LineMessage("message-$it", "\u0001".repeat(2000), false) })
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(loaded)), true)
        val messages = (page.body as PageBody.Conversation).messages
        assertTrue(messages.size in 1..4)
        assertEquals("message-63", messages.last().id)
        assertTrue(messages.sumOf { it.text.length * 6 + 800 } <= 48_000)
    }

    @Test fun expiredQueuedSendCanNeverStartLater() {
        val dispatch = LineDispatch()
        assertEquals("not_sent", dispatch.result())
        assertFalse(dispatch.start())
    }

    @Test fun startedSendWithoutAcknowledgementStaysUnconfirmed() {
        val dispatch = LineDispatch()
        assertTrue(dispatch.start())
        assertFalse(dispatch.start())
        assertEquals("unconfirmed", dispatch.result())
        dispatch.finish("sent")
        assertEquals("sent", dispatch.result())
    }

    @Test fun credentialsAreConsumedOnceAndCanBeDiscarded() {
        val credentials = LineCredentials("test@example.invalid", "synthetic-password")
        LineSignInRequest.put(credentials)
        assertSame(credentials, LineSignInRequest.take())
        assertNull(LineSignInRequest.take())
        LineSignInRequest.put(credentials)
        LineSignInRequest.clear()
        assertNull(LineSignInRequest.take())
    }
}
