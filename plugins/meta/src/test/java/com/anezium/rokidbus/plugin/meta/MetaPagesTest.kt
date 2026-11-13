package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.*
import org.junit.Test

class MetaPagesTest {
    private val alice = MetaChat("chat-1", "Alice", group = false, supported = true)
    private val team = MetaChat("chat-2", "Team", group = true, supported = true)
    private val locked = MetaChat("chat-3", "Locked", group = false, supported = false)

    private fun content(page: NexusPage) = page as NexusPage.Content

    @Test fun mainListsEveryServiceWithItsStateAndOnlySignedInOnesOpen() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Connecting)
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice, team)))
        val page = content(MetaPages.page(MetaPages.MAIN, 1, state, interactive = true))
        val rows = (page.body as PageBody.SelectableList).items
        assertEquals(listOf("WhatsApp", "Instagram", "Messenger"), rows.map { it.label })
        assertEquals(MetaPages.SUB_CONNECTING, rows[0].sub)
        assertEquals("2 conversations", rows[1].sub)
        assertEquals(MetaPages.SUB_SIGNED_OUT, rows[2].sub)
        assertFalse(rows[2].enabled)
        assertNull(rows[2].target)
        assertEquals(PageTarget.Page(MetaPages.servicePageId(MessagingService.INSTAGRAM)), rows[1].target)
        assertTrue(page.live)
    }

    @Test fun mainDrawsAServiceMarkOnEachRowOnlyWhereTheHubsDrawRowIcons() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Ready(listOf(alice)))
        val mark = MonoMark.pack(ServiceMarks.EDGE, ServiceMarks.EDGE) { x, y -> x == y }
        val withIcons = content(MetaPages.page(MetaPages.MAIN, 1, state, PageSupport(interactive = true, rowIcon = { mark })))
        assertTrue((withIcons.body as PageBody.SelectableList).items.all { it.icon == mark })
        val plain = content(MetaPages.page(MetaPages.MAIN, 1, state, PageSupport(interactive = true)))
        assertTrue((plain.body as PageBody.SelectableList).items.all { it.icon == null })
        // A signed-out service keeps its mark too: the row is listed, only inert.
        assertFalse((withIcons.body as PageBody.SelectableList).items[2].enabled)
    }

    @Test fun chatPageIdsNameTheServiceAndADigestOfTheConversation() {
        val state = MetaPagesState()
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice)))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, alice)
        assertFalse("page ids never carry the chat id", pageId.contains(alice.id))
        assertEquals(MessagingService.INSTAGRAM to alice, state.chatFor(pageId))
        assertEquals(MessagingService.INSTAGRAM to MetaPages.chatKey(MessagingService.INSTAGRAM, alice), MetaPages.chatKeyOf(pageId))
        assertNull(MetaPages.chatKeyOf("meta-messages:photo:instagram:0000"))
        assertTrue(MetaPages.photoOffered(MessagingService.WHATSAPP, team))
        assertTrue(MetaPages.photoOffered(MessagingService.MESSENGER, team))
        assertFalse(MetaPages.photoOffered(MessagingService.MESSENGER, locked))
    }

    @Test fun servicePageDimsEncryptedChatsAndOpensTheOthers() {
        val state = MetaPagesState()
        state.set(MessagingService.MESSENGER, ServiceState.Ready(listOf(alice, team, locked)))
        val pageId = MetaPages.servicePageId(MessagingService.MESSENGER)
        val page = content(MetaPages.page(pageId, 2, state, interactive = true))
        val rows = (page.body as PageBody.SelectableList).items
        assertEquals("Messenger", page.title)
        assertEquals(3, rows.size)
        assertNull(rows[0].sub)
        assertEquals(MetaPages.SUB_GROUP, rows[1].sub)
        assertEquals(MetaPages.SUB_ENCRYPTED, rows[2].sub)
        assertFalse(rows[2].enabled)
        assertNull(rows[2].target)
        val target = rows[0].target as PageTarget.Page
        assertEquals(MetaPages.chatPageId(MessagingService.MESSENGER, alice), target.pageId)
        assertFalse("page ids never carry the chat id", target.pageId.contains(alice.id))
        assertEquals(3, rows.map { it.id }.toSet().size)
    }

    @Test fun servicePageWhileConnectingThenAfterFailureOffersTryAgain() {
        val state = MetaPagesState()
        val pageId = MetaPages.servicePageId(MessagingService.WHATSAPP)
        state.set(MessagingService.WHATSAPP, ServiceState.Connecting)
        val connecting = content(MetaPages.page(pageId, 1, state, interactive = true))
        assertEquals(MetaPages.EMPTY_CONNECTING, (connecting.body as PageBody.SelectableList).emptyText)
        assertTrue(connecting.actions.isEmpty())

        state.set(MessagingService.WHATSAPP, ServiceState.Failed)
        val failed = content(MetaPages.page(pageId, 2, state, interactive = true))
        val row = (failed.body as PageBody.SelectableList).items.single()
        assertEquals(MetaPages.RETRY_LABEL, row.label)
        assertEquals(PageTarget.Action(MetaPages.RETRY_ACTION_ID), row.target)
        assertEquals(MetaPages.RETRY_ACTION_ID, failed.actions.single().id)
    }

    @Test fun chatPageCarriesTheComposerBoundToTheRecipientAndOnlyTheSendAction() {
        val state = MetaPagesState()
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice)))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, alice)
        val page = content(MetaPages.page(pageId, 3, state, interactive = true))
        val body = page.body as PageBody.Conversation
        assertEquals("Alice", page.title)
        assertTrue(body.messages.isEmpty())
        val composer = checkNotNull(body.composer)
        assertEquals("Alice", composer.recipient)
        assertEquals(MetaPages.SEND_ACTION_ID, composer.sendActionId)
        assertTrue(composer.replyable)
        assertNull(composer.unavailableText)
        assertEquals(1, page.actions.size)
        assertEquals(NexusActionType.ComposeSend, page.actions.single().typed)
        assertTrue(body.items.isEmpty())
        assertEquals(MessagingService.INSTAGRAM to alice, state.chatFor(pageId))
    }

    @Test fun chatPageWithholdsSendWhileDisconnectedOrEncrypted() {
        val state = MetaPagesState()
        state.set(MessagingService.MESSENGER, ServiceState.Ready(listOf(locked)))
        val encrypted = content(MetaPages.page(MetaPages.chatPageId(MessagingService.MESSENGER, locked), 1, state, interactive = true))
        val composer = checkNotNull((encrypted.body as PageBody.Conversation).composer)
        assertFalse(composer.replyable)
        assertEquals(MetaPages.UNAVAILABLE_ENCRYPTED, composer.unavailableText)
        assertEquals(MetaPages.UNAVAILABLE_SUB, composer.unavailableSub)

        state.set(MessagingService.MESSENGER, ServiceState.Connecting)
        val connecting = content(MetaPages.page(MetaPages.chatPageId(MessagingService.MESSENGER, locked), 2, state, interactive = true))
        val reconnecting = checkNotNull((connecting.body as PageBody.Conversation).composer)
        assertFalse(reconnecting.replyable)
        assertEquals(MetaPages.UNAVAILABLE_DISCONNECTED, reconnecting.unavailableText)
        assertNull(state.chatFor(MetaPages.chatPageId(MessagingService.MESSENGER, locked)))
    }

    @Test fun chatPageShowsThisSessionsSentMessagesAsOurs() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Ready(listOf(alice)))
        val pageId = MetaPages.chatPageId(MessagingService.WHATSAPP, alice)
        state.recordSent(pageId, SentMessage("m1", "hello", ConversationMessageStatus.HANDED_OFF))
        state.recordSent(pageId, SentMessage("m2", "again", ConversationMessageStatus.UNCONFIRMED))
        val body = content(MetaPages.page(pageId, 5, state, interactive = true)).body as PageBody.Conversation
        assertEquals(listOf("hello", "again"), body.messages.map { it.text })
        assertTrue(body.messages.all { it.role == ConversationRole.OURS })
        assertEquals(ConversationMessageStatus.HANDED_OFF, body.messages[0].status)
        state.forgetSent(pageId, "m2")
        assertEquals(listOf("m1"), state.sentOn(pageId).map { it.id })
        state.clear()
        assertTrue(state.sentOn(pageId).isEmpty())
        assertEquals(ServiceState.SignedOut, state.state(MessagingService.WHATSAPP))
    }

    @Test fun chatPageShowsLoadedHistoryBeforeThisSessionsSends() {
        val state = MetaPagesState()
        val withHistory = alice.copy(history = listOf(HistoryMessage("h1", mine = false, text = "hi"), HistoryMessage("h2", mine = true, text = "hey")))
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(withHistory)))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, withHistory)
        state.recordSent(pageId, SentMessage("m1", "dictated", ConversationMessageStatus.HANDED_OFF))
        val body = content(MetaPages.page(pageId, 3, state, interactive = true)).body as PageBody.Conversation
        assertEquals(listOf("hi", "hey", "dictated"), body.messages.map { it.text })
        assertEquals(listOf(ConversationRole.RECEIVED, ConversationRole.OURS, ConversationRole.OURS), body.messages.map { it.role })
        assertNull(body.caption)
    }

    @Test fun chatPageFallsBackToADocumentWithoutTheLauncherBundle() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Ready(listOf(alice)))
        val page = content(MetaPages.page(MetaPages.chatPageId(MessagingService.WHATSAPP, alice), 1, state, interactive = false))
        assertTrue(page.body is PageBody.Document)
        assertTrue(page.actions.isEmpty())
    }

    @Test fun chatPageOfAConversationNoLongerListedSaysSo() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Ready(listOf(team)))
        val page = content(MetaPages.page(MetaPages.chatPageId(MessagingService.WHATSAPP, alice), 1, state, interactive = true))
        val body = page.body as PageBody.Document
        assertEquals(MetaPages.MISSING_TEXT, body.segments.single().text)
        assertEquals(NexusPage.Unavailable, MetaPages.page("meta-messages:other", 1, state, interactive = true))
        assertEquals(NexusPage.Unavailable, MetaPages.page(MetaPages.SERVICE_PREFIX + "nope", 1, state, interactive = true))
    }

    @Test fun revisionsOnlyGoUpAndStayAboveTheRetainedFloor() {
        val state = MetaPagesState()
        assertEquals(1, state.nextRevision(null))
        assertEquals(11, state.nextRevision(10))
        assertEquals(12, state.nextRevision(3))
        assertEquals(13, state.nextRevision(null))
    }

    @Test fun sendOutcomesFollowTheEnginesStateAndReason() {
        assertEquals(SendOutcome.HandedOff, sendOutcomeOf("acked", null))
        assertEquals(SendOutcome.RecipientChanged, sendOutcomeOf("not_sent", "recipient_changed"))
        assertEquals(SendOutcome.AuthorityLost, sendOutcomeOf("not_sent", "encrypted_chat_unsupported"))
        assertEquals(SendOutcome.NotSent("disconnected"), sendOutcomeOf("not_sent", "not_connected"))
        assertEquals(SendOutcome.NotSent(""), sendOutcomeOf("not_found", null))
        assertEquals(SendOutcome.NotSent("invalid_image"), sendOutcomeOf("not_sent", "invalid_image"))
        assertEquals(SendOutcome.NotSent("upload_failed"), sendOutcomeOf("not_sent", "upload_failed"))
        assertEquals(SendOutcome.Unconfirmed, sendOutcomeOf("unknown", null))
        assertEquals(SendOutcome.Unconfirmed, sendOutcomeOf(null, null))
    }

    @Test fun outcomeTextsAreTheComposersAndNameTheService() {
        val service = MessagingService.INSTAGRAM
        assertEquals("Sent", sendOutcomeText(SendOutcome.HandedOff, service))
        assertEquals("Unconfirmed · check Instagram", sendOutcomeText(SendOutcome.Unconfirmed, service))
        assertEquals("Not sent · Instagram disconnected", sendOutcomeText(SendOutcome.NotSent("disconnected"), service))
        assertEquals("Not sent · Instagram is busy, try again", sendOutcomeText(SendOutcome.NotSent("busy"), service))
        assertEquals("Not sent · Instagram refused the photo", sendOutcomeText(SendOutcome.NotSent("invalid_image"), service))
        assertEquals("Not sent · upload failed, try again", sendOutcomeText(SendOutcome.NotSent("upload_failed"), service))
        assertEquals("Not sent", sendOutcomeText(SendOutcome.NotSent(""), service))
        assertEquals("That conversation changed. Pick it again", sendOutcomeText(SendOutcome.RecipientChanged, service))
        assertEquals("Not sent · ${MetaPages.UNAVAILABLE_SUB}", sendOutcomeText(SendOutcome.AuthorityLost, service))
    }
}
