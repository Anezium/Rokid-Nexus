package com.anezium.rokidbus.glasses.session

import org.json.JSONObject

/** Facts fed by the host. Gestures are already classified; time is the host clock. */
internal sealed interface SessionEvent {
    /** [underneath] is what the triple tap was made over; a session records it when it opens. */
    data class TripleTap(val nowMs: Long, val underneath: Underneath = Underneath.Unknown) : SessionEvent
    data class Contact(val nowMs: Long) : SessionEvent
    data class Enter(val nowMs: Long) : SessionEvent
    data class Back(val nowMs: Long) : SessionEvent

    data class Step(val delta: Int, val nowMs: Long) : SessionEvent {
        init {
            require(delta == 1 || delta == -1) { "step delta must be ±1" }
        }
    }

    data class Tick(val nowMs: Long) : SessionEvent
    data object NoticeArmed : SessionEvent
    data object NoticeCleared : SessionEvent
    data class NoticeArrived(val preview: NoticePreview) : SessionEvent
    data class ActivityEnded(val stopId: String) : SessionEvent
    data class ActivityStarted(val stop: ActivityStop) : SessionEvent

    /** Raw `/page/response` payload; the reducer validates and correlates it. */
    data class PageResponse(val payload: JSONObject, val nowMs: Long) : SessionEvent

    /** Raw `/page/result` payload; the reducer validates and correlates it. */
    data class PageResult(val payload: JSONObject, val nowMs: Long) : SessionEvent

    data object LinkLost : SessionEvent
    data object LinkRestored : SessionEvent
    data class EditableFocused(val focused: Boolean) : SessionEvent

    /** A plugin surface came to the front; [ownerPluginId] is the hub's own record, never a payload claim. */
    data class SurfaceShown(val surfaceId: String, val ownerPluginId: String, val nowMs: Long) : SessionEvent

    /** The open sent for [token] did not leave the hub, or was refused. */
    data class OpenFailed(val token: Long, val reason: OpenFailure) : SessionEvent

    /** The host takes the session down: a backend switch, or a window that could not be added. */
    data object Abort : SessionEvent
}

internal enum class OpenFailure { SEND_FAILED, REJECTED, TIMEOUT }
