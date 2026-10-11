package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.ActivityCommand
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageSurfaceInvocation
import com.anezium.rokidbus.shared.PageSurfaceRequest

/** Returned by the reducer for a host to execute; the reducer never performs them. */
internal sealed interface SessionEffect {
    data object ShowGate : SessionEffect
    data object ShowRoot : SessionEffect
    data object ShowFrame : SessionEffect
    /**
     * [deadlineMs] (the request's absolute deadline) and [minimumRevision] travel with it for the
     * wire but are not part of its identity: a request is specified by its correlation and reason.
     */
    data class RequestPage(val request: PageSurfaceRequest) : SessionEffect {
        var deadlineMs: Long? = null
            private set
        var minimumRevision: Long? = null
            private set

        constructor(request: PageSurfaceRequest, deadlineMs: Long, minimumRevision: Long?) : this(request) {
            this.deadlineMs = deadlineMs
            this.minimumRevision = minimumRevision
        }
    }

    data class SendAction(val action: PageSurfaceInvocation) : SessionEffect

    /** One activity command in its launcher-bundle shape, sent once and never replayed. */
    data class SendActivityCommand(val command: ActivityCommand) : SessionEffect

    /** One message to the phone's capture engines; it never carries a reply. */
    data class SendCompose(val message: ComposeOutbound) : SessionEffect

    /**
     * [frame] names the frame for the wire, captured while it still exists; like the request's
     * deadline it is carried, not compared. Without one, nothing is sent.
     */
    data class SendVisibility(val pageId: String, val visible: Boolean, val leaseUntilMs: Long? = null) : SessionEffect {
        var frame: FrameIdentity? = null
            private set

        constructor(pageId: String, visible: Boolean, leaseUntilMs: Long?, frame: FrameIdentity?) :
            this(pageId, visible, leaseUntilMs) {
            this.frame = frame
        }
    }

    /** A close with no [frame] names no frame, as for a refused seventh one: it is never sent. */
    data class SendClosed(val pageId: String, val reason: String) : SessionEffect {
        var frame: FrameIdentity? = null
            private set

        constructor(pageId: String, reason: String, frame: FrameIdentity?) : this(pageId, reason) {
            this.frame = frame
        }
    }

    /** Launches a hub-authored local entry, a native app or the hub camera, by its catalog key. */
    data class LaunchLocal(val key: String, val label: String) : SessionEffect

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

    /**
     * The wearer saved [size] on the display page for [ownerPluginId]: the hub applies it and
     * sends it to the phone, or queues it. Only the display page writes the ambient size.
     */
    data class SaveAmbientSize(val ownerPluginId: String, val size: AmbientSize) : SessionEffect

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

/** Which frame of which session a page call concerns. */
internal data class FrameIdentity(val sessionGeneration: Long, val frameIndex: Int)

/** A short line the host shows over the current screen. */
internal sealed interface SessionStatus {
    data class OpenFailed(val pluginId: String, val reason: OpenFailure) : SessionStatus

    /** A native app or the camera did not open, or is no longer in the catalog. */
    data class LaunchFailed(val label: String) : SessionStatus

    /** The six-frame stack is full; nothing was opened. */
    data object FrameLimit : SessionStatus

    /** A row whose action this hub does not perform. */
    data object NotAvailable : SessionStatus
}
