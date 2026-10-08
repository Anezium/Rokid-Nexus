package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse

/** A validated page as retained by a frame; [byteSize] is the UTF-8 size of its serialized payload. */
internal data class Snapshot(
    val page: PageSurfaceResponse.Page,
    val byteSize: Int = page.byteSize,
)

internal data class PendingInvocation(val invocationId: String, val actionId: String, val sinceMs: Long)

/** The last action outcome shown on a frame: `done`, `rejected`, `stale`, or `UNCONFIRMED_ACTION`. */
internal data class ActionOutcome(val actionId: String, val status: String, val message: String? = null)

internal sealed interface FrameStatus {
    /**
     * One request in flight. [previous] stays displayed while a refresh or retry
     * validates it; [leaseUntilMs] is the lease already granted on uncover.
     */
    data class Loading(
        val sinceMs: Long,
        val requestId: String,
        val reason: String,
        val stillLoading: Boolean = false,
        val previous: Snapshot? = null,
        val leaseUntilMs: Long? = null,
    ) : FrameStatus

    /**
     * [leaseUntilMs] is null while the frame is covered. [requestId] is the
     * request that established the page; live revisions must carry it.
     */
    data class Shown(
        val snapshot: Snapshot,
        val revision: Long,
        val leaseUntilMs: Long?,
        val requestId: String,
    ) : FrameStatus

    data class Unavailable(val lastSnapshot: Snapshot?, val reason: String) : FrameStatus
}

internal data class Frame(
    val pageId: String,
    val status: FrameStatus,
    val selected: Int = 0,
    val paramsJson: String? = null,
    val invocation: PendingInvocation? = null,
    val outcome: ActionOutcome? = null,
    /** Highest revision shown on this frame; it survives eviction, timeouts and link loss. */
    val revisionFloor: Long? = null,
)

internal sealed interface SessionState {
    data object Closed : SessionState

    /** [root] is frozen when the triple tap is accepted, before the gate ends. */
    data class Opening(val sinceMs: Long, val root: Root) : SessionState

    data class Root(val stops: List<RootStop>, val selected: Int = 0) : SessionState

    /** [frames] excludes the root; `frames[i]` is wire frame index `i + 1`. */
    data class InPage(val frames: List<Frame>, val root: Root) : SessionState

    /**
     * A plugin immersion was asked to open; [previous] stays drawn until the plugin's surface
     * arrives, the open fails, or the wearer cancels it.
     */
    data class Launching(
        val pluginId: String,
        val token: Long,
        val deadlineMs: Long,
        val previous: SessionState,
    ) : SessionState

    /**
     * The earliest time a `Tick` has something to do in this state, or null. Only the top frame
     * holds a deadline: a pending request, its loading hint, its pending action, or the renewal
     * point of its lease.
     */
    fun nextDeadlineMs(): Long? = when (this) {
        Closed, is Root -> null
        is Opening -> sinceMs + PageSurfaceContract.GATE_MS
        is InPage -> frames.last().nextDeadlineMs()
        is Launching -> listOfNotNull(deadlineMs, previous.nextDeadlineMs()).min()
    }
}

private fun Frame.nextDeadlineMs(): Long? = when (val status = status) {
    is FrameStatus.Loading ->
        status.sinceMs + if (status.stillLoading) PageSurfaceContract.PAGE_TIMEOUT_MS else PageSurfaceContract.LOADING_HINT_MS
    is FrameStatus.Shown -> listOfNotNull(
        status.leaseUntilMs?.let { it - PageSurfaceContract.LEASE_RENEW_MS },
        invocation?.let { it.sinceMs + PageSurfaceContract.PAGE_TIMEOUT_MS },
    ).minOrNull()
    is FrameStatus.Unavailable -> null
}

/**
 * Everything the reducer remembers. Besides [state], it keeps the host facts
 * and root inputs that must survive a closed session, plus per-session
 * bookkeeping that is reset whenever a session opens or closes.
 */
internal data class SessionModel(
    val state: SessionState = SessionState.Closed,
    val activities: List<ActivityStop> = emptyList(),
    val queuedActivities: List<ActivityStop> = emptyList(),
    val preview: NoticePreview? = null,
    val notificationCount: Int = 0,
    val noticeArmed: Boolean = false,
    val editableFocused: Boolean = false,
    val linkLost: Boolean = false,
    /** Start times of contacts begun inside the gate whose classification has not arrived. */
    val gateContacts: List<Long> = emptyList(),
    val generation: Long = 0,
    val nextId: Long = 0,
    /** What the open session was opened over; [Underneath.Unknown] while closed. */
    val underneath: Underneath = Underneath.Unknown,
    /**
     * Opens the wearer cancelled, by plugin, with the deadline of each open: a surface that
     * plugin shows before it is closed unseen. It outlives the session that recorded it.
     */
    val cancelledOpen: Map<String, Long> = emptyMap(),
) {
    /**
     * The one time the runner's timer is set for: the state's own deadline and, while a session
     * is open, the expiry of a cancelled open. Never anything while closed.
     */
    fun nextDeadlineMs(): Long? {
        if (state == SessionState.Closed) return null
        return listOfNotNull(state.nextDeadlineMs(), cancelledOpen.values.minOrNull()).minOrNull()
    }
}

internal data class SessionTransition(val model: SessionModel, val effects: List<SessionEffect>)
