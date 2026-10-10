package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ActivityCommandResult
import org.json.JSONObject

/** Facts fed by the host. Gestures are already classified; time is the host clock. */
internal sealed interface SessionEvent {
    /** [underneath] is what the triple tap was made over; a session records it when it opens. */
    data class TripleTap(val nowMs: Long, val underneath: Underneath = Underneath.Unknown) : SessionEvent
    data class Contact(val nowMs: Long) : SessionEvent

    /**
     * [pressStartMs] is when the physical press began: the touchpad contact-down the firmware
     * classified as this tap, or the ring tap's own down time. The input arbiter always supplies
     * it; the default, the classification itself, is for hub-internal callers and tests.
     */
    data class Enter(val nowMs: Long, val pressStartMs: Long = nowMs) : SessionEvent
    data class Back(val nowMs: Long) : SessionEvent

    data class Step(val delta: Int, val nowMs: Long) : SessionEvent {
        init {
            require(delta == 1 || delta == -1) { "step delta must be ±1" }
        }
    }

    data class Tick(val nowMs: Long) : SessionEvent
    data object NoticeArmed : SessionEvent
    data object NoticeCleared : SessionEvent
    /**
     * A new notice instance. [entry] is what the band showed, launcher-bundle fields included when
     * the pair negotiated them; without one, the history records [preview] as a generic entry.
     */
    data class NoticeArrived(val preview: NoticePreview, val entry: NoticeEntry? = null) : SessionEvent

    /** `/notice/withdraw` as the phone forwarded it: the event is no longer on the phone. */
    data class NoticeWithdrawn(val ownerPluginId: String, val eventKey: String) : SessionEvent

    /** `/core/notice/purge`: every history entry of [ownerPluginId] goes. */
    data class NoticePurged(val ownerPluginId: String) : SessionEvent

    /** A capture engine's message from the phone hub, for the capture it names. */
    data class ComposeReceived(val message: ComposeInbound, val nowMs: Long) : SessionEvent
    data class ActivityEnded(val stopId: String) : SessionEvent
    data class ActivityStarted(val stop: ActivityStop) : SessionEvent

    /** New values for an activity already known; membership and order never change for it. */
    data class ActivityUpdated(val stop: ActivityStop) : SessionEvent

    /** Raw `/page/response` payload; the reducer validates and correlates it. */
    data class PageResponse(val payload: JSONObject, val nowMs: Long) : SessionEvent

    /** Raw `/page/result` payload; the reducer validates and correlates it. */
    data class PageResult(val payload: JSONObject, val nowMs: Long) : SessionEvent

    data object LinkLost : SessionEvent

    /** SPP went away while CXR stayed up: answers too big for CXR can no longer arrive. */
    data object DataPlaneLost : SessionEvent

    /** The pair no longer negotiates page v1 although the link is up. */
    data object PageSupportLost : SessionEvent
    data object LinkRestored : SessionEvent
    data class EditableFocused(val focused: Boolean) : SessionEvent

    /** A plugin surface came to the front; [ownerPluginId] is the hub's own record, never a payload claim. */
    data class SurfaceShown(val surfaceId: String, val ownerPluginId: String, val nowMs: Long) : SessionEvent

    /** The open sent for [token] did not leave the hub, or was refused. */
    data class OpenFailed(val token: Long, val reason: OpenFailure) : SessionEvent

    /** The host takes the session down: a backend switch, or a window that could not be added. */
    data object Abort : SessionEvent

    /**
     * The trusted phone hub invalidated [ownerPluginId]'s work, or the hub itself lost that
     * provider: its frames keep their snapshots, inert, and its pending work is cancelled.
     */
    data class ProviderInvalidated(val ownerPluginId: String) : SessionEvent

    /**
     * The renderer laid out a shown document: where each page starts, and which page holds the
     * frame's reading anchor. Ignored unless it describes the top frame at its current revision.
     */
    data class DocumentPaged(
        val frame: FrameIdentity,
        val pageId: String,
        val revision: Long,
        val pageStarts: List<DocumentAnchor>,
        val anchorPage: Int,
    ) : SessionEvent

    /**
     * The renderer laid out the top frame's content and it does not fit the viewport with its
     * first row or control in view. Ignored unless it describes the top frame's retained revision.
     */
    data class ContentOverflowed(val frame: FrameIdentity, val pageId: String, val revision: Long) : SessionEvent

    /** A [SessionEffect.LaunchLocal] ran: [launched] closes the session, a failure keeps it open. */
    data class LocalLaunchFinished(val key: String, val label: String, val launched: Boolean) : SessionEvent

    /**
     * The host drew the frame whose focused target is [token] (see [SessionFocus.tokenOf]) at
     * [atMs], on the session clock. Only an acknowledgement of the current target counts.
     */
    data class FocusShown(val token: String, val atMs: Long) : SessionEvent

    /** An owner's `/activity/result`, as the phone forwarded it for a command these glasses sent. */
    data class ActivityResult(val result: ActivityCommandResult, val nowMs: Long) : SessionEvent

    /**
     * The launcher bundle's negotiation or the user's quick-action policy changed: what one tap
     * does may differ now, so the focused target is a new one and the screen is redrawn.
     */
    data object LauncherFactsChanged : SessionEvent
}

internal enum class OpenFailure { SEND_FAILED, REJECTED, TIMEOUT }
