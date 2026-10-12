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
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ActivityCommand
import com.anezium.rokidbus.shared.ActivityCommandMode
import com.anezium.rokidbus.shared.BusPaths
import com.anezium.rokidbus.shared.ComposeDictateStart
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ComposeTypeStart
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceInvocation
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceResult
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
 *
 * [quickGate] decides which activity commands one tap may run; page rows get the same answer
 * from the resolver. [launcherActive] is whether the pair negotiated the launcher bundle now:
 * only then do activity cards activate, provider pages carry the phone's quick stamps, and every
 * effect (a plugin action or activity command, quick or confirmed, an app launch, a composer
 * capture or Send) needs a fresh contact, see [SessionFocus]. Without it the session keeps its v1
 * behaviour exactly.
 *
 * [wallClock] dates the notification history only: its entries are displayed for 24 hours of wall
 * time, which the session clock cannot measure across a deep sleep.
 */
internal class SessionReducer(
    private val itemResolver: PageItemResolver = DefaultPageItems,
    private val maxSnapshotTotalBytes: Int = PageSurfaceContract.MAX_SNAPSHOT_TOTAL_BYTES,
    private val isLocalPage: (String) -> Boolean = { false },
    private val quickGate: QuickGate = QuickGate.NEVER,
    private val launcherActive: () -> Boolean = { false },
    private val wallClock: () -> Long = { 0L },
    /**
     * The hub's Open app for an owner whose full app is a launchable, non-native entry of the
     * Applications catalog; null for any other owner.
     */
    private val ownerApp: (String) -> OpenApp? = { null },
) {
    fun reduce(model: SessionModel, event: SessionEvent): SessionTransition {
        val reduced = when (event) {
            SessionEvent.NoticeArmed -> keep(model.copy(noticeArmed = true))
            SessionEvent.NoticeCleared -> keep(model.copy(noticeArmed = false))
            is SessionEvent.EditableFocused -> keep(model.copy(editableFocused = event.focused))
            is SessionEvent.NoticeArrived -> onNoticeArrived(model, event)
            is SessionEvent.NoticeWithdrawn -> onNoticeWithdrawn(model, event)
            is SessionEvent.NoticePurged -> onNoticePurged(model, event.ownerPluginId)
            is SessionEvent.ComposeReceived -> onCompose(model, event)
            is SessionEvent.ActivityStarted -> onActivityStarted(model, event.stop)
            is SessionEvent.ActivityUpdated -> onActivityUpdated(model, event.stop)
            is SessionEvent.ActivityEnded -> onActivityEnded(model, event.stopId)
            is SessionEvent.SurfaceShown -> onSurfaceShown(model, event)
            is SessionEvent.ProviderInvalidated -> invalidateOwner(model, event.ownerPluginId)
            // Small pages keep working over CXR; nothing is asked again when SPP returns.
            SessionEvent.DataPlaneLost -> inertProviderFrames(model, UNAVAILABLE_DATA_PLANE) { it.dataPlane }
            // Unsupported negotiation leaves every provider page inert, with no replay.
            SessionEvent.PageSupportLost -> inertProviderFrames(model, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE) { true }
            SessionEvent.Abort -> abort(model)
            is SessionEvent.FocusShown -> onFocusShown(model, event)
            is SessionEvent.ActivityResult -> onActivityResult(model, event)
            SessionEvent.LauncherFactsChanged -> redraw(model, model.copy(factsEpoch = model.factsEpoch + 1))
            else -> {
                // Rule 17: a cancelled open is remembered only until its own deadline.
                val ticked = (event as? SessionEvent.Tick)?.let { model.expireCancelledOpens(it.nowMs).expireCommands(it.nowMs) }
                val current = ticked ?: model
                val transition = when (val state = current.state) {
                    SessionState.Closed -> onClosed(current, event)
                    is SessionState.Opening -> onOpening(current, state, event)
                    is SessionState.Root -> absorbGateClassification(current, event) ?: onRoot(current, state, event)
                    is SessionState.InPage -> absorbGateClassification(current, event) ?: onPage(current, state, event)
                    is SessionState.Launching ->
                        absorbGateClassification(current, event) ?: onLaunching(current, state, event)
                }
                // A command that expired on this tick is now shown as unconfirmed.
                if (ticked != null && ticked.commandOutcomes != model.commandOutcomes) {
                    transition.copy(effects = (transition.effects + listOfNotNull(showEffect(transition.model.state))).distinct())
                } else {
                    transition
                }
            }
        }
        val transition = settleComposer(reduced, event)
        // An acknowledgement holds only while its target stays on screen: once something else is
        // focused, coming back to the same target needs a new one.
        val after = transition.model
        val settled = after.shownFocus?.takeIf { it.token != SessionFocus.tokenOf(after) }
            ?.let { after.copy(shownFocus = null) } ?: after
        return SessionTransition(settled, transition.effects.ifEmpty { listOf(SessionEffect.None) })
    }

    /** Only an acknowledgement of the target on screen now counts, and only its first one. */
    private fun onFocusShown(model: SessionModel, event: SessionEvent.FocusShown): SessionTransition {
        val token = SessionFocus.tokenOf(model) ?: return keep(model)
        if (event.token != token || model.shownFocus?.token == token) return keep(model)
        return keep(model.copy(shownFocus = ShownFocus(token, event.atMs)))
    }

    /**
     * An owner's answer settles its pending command by invocation id. A late answer (at or past
     * the glasses' deadline) is unconfirmed like a lost one, and an unknown one is dropped.
     */
    private fun onActivityResult(model: SessionModel, event: SessionEvent.ActivityResult): SessionTransition {
        val result = event.result
        val pending = model.pendingCommands.values
            .firstOrNull { it.invocationId == result.invocationId && it.activityId == result.activityId }
            ?: return keep(model)
        val outcome = if (event.nowMs >= pending.deadlineMs) {
            CommandOutcome(pending.actionId, PageSurfaceContract.ERROR_UNCONFIRMED_ACTION)
        } else {
            CommandOutcome(pending.actionId, result.status.wireValue, result.message)
        }
        return redraw(
            model,
            model.copy(
                pendingCommands = model.pendingCommands - pending.ownerPluginId,
                commandOutcomes = model.commandOutcomes + (pending.ownerPluginId to outcome),
            ),
        )
    }

    /** [after] with the redraw its open screen needs; nothing is drawn for a closed session. */
    private fun redraw(before: SessionModel, after: SessionModel): SessionTransition =
        SessionTransition(after, listOfNotNull(showEffect(after.state).takeIf { after != before }))

    private fun showEffect(state: SessionState): SessionEffect? = when (state) {
        is SessionState.Root -> ShowRoot
        is SessionState.InPage -> ShowFrame
        else -> null
    }

    /**
     * Every arrival joins the history (plan 028a §6.3); the root counts the entries it may show,
     * while [SessionModel.notificationCount] counts arrivals. A new notice for a lost draft's exact
     * thread lets Type reopen it.
     */
    private fun onNoticeArrived(model: SessionModel, event: SessionEvent.NoticeArrived): SessionTransition {
        val now = wallClock()
        val entry = event.entry ?: NoticeEntry.of(event.preview, now)
        val history = model.history.add(entry)
        val updated = model.copy(
            preview = event.preview,
            notificationCount = model.notificationCount + 1,
            history = history,
            draft = DraftStore.noticeArrived(model.draft, entry),
            state = model.state.mapRootStops { RootStops.withHistory(it, history, now) },
        )
        return withComposerRedraw(model, rootBookkeeping(model, updated))
    }

    /** The event left the phone: its entries say so, and a draft for its thread can no longer be sent. */
    private fun onNoticeWithdrawn(model: SessionModel, event: SessionEvent.NoticeWithdrawn): SessionTransition {
        val history = model.history.withdraw(event.ownerPluginId, event.eventKey)
        val updated = model.copy(
            history = history,
            draft = DraftStore.authorityLost(
                model.draft, event.ownerPluginId, event.eventKey, threadRevision(model, event.ownerPluginId, event.eventKey),
            ),
            state = model.state.mapRootStops { RootStops.withHistory(it, history, wallClock()) },
        )
        return withComposerRedraw(model, rootBookkeeping(model, updated))
    }

    /** The owner was revoked, disabled, uninstalled or re-signed: its entries go, its draft loses authority. */
    private fun onNoticePurged(model: SessionModel, owner: String): SessionTransition {
        val history = model.history.purge(owner)
        // A capture for the owner ends with it; nothing it would have produced may be sent.
        val capture = model.capture?.takeIf { it.ownerPluginId == owner }
        val updated = model.copy(
            history = history,
            draft = DraftStore.authorityLost(model.draft, owner, revision = threadRevision(model, owner, model.draft?.threadKey)),
            capture = if (capture != null) null else model.capture,
            state = model.state.mapRootStops { RootStops.withHistory(it, history, wallClock()) },
        )
        val transition = withComposerRedraw(model, rootBookkeeping(model, updated))
        if (capture == null) return transition
        val effects = transition.effects + SessionEffect.SendCompose(ComposeClient.cancel(capture))
        return transition.copy(effects = if (ShowFrame in effects) effects else effects + ShowFrame)
    }

    /** A conversation page draws the draft, so a draft change redraws an open page. */
    private fun withComposerRedraw(before: SessionModel, transition: SessionTransition): SessionTransition {
        val after = transition.model
        if (after.draft == before.draft || after.state !is SessionState.InPage || ShowFrame in transition.effects) return transition
        return transition.copy(effects = transition.effects + ShowFrame)
    }

    private fun onActivityStarted(model: SessionModel, stop: ActivityStop): SessionTransition =
        if (model.state == SessionState.Closed) {
            keep(model.copy(activities = model.activities.upsert(stop)))
        } else {
            rootBookkeeping(model, reconcileFocus(model.copy(queuedActivities = model.queuedActivities.upsert(stop))))
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
        return rootBookkeeping(model, reconcileFocus(updated))
    }

    private fun onActivityEnded(model: SessionModel, stopId: String): SessionTransition {
        val updated = model.copy(
            activities = model.activities.filterNot { it.id == stopId },
            queuedActivities = model.queuedActivities.filterNot { it.id == stopId },
            state = model.state.mapRootStops { RootStops.withEnded(it, stopId) },
        )
        return rootBookkeeping(model, reconcileFocus(updated))
    }

    /**
     * Command focus follows its entry: an update keeps it on the same command (or clamps it), and
     * a confirmation bound to an older revision is withdrawn. An entry that ended, or left the
     * collection, takes its focus and panel with it.
     */
    private fun reconcileFocus(model: SessionModel): SessionModel {
        fun focusOn(stop: ActivityStop?, focus: CommandFocus, panel: FramePanel?): Pair<CommandFocus?, FramePanel?> {
            val slots = stop?.let(ActivityCommands::slots).orEmpty()
            val next = if (stop == null) null else ActivityCommands.reconcile(focus, slots)
            val kept = when (panel) {
                is FramePanel.ConfirmCommand ->
                    panel.takeIf { next != null && stop != null && !stop.lastKnown && stop.seq == panel.seq && stop.commands.any { it.id == panel.actionId } }
                else -> panel.takeIf { next != null }
            }
            return next to kept
        }
        fun rootOf(root: SessionState.Root): SessionState.Root {
            val focus = root.focus ?: return root
            val card = root.stops.firstOrNull { it.id == focus.stopId }
            val (next, panel) = focusOn(card?.takeUnless { it.ended }?.activity, focus, root.panel)
            return root.copy(focus = next, panel = panel)
        }
        fun pageOf(page: SessionState.InPage): SessionState.InPage {
            val top = page.frames.last()
            val focus = top.commandFocus?.takeIf { it.stopId != CommandFocus.MEDIA } ?: return page
            val (next, panel) = focusOn(model.latestActivityById(focus.stopId), focus, top.panel)
            return page.withTop(top.copy(commandFocus = next, panel = panel))
        }
        val state = when (val state = model.state) {
            is SessionState.Root -> rootOf(state)
            is SessionState.InPage -> pageOf(state)
            else -> state
        }
        return if (state == model.state) model else model.copy(state = state)
    }

    private fun onClosed(model: SessionModel, event: SessionEvent): SessionTransition {
        // The session never opens over a composer capture (plan 028a §5.4).
        if (event !is SessionEvent.TripleTap || model.noticeArmed || model.editableFocused || model.capture != null) return keep(model)
        val now = wallClock()
        val root = SessionState.Root(
            RootStops.build(model.activities, model.history.rootPreview(now), model.history.visible(now).size),
        )
        return SessionTransition(
            model.copy(
                state = SessionState.Opening(event.nowMs, root),
                generation = model.generation + 1,
                linkLost = false,
                gateContacts = emptyList(),
                underneath = event.underneath,
                // A pending command stays pending across sessions; an answer already shown does not.
                commandOutcomes = emptyMap(),
                shownFocus = null,
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
            is SessionEvent.Step -> when {
                state.panel != null -> panelStep(model, rootSite(state), event.delta)
                state.focus != null -> focusStep(model, rootSite(state), rootActivity(state, state.focus.stopId), event.delta)
                else -> {
                    val target = (state.selected + event.delta).coerceIn(0, state.stops.lastIndex.coerceAtLeast(0))
                    if (target == state.selected) {
                        keep(model)
                    } else {
                        SessionTransition(model.copy(state = state.copy(selected = target)), listOf(ShowRoot))
                    }
                }
            }
            is SessionEvent.Enter -> when {
                state.panel != null -> commandPanelEnter(model, rootSite(state), state.focus?.let { rootActivity(state, it.stopId) }, event)
                state.focus != null -> focusEnter(model, rootSite(state), rootActivity(state, state.focus.stopId), event)
                // An ended activity keeps its place but opens nothing.
                else -> state.stops.getOrNull(state.selected)?.takeUnless { it.ended }?.let { stop ->
                    val activity = stop.activity
                    when {
                        stop.kind == RootStopKind.ACTIVITY && activity != null ->
                            activate(model, rootSite(state), activity, state, emptyList(), stop.pageId, event.nowMs)
                        stop.kind == RootStopKind.NOTICE_PREVIEW -> openNoticeStop(model, state, stop, event.nowMs)
                        else -> openFrame(model, state, emptyList(), stop.pageId, null, event.nowMs)
                    }
                } ?: keep(model)
            }
            // BACK leaves a panel, then command focus, then the session.
            is SessionEvent.Back -> when {
                state.panel != null -> SessionTransition(model.copy(state = state.copy(panel = null)), listOf(ShowRoot))
                state.focus != null -> SessionTransition(model.copy(state = state.copy(focus = null)), listOf(ShowRoot))
                else -> SessionTransition(closed(model), listOf(RestoreUnderneath(model.underneath)))
            }
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
            is SessionEvent.Enter -> enter(model, state, event)
            is SessionEvent.Back -> back(model, state, event.nowMs)
            // A capture is finished or cancelled first; triple tap is not recognised over it.
            is SessionEvent.TripleTap -> if (model.capture != null) {
                keep(model)
            } else {
                SessionTransition(
                    model.copy(state = state.root),
                    state.frames.withIndex().reversed().filter { model.canReach(it.value) }
                        .map { closedEffect(model, it.value, it.index, CLOSE_BACK) } + ShowRoot,
                )
            }
            is SessionEvent.Tick -> tick(model, state, event.nowMs)
            is SessionEvent.PageResponse ->
                carried(model, response(model, state, event.payload, event.nowMs), BusPaths.PAGE_RESPONSE, event.payload)
            is SessionEvent.PageResult ->
                carried(model, result(model, state, event.payload, event.nowMs), BusPaths.PAGE_RESULT, event.payload)
            is SessionEvent.DocumentPaged -> documentPaged(model, state, event)
            is SessionEvent.ContentOverflowed -> contentOverflowed(model, state, event)
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

    private fun launch(model: SessionModel, state: SessionState, pluginId: String, nowMs: Long): SessionTransition {
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
        micUnavailable = false,
        mapViews = emptyMap(),
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
        (top.panel as? FramePanel.Display)?.let { return displayStep(model, state, top, it, delta) }
        if (top.panel != null) return panelStep(model, frameSite(state), delta)
        top.fullMapMode()?.let { mode -> return fullMapStep(model, state, top, mode, delta) }
        top.routeCommandsIndex()?.let { commands -> return routePageStep(model, state, top, commands, delta) }
        top.shownConversation()?.let { (_, body) -> return conversationStep(model, state, top, body, delta) }
        top.commandFocus?.let { focus ->
            if (focus.stopId != CommandFocus.MEDIA) {
                return focusStep(model, frameSite(state), model.latestActivityById(focus.stopId), delta)
            }
            // A media page's focus walks its controls, then the page's other rows, without wrapping.
            val entries = entriesOf(top)
            val target = (top.selected + delta).coerceIn(0, (entries.size - 1).coerceAtLeast(0))
            if (target == top.selected || top.tombstone != null || target !in entries.indices) return keep(model)
            val key = entries[target].key
            val frame = top.copy(selected = target, selectedKey = key, commandFocus = focus.copy(key = key, index = target))
            return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        // Outside focus a control strip is not a list to walk: a tap focuses it first.
        if (controlsOf(top) != null) return keep(model)
        val status = top.status
        if (top.reading()) {
            // Read text pages through its own measured layout; bounded at both ends.
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
        val frame = top.copy(
            selected = target,
            selectedKey = entries.getOrNull(target)?.key?.takeIf { status is FrameStatus.Shown },
        )
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    private fun enter(model: SessionModel, state: SessionState.InPage, event: SessionEvent.Enter): SessionTransition {
        val nowMs = event.nowMs
        val top = state.frames.last()
        return when (val status = top.status) {
            // One request per frame and one action per gesture; nothing is queued.
            is FrameStatus.Loading -> keep(model)
            // SELECT on retained text reaches Retry and Back; the text itself never acts.
            is FrameStatus.Unavailable -> if (top.reading()) {
                SessionTransition(model.copy(state = state.withTop(top.copy(mode = FrameMode.ROWS, selected = 0))), listOf(ShowFrame))
            } else when (UNAVAILABLE_ITEMS.getOrNull(top.selected)) {
                PageItem.Retry -> retry(model, state, status, nowMs)
                PageItem.Back -> pop(model, state, nowMs)
                else -> keep(model)
            }
            is FrameStatus.Shown -> {
                top.panel?.let { return enterPanel(model, state, top, status, it, event) }
                top.commandFocus?.takeIf { it.stopId != CommandFocus.MEDIA }?.let { focus ->
                    return focusEnter(model, frameSite(state), model.latestActivityById(focus.stopId), event)
                }
                itemResolver.conversation(status.snapshot.page)?.let { return conversationEnter(model, state, top, status, it, event) }
                routeEnter(model, state, top)?.let { return it }
                if (top.invocation != null) return keep(model)
                if (top.reading()) {
                    // SELECT on read text reaches its rows, when it has any; reading never fires one.
                    if (entriesOf(top).isEmpty()) return keep(model)
                    return SessionTransition(
                        model.copy(state = state.withTop(top.copy(mode = FrameMode.ROWS, selected = 0, selectedKey = entriesOf(top).first().key))),
                        listOf(ShowFrame),
                    )
                }
                if (top.tombstone != null) return keep(model)
                if (top.commandFocus == null) {
                    controlsOf(top)?.let { controls ->
                        // The first tap on a media page focuses its default control; it never plays.
                        val index = entriesOf(top).indexOfFirst { it.key == controls.defaultKey }
                        if (index < 0) return keep(model)
                        val frame = top.copy(
                            selected = index,
                            selectedKey = controls.defaultKey,
                            commandFocus = CommandFocus(CommandFocus.MEDIA, controls.defaultKey, index),
                        )
                        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
                    }
                }
                when (val item = entriesOf(top).getOrNull(top.selected)?.item) {
                    is PageItem.OpenPage ->
                        openFrame(model, state.root, state.frames, item.pageId, item.paramsJson, nowMs, applicationOpenApp(top, item.pageId))
                    is PageItem.Invoke -> if (fresh(model, event)) invoke(model, state, status, item.actionId, nowMs) else keep(model)
                    // One tap runs it, only as the gate allowed when the row was resolved and only for a fresh press.
                    is PageItem.Quick ->
                        if (launcherActive() && fresh(model, event)) {
                            invoke(model, state, status, item.actionId, nowMs, PageSurfaceContract.MODE_QUICK)
                        } else {
                            keep(model)
                        }
                    is PageItem.Launch -> if (fresh(model, event)) launch(model, state, item.pluginId, nowMs) else keep(model)
                    is PageItem.Activity -> model.latestActivity(item.ownerPluginId)
                        ?.let { activate(model, frameSite(state), it, state.root, state.frames, it.pageId, nowMs) }
                        ?: keep(model)
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
                    is PageItem.Notification -> openEntry(model, state.root, state.frames, item.entryId, nowMs)
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
        event: SessionEvent.Enter,
    ): SessionTransition {
        val nowMs = event.nowMs
        val cleared = state.withTop(top.copy(panel = null))
        return when (panel) {
            is FramePanel.Confirm -> {
                if (panel.selected != FramePanel.CONFIRM) {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                } else if (!fresh(model, event)) {
                    keep(model)
                } else if (panel.revision != status.revision || panel.providerEpoch != top.providerEpoch || top.invocation != null) {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                } else {
                    invoke(model.copy(state = cleared), cleared, status, panel.actionId, nowMs)
                }
            }
            is FramePanel.Launch ->
                if (panel.selected != FramePanel.OPEN) {
                    SessionTransition(model.copy(state = cleared), listOf(ShowFrame))
                } else if (!fresh(model, event)) {
                    keep(model)
                } else {
                    launch(model.copy(state = cleared), cleared, panel.pluginId, nowMs)
                }
            is FramePanel.ConfirmCommand ->
                commandPanelEnter(model, frameSite(state), model.latestActivityById(panel.stopId), event)
            // The display page saves what is selected; it changes a hub preference, nothing on the plugin.
            is FramePanel.Display -> SessionTransition(
                model.copy(state = cleared),
                listOf(SessionEffect.SaveAmbientSize(panel.ownerPluginId, panel.size), ShowFrame),
            )
        }
    }

    /** BACK closes a panel, then leaves command focus, then the rows of read text for the text, then pops the frame. */
    private fun back(model: SessionModel, state: SessionState.InPage, nowMs: Long): SessionTransition {
        val top = state.frames.last()
        // BACK cancels a capture (the earlier draft stays), then leaves the composer's focus.
        model.capture?.let { capture ->
            return SessionTransition(
                model.copy(capture = null),
                listOf(SessionEffect.SendCompose(ComposeClient.cancel(capture)), ShowFrame),
            )
        }
        if (top.composerFocus != null) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(composerFocus = null))), listOf(ShowFrame))
        }
        if (top.panel != null) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(panel = null))), listOf(ShowFrame))
        }
        if (top.commandFocus != null) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(commandFocus = null))), listOf(ShowFrame))
        }
        // The command list goes back where it was opened: the full map, or the page with Commands
        // selected; the full map goes back to its page with the map selected.
        when (top.fullMapMode()) {
            FullMapMode.COMMANDS -> {
                val back = if (top.routeMapIndex() != null) top.onMap(FullMapMode.MAP) else top.onCommandsRow()
                return SessionTransition(model.copy(state = state.withTop(back)), listOf(ShowFrame))
            }
            FullMapMode.PAGE_COMMANDS -> return SessionTransition(model.copy(state = state.withTop(top.onCommandsRow())), listOf(ShowFrame))
            FullMapMode.MAP -> return SessionTransition(model.copy(state = state.withTop(top.onMap(null))), listOf(ShowFrame))
            // No map left to show (a revision without geometry): the page is what is drawn.
            null -> Unit
        }
        if (top.mode == FrameMode.ROWS && top.readsFirst()) {
            return SessionTransition(model.copy(state = state.withTop(top.copy(mode = FrameMode.READING))), listOf(ShowFrame))
        }
        return pop(model, state, nowMs)
    }

    /**
     * Opens the frame above [below]; the root counts as frame 0 in the six-frame budget. [openApp]
     * ends an activity's destination page with the hub's own Open app row.
     */
    private fun openFrame(
        model: SessionModel,
        root: SessionState.Root,
        below: List<Frame>,
        pageId: String,
        paramsJson: String?,
        nowMs: Long,
        openApp: OpenApp? = null,
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
            val frame = Frame(pageId, FrameStatus.Unavailable(null, UNAVAILABLE_LINK_LOST), paramsJson = paramsJson, openApp = openApp)
            return SessionTransition(model.copy(state = SessionState.InPage(below + frame, root)), listOf(ShowFrame))
        }
        val (requestId, counted) = model.nextId("r")
        val request = request(model, requestId, frameIndex, pageId, REASON_OPEN, paramsJson)
        if (!isWireValid(request)) return keep(model)
        val covered = below.lastOrNull()?.let { coverFrame(model, it, below.lastIndex) }
        val frame = Frame(pageId, FrameStatus.Loading(nowMs, requestId, REASON_OPEN), paramsJson = paramsJson, openApp = openApp)
        val frames = below.dropLast(if (covered == null) 0 else 1) + listOfNotNull(covered?.first) + frame
        return SessionTransition(
            counted.copy(state = SessionState.InPage(frames, root)),
            listOfNotNull(covered?.second) + requestEffect(request, nowMs, null) + ShowFrame,
        )
    }

    private fun coverFrame(model: SessionModel, frame: Frame, index: Int): Pair<Frame, SessionEffect> {
        val status = frame.status
        val next = (if (status is FrameStatus.Shown) frame.copy(status = status.copy(leaseUntilMs = null)) else frame)
            .copy(panel = null, commandFocus = null)
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
            commandFocus = null,
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
        mode: String = PageSurfaceContract.MODE_CONFIRMED,
        text: String? = null,
    ): SessionTransition {
        val top = state.frames.last()
        val (invocationId, counted) = model.nextId("i")
        val action = PageSurfaceInvocation(
            invocationId, model.generation, state.frames.size, top.pageId, status.revision, actionId, mode = mode, text = text,
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
            commandFocus = null,
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
            commandFocus = null,
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

    /**
     * When [transition] put a new snapshot on the top frame, records whether the message that
     * carried it needed SPP, by the same rule the phone used to send it.
     */
    private fun carried(before: SessionModel, transition: SessionTransition, path: String, payload: JSONObject): SessionTransition {
        val after = transition.model.state as? SessionState.InPage ?: return transition
        val top = after.frames.last()
        val shown = top.status as? FrameStatus.Shown ?: return transition
        if (isLocalPage(top.pageId)) return transition
        val earlier = (before.state as? SessionState.InPage)?.frames?.getOrNull(after.frames.lastIndex)?.status
        if ((earlier as? FrameStatus.Shown)?.snapshot === shown.snapshot) return transition
        val frame = top.copy(dataPlane = PageSurfaceContract.needsDataPlane(path, payload))
        return transition.copy(model = transition.model.copy(state = after.withTop(frame)))
    }

    /** The provider frames [which] selects keep their snapshots, inert, under a page or a pending launch. */
    private fun inertProviderFrames(model: SessionModel, reason: String, which: (Frame) -> Boolean): SessionTransition {
        fun inert(page: SessionState.InPage): SessionState.InPage = page.copy(
            frames = page.frames.map { frame ->
                if (isLocalPage(frame.pageId) || !which(frame) || frame.status is FrameStatus.Unavailable) {
                    frame
                } else {
                    frame.copy(
                        status = FrameStatus.Unavailable(frame.retained(), reason),
                        selected = 0,
                        invocation = null,
                        panel = null,
                        commandFocus = null,
                    )
                }
            },
        )
        return when (val state = model.state) {
            is SessionState.InPage -> {
                val next = inert(state)
                if (next == state) keep(model) else SessionTransition(model.copy(state = next), listOf(ShowFrame))
            }
            is SessionState.Launching -> {
                val previous = state.previous as? SessionState.InPage ?: return keep(model)
                keep(model.copy(state = state.copy(previous = inert(previous))))
            }
            else -> keep(model)
        }
    }

    /**
     * Every frame of [owner] keeps its snapshot, inert; its requests, actions, leases and panels
     * end. Its pages are gone, so a draft for one of its threads loses its authority to be sent.
     */
    private fun invalidateOwner(before: SessionModel, owner: String): SessionTransition {
        val model = before.copy(
            draft = DraftStore.authorityLost(before.draft, owner, revision = threadRevision(before, owner, before.draft?.threadKey)),
        )
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
                        commandFocus = null,
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
        val failure = when (val result = PageSurfaceContract.validateResponse(payload, pending, hubStamps(top.pageId))) {
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
        val frame = top.copy(status = FrameStatus.Unavailable(status.previous, failure), selected = 0, panel = null, commandFocus = null)
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
        // The same revision keeps its laid-out snapshot, unless the templates it was read with changed.
        val kept = status.previous?.takeIf { page.revision == floor && it.extended == itemResolver.acceptsExtended() }
        val snapshot = kept ?: Snapshot(page, extended = itemResolver.acceptsExtended())
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
            reselect(shown, top, snapshot.page)
                .let { if (it.mode == FrameMode.READING && !it.readsFirst()) it.copy(mode = FrameMode.ROWS) else it }
                // The same snapshot lays out the same pages, and the renderer has no reason to report them again.
                .let { if (kept != null) it.copy(pageStarts = top.pageStarts) else it }
                .refocused()
        } else {
            val entries = entriesFor(snapshot.page, top.openApp)
            shown.copy(
                selectedKey = entries.firstOrNull()?.key,
                tombstone = null,
                commandFocus = null,
                mode = if (document) FrameMode.READING else FrameMode.ROWS,
                // Opened or retried content is measured again, as it is now drawn.
                overflowing = false,
                anchor = null,
                page = 0,
                // A Retry answered with the retained snapshot lays out the pages already measured.
                pageStarts = if (kept != null) top.pageStarts else emptyList(),
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
        val page = (PageSurfaceContract.validateResponse(payload, hubStamps = hubStamps(top.pageId)) as? PageSurfaceValidationResult.Valid)
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
        val result = (PageSurfaceContract.validateResult(payload, hubStamps(top.pageId)) as? PageSurfaceValidationResult.Valid)?.value
            ?: return keep(model)
        if (invocation.invocationId != result.invocationId || !sameEpoch(top, payload)) return keep(model)
        val send = model.pendingSend?.takeIf { it.invocationId == invocation.invocationId }
        val current = send?.let { composeSettled(model, it, result, (top.status as? FrameStatus.Shown)?.revision) } ?: model
        // A reply's outcome is its bubble or the composer's line; only a refusal or a stale page is said in words.
        val outcome = ActionOutcome(invocation.actionId, result.status, result.message)
            .takeUnless { send != null && (result.status == RESULT_DONE || result.code == PageSurfaceContract.CODE_AUTHORITY_LOST) }
        val settled = top.copy(invocation = null, outcome = outcome)
        val status = settled.status
        val replacement = result.replacement
        if (result.status == RESULT_DONE && status is FrameStatus.Shown && replacement != null &&
            replacement.correlation.sessionGeneration == current.generation &&
            replacement.correlation.frameIndex == state.frames.size &&
            replacement.correlation.pageId == top.pageId &&
            replacement.revision > status.revision &&
            itemResolver.accepts(replacement)
        ) {
            val currentState = current.state as? SessionState.InPage ?: state
            return replaceTopSnapshot(current, currentState, settled, status, replacement, nowMs)
        }
        // A rejected or stale action keeps the page exactly as the wearer saw it.
        val currentState = current.state as? SessionState.InPage ?: state
        return SessionTransition(current.copy(state = currentState.withTop(settled)), listOf(ShowFrame))
    }

    /**
     * A compose send's result (plan 028a §7.2). `done`: the app took it, so the draft goes and its
     * thread is handed off. `authority_lost`: the text stays and can never be sent as is. Anything
     * else (stale, rejected) keeps the draft; a new Send needs a new focus on what is shown then.
     */
    private fun composeSettled(model: SessionModel, send: PendingSend, result: PageSurfaceResult, revision: Long?): SessionModel {
        val base = model.copy(pendingSend = null)
        return when {
            result.status == RESULT_DONE -> {
                val history = model.history.markHandedOff(send.ownerPluginId, send.threadKey)
                base.copy(
                    draft = DraftStore.handedOff(model.draft, send),
                    history = history,
                    handoff = revision?.let { Handoff(send.ownerPluginId, send.threadKey, send.text, it) },
                    state = model.state.mapRootStops { RootStops.withHistory(it, history, wallClock()) },
                )
            }
            result.code == PageSurfaceContract.CODE_AUTHORITY_LOST ->
                base.copy(draft = DraftStore.authorityLost(model.draft, send.ownerPluginId, send.threadKey, revision))
            // Any other refusal of a send may come after the app already took the text (a provider
            // that cannot tell): it is unconfirmed, Type comes first, and it is never resent as is.
            result.status == RESULT_REJECTED -> base.copy(draft = DraftStore.unconfirmed(model.draft, send))
            else -> base
        }
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
            status = status.copy(snapshot = Snapshot(page, extended = itemResolver.acceptsExtended()), revision = page.revision),
            revisionFloor = page.revision,
            shownAtMs = nowMs,
            // A confirmation was for the revision the wearer saw; a new one cancels it. The display
            // page is the hub's own preference, bound to no revision: guidance updates keep it.
            panel = top.panel as? FramePanel.Display,
        )
        val frame = reselect(replaced, top, page)
            .let { if (it.mode == FrameMode.READING && !it.readsFirst()) it.copy(mode = FrameMode.ROWS) else it }
            .refocused()
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
        val entries = entriesFor(page, next.openApp)
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
        val label = before.retained()?.let { previous -> entriesFor(previous.page, before.openApp).firstOrNull { it.key == key }?.label }
        return withLayout.copy(
            selected = before.selected.coerceIn(0, entries.size),
            selectedKey = null,
            tombstone = label?.ifBlank { null } ?: REMOVED_ROW_LABEL,
        )
    }

    /** The renderer's measured pages of the text being read; never an action, never another frame's. */
    private fun documentPaged(
        model: SessionModel,
        state: SessionState.InPage,
        event: SessionEvent.DocumentPaged,
    ): SessionTransition {
        val top = state.frames.last()
        // A conversation is read from its newest page, then where the wearer paged it to.
        if (top.shownConversation() != null) {
            if (!describesTop(model, state, event.frame, event.pageId, event.revision) || event.pageStarts.isEmpty()) return keep(model)
            val page = if (top.anchor == null) event.pageStarts.lastIndex else event.anchorPage.coerceIn(0, event.pageStarts.lastIndex)
            val frame = top.copy(pageStarts = event.pageStarts, page = page, anchor = top.anchor ?: event.pageStarts.last())
            return if (frame == top) keep(model) else SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        // A report posted before the wearer went to the rows still describes what BACK returns to.
        if (!top.readsFirst() ||!describesTop(model, state, event.frame, event.pageId, event.revision) || event.pageStarts.isEmpty()) {
            return keep(model)
        }
        val page = event.anchorPage.coerceIn(0, event.pageStarts.lastIndex)
        val frame = top.copy(pageStarts = event.pageStarts, page = page, anchor = top.anchor ?: event.pageStarts.first())
        return if (frame == top) keep(model) else SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    /**
     * Content taller than the viewport is read page by page from its start; SELECT then reaches
     * its rows, or an unavailable page's Retry and Back. A panel is never displaced, and a row past
     * the first or a removed row the wearer holds is kept: its text is then reached by BACK.
     */
    private fun contentOverflowed(
        model: SessionModel,
        state: SessionState.InPage,
        event: SessionEvent.ContentOverflowed,
    ): SessionTransition {
        val top = state.frames.last()
        if (top.overflowing || top.panel != null || !describesTop(model, state, event.frame, event.pageId, event.revision)) {
            return keep(model)
        }
        // SELECT from the text returns to the first row, or to Retry: only a wearer already there reads it at once.
        val opens = if (top.status is FrameStatus.Unavailable) top.selected == 0 else top.selected == 0 && top.tombstone == null
        val frame = top.copy(overflowing = true)
        if (!frame.readsFirst()) return keep(model)
        // Pages measured for this revision stay: the renderer reports the same layout only once.
        val next = if (opens) frame.copy(mode = FrameMode.READING, anchor = null, page = 0) else frame
        return SessionTransition(model.copy(state = state.withTop(next)), listOf(ShowFrame))
    }

    /** A renderer report names the top frame at the revision of the content it retains. */
    private fun describesTop(model: SessionModel, state: SessionState.InPage, frame: FrameIdentity, pageId: String, revision: Long): Boolean {
        val top = state.frames.last()
        val retained = when (val status = top.status) {
            is FrameStatus.Shown -> status.revision
            is FrameStatus.Unavailable -> status.lastSnapshot?.page?.revision
            is FrameStatus.Loading -> null
        }
        return frame == FrameIdentity(model.generation, state.frames.size) && pageId == top.pageId && revision == retained
    }

    /**
     * Whether the frame's retained content is read before its rows: a document, or text measured
     * taller than the viewport. An unavailable page's retained content of any kind can overflow.
     */
    private fun Frame.readsFirst(): Boolean = when (val status = status) {
        is FrameStatus.Shown -> itemResolver.isDocument(status.snapshot.page) || (overflowing && itemResolver.hasText(status.snapshot.page))
        is FrameStatus.Unavailable -> status.lastSnapshot?.let { overflowing || itemResolver.isDocument(it.page) } == true
        is FrameStatus.Loading -> false
    }

    private fun Frame.reading(): Boolean = mode == FrameMode.READING && readsFirst()

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
        is FrameStatus.Shown -> entriesFor(status.snapshot.page, frame.openApp)
        is FrameStatus.Loading -> status.previous?.let { entriesFor(it.page, frame.openApp) }.orEmpty()
        is FrameStatus.Unavailable -> UNAVAILABLE_ITEMS.mapIndexed { index, item -> PageEntry("#$index", item) }
    }

    /** The page's own rows, then the hub's Open app row of a page opened for a launchable owner. */
    private fun entriesFor(page: PageSurfaceResponse.Page, openApp: OpenApp?): List<PageEntry> =
        ActivityCommands.withOpenApp(itemResolver.entries(page), openApp)

    /**
     * A provider page entered from Applications, over the launcher bundle, ends with the hub's
     * Open app when its owner's full app is launchable: the same row an activity's destination
     * page gets, so a page-capable plugin keeps its full app one labelled step away.
     */
    private fun applicationOpenApp(from: Frame, pageId: String): OpenApp? {
        if (!launcherActive() || from.pageId != RootStops.APPLICATIONS_PAGE_ID || isLocalPage(pageId)) return null
        return PageTemplateContract.namespaceOf(pageId)?.let(ownerApp)
    }

    private fun controlsOf(frame: Frame): PageControls? =
        (frame.status as? FrameStatus.Shown)?.let { itemResolver.controls(it.snapshot.page) }

    // ---- navigation: route pages, the full map and the display page (plan 028a §5.5, §8.1) ----

    /** The index of a shown route frame's compact map among its entries, or null without one. */
    private fun Frame.routeMapIndex(): Int? {
        if (status !is FrameStatus.Shown) return null
        return entriesOf(this).indexOfFirst { it.item == PageItem.RouteMap }.takeIf { it >= 0 }
    }

    /**
     * The index of a shown route frame's Commands entry, or null for any other frame. The page
     * selects only what comes up to it (the map, Commands); the command list is what follows it.
     */
    private fun Frame.routeCommandsIndex(): Int? {
        if (status !is FrameStatus.Shown) return null
        return entriesOf(this).indexOfFirst { it.item == PageItem.RouteCommands }.takeIf { it >= 0 }
    }

    /** The mode in force: the full map only while there is a map to draw, a command list while there is one. */
    private fun Frame.fullMapMode(): FullMapMode? = when (val mode = fullMap) {
        null -> null
        FullMapMode.MAP -> mode.takeIf { routeMapIndex() != null }
        FullMapMode.COMMANDS, FullMapMode.PAGE_COMMANDS -> mode.takeIf { routeCommandsIndex() != null }
    }

    /** [mode] on this frame, with the map selected: where the map's own Back and its commands' land. */
    private fun Frame.onMap(mode: FullMapMode?): Frame {
        val index = routeMapIndex() ?: 0
        return copy(fullMap = mode, selected = index, selectedKey = entriesOf(this).getOrNull(index)?.key, tombstone = null)
    }

    /** The page itself with Commands selected: where a command list opened from it goes back to. */
    private fun Frame.onCommandsRow(): Frame {
        val index = routeCommandsIndex() ?: 0
        return copy(fullMap = null, selected = index, selectedKey = entriesOf(this).getOrNull(index)?.key, tombstone = null)
    }

    /** The command list over this frame, on its first command; null when there is none. */
    private fun Frame.onCommands(mode: FullMapMode): Frame? {
        val first = (routeCommandsIndex() ?: return null) + 1
        val entry = entriesOf(this).getOrNull(first) ?: return null
        return copy(fullMap = mode, selected = first, selectedKey = entry.key, tombstone = null)
    }

    /**
     * Step on a route page moves between the map and Commands, nothing else (r17 3.4); the ends
     * hold. Off a removed row it lands on the row on that side, as on any page.
     */
    private fun routePageStep(model: SessionModel, state: SessionState.InPage, top: Frame, commands: Int, delta: Int): SessionTransition {
        val entries = entriesOf(top)
        val target = if (top.tombstone != null) {
            (if (delta > 0) top.selected else top.selected - 1).takeIf { it in 0..commands }
        } else {
            (top.selected + delta).coerceIn(0, commands).takeIf { it != top.selected }
        }
        if (target == null || target !in entries.indices) return keep(model)
        val frame = top.copy(selected = target, selectedKey = entries[target].key, tombstone = null)
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    /**
     * Step on the full map switches between its two views, without wrapping; the view is kept per
     * owner for the session. Step in a command list walks its commands, never back onto the map
     * or Commands.
     */
    private fun fullMapStep(model: SessionModel, state: SessionState.InPage, top: Frame, mode: FullMapMode, delta: Int): SessionTransition =
        when (mode) {
            FullMapMode.MAP -> {
                val owner = PageTemplateContract.namespaceOf(top.pageId)
                val view = owner?.let { model.mapViews[it] } ?: MapView.FOLLOW
                val next = RoutePresentation.stepView(view, delta)
                if (owner == null || next == view) {
                    keep(model)
                } else {
                    SessionTransition(model.copy(mapViews = model.mapViews + (owner to next)), listOf(ShowFrame))
                }
            }
            FullMapMode.COMMANDS, FullMapMode.PAGE_COMMANDS -> {
                val entries = entriesOf(top)
                val first = (top.routeCommandsIndex() ?: -1) + 1
                val target = (top.selected + delta).coerceIn(first, entries.lastIndex.coerceAtLeast(first))
                if (target == top.selected || target !in entries.indices) {
                    keep(model)
                } else {
                    val frame = top.copy(selected = target, selectedKey = entries[target].key, tombstone = null)
                    SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
                }
            }
        }

    /**
     * Enter on the full map, and on a route page's hub rows; null for anything else, which the
     * page's own rows answer. None of these reaches the plugin, so none waits for a fresh press.
     */
    private fun routeEnter(model: SessionModel, state: SessionState.InPage, top: Frame): SessionTransition? {
        val mode = top.fullMapMode()
        val entries = entriesOf(top)
        if (mode == FullMapMode.MAP) {
            // Enter on the full map opens the command list, on the first command.
            if (top.routeCommandsIndex() == null) return null
            val frame = top.onCommands(FullMapMode.COMMANDS) ?: return keep(model)
            return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        if (top.tombstone != null) return null
        return when (val item = entries.getOrNull(top.selected)?.item) {
            PageItem.RouteMap, PageItem.RouteFullScreen ->
                SessionTransition(model.copy(state = state.withTop(top.onMap(FullMapMode.MAP))), listOf(ShowFrame))
            PageItem.RouteCommands -> {
                val frame = top.onCommands(FullMapMode.PAGE_COMMANDS) ?: return keep(model)
                SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
            }
            is PageItem.RouteView -> {
                val owner = PageTemplateContract.namespaceOf(top.pageId) ?: return keep(model)
                // A view chosen in the command list is shown on the full map (r17 3.7).
                val next = if (top.routeMapIndex() != null) state.withTop(top.onMap(FullMapMode.MAP)) else state
                SessionTransition(model.copy(state = next, mapViews = model.mapViews + (owner to item.view)), listOf(ShowFrame))
            }
            is PageItem.RouteDisplay -> {
                val selected = item.current?.let(RoutePresentation.SIZES::indexOf)?.takeIf { it >= 0 } ?: 0
                val panel = FramePanel.Display(item.ownerPluginId, item.current, selected)
                SessionTransition(model.copy(state = state.withTop(top.copy(panel = panel))), listOf(ShowFrame))
            }
            else -> null
        }
    }

    /** The display page walks Text, Minimap and Panel without wrapping; the island previews the choice. */
    private fun displayStep(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        panel: FramePanel.Display,
        delta: Int,
    ): SessionTransition {
        val target = (panel.selected + delta).coerceIn(0, RoutePresentation.SIZES.lastIndex)
        if (target == panel.selected) return keep(model)
        val frame = top.copy(panel = panel.copy(selected = target, moved = true))
        return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
    }

    /** A media page's focus follows its control into new content, and ends with it. */
    private fun Frame.refocused(): Frame {
        val focus = commandFocus?.takeIf { it.stopId == CommandFocus.MEDIA } ?: return this
        val key = selectedKey
        if (tombstone != null || key == null || controlsOf(this) == null) return copy(commandFocus = null)
        return copy(commandFocus = focus.copy(key = key, index = selected))
    }

    /** Provider pages carry the phone's quick stamps only over a pair that negotiated the bundle. */
    private fun hubStamps(pageId: String): Boolean = launcherActive() && !isLocalPage(pageId)

    /**
     * The fresh-contact rule (plan 028a §5.3), for an Enter that would cause an effect. A v1 pair
     * keeps its behaviour; over the bundle the press must have begun after the target was drawn.
     */
    private fun fresh(model: SessionModel, event: SessionEvent.Enter): Boolean =
        !launcherActive() || SessionFocus.isFresh(model, event.pressStartMs)

    // ---- notifications: the root's notification stop and the Notifications page -------------

    /**
     * The notification stop opens what it stands for and marks its entry seen: a conversation card
     * its exact destination, an aggregate its owner's main page, a generic preview the hub's notice
     * page on its entry. While the phone is away an entry is read-only: its notice page opens.
     */
    private fun openNoticeStop(model: SessionModel, state: SessionState.Root, stop: RootStop, nowMs: Long): SessionTransition {
        val card = stop.notice
        val entryId = card?.entryId
        if (model.linkLost && card?.kind == NoticeCardKind.CONVERSATION && entryId != null) {
            return openFrame(model, state, emptyList(), RootStops.NOTICE_PAGE_ID, NotificationHistory.entryParams(entryId), nowMs)
        }
        val seen = if (entryId != null && !model.linkLost) model.copy(history = model.history.markSeen(entryId)) else model
        return openFrame(seen, state, emptyList(), stop.pageId, stop.paramsJson, nowMs)
    }

    /** A Notifications row opens its entry as the notification stop would, and marks it seen. */
    private fun openEntry(model: SessionModel, root: SessionState.Root, below: List<Frame>, entryId: String, nowMs: Long): SessionTransition {
        val entry = model.history.entry(entryId) ?: return keep(model)
        val destination = entry.notice.destination?.takeIf { entry.notice.threadKey != null && !model.linkLost }
        val seen = if (model.linkLost) model else model.copy(history = model.history.markSeen(entryId))
        return if (destination != null) {
            openFrame(seen, root, below, destination.pageId, destination.paramsJson, nowMs)
        } else {
            openFrame(seen, root, below, RootStops.NOTICE_PAGE_ID, NotificationHistory.entryParams(entryId), nowMs)
        }
    }

    // ---- the composer: conversation pages, captures and Send ----------------------------------

    /** This frame's conversation when it shows one: the page's owner and its body. */
    /**
     * The newest revision of [owner]'s thread [threadKey] page shown in the open session, if any:
     * the revision a lost authority is bound to, so only a newer one may reopen the draft.
     */
    private fun threadRevision(model: SessionModel, owner: String, threadKey: String?): Long? {
        threadKey ?: return null
        val frames = when (val state = model.state) {
            is SessionState.InPage -> state.frames
            is SessionState.Launching -> (state.previous as? SessionState.InPage)?.frames.orEmpty()
            else -> emptyList()
        }
        return frames.mapNotNull { frame ->
            val shown = frame.status as? FrameStatus.Shown ?: return@mapNotNull null
            val (frameOwner, body) = frame.shownConversation() ?: return@mapNotNull null
            shown.revision.takeIf { frameOwner == owner && body.threadKey == threadKey }
        }.maxOrNull()
    }

    private fun Frame.shownConversation(): Pair<String?, PageBody.Conversation>? {
        val shown = status as? FrameStatus.Shown ?: return null
        val body = itemResolver.conversation(shown.snapshot.page) ?: return null
        return PageTemplateContract.namespaceOf(pageId) to body
    }

    private fun composerView(model: SessionModel, top: Frame, body: PageBody.Conversation): ComposerView? =
        ComposerRules.view(PageTemplateContract.namespaceOf(top.pageId), body, model.draft, model.micUnavailable, model.pendingSend)

    /**
     * Reading pages the conversation through the renderer's measured layout; in focus, STEP walks
     * the composer's buttons without wrapping; a capture ignores it.
     */
    private fun conversationStep(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        body: PageBody.Conversation,
        delta: Int,
    ): SessionTransition {
        if (model.capture != null) return keep(model)
        top.composerFocus?.let { focus ->
            val view = composerView(model, top, body) ?: return keep(model)
            val target = (focus.index + delta).coerceIn(0, view.buttons.lastIndex)
            if (target == focus.index) return keep(model)
            val frame = top.copy(composerFocus = ComposerFocus(view.buttons[target], target))
            return SessionTransition(model.copy(state = state.withTop(frame)), listOf(ShowFrame))
        }
        if (top.pageStarts.isEmpty()) return keep(model)
        val target = (top.page + delta).coerceIn(0, top.pageStarts.lastIndex)
        if (target == top.page) return keep(model)
        return SessionTransition(model.copy(state = state.withTop(top.copy(page = target, anchor = top.pageStarts[target]))), listOf(ShowFrame))
    }

    /**
     * Plan 028a §7.2. Reading: a tap focuses the composer's first button. Focus: a tap starts the
     * focused capture, or sends. Capture: a tap finishes it into the draft and nothing is sent.
     * Focusing, starting and sending each need a press begun after their screen was drawn.
     */
    private fun conversationEnter(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        status: FrameStatus.Shown,
        body: PageBody.Conversation,
        event: SessionEvent.Enter,
    ): SessionTransition {
        model.capture?.let { capture ->
            if (capture.finishing) return keep(model)
            return SessionTransition(
                model.copy(capture = capture.copy(finishing = true, deadlineMs = event.nowMs + ComposerRules.FINISH_TIMEOUT_MS)),
                listOf(SessionEffect.SendCompose(ComposeClient.finish(capture)), ShowFrame),
            )
        }
        if (top.invocation != null || model.pendingSend != null || model.linkLost) return keep(model)
        val view = composerView(model, top, body)?.takeIf { it.available } ?: return keep(model)
        if (!fresh(model, event)) return keep(model)
        val focus = top.composerFocus
            ?: return SessionTransition(
                model.copy(state = state.withTop(top.copy(composerFocus = ComposerFocus(view.buttons[view.initialIndex], view.initialIndex)))),
                listOf(ShowFrame),
            )
        if (view.buttons.getOrNull(focus.index) != focus.key) return keep(model)
        return when (focus.key) {
            ComposerRules.SEND -> send(model, state, top, status, body, view, event.nowMs)
            ComposerRules.DICTATE -> startCapture(model, state, top, body, view, CaptureKind.DICTATE)
            ComposerRules.TYPE -> startCapture(model, state, top, body, view, CaptureKind.TYPE)
            else -> keep(model)
        }
    }

    /** Typing opens the phone keyboard on this thread's draft; dictation listens on the glasses mic. */
    private fun startCapture(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        body: PageBody.Conversation,
        view: ComposerView,
        kind: CaptureKind,
    ): SessionTransition {
        val owner = PageTemplateContract.namespaceOf(top.pageId) ?: return keep(model)
        val composer = body.composer ?: return keep(model)
        val maxChars = ComposerRules.maxChars(body)
        val initial = if (kind == CaptureKind.TYPE) view.threadDraft?.text?.take(maxChars).orEmpty() else ""
        val captureId = ComposerRules.captureId(model.generation, model.nextId)
        val capture = Capture(
            captureId, kind, model.generation, state.frames.size, top.pageId, owner, body.threadKey,
            PageDestination(top.pageId, top.paramsJson), composer.recipient, maxChars, liveText = initial,
        )
        val message = when (kind) {
            CaptureKind.DICTATE -> ComposeOutbound.DictateStart(ComposeDictateStart(captureId))
            CaptureKind.TYPE -> ComposeOutbound.TypeStart(ComposeTypeStart(captureId, initial, maxChars))
        }
        return SessionTransition(
            model.copy(nextId = model.nextId + 1, capture = capture, state = state.withTop(top.copy(composerFocus = null))),
            listOf(SessionEffect.SendCompose(message), ShowFrame),
        )
    }

    /**
     * Plan 028a §7.4: only this page's own ready draft, for this exact thread and owner, through
     * the page's compose action, once. Nothing ever retries or resends it.
     */
    private fun send(
        model: SessionModel,
        state: SessionState.InPage,
        top: Frame,
        status: FrameStatus.Shown,
        body: PageBody.Conversation,
        view: ComposerView,
        nowMs: Long,
    ): SessionTransition {
        val composer = body.composer ?: return keep(model)
        val draft = view.threadDraft?.takeIf { it.state == DraftState.READY } ?: return keep(model)
        if (!composer.replyable || draft.text.isBlank() || draft.text.length > composer.maxChars) return keep(model)
        val cleared = state.withTop(top.copy(composerFocus = null))
        val sent = invoke(model.copy(state = cleared), cleared, status, composer.sendActionId, nowMs, PageSurfaceContract.MODE_COMPOSE, draft.text)
        val invocationId = (sent.model.state as? SessionState.InPage)?.frames?.last()?.invocation?.invocationId ?: return keep(model)
        return sent.copy(model = sent.model.copy(pendingSend = PendingSend(invocationId, draft.ownerPluginId, draft.threadKey, draft.text)))
    }

    /**
     * A capture engine's message counts only for the capture in progress, by its id; anything else
     * is stale and dropped. A completed capture replaces the draft and returns to reading; a
     * dictation ending any other way sends the wearer to Type; nothing here ever sends a reply.
     */
    private fun onCompose(model: SessionModel, event: SessionEvent.ComposeReceived): SessionTransition {
        val capture = model.capture?.takeIf { it.captureId == event.message.captureId } ?: return keep(model)
        val next = when (val message = event.message) {
            is ComposeInbound.Partial ->
                if (capture.kind == CaptureKind.DICTATE) model.copy(capture = capture.copy(liveText = message.partial.text.take(capture.maxChars))) else model
            is ComposeInbound.Text ->
                if (capture.kind == CaptureKind.TYPE) model.copy(capture = capture.copy(liveText = message.text.text.take(capture.maxChars))) else model
            is ComposeInbound.Final -> {
                val text = message.result.text
                when {
                    capture.kind != CaptureKind.DICTATE -> model
                    message.result.reason == ComposeDictationReason.COMPLETED && !text.isNullOrBlank() ->
                        model.copy(capture = null, micUnavailable = false, draft = DraftStore.completed(capture, text, model.history, model.draft, threadRevision(model, capture.ownerPluginId, capture.threadKey)))
                    // No phone microphone stands in: the wearer is offered Type, focused.
                    else -> focusType(model.copy(capture = null, micUnavailable = true), capture)
                }
            }
            is ComposeInbound.Done -> {
                val text = message.done.text?.takeIf { message.done.committed }
                when {
                    capture.kind != CaptureKind.TYPE -> model
                    // Dismissed at the phone: nothing changes.
                    text == null -> model.copy(capture = null)
                    // An empty commit is the one explicit discard.
                    text.isBlank() -> model.copy(capture = null, draft = DraftStore.committedEmpty(model.draft, capture))
                    else -> model.copy(capture = null, draft = DraftStore.completed(capture, text, model.history, model.draft, threadRevision(model, capture.ownerPluginId, capture.threadKey)))
                }
            }
        }
        if (next == model) return keep(model)
        return SessionTransition(next, listOfNotNull(showEffect(next.state)))
    }

    /** After a dictation that ended without text, its conversation frame focuses Type. */
    private fun focusType(model: SessionModel, capture: Capture): SessionModel {
        val state = model.state as? SessionState.InPage ?: return model
        val top = state.frames.last()
        if (model.generation != capture.generation || state.frames.size != capture.frameIndex || top.pageId != capture.pageId) return model
        val (_, body) = top.shownConversation() ?: return model
        val view = composerView(model, top, body)?.takeIf { it.available } ?: return model
        val index = view.buttons.indexOf(ComposerRules.TYPE)
        return model.copy(state = state.withTop(top.copy(composerFocus = ComposerFocus(ComposerRules.TYPE, index))))
    }

    /**
     * The composer's invariants, after every transition. A capture lives only on its own live,
     * replyable conversation frame with the link up, and is cancelled at the phone as soon as it
     * does not (the earlier draft stays); a finished one whose text never came is given up. A send
     * whose frame stopped waiting for it will never hear its result here: its draft is unconfirmed.
     * A page saying its thread cannot be replied to takes its draft's authority. A focused button
     * that is gone returns the frame to reading.
     */
    private fun settleComposer(transition: SessionTransition, event: SessionEvent): SessionTransition {
        var after = transition.model
        val effects = transition.effects.toMutableList()
        after.capture?.let { capture ->
            val expired = event is SessionEvent.Tick && capture.deadlineMs?.let { event.nowMs >= it } == true
            if (expired || !captureHolds(after, capture)) {
                val micFailed = expired && capture.kind == CaptureKind.DICTATE
                after = after.copy(capture = null, micUnavailable = after.micUnavailable || micFailed)
                if (micFailed) after = focusType(after, capture)
                effects += SessionEffect.SendCompose(ComposeClient.cancel(capture))
            }
        }
        after.pendingSend?.let { send ->
            val top = (after.state as? SessionState.InPage)?.frames?.last()
            if (top?.invocation?.invocationId != send.invocationId) {
                after = after.copy(pendingSend = null, draft = DraftStore.unconfirmed(after.draft, send))
            }
        }
        val page = after.state as? SessionState.InPage
        val top = page?.frames?.last()
        val conversation = top?.shownConversation()
        val draft = after.draft
        if (conversation != null && draft != null) {
            val (owner, body) = conversation
            val revision = (top?.status as? FrameStatus.Shown)?.revision
            val lostAsIs = draft.state == DraftState.AUTHORITY_LOST && !draft.reopened
            if (body.composer?.replyable == false && draft.isFor(owner, body.threadKey) && !lostAsIs) {
                after = after.copy(draft = DraftStore.authorityLost(draft, draft.ownerPluginId, draft.threadKey, revision))
            } else if (body.composer?.replyable == true && revision != null) {
                // A newer replyable revision of the draft's own thread reopens it like a new notice.
                after = after.copy(draft = DraftStore.pageReplyable(draft, owner, body.threadKey, revision))
            }
        }
        val focus = top?.composerFocus
        if (page != null && top != null && focus != null) {
            val view = conversation?.let { (owner, body) -> ComposerRules.view(owner, body, after.draft, after.micUnavailable, after.pendingSend) }
            val index = view?.takeIf { it.available && after.capture == null }?.buttons?.indexOf(focus.key) ?: -1
            val next = if (index >= 0) focus.copy(index = index) else null
            if (next != focus) after = after.copy(state = page.withTop(top.copy(composerFocus = next)))
        }
        if (after == transition.model) return transition
        showEffect(after.state)?.takeIf { it !in effects }?.let { effects += it }
        return SessionTransition(after, effects)
    }

    private fun captureHolds(model: SessionModel, capture: Capture): Boolean {
        val page = model.state as? SessionState.InPage ?: return false
        if (model.linkLost || model.generation != capture.generation || page.frames.size != capture.frameIndex) return false
        val top = page.frames.last()
        if (top.pageId != capture.pageId || top.panel != null) return false
        val (owner, body) = top.shownConversation() ?: return false
        if (owner != capture.ownerPluginId || body.threadKey != capture.threadKey) return false
        return ComposerRules.view(owner, body, model.draft, model.micUnavailable, model.pendingSend)?.available == true
    }

    // ---- activity cards: activation, command focus, commands --------------------------------

    /** Where a command focus lives and how its focus and panel are put back. */
    private class CommandSite(
        val focus: CommandFocus?,
        val panel: FramePanel?,
        val show: SessionEffect,
        val place: (CommandFocus?, FramePanel?) -> SessionState,
    )

    private fun rootSite(state: SessionState.Root) =
        CommandSite(state.focus, state.panel, ShowRoot) { focus, panel -> state.copy(focus = focus, panel = panel) }

    private fun frameSite(state: SessionState.InPage): CommandSite {
        val top = state.frames.last()
        return CommandSite(top.commandFocus, top.panel, ShowFrame) { focus, panel ->
            state.withTop(top.copy(commandFocus = focus, panel = panel))
        }
    }

    /** A root card's entry as last received; an ended card has none to act on. */
    private fun rootActivity(state: SessionState.Root, stopId: String): ActivityStop? =
        state.stops.firstOrNull { it.id == stopId }?.takeUnless { it.ended }?.activity

    /**
     * The first tap on an activity (plan 028a §5.1): its destination opens directly, with its
     * params and nothing else; commands take the focus without running; anything else opens
     * [pageId], the hub's summary. Without the bundle the v1 card opens [pageId] as it always did.
     */
    private fun activate(
        model: SessionModel,
        site: CommandSite,
        activity: ActivityStop,
        root: SessionState.Root,
        below: List<Frame>,
        pageId: String,
        nowMs: Long,
    ): SessionTransition {
        // A link loss keeps the destination the bundle validated: it opens inert, asking nothing.
        if (!launcherActive() && !model.linkLost) return openFrame(model, root, below, pageId, null, nowMs)
        val destination = activity.destination
        return when {
            activity.activation == ActivityActivation.DESTINATION && destination != null ->
                openFrame(model, root, below, destination.pageId, destination.paramsJson, nowMs, openAppOf(activity))
            activity.activation == ActivityActivation.COMMANDS && !activity.lastKnown && activity.commands.isNotEmpty() -> {
                val slots = ActivityCommands.slots(activity)
                val index = ActivityCommands.initialIndex(activity, slots)
                val focus = CommandFocus(activity.id, slots[index].key, index)
                SessionTransition(model.copy(state = site.place(focus, null)), listOf(site.show))
            }
            else -> openFrame(model, root, below, pageId, null, nowMs)
        }
    }

    private fun openAppOf(activity: ActivityStop): OpenApp? {
        val owner = activity.ownerPluginId ?: return null
        return if (activity.launchable) OpenApp(owner, activity.ownerLabel ?: owner) else null
    }

    /** Steps along the plugin's commands, then the hub's Open app, without wrapping. */
    private fun focusStep(model: SessionModel, site: CommandSite, activity: ActivityStop?, delta: Int): SessionTransition {
        val focus = site.focus ?: return keep(model)
        val slots = activity?.let(ActivityCommands::slots).orEmpty()
        if (slots.isEmpty()) return keep(model)
        val target = (focus.index + delta).coerceIn(0, slots.lastIndex)
        if (target == focus.index) return keep(model)
        return SessionTransition(model.copy(state = site.place(focus.copy(key = slots[target].key, index = target), null)), listOf(site.show))
    }

    /**
     * Runs the focused command: one tap for a stamped type the latest policy approves, over a
     * fresh press; otherwise the confirmation step with Cancel preselected. Open app takes the
     * labelled launch step. An offline entry's commands, and a second command for an owner that
     * has one pending, do nothing.
     */
    private fun focusEnter(model: SessionModel, site: CommandSite, activity: ActivityStop?, event: SessionEvent.Enter): SessionTransition {
        val focus = site.focus ?: return keep(model)
        activity ?: return keep(model)
        val owner = activity.ownerPluginId ?: return keep(model)
        val slot = ActivityCommands.slots(activity).getOrNull(focus.index)?.takeIf { it.key == focus.key } ?: return keep(model)
        val action = slot.action
            ?: return SessionTransition(
                model.copy(state = site.place(focus, FramePanel.Launch(owner, activity.ownerLabel ?: owner))),
                listOf(site.show),
            )
        if (!slot.enabled || activity.lastKnown || owner in model.pendingCommands) return keep(model)
        if (quickGate.allows(owner, action.typed, action.hubQuick)) {
            if (!fresh(model, event)) return keep(model)
            if (model.linkLost) return SessionTransition(model, listOf(SessionEffect.ShowStatus(SessionStatus.NotAvailable)))
            return sendCommand(model, activity, owner, action.id, ActivityCommandMode.QUICK, event.nowMs, site.show)
        }
        val panel = FramePanel.ConfirmCommand(activity.id, owner, action.id, action.label, activity.seq)
        return SessionTransition(model.copy(state = site.place(focus, panel)), listOf(site.show))
    }

    private fun panelStep(model: SessionModel, site: CommandSite, delta: Int): SessionTransition {
        val panel = site.panel ?: return keep(model)
        val target = (panel.selected + delta).coerceIn(0, 1)
        if (target == panel.selected) return keep(model)
        val next = when (panel) {
            is FramePanel.Confirm -> panel.copy(selected = target)
            is FramePanel.Launch -> panel.copy(selected = target)
            is FramePanel.ConfirmCommand -> panel.copy(selected = target)
            // Walked by displayStep; never reached from a command site.
            is FramePanel.Display -> return keep(model)
        }
        return SessionTransition(model.copy(state = site.place(site.focus, next)), listOf(site.show))
    }

    /** A command's confirmation, or the launch step of Open app, taken from command focus. */
    private fun commandPanelEnter(
        model: SessionModel,
        site: CommandSite,
        activity: ActivityStop?,
        event: SessionEvent.Enter,
    ): SessionTransition {
        val panel = site.panel ?: return keep(model)
        val placed = site.place(site.focus, null)
        val cleared = SessionTransition(model.copy(state = placed), listOf(site.show))
        return when (panel) {
            is FramePanel.Launch -> when {
                panel.selected != FramePanel.OPEN -> cleared
                !fresh(model, event) -> keep(model)
                else -> launch(model.copy(state = placed), placed, panel.pluginId, event.nowMs)
            }
            is FramePanel.ConfirmCommand -> {
                if (panel.selected != FramePanel.CONFIRM) return cleared
                if (!fresh(model, event)) return keep(model)
                val action = activity?.commands?.firstOrNull { it.id == panel.actionId }
                // The confirmation is for the revision the wearer saw; anything newer withdraws it.
                if (activity == null || action == null || activity.lastKnown || activity.seq != panel.seq ||
                    activity.ownerPluginId != panel.ownerPluginId || panel.ownerPluginId in model.pendingCommands
                ) {
                    return cleared
                }
                if (model.linkLost) return cleared.copy(effects = cleared.effects + SessionEffect.ShowStatus(SessionStatus.NotAvailable))
                sendCommand(model.copy(state = placed), activity, panel.ownerPluginId, action.id, ActivityCommandMode.CONFIRMED, event.nowMs, site.show)
            }
            // A page action's confirmation and the display page never reach a command site.
            is FramePanel.Confirm, is FramePanel.Display -> cleared
        }
    }

    /** Sends one command, once: it is pending until its result or its deadline, never resent. */
    private fun sendCommand(
        model: SessionModel,
        activity: ActivityStop,
        owner: String,
        actionId: String,
        mode: ActivityCommandMode,
        nowMs: Long,
        show: SessionEffect,
    ): SessionTransition {
        val (invocationId, counted) = model.nextId("c")
        val command = ActivityCommand(activity.id, actionId, invocationId, activity.seq, mode)
        val pending = PendingCommand(invocationId, owner, activity.id, actionId, activity.seq, nowMs)
        return SessionTransition(
            counted.copy(
                pendingCommands = counted.pendingCommands + (owner to pending),
                commandOutcomes = counted.commandOutcomes - owner,
            ),
            listOf(SessionEffect.SendActivityCommand(command), show),
        )
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

    /** The root redraws when it changed; a page only when its own top frame did, as command focus can. */
    private fun rootBookkeeping(before: SessionModel, after: SessionModel): SessionTransition {
        val state = after.state
        val effects = when {
            state is SessionState.Root && state != before.state -> listOf(ShowRoot)
            state is SessionState.InPage && state.frames.last() != (before.state as? SessionState.InPage)?.frames?.lastOrNull() ->
                listOf(ShowFrame)
            else -> emptyList()
        }
        return SessionTransition(after, effects)
    }

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
        const val RESULT_REJECTED = "rejected"
        const val UNAVAILABLE_LINK_LOST = "link_lost"

        /** SPP went away with CXR still up, and this page needed SPP. */
        const val UNAVAILABLE_DATA_PLANE = "data_plane"
        const val UNAVAILABLE_EVICTED = "evicted"

        /** A valid envelope whose typed body this hub cannot draw. */
        const val UNAVAILABLE_UNSUPPORTED = "unsupported"
        const val REMOVED_ROW_LABEL = "Removed"
        val UNAVAILABLE_ITEMS: List<PageItem> = listOf(PageItem.Retry, PageItem.Back)
        private val INERT_REASONS = setOf(UNAVAILABLE_LINK_LOST, UNAVAILABLE_DATA_PLANE, PageSurfaceContract.ERROR_PAGE_UNAVAILABLE)

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

/** A command past its deadline is unconfirmed from then on; nothing is ever sent again for it. */
private fun SessionModel.expireCommands(nowMs: Long): SessionModel {
    val expired = pendingCommands.values.filter { nowMs >= it.deadlineMs }
    if (expired.isEmpty()) return this
    return copy(
        pendingCommands = pendingCommands - expired.map(PendingCommand::ownerPluginId).toSet(),
        commandOutcomes = commandOutcomes +
            expired.associate { it.ownerPluginId to CommandOutcome(it.actionId, PageSurfaceContract.ERROR_UNCONFIRMED_ACTION) },
    )
}

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
