package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.shared.ComposerSlot
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationMessageStatus
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

class MetaThumbnailsTest {
    private val alice = MetaChat("chat-1", "Alice", group = false, supported = true)
    private val crew = MetaChat("chat-2", "Crew", group = true, supported = true)
    private val locked = MetaChat("chat-3", "Locked", group = false, supported = false)

    /** A JPEG the contract parser reads as [width]×[height], padded to [bytes] bytes; never decoded. */
    private fun jpeg(width: Int, height: Int, bytes: Int): LauncherArtwork {
        val header = byteArrayOf(
            0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xc0.toByte(), 0, 7, 8,
            (height shr 8).toByte(), height.toByte(), (width shr 8).toByte(), width.toByte(),
        )
        val data = header + ByteArray(bytes - header.size)
        return LauncherArtwork(width, height, Base64.getEncoder().encodeToString(data), LauncherArtwork.JPEG)
    }

    private fun message(id: String, image: LauncherArtwork?) = ConversationMessage(id, ConversationRole.RECEIVED, "[Photo]", image = image)

    private fun composerSupport(thumbnails: Map<String, LauncherArtwork> = emptyMap(), imagePages: Boolean = true) =
        PageSupport(interactive = true, composerTools = true, imagePages = imagePages, thumbnail = { thumbnails[it] })

    private fun content(page: NexusPage) = page as NexusPage.Content

    // ---- which messages get a thumbnail ----

    @Test fun candidatesAreTheNewestFetchablePreviewsUpToThePageCount() {
        val history = (1..7).map { HistoryMessage("m$it", mine = false, text = "[Photo]", preview = "https://cdn.example/$it.jpg") } +
            HistoryMessage("plain", mine = false, text = "hi") +
            HistoryMessage("http", mine = false, text = "[Photo]", preview = "http://cdn.example/clear.jpg") +
            HistoryMessage("file", mine = false, text = "[Photo]", preview = "file:///sdcard/x.jpg")
        val picked = MetaThumbnails.candidates(history)
        assertEquals(PageTemplateContract.MAX_MESSAGE_IMAGES, picked.size)
        assertEquals(listOf("m4", "m5", "m6", "m7"), picked.map { it.id })
    }

    @Test fun onlyHttpsPreviewsAreFetchable() {
        assertTrue(MetaThumbnails.fetchable("https://scontent.example/p.jpg"))
        assertTrue(MetaThumbnails.fetchable("HTTPS://scontent.example/p.jpg"))
        assertFalse(MetaThumbnails.fetchable("http://scontent.example/p.jpg"))
        assertFalse(MetaThumbnails.fetchable(""))
        assertFalse(MetaThumbnails.fetchable(null))
    }

    @Test fun whatsAppThumbnailsAreReadLocallyNotFetched() {
        val stored = "whatsapp-thumbnail:33600000001@s.whatsapp.net/3EB0ABC"
        assertTrue(MetaThumbnails.fetchable(stored))
        assertTrue(MetaThumbnails.stored(stored))
        assertFalse(MetaThumbnails.stored("https://scontent.example/p.jpg"))
        assertFalse(MetaThumbnails.fetchable("https://" + "a".repeat(2_100)))
    }

    @Test fun keepTakesTheNewestImagesWithinCountAndTotalBudget() {
        val sizes = listOf(10_000, null, 10_000, 10_000, 10_000, 10_000)
        // Six messages, five with 10 KiB images: four fit the count, but the total budget of 32 KiB stops at three.
        assertEquals(setOf(3, 4, 5), MetaThumbnails.keep(sizes))
        assertEquals(setOf(2, 3, 4, 5), MetaThumbnails.keep(listOf(8_000, 8_000, 8_000, 8_000, 8_000, 8_000)))
        assertEquals(emptySet<Int>(), MetaThumbnails.keep(listOf(null, null)))
    }

    @Test fun keepSkipsAnOversizedImageWithoutStoppingAtIt() {
        val sizes = listOf(1_000, PageTemplateContract.MAX_MESSAGE_IMAGE_BYTES + 1, 1_000, 0)
        assertEquals(setOf(0, 2), MetaThumbnails.keep(sizes))
    }

    @Test fun withinImageBudgetStripsOnlyTheImagesOverTheBudget() {
        val big = jpeg(160, 120, 10_000)
        val messages = listOf(message("a", big), message("b", null), message("c", big), message("d", big), message("e", big))
        val fitted = MetaPages.withinImageBudget(messages)
        assertEquals(listOf("a", "b", "c", "d", "e"), fitted.map { it.id })
        assertNull(fitted[0].image)
        assertNull(fitted[1].image)
        assertSame(big, fitted[2].image)
        assertSame(big, fitted[3].image)
        assertSame(big, fitted[4].image)
        // A caption stays only where its picture was dropped; a drawn thumbnail stands alone.
        assertEquals("[Photo]", fitted[0].text)
        assertEquals("", fitted[2].text)
        assertEquals("[Photo]", fitted[1].text)
        assertEquals("", MetaPages.withoutPhotoPlaceholder("[Photo]"))
        assertEquals("at the beach", MetaPages.withoutPhotoPlaceholder("[Photo] at the beach"))
        assertEquals("Reel · @someone — caption", MetaPages.withoutPhotoPlaceholder("Reel · @someone — caption"))
        assertEquals("[Video] trailer", MetaPages.withoutPhotoPlaceholder("[Video] trailer"))
    }

    // ---- the chat page with composer tools ----

    @Test fun chatPageDeclaresThePhotoToolAsAPluginActionWithImagePages() {
        val state = MetaPagesState()
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice)))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, alice)
        val page = content(MetaPages.page(pageId, 3, state, composerSupport()))
        val composer = checkNotNull((page.body as PageBody.Conversation).composer)
        val tool = composer.tools.single()
        assertEquals(MetaPages.PHOTO_TOOL_ID, tool.id)
        assertEquals("Photo", tool.label)
        assertEquals(MetaPages.TAKE_PHOTO_ACTION_ID, tool.actionId)
        assertEquals("camera", tool.glyph)
        assertEquals(
            listOf(ComposerSlot.Dictate, ComposerSlot.Type, ComposerSlot.Tool(MetaPages.PHOTO_TOOL_ID), ComposerSlot.Send),
            composer.slots(),
        )
        val photo = page.actions.single { it.id == MetaPages.TAKE_PHOTO_ACTION_ID }
        assertEquals(NexusPageActionKind.PLUGIN, photo.kind)
        assertFalse(photo.confirm)
        assertNull(photo.typed)
        assertEquals(2, page.actions.size)
    }

    @Test fun chatPageWithholdsThePhotoToolWithoutImagePages() {
        val state = MetaPagesState()
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice)))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, alice)
        val page = content(MetaPages.page(pageId, 3, state, composerSupport(imagePages = false)))
        val composer = checkNotNull((page.body as PageBody.Conversation).composer)
        assertTrue(composer.tools.isEmpty())
        assertNull(composer.layout)
        assertEquals(listOf(MetaPages.SEND_ACTION_ID), page.actions.map { it.id })
    }

    @Test fun chatPageWithoutComposerToolsDeclaresNoToolNoLayoutAndNoImage() {
        val state = MetaPagesState()
        val photo = HistoryMessage("p1", mine = false, text = "[Photo]", preview = "https://cdn.example/1.jpg")
        state.set(MessagingService.INSTAGRAM, ServiceState.Ready(listOf(alice.copy(history = listOf(photo)))))
        val pageId = MetaPages.chatPageId(MessagingService.INSTAGRAM, alice)
        state.recordSent(pageId, SentMessage("s1", "[Photo]", ConversationMessageStatus.HANDED_OFF, image = jpeg(100, 100, 500)))
        val support = PageSupport(interactive = true, composerTools = false, thumbnail = { jpeg(120, 90, 800) })
        val page = content(MetaPages.page(pageId, 3, state, support))
        val body = page.body as PageBody.Conversation
        val composer = checkNotNull(body.composer)
        assertTrue(composer.tools.isEmpty())
        assertNull(composer.layout)
        assertEquals(1, page.actions.size)
        assertTrue(body.messages.all { it.image == null })
    }

    @Test fun chatPageWithholdsThePhotoToolForAWhatsAppGroupAnEncryptedChatOrWhileNotReady() {
        val state = MetaPagesState()
        state.set(MessagingService.WHATSAPP, ServiceState.Ready(listOf(crew)))
        state.set(MessagingService.MESSENGER, ServiceState.Ready(listOf(locked)))
        state.set(MessagingService.INSTAGRAM, ServiceState.Connecting)
        for (pageId in listOf(
            MetaPages.chatPageId(MessagingService.WHATSAPP, crew),
            MetaPages.chatPageId(MessagingService.MESSENGER, locked),
            MetaPages.chatPageId(MessagingService.INSTAGRAM, alice),
        )) {
            val page = content(MetaPages.page(pageId, 2, state, composerSupport()))
            val composer = checkNotNull((page.body as PageBody.Conversation).composer)
            assertTrue(pageId, composer.tools.isEmpty())
            assertNull(pageId, composer.layout)
            assertTrue(pageId, page.actions.none { it.id == MetaPages.TAKE_PHOTO_ACTION_ID })
        }
    }

    @Test fun chatPageShowsFetchedThumbnailsAndTheSentPhotosOwn() {
        val state = MetaPagesState()
        val history = listOf(
            HistoryMessage("p1", mine = false, text = "[Photo]", preview = "https://cdn.example/1.jpg"),
            HistoryMessage("t1", mine = true, text = "nice"),
            HistoryMessage("p2", mine = false, text = "Reel · @someone — caption", preview = "https://cdn.example/2.jpg"),
        )
        state.set(MessagingService.MESSENGER, ServiceState.Ready(listOf(alice.copy(history = history))))
        val pageId = MetaPages.chatPageId(MessagingService.MESSENGER, alice)
        val own = jpeg(160, 90, 4_000)
        state.recordSent(pageId, SentMessage("s1", "[Photo]", ConversationMessageStatus.HANDED_OFF, image = own))
        val fetched = jpeg(120, 160, 6_000)
        val page = content(MetaPages.page(pageId, 5, state, composerSupport(mapOf("p2" to fetched))))
        val messages = (page.body as PageBody.Conversation).messages
        assertEquals(listOf("p1", "t1", "p2", "s1"), messages.map { it.id })
        // p1 has a preview but nothing fetched yet: caption alone until the loader publishes again.
        assertNull(messages[0].image)
        assertNull(messages[1].image)
        assertSame(fetched, messages[2].image)
        assertEquals("Reel · @someone — caption", messages[2].text)
        assertSame(own, messages[3].image)
        assertEquals(ConversationRole.OURS, messages[3].role)
    }
}
