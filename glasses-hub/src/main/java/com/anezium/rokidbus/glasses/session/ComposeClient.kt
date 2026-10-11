package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ComposeContract
import com.anezium.rokidbus.shared.ComposeDictateFinal
import com.anezium.rokidbus.shared.ComposeDictatePartial
import com.anezium.rokidbus.shared.ComposeDictateStart
import com.anezium.rokidbus.shared.ComposeDictateStop
import com.anezium.rokidbus.shared.ComposeTypeDone
import com.anezium.rokidbus.shared.ComposeTypeStart
import com.anezium.rokidbus.shared.ComposeTypeText
import org.json.JSONObject

/** A capture engine's message from the phone hub, already validated by the shared contract. */
internal sealed interface ComposeInbound {
    val captureId: String

    data class Partial(val partial: ComposeDictatePartial) : ComposeInbound {
        override val captureId: String get() = partial.captureId
    }

    data class Final(val result: ComposeDictateFinal) : ComposeInbound {
        override val captureId: String get() = result.captureId
    }

    data class Text(val text: ComposeTypeText) : ComposeInbound {
        override val captureId: String get() = text.captureId
    }

    data class Done(val done: ComposeTypeDone) : ComposeInbound {
        override val captureId: String get() = done.captureId
    }
}

/** What the glasses composer asks of the phone's capture engines. */
internal sealed interface ComposeOutbound {
    val captureId: String

    data class DictateStart(val start: ComposeDictateStart) : ComposeOutbound {
        override val captureId: String get() = start.captureId
    }

    data class DictateStop(val stop: ComposeDictateStop) : ComposeOutbound {
        override val captureId: String get() = stop.captureId
    }

    data class TypeStart(val start: ComposeTypeStart) : ComposeOutbound {
        override val captureId: String get() = start.captureId
    }

    data class TypeFinish(override val captureId: String) : ComposeOutbound

    data class TypeCancel(override val captureId: String) : ComposeOutbound
}

/**
 * The capture clients' wire, over the trusted hub-to-hub `/core/compose/...` routes only. Dictation
 * runs on the glasses microphone through the phone's hub speech engine and typing on the phone
 * keyboard; there is no phone-microphone path here, and nothing here sends a reply.
 */
internal object ComposeClient {
    /** A phone-to-glasses message, or null when it is not one or does not hold to the contract. */
    fun inbound(path: String, payload: JSONObject): ComposeInbound? = when (path) {
        ComposeContract.DICTATE_PARTIAL_PATH -> ComposeContract.parseDictatePartial(payload)?.let { ComposeInbound.Partial(it) }
        ComposeContract.DICTATE_FINAL_PATH -> ComposeContract.parseDictateFinal(payload)?.let { ComposeInbound.Final(it) }
        ComposeContract.TYPE_TEXT_PATH -> ComposeContract.parseTypeText(payload)?.let { ComposeInbound.Text(it) }
        ComposeContract.TYPE_DONE_PATH -> ComposeContract.parseTypeDone(payload)?.let { ComposeInbound.Done(it) }
        else -> null
    }

    /** The path and payload of a glasses-to-phone message. */
    fun outbound(message: ComposeOutbound): Pair<String, JSONObject> = when (message) {
        is ComposeOutbound.DictateStart -> ComposeContract.DICTATE_START_PATH to ComposeContract.dictateStart(message.start)
        is ComposeOutbound.DictateStop -> ComposeContract.DICTATE_STOP_PATH to ComposeContract.dictateStop(message.stop)
        is ComposeOutbound.TypeStart -> ComposeContract.TYPE_START_PATH to ComposeContract.typeStart(message.start)
        is ComposeOutbound.TypeFinish -> ComposeContract.TYPE_FINISH_PATH to ComposeContract.typeEnd(message.captureId)
        is ComposeOutbound.TypeCancel -> ComposeContract.TYPE_CANCEL_PATH to ComposeContract.typeEnd(message.captureId)
    }

    /** Ends [capture] at the phone: a dictation is cancelled, a typing prompt dismissed. */
    fun cancel(capture: Capture): ComposeOutbound = when (capture.kind) {
        CaptureKind.DICTATE -> ComposeOutbound.DictateStop(ComposeDictateStop(capture.captureId, cancel = true))
        CaptureKind.TYPE -> ComposeOutbound.TypeCancel(capture.captureId)
    }

    /** Asks the phone to finish [capture] and send its final text. */
    fun finish(capture: Capture): ComposeOutbound = when (capture.kind) {
        CaptureKind.DICTATE -> ComposeOutbound.DictateStop(ComposeDictateStop(capture.captureId, cancel = false))
        CaptureKind.TYPE -> ComposeOutbound.TypeFinish(capture.captureId)
    }
}
