package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceInvocation
import com.anezium.rokidbus.shared.PageSurfaceRequest

/** Returned by the reducer for a host to execute; the reducer never performs them. */
internal sealed interface SessionEffect {
    data object ShowGate : SessionEffect
    data object ShowRoot : SessionEffect
    data object ShowFrame : SessionEffect
    data class RequestPage(val request: PageSurfaceRequest) : SessionEffect
    data class SendAction(val action: PageSurfaceInvocation) : SessionEffect
    data class SendVisibility(val pageId: String, val visible: Boolean, val leaseUntilMs: Long? = null) : SessionEffect
    data class SendClosed(val pageId: String, val reason: String) : SessionEffect
    data object RestoreUnderneath : SessionEffect

    /** The single effect of a transition that changes nothing visible or on the wire. */
    data object None : SessionEffect
}
