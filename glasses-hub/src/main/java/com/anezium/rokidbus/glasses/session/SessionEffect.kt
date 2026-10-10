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

    /**
     * The session closed over [underneath], which is left as it was: nothing is sent to it. The
     * companion is the restore over an unknown base, the only kind PR1 had, so the bare name
     * `RestoreUnderneath` still denotes that value.
     */
    open class RestoreUnderneath(val underneath: Underneath) : SessionEffect {
        override fun equals(other: Any?): Boolean = other is RestoreUnderneath && other.underneath == underneath
        override fun hashCode(): Int = underneath.hashCode()
        override fun toString(): String = "RestoreUnderneath(underneath=$underneath)"

        companion object : RestoreUnderneath(Underneath.Unknown)
    }

    /** Asks the phone to open [pluginId]'s immersion, exactly as the launcher opens an entry. */
    data class SendLauncherOpen(val pluginId: String, val token: Long) : SessionEffect

    /** Closes a surface unseen, through the surface's own close path; no BACK is forwarded to it. */
    data class CloseSurface(val surfaceId: String, val reason: String) : SessionEffect

    data class ShowStatus(val status: SessionStatus) : SessionEffect

    /** Informational for the runner, which keeps one timer at [SessionModel.nextDeadlineMs]. */
    data class ScheduleDeadline(val atMs: Long) : SessionEffect

    /** Informational for the runner, which keeps one timer at [SessionModel.nextDeadlineMs]. */
    data object CancelDeadline : SessionEffect

    /** Emitted by the runner, never by the reducer, before the first effect of a session. */
    data object AttachHost : SessionEffect

    /** Emitted by the runner, never by the reducer, after the last effect of a session. */
    data object DetachHost : SessionEffect

    /** The single effect of a transition that changes nothing visible or on the wire. */
    data object None : SessionEffect
}

/** A short line the host shows over the current screen. */
internal sealed interface SessionStatus {
    data class OpenFailed(val pluginId: String, val reason: OpenFailure) : SessionStatus
}
