package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageTarget
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/** The photo flow of a conversation page (launcher bundle v6): its steps, actions and pages. */
class PhotoFlowTest {
    private val preview = LauncherArtwork(64, 48, Base64.getEncoder().encodeToString(ByteArray(64 * 48 / 8)))

    private fun reviewed(): PhotoFlow = PhotoFlow("Alice", MessagingService.INSTAGRAM).also { assertTrue(it.captured()) }

    @Test fun startsCapturingAndShowsTakingPhotoWithoutAnyAction() {
        val flow = PhotoFlow("Alice", MessagingService.INSTAGRAM)
        assertEquals(PhotoStep.Capturing, flow.step)
        assertFalse(flow.holdsPhoto)
        val page = flow.page(7, null)
        assertEquals(7, page.revision)
        assertEquals("Alice", page.title)
        assertTrue(page.live)
        val body = page.body as PageBody.Summary
        assertEquals(PhotoFlow.TAKING, body.primary)
        assertEquals("Photo to Alice · Instagram", body.secondary)
        assertTrue(page.actions.isEmpty())
        // Nothing on the page sends while the camera works; Send is not even an action yet.
        assertEquals(PhotoCommand.NONE, flow.action(MetaPages.SEND_PHOTO_ACTION_ID))
    }

    @Test fun aCameraRefusalBecomesPageTextWithTryAgainAndCancel() {
        val flow = PhotoFlow("Alice", MessagingService.INSTAGRAM)
        assertTrue(flow.cameraFailed("Camera is busy"))
        val page = flow.page(8, null)
        val body = page.body as PageBody.Commands
        assertEquals("Camera is busy", body.text)
        assertEquals(listOf(PhotoFlow.TRY_AGAIN, PhotoFlow.CANCEL), body.items.map { it.label })
        assertEquals(PageTarget.Action(MetaPages.RETAKE_ACTION_ID), body.items[0].target)
        assertEquals(PageTarget.Action(MetaPages.CANCEL_PHOTO_ACTION_ID), body.items[1].target)
        assertEquals(listOf(MetaPages.RETAKE_ACTION_ID, MetaPages.CANCEL_PHOTO_ACTION_ID), page.actions.map { it.id })
        assertTrue(page.actions.all { it.kind == NexusPageActionKind.PLUGIN && !it.confirm })
        // A second refusal of the same capture changes nothing; Try again captures again.
        assertFalse(flow.cameraFailed("Camera timed out"))
        assertEquals(PhotoCommand.CAPTURE, flow.action(MetaPages.RETAKE_ACTION_ID))
        assertEquals(PhotoStep.Capturing, flow.step)
        assertTrue(flow.captured())
    }

    @Test fun theCapturedPhotoIsReviewedOnAnImagePageWithSendFirst() {
        val flow = reviewed()
        assertTrue(flow.holdsPhoto)
        val page = flow.page(9, preview)
        val body = page.body as PageBody.Image
        assertSame(preview, body.image)
        assertEquals("Photo to Alice · Instagram", body.caption)
        assertEquals(listOf(PhotoFlow.SEND, PhotoFlow.RETAKE, PhotoFlow.CANCEL), page.actions.map { it.label })
        assertEquals(listOf(MetaPages.SEND_PHOTO_ACTION_ID, MetaPages.RETAKE_ACTION_ID, MetaPages.CANCEL_PHOTO_ACTION_ID), page.actions.map { it.id })
        assertTrue(page.actions.all { it.kind == NexusPageActionKind.PLUGIN && !it.confirm && it.typed == null })
        assertTrue(page.live)
    }

    @Test fun onlySendOnTheReviewedPhotoSendsAndItSendsOnce() {
        val flow = reviewed()
        assertEquals(PhotoCommand.SEND, flow.action(MetaPages.SEND_PHOTO_ACTION_ID))
        assertEquals(PhotoStep.Sending, flow.step)
        assertFalse(flow.holdsPhoto)
        // While the send runs nothing else on the page acts, and a replayed Send is not a second send.
        assertEquals(PhotoCommand.NONE, flow.action(MetaPages.SEND_PHOTO_ACTION_ID))
        assertEquals(PhotoCommand.NONE, flow.action(MetaPages.RETAKE_ACTION_ID))
        assertEquals(PhotoCommand.NONE, flow.action(MetaPages.CANCEL_PHOTO_ACTION_ID))
        val sending = flow.page(10, preview)
        assertEquals(PhotoFlow.SENDING, (sending.body as PageBody.Image).caption)
        assertTrue(sending.actions.isEmpty())
    }

    @Test fun aRefusedSendWorthRetryingKeepsThePhotoUnderReview() {
        val flow = reviewed()
        assertEquals(PhotoCommand.SEND, flow.action(MetaPages.SEND_PHOTO_ACTION_ID))
        assertTrue(flow.keepForRetry())
        assertEquals(PhotoStep.Review, flow.step)
        assertTrue(flow.holdsPhoto)
        assertEquals(3, flow.page(11, preview).actions.size)
        assertFalse(flow.keepForRetry())
    }

    @Test fun retakeDropsThePhotoAndCapturesAgain() {
        val flow = reviewed()
        assertEquals(PhotoCommand.CAPTURE, flow.action(MetaPages.RETAKE_ACTION_ID))
        assertEquals(PhotoStep.Capturing, flow.step)
        assertFalse(flow.holdsPhoto)
        assertTrue(flow.page(12, null).body is PageBody.Summary)
    }

    @Test fun cancelClosesWithoutSendingFromEveryStepButTheSend() {
        assertEquals(PhotoCommand.CLOSE, PhotoFlow("Alice", MessagingService.INSTAGRAM).action(MetaPages.CANCEL_PHOTO_ACTION_ID))
        assertEquals(PhotoCommand.CLOSE, reviewed().action(MetaPages.CANCEL_PHOTO_ACTION_ID))
        val failed = PhotoFlow("Alice", MessagingService.INSTAGRAM).also { it.cameraFailed("x") }
        assertEquals(PhotoCommand.CLOSE, failed.action(MetaPages.CANCEL_PHOTO_ACTION_ID))
        assertEquals(PhotoCommand.NONE, reviewed().action("send"))
    }

    @Test fun longRecipientsAreClippedToThePageLimits() {
        val flow = PhotoFlow("A".repeat(300), MessagingService.WHATSAPP)
        val page = flow.page(1, null)
        assertEquals(48, page.title.length)
        assertTrue(page.title.endsWith("…"))
        assertEquals(240, (page.body as PageBody.Summary).secondary!!.length)
    }

    @Test fun cameraTextsNameTheLinkTheGrantAndTheBusyOwner() {
        assertTrue(cameraErrorText(NexusSnapshotError.LINK_DOWN).contains("Hi Rokid"))
        assertTrue(cameraErrorText(NexusSnapshotError.BUSY).contains("Lens"))
        assertTrue(cameraStartErrorText(NexusSdkResult.CAPABILITY_NOT_GRANTED).contains("Plugin access"))
        assertTrue(cameraStartErrorText(NexusSdkResult.NOT_REGISTERED).contains("Nexus"))
    }
}
