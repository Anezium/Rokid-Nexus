package com.anezium.rokidbus.plugin.line

import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.shared.ComposerSlot
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** The photo flow of a LINE conversation page: its steps, actions and pages, and the tool that opens it. */
class LinePhotoFlowTest {
    private val preview = LauncherArtwork(64, 48, Base64.getEncoder().encodeToString(ByteArray(64 * 48 / 8)))
    private val chat = LineChat("u0123456789abcdef0123456789abcdef", "Alice", false, emptyList())

    private fun reviewed(): PhotoFlow = PhotoFlow("Alice").also { assertTrue(it.captured()) }

    @Test fun thePhotoToolIsOfferedOnlyToHubsWithComposerToolsAndImagePages() {
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(chat)), true, photoTool = true)
        val composer = (page.body as PageBody.Conversation).composer!!
        assertEquals(listOf(LinePages.PHOTO_TOOL), composer.tools.map { it.id })
        assertEquals(LinePages.TAKE_PHOTO, composer.tools.single().actionId)
        assertEquals(listOf(ComposerSlot.Dictate, ComposerSlot.Type, ComposerSlot.Tool(LinePages.PHOTO_TOOL), ComposerSlot.Send), composer.layout)
        assertEquals(listOf(LinePages.SEND, LinePages.TAKE_PHOTO), page.actions.map { it.id })
        val take = page.actions.last()
        assertEquals(NexusPageActionKind.PLUGIN, take.kind)
        assertFalse(take.confirm)
        assertNull(take.typed)

        val without = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("ready", listOf(chat)), true)
        assertTrue((without.body as PageBody.Conversation).composer!!.tools.isEmpty())
        assertNull((without.body as PageBody.Conversation).composer!!.layout)
        assertEquals(listOf(LinePages.SEND), without.actions.map { it.id })
    }

    @Test fun aDisconnectedConversationOffersNoPhotoEvenToACapableHub() {
        val page = LinePages.render(LinePages.pageId(chat), 1, LineSnapshot("offline", listOf(chat)), true, photoTool = true)
        assertTrue((page.body as PageBody.Conversation).composer!!.tools.isEmpty())
        assertEquals(listOf(LinePages.SEND), page.actions.map { it.id })
    }

    @Test fun startsCapturingAndShowsTakingPhotoWithoutAnyAction() {
        val flow = PhotoFlow("Alice")
        assertEquals(PhotoStep.Capturing, flow.step)
        assertFalse(flow.holdsPhoto)
        val page = flow.page(7, null)
        assertEquals(7, page.revision)
        assertEquals("Alice", page.title)
        assertTrue(page.live)
        val body = page.body as PageBody.Summary
        assertEquals(PhotoFlow.TAKING, body.primary)
        assertEquals("Photo to Alice · LINE", body.secondary)
        assertTrue(page.actions.isEmpty())
        assertEquals(PhotoCommand.NONE, flow.action(LinePages.SEND_PHOTO))
    }

    @Test fun aCameraRefusalBecomesPageTextWithTryAgainAndCancel() {
        val flow = PhotoFlow("Alice")
        assertTrue(flow.cameraFailed("Camera is busy"))
        val page = flow.page(8, null)
        val body = page.body as PageBody.Commands
        assertEquals("Camera is busy", body.text)
        assertEquals(listOf(PhotoFlow.TRY_AGAIN, PhotoFlow.CANCEL), body.items.map { it.label })
        assertEquals(PageTarget.Action(LinePages.RETAKE_PHOTO), body.items[0].target)
        assertEquals(PageTarget.Action(LinePages.CANCEL_PHOTO), body.items[1].target)
        assertEquals(listOf(LinePages.RETAKE_PHOTO, LinePages.CANCEL_PHOTO), page.actions.map { it.id })
        assertTrue(page.actions.all { it.kind == NexusPageActionKind.PLUGIN && !it.confirm })
        assertFalse(flow.cameraFailed("Camera timed out"))
        assertEquals(PhotoCommand.CAPTURE, flow.action(LinePages.RETAKE_PHOTO))
        assertEquals(PhotoStep.Capturing, flow.step)
        assertTrue(flow.captured())
    }

    @Test fun theCapturedPhotoIsReviewedOnAnImagePageWithSendFirst() {
        val flow = reviewed()
        assertTrue(flow.holdsPhoto)
        val page = flow.page(9, preview)
        val body = page.body as PageBody.Image
        assertSame(preview, body.image)
        assertEquals("Photo to Alice · LINE", body.caption)
        assertEquals(listOf(PhotoFlow.SEND, PhotoFlow.RETAKE, PhotoFlow.CANCEL), page.actions.map { it.label })
        assertEquals(listOf(LinePages.SEND_PHOTO, LinePages.RETAKE_PHOTO, LinePages.CANCEL_PHOTO), page.actions.map { it.id })
        assertTrue(page.actions.all { it.kind == NexusPageActionKind.PLUGIN && !it.confirm && it.typed == null })
    }

    @Test fun onlySendOnTheReviewedPhotoSendsAndItSendsOnce() {
        val flow = reviewed()
        assertEquals(PhotoCommand.SEND, flow.action(LinePages.SEND_PHOTO))
        assertEquals(PhotoStep.Sending, flow.step)
        assertFalse(flow.holdsPhoto)
        assertEquals(PhotoCommand.NONE, flow.action(LinePages.SEND_PHOTO))
        assertEquals(PhotoCommand.NONE, flow.action(LinePages.RETAKE_PHOTO))
        assertEquals(PhotoCommand.NONE, flow.action(LinePages.CANCEL_PHOTO))
        val sending = flow.page(10, preview)
        assertEquals(PhotoFlow.SENDING, (sending.body as PageBody.Image).caption)
        assertTrue(sending.actions.isEmpty())
    }

    @Test fun aRefusedSendWorthRetryingKeepsThePhotoUnderReview() {
        val flow = reviewed()
        assertEquals(PhotoCommand.SEND, flow.action(LinePages.SEND_PHOTO))
        assertTrue(flow.keepForRetry())
        assertEquals(PhotoStep.Review, flow.step)
        assertEquals(3, flow.page(11, preview).actions.size)
        assertFalse(flow.keepForRetry())
    }

    @Test fun cancelClosesWithoutSendingFromEveryStepButTheSend() {
        assertEquals(PhotoCommand.CLOSE, PhotoFlow("Alice").action(LinePages.CANCEL_PHOTO))
        assertEquals(PhotoCommand.CLOSE, reviewed().action(LinePages.CANCEL_PHOTO))
        assertEquals(PhotoCommand.CLOSE, PhotoFlow("Alice").also { it.cameraFailed("x") }.action(LinePages.CANCEL_PHOTO))
        assertEquals(PhotoCommand.NONE, reviewed().action(LinePages.SEND))
    }

    @Test fun longRecipientsAreClippedToThePageLimits() {
        val page = PhotoFlow("A".repeat(300)).page(1, null)
        assertEquals(48, page.title.length)
        assertTrue(page.title.endsWith("…"))
        assertEquals(240, (page.body as PageBody.Summary).secondary!!.length)
    }

    @Test fun cameraTextsNameTheLinkTheGrantAndTheBusyOwner() {
        assertTrue(cameraErrorText(NexusSnapshotError.LINK_DOWN).contains("Hi Rokid"))
        assertTrue(cameraErrorText(NexusSnapshotError.BUSY).contains("Lens"))
        assertTrue(cameraStartErrorText(NexusSdkResult.CAPABILITY_NOT_GRANTED).contains("Plugin access"))
        assertTrue(cameraStartErrorText(NexusSdkResult.NOT_REGISTERED).contains("LINE Messages"))
    }
}
