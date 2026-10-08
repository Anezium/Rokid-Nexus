package com.anezium.rokidbus.glasses.session

import org.json.JSONObject

/** Facts fed by the host. Gestures are already classified; time is the host clock. */
internal sealed interface SessionEvent {
    data class TripleTap(val nowMs: Long) : SessionEvent
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
}
