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
import org.json.JSONObject

/**
 * Pure Nexus session reducer for the reserved session/page v1 contract.
 *
 * Nothing here schedules, posts, or draws: time arrives on events as `nowMs`
 * and every consequence is returned as a [SessionEffect] for a future host.
 * Host facts (armed notice, focused editable, link) are opaque inputs; the
 * notice dispatcher's own claims are not re-implemented here.
 */
internal class SessionReducer(
    private val itemResolver: PageItemResolver = DefaultPageItems,
    private val maxSnapshotTotalBytes: Int = PageSurfaceContract.MAX_SNAPSHOT_TOTAL_BYTES,
) {
    fun reduce(model: SessionModel, event: SessionEvent): SessionTransition {
        val transition = when (event) {
            SessionEvent.NoticeArmed -> keep(model.copy(noticeArmed = true))
            SessionEvent.NoticeCleared -> keep(model.copy(noticeArmed = false))
            is SessionEvent.EditableFocused -> keep(model.copy(editableFocused = event.focused))
            is SessionEvent.NoticeArrived -> onNoticeArrived(model, event.preview)
            is SessionEvent.ActivityStarted -> onActivityStarted(model, event.stop)
            is SessionEvent.ActivityEnded -> onActivityEnded(model, event.stopId)
            is SessionEvent.SurfaceShown -> onSurfaceShown(model, event)
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

    private fun onNoticeArrived(model: SessionModel, preview: NoticePreview): SessionTransition {
        val count = model.notificationCount + 1
        val updated = model.copy(
            preview = preview,
            notificationCount = count,
            state = model.state.mapRootStops { RootStops.withNotice(it, preview, count) },
        )
        return rootBookkeeping(model, updated)
    }

    private fun onActivityStarted(model: SessionModel, stop: ActivityStop): SessionTransition =
        if (model.state == SessionState.Closed) {
            keep(model.copy(activities = model.activities.upsert(stop)))
        } else {
            keep(model.copy(queuedActivities = model.queuedActivities.upsert(stop)))
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
        val root = SessionState.Root(RootStops.build(model.activities, model.preview, model.notificationCount))
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
            is SessionEvent.Enter -> state.stops.getOrNull(state.selected)
                ?.let { stop -> openFrame(model, state, emptyList(), stop.pageId, null, event.nowMs) }
                ?: keep(model)
            is SessionEvent.Back -> SessionTransition(closed(model), listOf(RestoreUnderneath(model.underneath)))
            SessionEvent.LinkLost -> keep(model.copy(linkLost = true))
            SessionEvent.LinkRestored -> keep(model.copy(linkLost = false))
            // A new contact after the gate proves the gate's gestures are over.
            is SessionEvent.Contact -> keep(model.copy(gateContacts = emptyList()))
            // Triple tap stays at the root; late page traffic never reopens a frame.
            else -> keep(model)
        }

    private fun onPage(model: SessionModel, state: SessionState.InPage, event: SessionEvent): SessionTransition =
        when (event) {
            is SessionEvent.Step -> step(model, state, event.delta)
            is SessionEvent.Enter -> enter(model, state, event.nowMs)
            is SessionEvent.Back -> pop(model, state, event.nowMs)
            is SessionEvent.TripleTap -> SessionTransition(
                model.copy(state = state.root),
                state.frames.asReversed().filter { model.canReach(it) }.map { SendClosed(it.pageId, CLOSE_BACK) } +
                    ShowRoot,
            )
            is SessionEvent.Tick -> tick(model, state, event.nowMs)
            is SessionEvent.PageResponse -> response(model, state, event.payload, event.nowMs)
            is SessionEvent.PageResult -> result(model, state, event.payload, event.nowMs)
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
        return frames.asReversed().filter { model.canReach(it) }.map { SendClosed(it.pageId, CLOSE_SESSION_CLOSED) }
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

    private fun step(model: SessionModel, state: SessionState.InPage, delta: Int): SessionTransition {
        val top = state.frames.last()
        val target = (top.selected + delta).coerceIn(0, itemsOf(top).lastIndex.coerceAtLeast(0))
        if (target == top.selected) return keep(model)
        return SessionTransition(model.copy(state = state.withTop(top.copy(selected = target))), listOf(ShowFrame))
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
                if (top.invocation != null) return keep(model)
                when (val item = itemResolver.items(status.snapshot.page).getOrNull(top.selected)) {
                    is PageItem.OpenPage -> openFrame(model, state.root, state.frames, item.pageId, item.paramsJson, nowMs)
                    is PageItem.Invoke -> invoke(model, state, status, item.actionId, nowMs)
                    is PageItem.Launch -> launch(model, state, item.pluginId, nowMs)
                    PageItem.Back -> pop(model, state, nowMs)
                    else -> keep(model)
                }
            }
        }
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
            return SessionTransition(model, listOf(SendClosed(pageId, CLOSE_FRAME_LIMIT)))
        }
        val frameIndex = below.size + 1
        if (model.linkLost) {
            val frame = Frame(pageId, FrameStatus.Unavailable(null, UNAVAILABLE_LINK_LOST), paramsJson = paramsJson)
            return SessionTransition(model.copy(state = SessionState.InPage(below + frame, root)), listOf(ShowFrame))
        }
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, frameIndex, pageId, REASON_OPEN, paramsJson)
        if (!isWireValid(request)) return keep(model)
        val covered = below.lastOrNull()?.let { coverFrame(it) }
        val frame = Frame(pageId, FrameStatus.Loading(nowMs, requestId, REASON_OPEN), paramsJson = paramsJson)
        val frames = below.dropLast(if (covered == null) 0 else 1) + listOfNotNull(covered?.first) + frame
        return SessionTransition(
            counted.copy(state = SessionState.InPage(frames, root)),
            listOfNotNull(covered?.second) + RequestPage(request) + ShowFrame,
        )
    }

    private fun coverFrame(frame: Frame): Pair<Frame, SessionEffect> {
        val status = frame.status
        val next = if (status is FrameStatus.Shown) frame.copy(status = status.copy(leaseUntilMs = null)) else frame
        return next to SendVisibility(frame.pageId, visible = false)
    }

    private fun retry(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Unavailable,
        nowMs: Long,
    ): SessionTransition {
        if (model.linkLost) return keep(model)
        val top = state.frames.last()
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, state.frames.size, top.pageId, REASON_RETRY, top.paramsJson)
        val frame = top.copy(
            status = FrameStatus.Loading(nowMs, requestId, REASON_RETRY, previous = status.lastSnapshot),
            selected = 0,
        )
        return SessionTransition(counted.copy(state = state.withTop(frame)), listOf(RequestPage(request), ShowFrame))
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
        val closed = listOfNotNull(SendClosed(popped.pageId, CLOSE_BACK).takeIf { model.canReach(popped) })
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
        )
        return SessionTransition(
            counted.copy(state = state.copy(frames = below.dropLast(1) + frame)),
            closed + SendVisibility(uncovered.pageId, visible = true, leaseUntilMs = lease) + RequestPage(request) +
                ShowFrame,
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
                    effects += SendVisibility(top.pageId, visible = true, leaseUntilMs = renewed)
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
        )
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun response(
        model: SessionModel,
        state: SessionState.InPage,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        if (model.linkLost) return keep(model)
        val top = state.frames.last()
        return when (val status = top.status) {
            // The deadline is absolute: a reply arriving after it loses even if no tick ran yet.
            is FrameStatus.Loading ->
                if (status.expiredAt(nowMs)) timeOut(model, state, status) else pendingResponse(model, state, status, payload, nowMs)
            is FrameStatus.Shown -> liveRevision(model, state, status, payload, nowMs)
            is FrameStatus.Unavailable -> keep(model)
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
                is PageSurfaceResponse.Page -> return acceptPending(model, state, status, response, nowMs)
            }
            is PageSurfaceValidationResult.Invalid -> {
                val ours = result.error != PageSurfaceContract.ERROR_STALE_GENERATION &&
                    payload.opt("requestId") == status.requestId &&
                    (payload.opt("sessionGeneration") as? Number)?.toLong() == model.generation
                if (!ours) return keep(model)
                result.error
            }
        }
        val frame = top.copy(status = FrameStatus.Unavailable(status.previous, failure), selected = 0)
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun acceptPending(
        model: SessionModel,
        state: SessionState.InPage,
        status: FrameStatus.Loading,
        page: PageSurfaceResponse.Page,
        nowMs: Long,
    ): SessionTransition {
        val top = state.frames.last()
        // Revisions stay monotonic across refresh and retry; a rollback is dropped as stale.
        val floor = top.revisionFloor
        if (floor != null && page.revision < floor) return keep(model)
        val snapshot = status.previous?.takeIf { page.revision == floor } ?: Snapshot(page)
        // The first display of a frame starts its lease; an uncovered frame already holds one.
        val lease = status.leaseUntilMs ?: (nowMs + PageSurfaceContract.LEASE_MS)
        val granted = listOfNotNull(
            SendVisibility(top.pageId, visible = true, leaseUntilMs = lease).takeIf { status.leaseUntilMs == null },
        )
        val shown = top.copy(
            status = FrameStatus.Shown(snapshot, page.revision, lease, status.requestId),
            selected = if (status.reason == REASON_REFRESH) top.selected else 0,
            revisionFloor = page.revision,
        )
        val frame = shown.copy(selected = shown.selected.coerceIn(0, itemsOf(shown).lastIndex.coerceAtLeast(0)))
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
            lease != null && nowMs < lease && status.snapshot.page.live && page.revision > status.revision
        if (!accepted) return keep(model)
        return replaceTopSnapshot(model, state, top, status, page)
    }

    private fun result(
        model: SessionModel,
        state: SessionState.InPage,
        payload: JSONObject,
        nowMs: Long,
    ): SessionTransition {
        if (model.linkLost) return keep(model)
        val top = state.frames.last()
        val invocation = top.invocation ?: return keep(model)
        if (invocation.expiredAt(nowMs)) {
            return SessionTransition(model.copy(state = state.withTop(top.unconfirmed(invocation))), listOf(ShowFrame))
        }
        val result = (PageSurfaceContract.validateResult(payload) as? PageSurfaceValidationResult.Valid)?.value
            ?: return keep(model)
        if (invocation.invocationId != result.invocationId) return keep(model)
        val settled = top.copy(invocation = null, outcome = ActionOutcome(invocation.actionId, result.status, result.message))
        val status = settled.status
        val replacement = result.replacement
        if (result.status == RESULT_DONE && status is FrameStatus.Shown && replacement != null &&
            replacement.correlation.sessionGeneration == model.generation &&
            replacement.correlation.frameIndex == state.frames.size &&
            replacement.correlation.pageId == top.pageId &&
            replacement.revision > status.revision
        ) {
            return replaceTopSnapshot(model, state, settled, status, replacement)
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
    ): SessionTransition {
        val replaced = top.copy(
            status = status.copy(snapshot = Snapshot(page), revision = page.revision),
            revisionFloor = page.revision,
        )
        val frame = replaced.copy(selected = replaced.selected.coerceIn(0, itemsOf(replaced).lastIndex.coerceAtLeast(0)))
        return SessionTransition(
            model.copy(state = state.copy(frames = evictCovered(state.frames.dropLast(1) + frame))),
            listOf(ShowFrame),
        )
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

    private fun itemsOf(frame: Frame): List<PageItem> = when (val status = frame.status) {
        is FrameStatus.Shown -> itemResolver.items(status.snapshot.page)
        is FrameStatus.Loading -> status.previous?.let { itemResolver.items(it.page) }.orEmpty()
        is FrameStatus.Unavailable -> UNAVAILABLE_ITEMS
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

    private fun isWireValid(request: PageSurfaceRequest): Boolean =
        runCatching { PageSurfaceContract.validateRequest(request.toPayload()) }.getOrNull() is
            PageSurfaceValidationResult.Valid

    private fun keep(model: SessionModel) = SessionTransition(model, emptyList())

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
        val UNAVAILABLE_ITEMS: List<PageItem> = listOf(PageItem.Retry, PageItem.Back)

        /** The fork's F-4 bound, equal to the ring's surface handoff. */
        const val OPEN_TIMEOUT_MS = 10_000L
        const val SURFACE_OPEN_CANCELLED = "OPEN_CANCELLED"
    }
}

/** Frames invalidated by link loss stay inert: nothing is sent for them, even after the link returns. */
private fun SessionModel.canReach(frame: Frame): Boolean {
    val status = frame.status
    return !linkLost && !(status is FrameStatus.Unavailable && status.reason == SessionReducer.UNAVAILABLE_LINK_LOST)
}

private fun SessionModel.nextId(kind: String): Pair<String, SessionModel> =
    "s$generation-$kind$nextId" to copy(nextId = nextId + 1)

private fun Frame.retained(): Snapshot? = when (val status = status) {
    is FrameStatus.Loading -> status.previous
    is FrameStatus.Shown -> status.snapshot
    is FrameStatus.Unavailable -> status.lastSnapshot
}

private fun SessionState.InPage.withTop(frame: Frame) = copy(frames = frames.dropLast(1) + frame)

/** Link loss makes every frame unavailable and drops leases and pending actions (rule 12). */
private fun SessionState.InPage.linkLost() = copy(
    frames = frames.map {
        it.copy(
            status = FrameStatus.Unavailable(it.retained(), SessionReducer.UNAVAILABLE_LINK_LOST),
            selected = 0,
            invocation = null,
        )
    },
)

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
