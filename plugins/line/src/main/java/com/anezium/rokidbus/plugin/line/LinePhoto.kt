package com.anezium.rokidbus.plugin.line

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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
import java.io.ByteArrayOutputStream
import java.util.Base64

/** Where a conversation's photo stands; only [Review] holds captured bytes. */
internal sealed interface PhotoStep {
    data object Capturing : PhotoStep
    data class CameraFailed(val message: String) : PhotoStep
    data object Review : PhotoStep
    data object Sending : PhotoStep
}

/** What the service does after an action on the photo; the flow itself touches no camera, page or engine. */
internal enum class PhotoCommand { NONE, CAPTURE, SEND, CLOSE }

/**
 * The photo flow of one LINE conversation page, the same shape as Meta Messages': capture,
 * review on an image page, send once on a tap on Send. Nothing is sent before that tap and the
 * bytes are dropped on every other exit. The page keeps the conversation's id and title, so
 * Back leaves as it always did.
 */
internal class PhotoFlow(val recipient: String) {
    var step: PhotoStep = PhotoStep.Capturing
        private set

    val holdsPhoto: Boolean get() = step == PhotoStep.Review

    fun action(actionId: String): PhotoCommand = when (actionId) {
        LinePages.TAKE_PHOTO, LinePages.RETAKE_PHOTO ->
            if (step == PhotoStep.Sending) PhotoCommand.NONE else { step = PhotoStep.Capturing; PhotoCommand.CAPTURE }
        LinePages.SEND_PHOTO -> if (step == PhotoStep.Review) { step = PhotoStep.Sending; PhotoCommand.SEND } else PhotoCommand.NONE
        LinePages.CANCEL_PHOTO -> if (step == PhotoStep.Sending) PhotoCommand.NONE else PhotoCommand.CLOSE
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

    fun page(revision: Long, preview: LauncherArtwork?): NexusPage.Content {
        val title = clip(recipient, PageSurfaceContract.MAX_TITLE_CHARS)
        val caption = clip("Photo to $recipient · LINE", PageTemplateContract.MAX_DETAIL_CHARS)
        return when (val current = step) {
            PhotoStep.Capturing -> NexusPage.Content(revision, title, PageBody.Summary(primary = TAKING, secondary = caption), live = true)
            is PhotoStep.CameraFailed -> NexusPage.Content(
                revision, title,
                PageBody.Commands(
                    listOf(
                        PageRow(LinePages.RETAKE_PHOTO, TRY_AGAIN, target = PageTarget.Action(LinePages.RETAKE_PHOTO)),
                        PageRow(LinePages.CANCEL_PHOTO, CANCEL, target = PageTarget.Action(LinePages.CANCEL_PHOTO)),
                    ),
                    text = clip(current.message, PageTemplateContract.MAX_TEXT_CHARS),
                ),
                listOf(action(LinePages.RETAKE_PHOTO, TRY_AGAIN), action(LinePages.CANCEL_PHOTO, CANCEL)),
                live = true,
            )
            // Send is first, so one tap sends; it sends only what the wearer sees above it.
            PhotoStep.Review, PhotoStep.Sending -> NexusPage.Content(
                revision, title,
                PageBody.Image(checkNotNull(preview) { "a reviewed photo has its preview" }, if (current == PhotoStep.Sending) SENDING else caption),
                if (current == PhotoStep.Review) {
                    listOf(action(LinePages.SEND_PHOTO, SEND), action(LinePages.RETAKE_PHOTO, RETAKE), action(LinePages.CANCEL_PHOTO, CANCEL))
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
    NexusSdkResult.CAPABILITY_NOT_GRANTED -> "Allow Camera for LINE Messages in Nexus › Plugin access"
    NexusSdkResult.NOT_REGISTERED -> "LINE Messages isn't connected to Nexus"
    else -> "Glasses camera is unavailable"
}

/**
 * Shrinks the camera's JPEG to what an image page accepts: both edges at most
 * [LauncherArtwork.MAX_PAGE_EDGE] and the encoding at most [LauncherArtwork.MAX_PAGE_BYTES],
 * lowering the quality until it fits. The photo sent to LINE is never this reduced copy.
 */
internal object PhotoPreviewEncoder {
    fun encode(jpeg: ByteArray): LauncherArtwork? {
        if (jpeg.isEmpty()) return null
        val maxEdge = LauncherArtwork.MAX_PAGE_EDGE
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        var bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        try {
            val edge = maxOf(bitmap.width, bitmap.height)
            if (edge > maxEdge) {
                val scale = maxEdge.toFloat() / edge
                val scaled = Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true)
                if (scaled !== bitmap) { bitmap.recycle(); bitmap = scaled }
            }
            for (quality in 75 downTo 30 step 15) {
                val output = ByteArrayOutputStream()
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return null
                val bytes = output.toByteArray()
                if (bytes.size <= LauncherArtwork.MAX_PAGE_BYTES) {
                    return runCatching { LauncherArtwork(bitmap.width, bitmap.height, Base64.getEncoder().encodeToString(bytes), LauncherArtwork.JPEG) }.getOrNull()
                }
            }
            return null
        } finally {
            bitmap.recycle()
        }
    }
}
