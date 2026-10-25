package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceResponse

/**
 * A validated page as retained by a frame; [byteSize] is the UTF-8 size of its serialized payload.
 * [extended] records that it was accepted with the launcher bundle's templates, so a retained copy
 * stays readable after the bundle goes; it is never a reason to act on it or to accept another.
 */
internal data class Snapshot(
    val page: PageSurfaceResponse.Page,
    val byteSize: Int = page.byteSize,
    val extended: Boolean = false,
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

/**
 * A reading position in a document: a segment and a character offset inside it. It names text,
 * not a screen, so it survives relayout, live revisions and cover/uncover.
 */
internal data class DocumentAnchor(val segmentId: String, val offset: Int = 0)

/**
 * A document, or text the renderer measured taller than the viewport, is read page by page
 * first; its rows (or an unavailable page's Retry and Back) are a separate mode reached by SELECT.
 */
internal enum class FrameMode { READING, ROWS }

/** A hub-owned step over a frame that consumes no wire frame. */
internal sealed interface FramePanel {
    val selected: Int

    /**
     * A plugin action waiting for the wearer, bound to what they were shown. Cancel is row 0 and
     * preselected; Confirm is row 1.
     */
    data class Confirm(
        val actionId: String,
        val label: String,
        val revision: Long,
        val providerEpoch: String?,
        override val selected: Int = CANCEL,
    ) : FramePanel

    /** The labelled step before an exclusive immersion replaces the screen. Open is row 0 and preselected. */
    data class Launch(val pluginId: String, val label: String, override val selected: Int = OPEN) : FramePanel

    /**
     * An activity command waiting for the wearer, bound to the activity revision ([seq]) the
     * card showed. Cancel is row 0 and preselected; Confirm is row 1.
     */
    data class ConfirmCommand(
        val stopId: String,
        val ownerPluginId: String,
        val actionId: String,
        val label: String,
        val seq: Long,
        override val selected: Int = CANCEL,
    ) : FramePanel

    /**
     * The hub's display page for [ownerPluginId]'s ambient size: Text, Minimap, Panel in
     * [RoutePresentation.SIZES] order, opened on [current] (the first while automatic). Once the
     * wearer swipes ([moved]) the island previews the selected size; a tap saves it, BACK cancels.
     */
    data class Display(
        val ownerPluginId: String,
        val current: AmbientSize?,
        override val selected: Int,
        val moved: Boolean = false,
    ) : FramePanel {
        val size: AmbientSize get() = RoutePresentation.SIZES[selected]
    }

    companion object {
        const val CANCEL = 0
        const val CONFIRM = 1
        const val OPEN = 0
        const val DISMISS = 1
    }
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
    /** The stable key of the selected row, when the resolver gives rows a stable identity. */
    val selectedKey: String? = null,
    /**
     * The label of a selected row that a newer revision removed. It stays at [selected], inert,
     * until the wearer steps away, so a completing tap never lands on its neighbour.
     */
    val tombstone: String? = null,
    val mode: FrameMode = FrameMode.ROWS,
    /**
     * The renderer found the retained content taller than the viewport as drawn: it is then read
     * page by page like a document. It holds until a new answer opens or retries the frame.
     */
    val overflowing: Boolean = false,
    val anchor: DocumentAnchor? = null,
    /** Where each laid-out page of the shown document starts, as the renderer measured it. */
    val pageStarts: List<DocumentAnchor> = emptyList(),
    val page: Int = 0,
    val panel: FramePanel? = null,
    /** The registration epoch the phone stamped on the answer that established this frame. */
    val providerEpoch: String? = null,
    /** When the retained snapshot arrived, on the session clock, so a stale summary can be dated. */
    val shownAtMs: Long? = null,
    /** The message that carried the retained snapshot needed SPP, so it is lost when SPP is. */
    val dataPlane: Boolean = false,
    /**
     * The wearer is choosing among commands on this frame: an activity row's commands on the
     * Activities page, or a media page's controls ([CommandFocus.MEDIA]).
     */
    val commandFocus: CommandFocus? = null,
    /**
     * The launchable owner of the activity this frame was opened from as its destination: the
     * frame then ends with the hub's own Open app row.
     */
    val openApp: OpenApp? = null,
    /** A conversation frame's focused composer button; null while the wearer reads. */
    val composerFocus: ComposerFocus? = null,
    /** A route frame's full-map excursion; null on the page itself. */
    val fullMap: FullMapMode? = null,
)

/** The hub's own Open app row at the end of a destination page, for a launchable owner. */
internal data class OpenApp(val pluginId: String, val label: String)

/**
 * The wearer is choosing among an activity card's commands: [key] names the focused slot (an
 * action id, or [ActivityCommands.OPEN_APP_KEY]) so it survives updates; [index] is where it was.
 */
internal data class CommandFocus(val stopId: String, val key: String, val index: Int) {
    companion object {
        /** The [stopId] of a media page's control focus; a space keeps it out of every wire id. */
        const val MEDIA = "page media"
    }
}

/** One activity command sent and not yet answered; one per owner at most. */
internal data class PendingCommand(
    val invocationId: String,
    val ownerPluginId: String,
    val activityId: String,
    val actionId: String,
    val seq: Long,
    val sinceMs: Long,
) {
    /** Past the phone's own deadline, with a margin for the hop back: unconfirmed from then on. */
    val deadlineMs: Long get() = sinceMs + ActivityCommands.RESULT_DEADLINE_MS
}

/** The last answer shown for an owner's command: `done`, `rejected`, `stale`, or `UNCONFIRMED_ACTION`. */
internal data class CommandOutcome(val actionId: String, val status: String, val message: String? = null)

/**
 * The host's acknowledgement that the frame showing the focused target [token] was drawn at
 * [atMs]. An effect needs a press that began strictly after it.
 */
internal data class ShownFocus(val token: String, val atMs: Long)

internal sealed interface SessionState {
    data object Closed : SessionState

    /** [root] is frozen when the triple tap is accepted, before the gate ends. */
    data class Opening(val sinceMs: Long, val root: Root) : SessionState

    /**
     * [focus] is an activity card's command focus; [panel] the confirmation or launch step taken
     * from it. Both belong to the card at [selected].
     */
    data class Root(
        val stops: List<RootStop>,
        val selected: Int = 0,
        val focus: CommandFocus? = null,
        val panel: FramePanel? = null,
    ) : SessionState

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
    /** The latest arrival's preview, as it was reported. */
    val preview: NoticePreview? = null,
    /** Notice instances that ever arrived; the root counts the [history] entries it may show. */
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
    /**
     * Activity commands awaiting their owner's result, by owner. They outlive a session that
     * closes, so a reopened card still shows the command as pending and never sends a second.
     */
    val pendingCommands: Map<String, PendingCommand> = emptyMap(),
    /** The last command answer per owner, shown on its card until the next command or session. */
    val commandOutcomes: Map<String, CommandOutcome> = emptyMap(),
    /** The latest draw acknowledgement of the focused target; see [SessionFocus]. */
    val shownFocus: ShownFocus? = null,
    /**
     * Counts changes of the launcher facts (negotiation, quick-action policy). A change can turn a
     * confirmed target into a quick one, so it is part of the focus identity.
     */
    val factsEpoch: Long = 0,
    /** Every notice the band showed, as it showed it; in memory only (plan 028a §6.3). */
    val history: NotificationHistory = NotificationHistory(),
    /** The one composer draft; it outlives root, Back and the session (plan 028a §7.1). */
    val draft: Draft? = null,
    /** The composer capture in progress, on the conversation frame it was started from. */
    val capture: Capture? = null,
    /** The last dictation ended without text: Type is focused first until the session closes. */
    val micUnavailable: Boolean = false,
    /** A compose send awaiting its result; never sent twice, never resent on reconnect. */
    val pendingSend: PendingSend? = null,
    /**
     * The replies handed to their app from one conversation frame, oldest first, each shown there
     * until that page carries it; a send from another frame starts over.
     */
    val handoffs: List<Handoff> = emptyList(),
    /**
     * The map view the wearer chose per route owner, kept for the session only and never written
     * to the ambient size preference (plan 028a §5.5).
     */
    val mapViews: Map<String, MapView> = emptyMap(),
    /** When the next notification shown leaves the 24-hour display bound, on the session clock. */
    val historyExpiryAtMs: Long? = null,
) {
    /**
     * The one time the runner's timer is set for: the state's own deadline and, while a session
     * is open, the expiry of a cancelled open, of a pending command or of a notification shown.
     * Never anything while closed.
     */
    fun nextDeadlineMs(): Long? {
        if (state == SessionState.Closed) return null
        return listOfNotNull(
            state.nextDeadlineMs(),
            cancelledOpen.values.minOrNull(),
            pendingCommands.values.minOfOrNull(PendingCommand::deadlineMs),
            capture?.deadlineMs,
            historyExpiryAtMs,
        ).minOrNull()
    }
}

internal data class SessionTransition(val model: SessionModel, val effects: List<SessionEffect>)
