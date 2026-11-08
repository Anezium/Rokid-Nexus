package com.anezium.rokidbus.plugin.line

import com.anezium.rokidbus.client.plugin.NexusActionType
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageTemplateContract
import java.util.Base64
import org.junit.Assert.*
import org.junit.Test

class LinePagesTest {
    private val chat = LineChat("u0123456789abcdef0123456789abcdef", "Alice", false, emptyList())

    @Test fun newestFourMediaKeepTheirImagesAndOlderMessagesKeepTheirLabels() {
        val thumbnail = LauncherArtwork(160, 160, Base64.getEncoder().encodeToString(ByteArray(3200)))
        val loaded = chat.copy(history = (0..7).map { LineMessage("media-$it", "[Sticker]", false, "sticker") })
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(loaded)), true) { _, _ -> thumbnail }
        val messages = (page.body as PageBody.Conversation).messages
        assertEquals(listOf("media-4", "media-5", "media-6", "media-7"), messages.filter { it.image != null }.map { it.id })
        assertTrue(messages.take(4).all { it.text == "[Sticker]" })
        assertTrue(messages.takeLast(4).all { it.text.isEmpty() })
        assertTrue(messages.sumOf { it.image?.bytes()?.size ?: 0 } <= PageTemplateContract.MAX_MESSAGE_IMAGES_TOTAL_BYTES)
    }

    @Test fun largeImagesAreDroppedWithoutHidingTheMediaLabel() {
        val oversized = LauncherArtwork(512, 512, Base64.getEncoder().encodeToString(ByteArray(32768)))
        val loaded = chat.copy(history = listOf(LineMessage("photo", "[Photo]", true, "photo")))
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(loaded)), true) { _, _ -> oversized }
        val message = (page.body as PageBody.Conversation).messages.single()
        assertNull(message.image)
        assertEquals("[Photo]", message.text)
    }

    @Test fun escapedTextAndThumbnailsShareOnePageBudget() {
        val thumbnail = LauncherArtwork(160, 160, Base64.getEncoder().encodeToString(ByteArray(3200)))
        val loaded = chat.copy(history = (0..23).map { LineMessage("message-$it", "\u0001".repeat(2000), false, "photo") })
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(loaded)), true) { _, _ -> thumbnail }
        val messages = (page.body as PageBody.Conversation).messages
        assertEquals("message-23", messages.last().id)
        assertTrue(messages.sumOf { it.text.length * 6 + (it.image?.data?.length ?: 0) + 800 } <= 48_000)
    }

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

    @Test fun thumbnailsAreFetchedForOpenConversationsFirstThenTheListedOnes() {
        fun photo(id: String) = LineMessage(id, "[Photo]", false, media = "photo")
        fun text(id: String) = LineMessage(id, "hi", false)
        val a = LineChat("a", "A", false, listOf(photo("a1"), text("a2"), photo("a3"), photo("a4"), photo("a5"), photo("a6")))
        val b = LineChat("b", "B", true, listOf(photo("b1"), LineMessage("b2", "[Sticker]", true, media = "sticker")))
        val c = LineChat("c", "C", false, listOf(text("c1")))
        val others = (1..6).map { LineChat("x$it", "X$it", false, listOf(photo("x${it}p"))) }
        val state = LineSnapshot("ready", listOf(b, c, a) + others)

        // An open conversation: its newest pictures only, newest first, capped at what a page shows.
        assertEquals(listOf("a6", "a5", "a4", "a3"), LinePages.mediaCandidates(setOf(LinePages.pageId(a)), state).map { it.second.id })
        // The list alone: the first chats in list order, each capped the same way; chats without pictures add nothing.
        val listed = LinePages.mediaCandidates(setOf(LinePages.ROOT), state)
        assertEquals(listOf("b2", "b1", "a6", "a5", "a4", "a3", "x1p", "x2p", "x3p"), listed.map { it.second.id })
        // An open conversation comes before the list, and is not fetched twice.
        val both = LinePages.mediaCandidates(setOf(LinePages.pageId(a), LinePages.ROOT), state)
        assertEquals(listOf("a6", "a5", "a4", "a3", "b2", "b1", "x1p", "x2p", "x3p"), both.map { it.second.id })
        assertTrue(LinePages.mediaCandidates(emptySet(), state).isEmpty())
    }
}
