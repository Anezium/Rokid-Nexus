// Adapted from the Rokid-Nexus fork by alvarosw (https://github.com/alvarosw/Rokid-Nexus), Apache-2.0.
// Only the plugin open handoff (rules 15-17) and the record of what a session covers (rule 18)
// come from the fork's HudStateMachine; the rest is this repository's PR1 reducer.
package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.CancelDeadline
import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.RestoreUnderneath
import com.anezium.rokidbus.glasses.session.SessionEffect.SendClosed
import com.anezium.rokidbus.glasses.session.SessionEffect.SendVisibility
import com.anezium.rokidbus.glasses.session.SessionEffect.ShowFrame
import com.anezium.rokidbus.glasses.session.SessionEffect.ShowRoot
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceInvocation
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONObject

/**
 * Pure Nexus session reducer for the session/page v1 contract.
 *
 * Nothing here schedules, posts, or draws: time arrives on events as `nowMs`
 * and every consequence is returned as a [SessionEffect] for a host.
 * Host facts (armed notice, focused editable, link) are opaque inputs; the
 * notice dispatcher's own claims are not re-implemented here.
 *
 * [isLocalPage] names the pages the glasses hub answers itself: they need no phone, so link loss
 * and provider invalidation never touch them, while provider frames keep every loss rule.
 */
internal class SessionReducer(
    private val itemResolver: PageItemResolver = DefaultPageItems,
    private val maxSnapshotTotalBytes: Int = PageSurfaceContract.MAX_SNAPSHOT_TOTAL_BYTES,
    private val isLocalPage: (String) -> Boolean = { false },
) {
    fun reduce(model: SessionModel, event: SessionEvent): SessionTransition {
        val transition = when (event) {
            SessionEvent.NoticeArmed -> keep(model.copy(noticeArmed = true))
            SessionEvent.NoticeCleared -> keep(model.copy(noticeArmed = false))
            is SessionEvent.EditableFocused -> keep(model.copy(editableFocused = event.focused))
            is SessionEvent.NoticeArrived -> onNoticeArrived(model, event.preview)
            is SessionEvent.ActivityStarted -> onActivityStarted(model, event.stop)
            is SessionEvent.ActivityUpdated -> onActivityUpdated(model, event.stop)
            is SessionEvent.ActivityEnded -> onActivityEnded(model, event.stopId)
            is SessionEvent.SurfaceShown -> onSurfaceShown(model, event)
            is SessionEvent.ProviderInvalidated -> invalidateOwner(model, event.ownerPluginId)
            SessionEvent.Abort -> abort(model)
            else -> {
                // Rule 17: a cancelled open is remembered only until its own deadline.
                val current = if (event is SessionEvent.Tick) model.expireCancelledOpens(event.nowMs) else model
                when (val state = current.state) {
                    SessionState.Closed -> onClosed(current, event)
                    is SessionState.Opening -> onOpening(current, state, event)
                    is SessionState.Root -> absorbGateClassification(current, event) ?: onRoot(current, state, event)
                    is SessionState.InPage -> absorbGateClassification(current, event) ?: onPage(current, state, event)
                    is SessionState.Launching ->
                        absorbGateClassification(current, event) ?: onLaunching(current, state, event)
                }
            }
        }
        return transition.copy(effects = transition.effects.ifEmpty { listOf(SessionEffect.None) })
    }

    /** D1: the root counts the one retained notice, while [SessionModel.notificationCount] counts arrivals. */
    private fun onNoticeArrived(model: SessionModel, preview: NoticePreview): SessionTransition {
        val retained = RootStops.retainedNotices(preview)
        val updated = model.copy(
            preview = preview,
            notificationCount = model.notificationCount + 1,
            state = model.state.mapRootStops { RootStops.withNotice(it, preview, retained) },
        )
        return rootBookkeeping(model, updated)
    }

    private fun onActivityStarted(model: SessionModel, stop: ActivityStop): SessionTransition =
        if (model.state == SessionState.Closed) {
            keep(model.copy(activities = model.activities.upsert(stop)))
        } else {
            keep(model.copy(queuedActivities = model.queuedActivities.upsert(stop)))
        }

    /** Values change in place; an activity that is not a member yet waits like a start would. */
    private fun onActivityUpdated(model: SessionModel, stop: ActivityStop): SessionTransition {
        val known = model.activities.any { it.id == stop.id }
        val updated = when {
            known -> model.copy(
                activities = model.activities.upsert(stop),
                state = model.state.mapRootStops { RootStops.withActivity(it, stop) },
            )
            model.state == SessionState.Closed -> model.copy(activities = model.activities.upsert(stop))
            else -> model.copy(queuedActivities = model.queuedActivities.upsert(stop))
        }
        return rootBookkeeping(model, updated)
    }

    private fun onActivityEnded(model: SessionModel, stopId: String): SessionTransition {
        val updated = model.copy(
            activities = model.activities.filterNot { it.id == stopId },
            queuedActivities = model.queuedActivities.filterNot { it.id == stopId },
            state = model.state.mapRootStops { RootStops.withEnded(it, stopId) },
        )
        return rootBookkeeping(model, updated)
    }

    private fun onClosed(model: SessionModel, event: SessionEvent): SessionTransition {
        if (event !is SessionEvent.TripleTap || model.noticeArmed || model.editableFocused) return keep(model)
        val root = SessionState.Root(
            RootStops.build(model.activities, model.preview, RootStops.retainedNotices(model.preview)),
        )
        return SessionTransition(
            model.copy(
                state = SessionState.Opening(event.nowMs, root),
                generation = model.generation + 1,
                linkLost = false,
                gateContacts = emptyList(),
                underneath = event.underneath,
            ),
            listOf(SessionEffect.ShowGate),
        )
    }

    private fun onOpening(model: SessionModel, state: SessionState.Opening, event: SessionEvent): SessionTransition {
        val deadline = state.sinceMs + PageSurfaceContract.GATE_MS
        return when (event) {
            is SessionEvent.Contact -> keep(
                if (event.nowMs < deadline) {
                    model.copy(gateContacts = model.gateContacts + event.nowMs)
                } else {
                    model.copy(gateContacts = emptyList())
                },
            )
            is SessionEvent.Enter, is SessionEvent.Back, is SessionEvent.Step ->
                keep(model.copy(gateContacts = model.gateContacts.drop(1)))
            is SessionEvent.Tick ->
                if (event.nowMs >= deadline) SessionTransition(model.copy(state = state.root), listOf(ShowRoot)) else keep(model)
            SessionEvent.LinkLost -> keep(model.copy(linkLost = true))
            SessionEvent.LinkRestored -> keep(model.copy(linkLost = false))
            else -> keep(model)
        }
    }

    private fun onRoot(model: SessionModel, state: SessionState.Root, event: SessionEvent): SessionTransition =
        when (event) {
            is SessionEvent.Step -> {
                val target = (state.selected + event.delta).coerceIn(0, state.stops.lastIndex.coerceAtLeast(0))
                if (target == state.selected) {
                    keep(model)
                } else {
                    SessionTransition(model.copy(state = state.copy(selected = target)), listOf(ShowRoot))
                }
            }
            // An ended activity keeps its place but opens nothing.
            is SessionEvent.Enter -> state.stops.getOrNull(state.selected)?.takeUnless { it.ended }
                ?.let { stop -> openFrame(model, state, emptyList(), stop.pageId, null, event.nowMs) }
                ?: keep(model)
            is SessionEvent.Back -> SessionTransition(closed(model), listOf(RestoreUnderneath(model.underneath)))
            SessionEvent.LinkLost -> keep(model.copy(linkLost = true))
            SessionEvent.LinkRestored -> keep(model.copy(linkLost = false))
            // A new contact after the gate proves the gate's gestures are over.
            is SessionEvent.Contact -> keep(model.copy(gateContacts = emptyList()))
            is SessionEvent.LocalLaunchFinished -> localLaunchFinished(model, state, event)
            // Triple tap stays at the root; late page traffic never reopens a frame.
            else -> keep(model)
        }

    private fun onPage(model: SessionModel, state: SessionState.InPage, event: SessionEvent): SessionTransition =
        when (event) {
            is SessionEvent.Step -> step(model, state, event.delta)
            is SessionEvent.Enter -> enter(model, state, event.nowMs)
            is SessionEvent.Back -> back(model, state, event.nowMs)
            is SessionEvent.TripleTap -> SessionTransition(
                model.copy(state = state.root),
                state.frames.withIndex().reversed().filter { model.canReach(it.value) }
                    .map { closedEffect(model, it.value, it.index, CLOSE_BACK) } + ShowRoot,
            )
            is SessionEvent.Tick -> tick(model, state, event.nowMs)
            is SessionEvent.PageResponse -> response(model, state, event.payload, event.nowMs)
            is SessionEvent.PageResult -> result(model, state, event.payload, event.nowMs)
            is SessionEvent.DocumentPaged -> documentPaged(model, state, event)
            is SessionEvent.LocalLaunchFinished -> localLaunchFinished(model, state, event)
            SessionEvent.LinkLost -> SessionTransition(model.copy(linkLost = true, state = state.linkLost()), listOf(ShowFrame))
            // Restoration replays nothing; the wearer's next Retry does.
            SessionEvent.LinkRestored -> keep(model.copy(linkLost = false))
            is SessionEvent.Contact -> keep(model.copy(gateContacts = emptyList()))
            else -> keep(model)
        }

    /**
     * Rule 15. The previous screen stays drawn while the plugin answers; only BACK acts, and it
     * cancels the open so the plugin's late surface is closed unseen (rule 16).
     */
    private fun onLaunching(model: SessionModel, state: SessionState.Launching, event: SessionEvent): SessionTransition =
        when (event) {
            is SessionEvent.Back -> SessionTransition(
                model.copy(
                    state = state.previous,
                    cancelledOpen = model.expireCancelledOpens(event.nowMs).cancelledOpen +
                        (state.pluginId to state.deadlineMs),
                ),
                listOf(CancelDeadline),
            )
            is SessionEvent.OpenFailed ->
                if (event.token == state.token) openFailed(model, state, event.reason) else keep(model)
            is SessionEvent.Tick ->
                if (event.nowMs >= state.deadlineMs) {
                    openFailed(model, state, OpenFailure.TIMEOUT)
                } else {
                    underPrevious(model, state, event)
                }
            SessionEvent.LinkLost -> keep(
                model.copy(
                    linkLost = true,
                    state = state.copy(previous = (state.previous as? SessionState.InPage)?.linkLost() ?: state.previous),
                ),
            )
            SessionEvent.LinkRestored -> keep(model.copy(linkLost = false))
            is SessionEvent.Contact -> keep(model.copy(gateContacts = emptyList()))
            // P4: the phone invalidating an owner reaches the page underneath; nothing else does.
            is SessionEvent.PageResponse -> {
                val previous = state.previous as? SessionState.InPage
                if (!model.linkLost && previous != null && isInvalidation(model, previous, event.payload)) {
                    invalidateOwner(model, event.payload.optString(PageSurfaceContract.FIELD_PLUGIN_ID))
                } else {
                    keep(model)
                }
            }
            // A second selection never sends a second open; late page traffic waits for the return.
            else -> keep(model)
        }

    private fun launch(model: SessionModel, state: SessionState.InPage, pluginId: String, nowMs: Long): SessionTransition {
        val token = model.nextId
        val deadline = nowMs + OPEN_TIMEOUT_MS
        return SessionTransition(
            model.copy(
                state = SessionState.Launching(pluginId, token, deadline, state),
                nextId = model.nextId + 1,
                // Opening the plugin again makes its next surface this open's answer, not a late one.
                cancelledOpen = model.cancelledOpen - pluginId,
            ),
            listOf(SessionEffect.SendLauncherOpen(pluginId, token), SessionEffect.ScheduleDeadline(deadline)),
        )
    }

    /** Rule 17: back to the screen the open was made from, with the reason on the status line. */
    private fun openFailed(model: SessionModel, state: SessionState.Launching, reason: OpenFailure): SessionTransition =
        SessionTransition(
            model.copy(state = state.previous),
            listOf(SessionEffect.ShowStatus(SessionStatus.OpenFailed(state.pluginId, reason))),
        )

    /** A tick before the open deadline keeps the screen underneath alive: its lease, its pending action. */
    private fun underPrevious(
        model: SessionModel,
        state: SessionState.Launching,
        event: SessionEvent.Tick,
    ): SessionTransition {
        val previous = state.previous as? SessionState.InPage ?: return keep(model)
        val transition = tick(model.copy(state = previous), previous, event.nowMs)
        return transition.copy(model = transition.model.copy(state = state.copy(previous = transition.model.state)))
    }

    /**
     * Rule 16. The surface of the open in progress replaces the session; the base it covered is
     * not restored. A surface from a cancelled open is closed before the wearer sees it, unless it
     * is the very surface the session was opened over (rule 18).
     */
    private fun onSurfaceShown(model: SessionModel, event: SessionEvent.SurfaceShown): SessionTransition {
        val state = model.state
        if (state is SessionState.Launching && state.pluginId == event.ownerPluginId) {
            return SessionTransition(closed(model), closedFrames(model, state.previous))
        }
        val current = model.expireCancelledOpens(event.nowMs)
        val base = (model.underneath as? Underneath.NexusSurface)?.surfaceId
        if (event.ownerPluginId in current.cancelledOpen && event.surfaceId != base) {
            return SessionTransition(
                current.copy(cancelledOpen = current.cancelledOpen - event.ownerPluginId),
                listOf(SessionEffect.CloseSurface(event.surfaceId, SURFACE_OPEN_CANCELLED)),
            )
        }
        return keep(current)
    }

    /** Rule 19: the host takes the session down from wherever it is. */
    private fun abort(model: SessionModel): SessionTransition {
        val state = model.state
        if (state == SessionState.Closed) return keep(model)
        return SessionTransition(
            closed(model),
            closedFrames(model, state) + RestoreUnderneath(model.underneath) + CancelDeadline,
        )
    }

    /** The frames a closing session tells their providers about, top first. */
    private fun closedFrames(model: SessionModel, state: SessionState): List<SessionEffect> {
        val frames = when (state) {
            is SessionState.InPage -> state.frames
            is SessionState.Launching -> (state.previous as? SessionState.InPage)?.frames.orEmpty()
            else -> emptyList()
        }
        return frames.withIndex().reversed().filter { model.canReach(it.value) }
            .map { closedEffect(model, it.value, it.index, CLOSE_SESSION_CLOSED) }
    }

    /** Leaves the session: queued activities join the live ones and per-session bookkeeping resets. */
    private fun closed(model: SessionModel): SessionModel = model.copy(
        state = SessionState.Closed,
        activities = model.queuedActivities.fold(model.activities) { live, stop -> live.upsert(stop) },
        queuedActivities = emptyList(),
        linkLost = false,
        gateContacts = emptyList(),
        underneath = Underneath.Unknown,
    )

    /**
     * A native app or the camera opened from the session: on success the session closes cleanly,
     * it never waits for a surface; on failure the wearer stays where they were, told why.
     */
    private fun localLaunchFinished(
        model: SessionModel,
        state: SessionState,
        event: SessionEvent.LocalLaunchFinished,
    ): SessionTransition =
        if (event.launched) {
            SessionTransition(closed(model), closedFrames(model, state) + RestoreUnderneath(model.underneath))
        } else {
            SessionTransition(model, listOf(SessionEffect.ShowStatus(SessionStatus.LaunchFailed(event.label))))
        }

    private fun step(model: SessionModel, state: SessionState.InPage, delta: Int): SessionTransition {
        val top = state.frames.last()
        top.panel?.let { panel ->
            val target = (panel.selected + delta).coerceIn(0, 1)
            if (target == panel.selected) return keep(model)
            val next = when (panel) {
                is FramePanel.Confirm -> panel.copy(selected = target)
                is FramePanel.Launch -> panel.copy(selected = target)
            }
            return SessionTransition(model.copy(state = state.withTop(top.copy(panel = next))), listOf(ShowFrame))
        }
        val status = top.status
        if (status is FrameStatus.Shown && top.mode == FrameMode.READING) {
            // A document pages through its own measured layout; bounded at both ends.
            if (top.pageStarts.isEmpty()) return keep(model)
            val target = (top.page + delta).coerceIn(0, top.pageStarts.lastIndex)
            if (target == top.page) return keep(model)
            val frame = top.copy(page = target, anchor = top.pageStarts[target])
            return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        val entries = entriesOf(top)
        if (top.tombstone != null && status is FrameStatus.Shown) {
            // Stepping off a removed row lands on the row on that side of it, never across it.
            val at = top.selected
            val target = if (delta > 0) at else at - 1
            if (target !in entries.indices) return keep(model)
            val frame = top.copy(selected = target, selectedKey = entries[target].key, tombstone = null)
            return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        val count = if (status is FrameStatus.Shown || status is FrameStatus.Loading) entries.size else UNAVAILABLE_ITEMS.size
        val target = (top.selected + delta).coerceIn(0, (count - 1).coerceAtLeast(0))
        if (target == top.selected) return keep(model)
        val frame = top.copy(selected = target, selectedKey = entries.getOrNull(target)?.key?.takeIf { status is FrameStatus.Shown })
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun enter(model: SessionModel, state: SessionState.InPage, nowMs: Long): SessionTransition {
        val top = state.frames.last()
        return when (val status = top.status) {
            // One request per frame and one action per gesture; nothing is queued.
            is FrameStatus.Loading -> keep(model)
            is FrameStatus.Unavailable -> when (UNAVAILABLE_ITEMS.getOrNull(top.selected)) {
                PageItem.Retry -> retry(model, state, status, nowMs)
                PageItem.Back -> pop(model, state, nowMs)
                else -> keep(model)
            }
            is FrameStatus.Shown -> {
                top.panel?.let { return enterPanel(model, state, top, status, it, nowMs) }
                if (top.invocation != null) return keep(model)
                if (top.mode == FrameMode.READING) {
                    // SELECT on a document reaches its rows, when it has any; reading never fires one.
                    if (entriesOf(top).isEmpty()) return keep(model)
                    return SessionTransition(
                        model.copy(state = state.withTop(top.copy(mode = FrameMode.ROWS, selected = 0, selectedKey = entriesOf(top).first().key))),
                        listOf(ShowFrame),
                    )
                }
                if (top.tombstone != null) return keep(model)
                when (val item = entriesOf(top).getOrNull(top.selected)?.item) {
                    is PageItem.OpenPage -> openFrame(model, state.root, state.frames, item.pageId, item.paramsJson, nowMs)
                    is PageItem.Invoke -> invoke(model, state, status, item.actionId, nowMs)
                    is PageItem.Launch -> launch(model, state, item.pluginId, nowMs)
                    is PageItem.Confirm -> SessionTransition(
                        model.copy(
                            state = state.withTop(
                                top.copy(panel = FramePanel.Confirm(item.actionId, item.label, status.revision, top.providerEpoch)),
                            ),
                        ),
                        listOf(ShowFrame),
                    )
                    is PageItem.LaunchPrompt -> SessionTransition(
                        model.copy(state = state.withTop(top.copy(panel = FramePanel.Launch(item.pluginId, item.label)))),
                        listOf(ShowFrame),
                    )
                    is PageItem.LocalLaunch -> SessionTransition(model, listOf(SessionEffect.LaunchLocal(item.key, item.label)))
                    PageItem.Back -> pop(model, state, nowMs)
                    else -> keep(model)
                }
            }
        }
    }

    /** G5/G6: only the panel's own choice acts, and a confirmation only for what was shown. */
    private fun enterPanel(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        status: FrameStatus.Shown,
        panel: FramePanel,
        nowMs: Long,
    ): SessionTransition {
        val cleared = state.withTop(top.copy(panel = null))
        return when (panel) {
            is FramePanel.Confirm -> {
                if (panel.selected != FramePanel.CONFIRM) {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                } else if (panel.revision != status.revision || panel.providerEpoch != top.providerEpoch || top.invocation != null) {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                } else {
                    invoke(model.copy(state = cleared), cleared, status, panel.actionId, nowMs)
                }
            }
            is FramePanel.Launch ->
                if (panel.selected == FramePanel.OPEN) {
                    launch(model.copy(state = cleared), cleared, panel.pluginId, nowMs)
                } else {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                }
        }
    }

    /** BACK closes a panel, then leaves a document's rows for its text, then pops the frame. */
    private fun back(model: SessionModel, state: SessionState.InPage, nowMs: Long): SessionTransition {
        val top = state.frames.last()
        if (top.panel != null) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(panel = null))), listOf(ShowFrame))
        }
        val status = top.status
        if (status is FrameStatus.Shown && top.mode == FrameMode.ROWS && itemResolver.isDocument(status.snapshot.page)) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(mode = FrameMode.READING))), listOf(ShowFrame))
        }
        return pop(model, state, nowMs)
    }

    /** Opens the frame above [below]; the root counts as frame 0 in the six-frame budget. */
    private fun openFrame(
        model: SessionModel,
        root: SessionState.Root,
        below: List<Frame>,
        pageId: String,
        paramsJson: String?,
        nowMs: Long,
    ): SessionTransition {
        if (below.size + 1 >= PageSurfaceContract.MAX_FRAMES) {
            // Nothing is allocated: the close names no frame and is never sent.
            return SessionTransition(
                model,
                listOf(SessionEffect.ShowStatus(SessionStatus.FrameLimit), SendClosed(pageId, CLOSE_FRAME_LIMIT)),
            )
        }
        val frameIndex = below.size + 1
        if (model.linkLost && !isLocalPage(pageId)) {
            val frame = Frame(pageId, FrameStatus.Unavailable(null, UNAVAILABLE_LINK_LOST), paramsJson = paramsJson)
            return SessionTransition(model.copy(state = SessionState.InPage(below + frame, root)), listOf(ShowFrame))
        }
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, frameIndex, pageId, REASON_OPEN, paramsJson)
        if (!isWireValid(request)) return keep(model)
        val covered = below.lastOrNull()?.let { coverFrame(model, it, below.lastIndex) }
        val frame = Frame(pageId, FrameStatus.Loading(nowMs, requestId, REASON_OPEN), paramsJson = paramsJson)
        val frames = below.dropLast(if (covered == null) 0 else 1) + listOfNotNull(covered?.first) + frame
        return SessionTransition(
            counted.copy(state = SessionState.InPage(frames, root)),
            listOfNotNull(covered?.second) + requestEffect(request, nowMs, null) + ShowFrame,
        )
    }

    private fun coverFrame(model: SessionModel, frame: Frame, index: Int): Pair<Frame, SessionEffect> {
        val status = frame.status
        val next = (if (status is FrameStatus.Shown) frame.copy(status = status.copy(leaseUntilMs = null)) else frame)
            .copy(panel = null)
        return next to SendVisibility(frame.pageId, false, null, identity(model, index))
    }

    private fun retry(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Unavailable,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        if (model.linkLost && !isLocalPage(top.pageId)) return keep(model)
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, state.frames.size, top.pageId, REASON_RETRY, top.paramsJson)
        val frame = top.copy(
            status = FrameStatus.Loading(nowMs, requestId, REASON_RETRY, previous = status.lastSnapshot),
            selected = 0,
            selectedKey = null,
            tombstone = null,
            panel = null,
        )
        return SessionTransition(
            counted.copy(state = state.withTop(frame)),
            listOf(requestEffect(request, nowMs, top.revisionFloor), ShowFrame),
        )
    }

    private fun invoke(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Shown,
        actionId: String,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        val (invocationId, counted) = model.nextId("i")
        val action = PageSurfaceInvocation(
            invocationId, model.generation, state.frames.size, top.pageId, status.revision, actionId,
        )
        val frame = top.copy(invocation = PendingInvocation(invocationId, actionId, nowMs), outcome = null)
        return SessionTransition(
            counted.copy(state = state.withTop(frame)),
            listOf(SessionEffect.SendAction(action), ShowFrame),
        )
    }

    private fun pop(model: SessionModel, state: SessionState.InPage, nowMs: Long): SessionTransition {
        val popped = state.frames.last()
        val closed = listOfNotNull(
            closedEffect(model, popped, state.frames.lastIndex, CLOSE_BACK).takeIf { model.canReach(popped) },
        )
        val below = state.frames.dropLast(1)
        val uncovered = below.lastOrNull()
            ?: return SessionTransition(model.copy(state = state.root), closed + ShowRoot)
        if (!model.canReach(uncovered)) {
            return SessionTransition(model.copy(state = state.copy(frames = below)), closed + ShowFrame)
        }
        // Returning to a frame starts a fresh validation while its snapshot stays displayed.
        val lease = nowMs + PageSurfaceContract.LEASE_MS
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, below.size, uncovered.pageId, REASON_REFRESH, uncovered.paramsJson)
        val frame = uncovered.copy(
            status = FrameStatus.Loading(
                nowMs, requestId, REASON_REFRESH, previous = uncovered.retained(), leaseUntilMs = lease,
            ),
            invocation = null,
            panel = null,
        )
        return SessionTransition(
            counted.copy(state = state.copy(frames = below.dropLast(1) + frame)),
            closed + SendVisibility(uncovered.pageId, true, lease, identity(model, below.lastIndex)) +
                requestEffect(request, nowMs, uncovered.revisionFloor) + ShowFrame,
        )
    }

    private fun tick(model: SessionModel, state: SessionState.InPage, nowMs: Long): SessionTransition {
        val top = state.frames.last()
        return when (val status = top.status) {
            is FrameStatus.Loading -> when {
                status.expiredAt(nowMs) -> timeOut(model, state, status)
                !status.stillLoading && nowMs >= status.sinceMs + PageSurfaceContract.LOADING_HINT_MS ->
                    SessionTransition(
                        model.copy(state = state.withTop(top.copy(status = status.copy(stillLoading = true)))),
                        listOf(ShowFrame),
                    )
                else -> keep(model)
            }
            is FrameStatus.Shown -> {
                var frame = top
                val effects = mutableListOf<SessionEffect>()
                val lease = status.leaseUntilMs
                if (lease != null && nowMs >= lease - PageSurfaceContract.LEASE_RENEW_MS) {
                    val renewed = nowMs + PageSurfaceContract.LEASE_MS
                    frame = frame.copy(status = status.copy(leaseUntilMs = renewed))
                    effects += SendVisibility(top.pageId, true, renewed, identity(model, state.frames.lastIndex))
                }
                val invocation = top.invocation
                if (invocation != null && invocation.expiredAt(nowMs)) {
                    frame = frame.unconfirmed(invocation)
                    effects += ShowFrame
                }
                SessionTransition(model.copy(state = state.withTop(frame)), effects)
            }
            is FrameStatus.Unavailable -> keep(model)
        }
    }

    private fun timeOut(model: SessionModel, state: SessionState.InPage, status: FrameStatus.Loading): SessionTransition {
        val frame = state.frames.last().copy(
            status = FrameStatus.Unavailable(status.previous, PageSurfaceContract.ERROR_PAGE_TIMEOUT),
            selected = 0,
            panel = null,
        )
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun response(
        model: SessionModel,
        state: SessionState.InPage,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        if (model.linkLost && !isLocalPage(top.pageId)) return keep(model)
        if (isInvalidation(model, state, payload)) {
            return invalidateOwner(model, payload.optString(PageSurfaceContract.FIELD_PLUGIN_ID))
        }
        return when (val status = top.status) {
            // The deadline is absolute: a reply arriving after it loses even if no tick ran yet.
            is FrameStatus.Loading ->
                if (status.expiredAt(nowMs)) timeOut(model, state, status) else pendingResponse(model, state, status, payload, nowMs)
            is FrameStatus.Shown -> liveRevision(model, state, status, payload, nowMs)
            is FrameStatus.Unavailable -> keep(model)
        }
    }

    /**
     * P4: a hub-stamped `PAGE_UNAVAILABLE` naming a retained frame of its owner, when it answers
     * no pending request on top, is the phone hub invalidating that owner's work.
     */
    private fun isInvalidation(model: SessionModel, state: SessionState.InPage, payload: JSONObject): Boolean {
        if (payload.opt("error") != PageSurfaceContract.ERROR_PAGE_UNAVAILABLE) return false
        val owner = payload.opt(PageSurfaceContract.FIELD_PLUGIN_ID) as? String ?: return false
        val correlation = correlationOf(payload) ?: return false
        if (correlation.sessionGeneration != model.generation) return false
        val top = state.frames.last()
        val topPending = (top.status as? FrameStatus.Loading)?.requestId
        if (correlation.frameIndex == state.frames.size && correlation.requestId == topPending) return false
        val frame = state.frames.getOrNull(correlation.frameIndex - 1) ?: return false
        val requestId = when (val status = frame.status) {
            is FrameStatus.Loading -> status.requestId
            is FrameStatus.Shown -> status.requestId
            is FrameStatus.Unavailable -> null
        }
        return frame.pageId == correlation.pageId && requestId == correlation.requestId &&
            !isLocalPage(frame.pageId) && PageTemplateContract.namespaceOf(frame.pageId) == owner
    }

    /** Every frame of [owner] keeps its snapshot, inert; its requests, actions, leases and panels end. */
    private fun invalidateOwner(model: SessionModel, owner: String): SessionTransition {
        fun invalidated(page: SessionState.InPage): SessionState.InPage = page.copy(
            frames = page.frames.map { frame ->
                if (isLocalPage(frame.pageId) || PageTemplateContract.namespaceOf(frame.pageId) != owner) {
                    frame
                } else {
                    frame.copy(
                        status = FrameStatus.Unavailable(frame.retained(), PageSurfaceContract.ERROR_PAGE_UNAVAILABLE),
                        selected = 0,
                        invocation = null,
                        panel = null,
                    )
                }
            },
        )
        return when (val state = model.state) {
            is SessionState.InPage -> {
                val next = invalidated(state)
                if (next == state) keep(model) else SessionTransition(model.copy(state = next), listOf(ShowFrame))
            }
            is SessionState.Launching -> {
                val previous = state.previous as? SessionState.InPage ?: return keep(model)
                keep(model.copy(state = state.copy(previous = invalidated(previous))))
            }
            else -> keep(model)
        }
    }

    private fun pendingResponse(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Loading,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        val pending = request(model, status.requestId, state.frames.size, top.pageId, status.reason, top.paramsJson)
        val failure = when (val result = PageSurfaceContract.validateResponse(payload, pending)) {
            is PageSurfaceValidationResult.Valid -> when (val response = result.value) {
                is PageSurfaceResponse.Error -> response.code
                is PageSurfaceResponse.Page ->
                    if (itemResolver.accepts(response)) {
                        return acceptPending(model, state, status, response, epochOf(payload), nowMs)
                    } else {
                        UNAVAILABLE_UNSUPPORTED
                    }
            }
            is PageSurfaceValidationResult.Invalid -> {
                val ours = result.error != PageSurfaceContract.ERROR_STALE_GENERATION &&
                    payload.opt("requestId") == status.requestId &&
                    (payload.opt("sessionGeneration") as? Number)?.toLong() == model.generation
                if (!ours) return keep(model)
                result.error
            }
        }
        val frame = top.copy(status = FrameStatus.Unavailable(status.previous, failure), selected = 0, panel = null)
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun acceptPending(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Loading,
        page: PageSurfaceResponse.Page,
        epoch: String?,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        // Revisions stay monotonic across refresh and retry; a rollback is dropped as stale.
        val floor = top.revisionFloor
        if (floor != null && page.revision < floor) return keep(model)
        val kept = status.previous?.takeIf { page.revision == floor }
        val snapshot = kept ?: Snapshot(page)
        // The first display of a frame starts its lease; an uncovered frame already holds one.
        val lease = status.leaseUntilMs ?: (nowMs + PageSurfaceContract.LEASE_MS)
        val granted = listOfNotNull(
            SendVisibility(top.pageId, true, lease, identity(model, state.frames.lastIndex))
                .takeIf { status.leaseUntilMs == null },
        )
        val refresh = status.reason == REASON_REFRESH
        val shown = top.copy(
            status = FrameStatus.Shown(snapshot, page.revision, lease, status.requestId),
            selected = if (refresh) top.selected else 0,
            revisionFloor = page.revision,
            providerEpoch = epoch ?: top.providerEpoch,
            shownAtMs = if (kept != null) top.shownAtMs else nowMs,
            panel = null,
        )
        val document = itemResolver.isDocument(snapshot.page)
        val frame = if (refresh) {
            reselect(shown, top, snapshot.page).let { if (it.mode == FrameMode.READING && !document) it.copy(mode = FrameMode.ROWS) else it }
        } else {
            val entries = itemResolver.entries(snapshot.page)
            shown.copy(
                selectedKey = entries.firstOrNull()?.key,
                tombstone = null,
                mode = if (document) FrameMode.READING else FrameMode.ROWS,
                anchor = null,
                page = 0,
                pageStarts = emptyList(),
            ).let { it.copy(selected = it.selected.coerceIn(0, entries.lastIndex.coerceAtLeast(0))) }
        }
        return SessionTransition(
            model.copy(state = state.copy(frames = evictCovered(state.frames.dropLast(1) + frame))),
            granted + ShowFrame,
        )
    }

    private fun liveRevision(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Shown,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        val page = (PageSurfaceContract.validateResponse(payload) as? PageSurfaceValidationResult.Valid)
            ?.value as? PageSurfaceResponse.Page ?: return keep(model)
        val lease = status.leaseUntilMs
        val accepted = page.correlation == PageCorrelation(status.requestId, model.generation, state.frames.size, top.pageId) &&
            lease != null && nowMs < lease && status.snapshot.page.live && page.revision > status.revision &&
            sameEpoch(top, payload) && itemResolver.accepts(page)
        if (!accepted) return keep(model)
        return replaceTopSnapshot(model, state, top, status, page, nowMs)
    }

    private fun result(
        model: SessionModel,
        state: SessionState.InPage,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        if (model.linkLost && !isLocalPage(top.pageId)) return keep(model)
        val invocation = top.invocation ?: return keep(model)
        if (invocation.expiredAt(nowMs)) {
            return SessionTransition(model.copy(state = state.withTop(top.unconfirmed(invocation))), listOf(ShowFrame))
        }
        val result = (PageSurfaceContract.validateResult(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return keep(model)
        if (invocation.invocationId != result.invocationId || !sameEpoch(top, payload)) return keep(model)
        val settled = top.copy(invocation = null, outcome = ActionOutcome(invocation.actionId, result.status, result.message))
        val status = settled.status
        val replacement = result.replacement
        if (result.status == RESULT_DONE && status is FrameStatus.Shown && replacement != null &&
            replacement.correlation.sessionGeneration == model.generation &&
            replacement.correlation.frameIndex == state.frames.size &&
            replacement.correlation.pageId == top.pageId &&
            replacement.revision > status.revision &&
            itemResolver.accepts(replacement)
        ) {
            return replaceTopSnapshot(model, state, settled, status, replacement, nowMs)
        }
        // A rejected or stale action keeps the page exactly as the wearer saw it.
        return SessionTransition(model.copy(state = state.withTop(settled)), listOf(ShowFrame))
    }

    private fun replaceTopSnapshot(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        status: FrameStatus.Shown,
        page: PageSurfaceResponse.Page,
        nowMs: Long,
    ): SessionTransition {
        val replaced = top.copy(
            status = status.copy(snapshot = Snapshot(page), revision = page.revision),
            revisionFloor = page.revision,
            shownAtMs = nowMs,
            // A confirmation was for the revision the wearer saw; a new one cancels it.
            panel = null,
        )
        val frame = reselect(replaced, top, page)
        return SessionTransition(
            model.copy(state = state.copy(frames = evictCovered(state.frames.dropLast(1) + frame))),
            listOf(ShowFrame),
        )
    }

    /**
     * G3: the selection follows its row's stable key into new content. A row that is gone stays
     * selected as an inert tombstone at its place. Positional resolvers keep the clamped index.
     */
    private fun reselect(next: Frame, before: Frame, page: PageSurfaceResponse.Page): Frame {
        val entries = itemResolver.entries(page)
        val withLayout = next.copy(pageStarts = emptyList())
        if (!itemResolver.hasStableKeys()) {
            return withLayout.copy(selected = next.selected.coerceIn(0, entries.lastIndex.coerceAtLeast(0)))
        }
        val key = before.selectedKey
        if (before.tombstone != null) {
            return withLayout.copy(selected = before.selected.coerceIn(0, entries.size))
        }
        if (key == null) {
            return withLayout.copy(
                selected = next.selected.coerceIn(0, entries.lastIndex.coerceAtLeast(0)),
                selectedKey = entries.getOrNull(next.selected)?.key,
            )
        }
        val index = entries.indexOfFirst { it.key == key }
        if (index >= 0) return withLayout.copy(selected = index, selectedKey = key, tombstone = null)
        val label = before.retained()?.let { previous -> itemResolver.entries(previous.page).firstOrNull { it.key == key }?.label }
        return withLayout.copy(
            selected = before.selected.coerceIn(0, entries.size),
            selectedKey = null,
            tombstone = label?.ifBlank { null } ?: REMOVED_ROW_LABEL,
        )
    }

    /** The renderer's measured pages of the shown document; never an action, never another frame's. */
    private fun documentPaged(
        model: SessionModel,
        state: SessionState.InPage,
        event: SessionEvent.DocumentPaged,
    ): SessionTransition {
        val top = state.frames.last()
        val status = top.status as? FrameStatus.Shown ?: return keep(model)
        val current = event.frame == FrameIdentity(model.generation, state.frames.size) &&
            event.pageId == top.pageId && event.revision == status.revision && event.pageStarts.isNotEmpty()
        if (!current) return keep(model)
        val page = event.anchorPage.coerceIn(0, event.pageStarts.lastIndex)
        val frame = top.copy(pageStarts = event.pageStarts, page = page, anchor = top.anchor ?: event.pageStarts.first())
        return if (frame == top) keep(model) else SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    /** Drops the oldest covered snapshots until the retained total fits; page ids stay for a later refresh. */
    private fun evictCovered(frames: List<Frame>): List<Frame> {
        val result = frames.toMutableList()
        var total = result.sumOf { it.retained()?.byteSize ?: 0 }
        for (index in 0 until result.lastIndex) {
            if (total <= maxSnapshotTotalBytes) break
            val retained = result[index].retained() ?: continue
            result[index] = result[index].copy(status = FrameStatus.Unavailable(null, UNAVAILABLE_EVICTED))
            total -= retained.byteSize
        }
        return result
    }

    private fun entriesOf(frame: Frame): List<PageEntry> = when (val status = frame.status) {
        is FrameStatus.Shown -> itemResolver.entries(status.snapshot.page)
        is FrameStatus.Loading -> status.previous?.let { itemResolver.entries(it.page) }.orEmpty()
        is FrameStatus.Unavailable -> UNAVAILABLE_ITEMS.mapIndexed { index, item -> PageEntry("#$index", item) }
    }

    /**
     * A classification completing a contact begun inside the gate is absorbed
     * however late it arrives; ownership never expires by time. It is settled by
     * that classification or dropped when a new contact begins after the gate.
     */
    private fun absorbGateClassification(model: SessionModel, event: SessionEvent): SessionTransition? {
        val classification = event is SessionEvent.Enter || event is SessionEvent.Back || event is SessionEvent.Step
        if (!classification || model.gateContacts.isEmpty()) return null
        return SessionTransition(model.copy(gateContacts = model.gateContacts.drop(1)), listOf(SessionEffect.None))
    }

    private fun rootBookkeeping(before: SessionModel, after: SessionModel): SessionTransition =
        SessionTransition(
            after,
            if (after.state is SessionState.Root && after.state != before.state) listOf(ShowRoot) else emptyList(),
        )

    private fun request(
        model: SessionModel,
        requestId: String,
        frameIndex: Int,
        pageId: String,
        reason: String,
        paramsJson: String?,
    ) = PageSurfaceRequest(PageCorrelation(requestId, model.generation, frameIndex, pageId), reason, paramsJson)

    private fun requestEffect(request: PageSurfaceRequest, nowMs: Long, minimumRevision: Long?) =
        RequestPage(request, nowMs + PageSurfaceContract.PAGE_TIMEOUT_MS, minimumRevision)

    private fun closedEffect(model: SessionModel, frame: Frame, index: Int, reason: String) =
        SendClosed(frame.pageId, reason, identity(model, index))

    /** `frames[index]` is wire frame `index + 1`. */
    private fun identity(model: SessionModel, index: Int) = FrameIdentity(model.generation, index + 1)

    private fun isWireValid(request: PageSurfaceRequest): Boolean =
        runCatching { PageSurfaceContract.validateRequest(request.toPayload()) }.getOrNull() is
            PageSurfaceValidationResult.Valid

    private fun keep(model: SessionModel) = SessionTransition(model, emptyList())

    /**
     * Frames invalidated by link loss or by their provider's invalidation stay inert: nothing is
     * sent for them, even after the link returns, until the wearer's own Retry.
     */
    private fun SessionModel.canReach(frame: Frame): Boolean {
        if (isLocalPage(frame.pageId)) return true
        val status = frame.status
        return !linkLost && !(status is FrameStatus.Unavailable && status.reason in INERT_REASONS)
    }

    /** Link loss makes every provider frame unavailable and drops leases and pending actions (rule 12). */
    private fun SessionState.InPage.linkLost() = copy(
        frames = frames.map {
            if (isLocalPage(it.pageId)) {
                it
            } else {
                it.copy(
                    status = FrameStatus.Unavailable(it.retained(), UNAVAILABLE_LINK_LOST),
                    selected = 0,
                    invocation = null,
                    panel = null,
                )
            }
        },
    )

    companion object {
        const val REASON_OPEN = "open"
        const val REASON_REFRESH = "refresh"
        const val REASON_RETRY = "retry"
        const val CLOSE_BACK = "back"
        const val CLOSE_SESSION_CLOSED = "session_closed"
        const val CLOSE_FRAME_LIMIT = "frame_limit"
        const val RESULT_DONE = "done"
        const val UNAVAILABLE_LINK_LOST = "link_lost"
        const val UNAVAILABLE_EVICTED = "evicted"

        /** A valid envelope whose typed body this hub cannot draw. */
        const val UNAVAILABLE_UNSUPPORTED = "unsupported"
        const val REMOVED_ROW_LABEL = "Removed"
        val UNAVAILABLE_ITEMS: List<PageItem> = listOf(PageItem.Retry, PageItem.Back)
        private val INERT_REASONS = setOf(UNAVAILABLE_LINK_LOST, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)

        /** The fork's F-4 bound, equal to the ring's surface handoff. */
        const val OPEN_TIMEOUT_MS = 10_000L
        const val SURFACE_OPEN_CANCELLED = "OPEN_CANCELLED"
    }
}

private fun SessionModel.nextId(kind: String): Pair<String, SessionModel> =
    "s$generation-$kind$nextId" to copy(nextId = nextId + 1)

private fun Frame.retained(): Snapshot? = when (val status = status) {
    is FrameStatus.Loading -> status.previous
    is FrameStatus.Shown -> status.snapshot
    is FrameStatus.Unavailable -> status.lastSnapshot
}

private fun SessionState.InPage.withTop(frame: Frame) = copy(frames = frames.dropLast(1) + frame)

private fun epochOf(payload: JSONObject): String? =
    (payload.opt(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID) as? String)?.takeIf(PageSurfaceContract::isValidId)

/** Once a frame learned its provider's registration, only that registration may speak for it. */
private fun sameEpoch(frame: Frame, payload: JSONObject): Boolean =
    frame.providerEpoch == null || epochOf(payload) == frame.providerEpoch

private fun correlationOf(payload: JSONObject): PageCorrelation? = runCatching {
    PageCorrelation(
        payload.get("requestId") as String,
        (payload.get("sessionGeneration") as Number).toLong(),
        (payload.get("frameIndex") as Number).toInt(),
        payload.get("pageId") as String,
    )
}.getOrNull()

private fun SessionModel.expireCancelledOpens(nowMs: Long): SessionModel =
    if (cancelledOpen.values.all { it > nowMs }) this else copy(cancelledOpen = cancelledOpen.filterValues { it > nowMs })

/** Page and action deadlines run from the initiating selection, whichever event observes them. */
private fun FrameStatus.Loading.expiredAt(nowMs: Long) = nowMs >= sinceMs + PageSurfaceContract.PAGE_TIMEOUT_MS

private fun PendingInvocation.expiredAt(nowMs: Long) = nowMs >= sinceMs + PageSurfaceContract.PAGE_TIMEOUT_MS

/** A lost acknowledgement is shown as unconfirmed and never retried here. */
private fun Frame.unconfirmed(invocation: PendingInvocation) = copy(
    invocation = null,
    outcome = ActionOutcome(invocation.actionId, PageSurfaceContract.ERROR_UNCONFIRMED_ACTION),
)

private fun SessionState.mapRootStops(transform: (List<RootStop>) -> List<RootStop>): SessionState = when (this) {
    SessionState.Closed -> this
    is SessionState.Opening -> copy(root = root.copy(stops = transform(root.stops)))
    is SessionState.Root -> copy(stops = transform(stops))
    is SessionState.InPage -> copy(root = root.copy(stops = transform(root.stops)))
    is SessionState.Launching -> copy(previous = previous.mapRootStops(transform))
}

private fun List<ActivityStop>.upsert(stop: ActivityStop): List<ActivityStop> {
    val index = indexOfFirst { it.id == stop.id }
    return if (index < 0) this + stop else toMutableList().also { it[index] = stop }
}
