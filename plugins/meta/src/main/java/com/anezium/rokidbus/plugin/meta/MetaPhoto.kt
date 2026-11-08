package com.anezium.rokidbus.plugin.meta

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.client.plugin.NexusPageActionKind
import com.anezium.rokidbus.client.plugin.NexusPageActionSpec
import com.anezium.rokidbus.client.plugin.NexusSdkResult
import com.anezium.rokidbus.client.plugin.NexusSnapshotError
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract

/** Where a conversation's photo stands; only [Review] holds captured bytes. */
internal sealed interface PhotoStep {
    data object Capturing : PhotoStep
    data class CameraFailed(val message: String) : PhotoStep
    data object Review : PhotoStep
    data object Sending : PhotoStep
}

/** What the service does after an action on the photo; the flow itself touches no camera, page or engine. */
internal enum class PhotoCommand {
    NONE,
    /** Ask the glasses camera for a frame; the page says "Taking photo…" meanwhile. */
    CAPTURE,
    /** Dispatch the reviewed photo once. */
    SEND,
    /** Drop the photo and put the conversation back. */
    CLOSE,
}

/**
 * The photo flow of one conversation page (launcher bundle v6), as a pure state machine over the
 * page's own actions: capture, review the photo on an image page, send once on a tap on Send.
 * Nothing is sent before that tap; the bytes are dropped on every other exit. The page keeps the
 * conversation's id and title throughout, so Back leaves as it always did.
 */
internal class PhotoFlow(val recipient: String, val service: MessagingService) {
    var step: PhotoStep = PhotoStep.Capturing
        private set

    val holdsPhoto: Boolean get() = step == PhotoStep.Review

    /** A page action by its id; the three photo actions are only meaningful on the step that offers them. */
    fun action(actionId: String): PhotoCommand = when (actionId) {
        MetaPages.TAKE_PHOTO_ACTION_ID, MetaPages.RETAKE_ACTION_ID ->
            if (step == PhotoStep.Sending) PhotoCommand.NONE else { step = PhotoStep.Capturing; PhotoCommand.CAPTURE }
        MetaPages.SEND_PHOTO_ACTION_ID -> if (step == PhotoStep.Review) { step = PhotoStep.Sending; PhotoCommand.SEND } else PhotoCommand.NONE
        MetaPages.CANCEL_PHOTO_ACTION_ID -> if (step == PhotoStep.Sending) PhotoCommand.NONE else PhotoCommand.CLOSE
        else -> PhotoCommand.NONE
    }

    fun captured(): Boolean {
        if (step != PhotoStep.Capturing) return false
        step = PhotoStep.Review
        return true
    }

    fun cameraFailed(message: String): Boolean {
        if (step != PhotoStep.Capturing) return false
        step = PhotoStep.CameraFailed(message)
        return true
    }

    /** The send was refused in a way worth another try: the photo is kept and reviewed again. */
    fun keepForRetry(): Boolean {
        if (step != PhotoStep.Sending) return false
        step = PhotoStep.Review
        return true
    }

    /** What the conversation page shows at this step; [preview] is the bounded copy of the photo once there is one. */
    fun page(revision: Long, preview: LauncherArtwork?): NexusPage.Content {
        val title = clip(recipient, PageSurfaceContract.MAX_TITLE_CHARS)
        val caption = clip("Photo to $recipient · ${service.title}", PageTemplateContract.MAX_DETAIL_CHARS)
        return when (val current = step) {
            PhotoStep.Capturing -> NexusPage.Content(
                revision, title, PageBody.Summary(primary = TAKING, secondary = caption), live = true,
            )
            is PhotoStep.CameraFailed -> NexusPage.Content(
                revision, title,
                PageBody.Commands(
                    listOf(
                        PageRow(MetaPages.RETAKE_ACTION_ID, TRY_AGAIN, target = PageTarget.Action(MetaPages.RETAKE_ACTION_ID)),
                        PageRow(MetaPages.CANCEL_PHOTO_ACTION_ID, CANCEL, target = PageTarget.Action(MetaPages.CANCEL_PHOTO_ACTION_ID)),
                    ),
                    text = clip(current.message, PageTemplateContract.MAX_TEXT_CHARS),
                ),
                listOf(action(MetaPages.RETAKE_ACTION_ID, TRY_AGAIN), action(MetaPages.CANCEL_PHOTO_ACTION_ID, CANCEL)),
                live = true,
            )
            // Send is first and marked, so one tap sends; but it sends only what the wearer sees above it.
            PhotoStep.Review, PhotoStep.Sending -> NexusPage.Content(
                revision, title,
                PageBody.Image(checkNotNull(preview) { "a reviewed photo has its preview" }, if (current == PhotoStep.Sending) SENDING else caption),
                if (current == PhotoStep.Review) {
                    listOf(action(MetaPages.SEND_PHOTO_ACTION_ID, SEND), action(MetaPages.RETAKE_ACTION_ID, RETAKE), action(MetaPages.CANCEL_PHOTO_ACTION_ID, CANCEL))
                } else {
                    emptyList()
                },
                live = true,
            )
        }
    }

    private fun action(id: String, label: String) = NexusPageActionSpec(id, label, NexusPageActionKind.PLUGIN, confirm = false)

    private fun clip(value: String, limit: Int): String =
        if (value.length <= limit) value else value.substring(0, limit - 1).trimEnd() + "…"

    companion object {
        const val TAKING = "Taking photo…"
        const val SENDING = "Sending…"
        const val SEND = "Send"
        const val RETAKE = "Retake"
        const val TRY_AGAIN = "Try again"
        const val CANCEL = "Cancel"
        const val PREVIEW_FAILED = "The photo could not be prepared · try again"
        const val PHOTO_GONE = "That photo is no longer here · take another"
        /** How a photo sent from the glasses reads in the conversation. */
        const val SENT_TEXT = "[Photo]"
    }
}

/** The glasses camera's refusal, in the wearer's terms; the camera rides the Hi Rokid Bluetooth link. */
internal fun cameraErrorText(error: NexusSnapshotError): String = when (error) {
    NexusSnapshotError.BUSY -> "Camera is busy · close Lens or the Assistant and try again"
    NexusSnapshotError.LINK_DOWN -> "Camera needs the Hi Rokid Bluetooth link · check Hi Rokid is connected"
    NexusSnapshotError.TIMEOUT -> "Camera timed out · try again"
    NexusSnapshotError.CAPTURE_FAILED -> "Camera capture failed · try again"
    NexusSnapshotError.CANCELLED -> "Camera request was cancelled"
    NexusSnapshotError.ERROR -> "Camera request failed · try again"
}

/** Why the capture could not even be asked for. */
internal fun cameraStartErrorText(result: NexusSdkResult): String = when (result) {
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Allow Camera for Meta Messages in Nexus › Plugin access"
    NexusSdkResult.NOT_REGISTERED -> "Meta Messages isn't connected to Nexus"
    else -> "Glasses camera is unavailable"
}
