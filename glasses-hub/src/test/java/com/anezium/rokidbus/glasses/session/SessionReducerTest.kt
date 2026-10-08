package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.glasses.session.SessionEffect.SendClosed
import com.anezium.rokidbus.glasses.session.SessionEffect.SendVisibility
import com.anezium.rokidbus.glasses.session.SessionEffect.ShowFrame
import com.anezium.rokidbus.glasses.session.SessionEffect.ShowRoot
import com.anezium.rokidbus.glasses.session.SessionEvent.ActivityEnded
import com.anezium.rokidbus.glasses.session.SessionEvent.ActivityStarted
import com.anezium.rokidbus.glasses.session.SessionEvent.Back
import com.anezium.rokidbus.glasses.session.SessionEvent.Contact
import com.anezium.rokidbus.glasses.session.SessionEvent.EditableFocused
import com.anezium.rokidbus.glasses.session.SessionEvent.Enter
import com.anezium.rokidbus.glasses.session.SessionEvent.NoticeArrived
import com.anezium.rokidbus.glasses.session.SessionEvent.PageResponse
import com.anezium.rokidbus.glasses.session.SessionEvent.PageResult
import com.anezium.rokidbus.glasses.session.SessionEvent.Step
import com.anezium.rokidbus.glasses.session.SessionEvent.Tick
import com.anezium.rokidbus.glasses.session.SessionEvent.TripleTap
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionReducerTest {
    /** Test rows: a `hub` action opens the page named by its id, anything else invokes. */
    private val testItems = PageItemResolver { page ->
        page.actions.map { if (it.kind == "hub") PageItem.OpenPage(it.id) else PageItem.Invoke(it.id) }
    }
    private val reducer = SessionReducer(testItems)
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    @Test
    fun `rule 1 triple tap in closed opens the gate`() {
        send(TripleTap(1_000))

        assertEquals(SessionState.Opening::class, model.state::class)
        assertEquals(1_000L, (model.state as SessionState.Opening).sinceMs)
        assertEquals(listOf(SessionEffect.ShowGate), effects)
        assertEquals(1L, model.generation)
    }

    @Test
    fun `rule 1 triple tap is refused while a notice is armed`() {
        send(SessionEvent.NoticeArmed)
        val armed = model

        send(TripleTap(1_000))
        assertSame(SessionState.Closed, model.state)
        assertEquals(armed, model)
        assertEquals(listOf(SessionEffect.None), effects)

        send(SessionEvent.NoticeCleared)
        send(TripleTap(2_000))
        assertTrue(model.state is SessionState.Opening)
    }

    @Test
    fun `rule 1 triple tap is ignored while an editable field is focused`() {
        send(EditableFocused(true))
        send(TripleTap(1_000))
        assertSame(SessionState.Closed, model.state)
        assertEquals(listOf(SessionEffect.None), effects)

        send(EditableFocused(false))
        openRoot(2_000)
        send(EditableFocused(true))
        send(Back(4_000))
        assertTrue(model.editableFocused)
        send(TripleTap(5_000))
        assertSame(SessionState.Closed, model.state)
    }

    @Test
    fun `rule 2 the gate absorbs every gesture and opens the root at its deadline`() {
        send(TripleTap(1_000))
        listOf(Contact(1_100), Enter(1_150), Back(1_200), Step(1, 1_300), Step(-1, 1_400), TripleTap(1_500)).forEach {
            send(it)
            assertTrue(model.state is SessionState.Opening)
            assertEquals(listOf(SessionEffect.None), effects)
        }

        send(Tick(1_799))
        assertTrue(model.state is SessionState.Opening)
        send(Tick(1_000 + PageSurfaceContract.GATE_MS))
        assertTrue(model.state is SessionState.Root)
        assertEquals(listOf(ShowRoot), effects)
    }

    @Test
    fun `rule 2 a classification arriving after the gate for a gate contact is still absorbed`() {
        send(TripleTap(1_000))
        send(Contact(1_700))
        send(Tick(1_800))
        val root = model.state

        send(Enter(2_000))
        assertEquals(root, model.state)
        assertEquals(listOf(SessionEffect.None), effects)

        send(Enter(2_100))
        assertTrue(model.state is SessionState.InPage)
        assertEquals(1, effects.filterIsInstance<RequestPage>().size)
    }

    @Test
    fun `rule 2 review a gate-owned classification at contact plus 800 ms is still absorbed`() {
        send(TripleTap(1_000))
        send(Contact(1_700))
        send(Tick(1_800))
        val root = model.state

        send(Enter(1_700 + PageSurfaceContract.GATE_MS))
        assertEquals(root, model.state)
        assertEquals(listOf(SessionEffect.None), effects)
        assertTrue(model.gateContacts.isEmpty())
    }

    @Test
    fun `rule 2 a new contact after the gate releases an unsettled gate contact`() {
        send(TripleTap(1_000))
        send(Contact(1_600))
        send(Contact(1_700))
        send(Tick(1_800))
        send(Back(2_000))
        assertTrue(model.state is SessionState.Root)
        assertEquals(1, model.gateContacts.size)

        send(Contact(5_000))
        send(Step(1, 5_300))
        assertEquals(1, root().selected)
        assertEquals(listOf(ShowRoot), effects)
    }

    @Test
    fun `rule 3 step moves the root selection without wrapping`() {
        openRoot()
        send(Step(-1, 3_000))
        assertEquals(0, root().selected)
        assertEquals(listOf(SessionEffect.None), effects)

        repeat(root().stops.size + 2) { send(Step(1, 3_100L + it)) }
        assertEquals(root().stops.lastIndex, root().selected)
        assertEquals(listOf(SessionEffect.None), effects)
    }

    @Test
    fun `rule 3 enter on a stop opens one loading frame and requests it`() {
        openRoot()
        send(Enter(3_000))

        val state = model.state as SessionState.InPage
        assertEquals(1, state.frames.size)
        val loading = state.frames.single().status as FrameStatus.Loading
        val request = requested()
        assertEquals(listOf(RequestPage(request), ShowFrame), effects)
        assertEquals(1, request.correlation.frameIndex)
        assertEquals("maps:route", request.correlation.pageId)
        assertEquals("open", request.reason)
        assertEquals(model.generation, request.correlation.sessionGeneration)
        assertEquals(loading.requestId, request.correlation.requestId)
        assertEquals(3_000L, loading.sinceMs)
        assertTrue(PageSurfaceContract.validateRequest(request.toPayload()) is PageSurfaceValidationResult.Valid)
    }

    @Test
    fun `rule 3 back at the root closes and restores underneath`() {
        openRoot()
        send(Back(3_000))
        assertSame(SessionState.Closed, model.state)
        assertEquals(listOf(SessionEffect.RestoreUnderneath), effects)
    }

    @Test
    fun `rule 3 triple tap at the root stays at the root`() {
        openRoot()
        send(Step(1, 3_000))
        val root = model.state

        send(TripleTap(3_500))
        assertEquals(root, model.state)
        assertEquals(listOf(SessionEffect.None), effects)
    }

    @Test
    fun `rule 3 the stop list is frozen while the session is open`() {
        send(ActivityStarted(ActivityStop("transit", "transit:ride", "2 stops")))
        send(NoticeArrived(NoticePreview("relay:thread-1", "Ana: hi")))
        openRoot()
        send(Step(1, 3_000))
        val before = root()

        send(ActivityStarted(ActivityStop("timer", "timer:main", "4:00")))
        assertEquals(before, root())
        assertEquals(listOf("timer"), model.queuedActivities.map(ActivityStop::id))

        send(ActivityEnded("maps"))
        assertEquals(before.stops.map(RootStop::id), root().stops.map(RootStop::id))
        assertTrue(root().stops.first { it.id == "maps" }.ended)
        assertEquals(1, root().selected)

        send(NoticeArrived(NoticePreview("relay:thread-2", "Bo: on my way")))
        assertEquals(before.stops.map(RootStop::id), root().stops.map(RootStop::id))
        assertEquals(1, root().selected)
        val preview = root().stops.single { it.kind == RootStopKind.NOTICE_PREVIEW }
        assertEquals("Bo: on my way", preview.text)
        assertEquals(2, root().stops.single { it.kind == RootStopKind.NOTIFICATIONS }.count)
        assertEquals(listOf(ShowRoot), effects)

        send(Back(4_000))
        assertEquals(listOf("transit", "timer"), model.activities.map(ActivityStop::id))
        assertTrue(model.queuedActivities.isEmpty())
        openRoot(5_000)
        assertEquals(3, root().stops.single { it.kind == RootStopKind.ACTIVITIES }.count)
    }

    @Test
    fun `rule 4 the root builder orders stops and inserts the activities stop`() {
        val stops = RootStops.build(
            listOf(
                ActivityStop("timer", "timer:main", "4:00"),
                ActivityStop("maps", "maps:route", "300 m", pinned = true),
                ActivityStop("transit", "transit:ride", "2 stops"),
            ),
            NoticePreview("relay:thread-1", "Ana: hi"),
            notificationCount = 4,
        )

        assertEquals(
            listOf(
                RootStopKind.ACTIVITY, RootStopKind.ACTIVITY, RootStopKind.NOTICE_PREVIEW,
                RootStopKind.ACTIVITIES, RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS,
            ),
            stops.map(RootStop::kind),
        )
        assertEquals(listOf("maps", "timer"), stops.take(2).map(RootStop::id))
        assertEquals(3, stops[3].count)
        assertEquals(4, stops[4].count)
    }

    @Test
    fun `rule 4 the root never exceeds six stops and omits absent stops`() {
        val many = (1..10).map { ActivityStop("a$it", "a$it:page", "A$it") }
        assertEquals(
            PageSurfaceContract.MAX_ROOT_STOPS,
            RootStops.build(many, NoticePreview("relay:thread", "x"), 1).size,
        )
        val two = RootStops.build(many.take(2), null, 0)
        assertEquals(
            listOf(RootStopKind.ACTIVITY, RootStopKind.ACTIVITY, RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS),
            two.map(RootStop::kind),
        )
        assertEquals(
            listOf(RootStopKind.NOTIFICATIONS, RootStopKind.APPLICATIONS),
            RootStops.build(emptyList(), null, 0).map(RootStop::kind),
        )
    }

    @Test
    fun `rule 5 back pops one frame closing it and leasing the uncovered frame`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("detail", kind = "hub")))
        send(Enter(5_000))
        respond(requested(), now = 5_100)

        send(Back(6_000))
        val refresh = requested()
        assertEquals(
            listOf(
                SendClosed("detail", "back"),
                SendVisibility("maps:route", true, 6_000 + PageSurfaceContract.LEASE_MS),
                RequestPage(refresh),
                ShowFrame,
            ),
            effects,
        )
        assertEquals(1, frames().size)
    }

    @Test
    fun `rule 5 back on the last frame returns to the root selection`() {
        send(ActivityStarted(ActivityStop("transit", "transit:ride", "2 stops")))
        openRoot()
        send(Step(1, 3_000))
        send(Enter(3_100))
        assertEquals("transit:ride", requested().correlation.pageId)

        send(Back(3_200))
        assertEquals(1, root().selected)
        assertEquals(listOf(SendClosed("transit:ride", "back"), ShowRoot), effects)
    }

    @Test
    fun `rule 5 pushing beyond six frames is refused with frame limit`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("p2", kind = "hub")))
        for (index in 2..5) {
            send(Enter(4_000L + index * 100))
            respond(requested(), now = 4_050L + index * 100, actions = listOf(action("p${index + 1}", kind = "hub")))
        }
        assertEquals(PageSurfaceContract.MAX_FRAMES - 1, frames().size)
        val before = model

        send(Enter(5_000))
        assertTrue(effects.none { it is RequestPage })
        assertEquals(SendClosed("p6", "frame_limit"), effects.last())
        assertEquals(before, model)
    }

    @Test
    fun `rule 6 a second enter while the top frame is loading is ignored`() {
        openRoot()
        send(Enter(3_000))
        val loading = model

        send(Enter(3_100))
        assertEquals(loading, model)
        assertEquals(listOf(SessionEffect.None), effects)
    }

    @Test
    fun `rule 7 responses that do not match the pending request are dropped`() {
        openRoot()
        send(Enter(3_000))
        val request = requested()
        val loading = model

        send(PageResponse(page(request).put("requestId", "other"), 3_100))
        assertEquals(loading, model)
        send(PageResponse(page(request).put("sessionGeneration", request.correlation.sessionGeneration + 1), 3_100))
        assertEquals(loading, model)
        assertEquals(listOf(SessionEffect.None), effects)

        send(PageResponse(page(request), 3_200))
        assertTrue(top().status is FrameStatus.Shown)
    }

    @Test
    fun `rule 7 late responses after back or close never reopen anything`() {
        openRoot()
        send(Enter(3_000))
        val first = requested()
        send(Back(3_100))
        val root = model.state

        send(PageResponse(page(first), 3_200))
        assertEquals(root, model.state)
        assertEquals(listOf(SessionEffect.None), effects)

        send(Back(3_300))
        send(PageResponse(page(first), 3_400))
        assertSame(SessionState.Closed, model.state)

        openRoot(5_000)
        send(Enter(7_000))
        send(PageResponse(page(first), 7_100))
        assertTrue(top().status is FrameStatus.Loading)
    }

    @Test
    fun `rule 8 loading shows a hint at two seconds and times out at eight`() {
        openRoot()
        send(Enter(3_000))

        send(Tick(4_999))
        assertFalse((top().status as FrameStatus.Loading).stillLoading)
        send(Tick(3_000 + PageSurfaceContract.LOADING_HINT_MS))
        assertTrue((top().status as FrameStatus.Loading).stillLoading)
        assertEquals(listOf(ShowFrame), effects)

        send(Tick(3_000 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        assertEquals(FrameStatus.Unavailable(null, "PAGE_TIMEOUT"), top().status)
    }

    @Test
    fun `rule 8 a refresh timeout keeps the snapshot and retry sends a fresh request`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("detail", kind = "hub")))
        val shown = (top().status as FrameStatus.Shown).snapshot
        send(Enter(5_000))
        send(Back(5_100))
        val refresh = requested()
        assertEquals("refresh", refresh.reason)

        send(Tick(5_100 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        assertEquals(FrameStatus.Unavailable(shown, "PAGE_TIMEOUT"), top().status)
        assertEquals(0, top().selected)

        send(Enter(20_000))
        val retry = requested()
        assertEquals("retry", retry.reason)
        assertNotEquals(refresh.correlation.requestId, retry.correlation.requestId)
        assertEquals(shown, (top().status as FrameStatus.Loading).previous)

        send(Tick(20_000 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        send(Step(1, 30_000))
        send(Enter(30_100))
        assertTrue(model.state is SessionState.Root)
    }

    @Test
    fun `rule 9 covering a frame drops its lease and uncovering refreshes it`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("detail", kind = "hub")))
        val snapshot = (top().status as FrameStatus.Shown).snapshot

        send(Enter(5_000))
        assertEquals(SendVisibility("maps:route", false), effects.first())
        assertNull((frames().first().status as FrameStatus.Shown).leaseUntilMs)

        send(Back(6_000))
        val loading = top().status as FrameStatus.Loading
        assertEquals(snapshot, loading.previous)
        assertEquals(6_000 + PageSurfaceContract.LEASE_MS, loading.leaseUntilMs)

        respond(requested(), now = 6_100, revision = 2)
        assertEquals(listOf(ShowFrame), effects)
        assertEquals(6_000 + PageSurfaceContract.LEASE_MS, (top().status as FrameStatus.Shown).leaseUntilMs)
    }

    @Test
    fun `rule 9 a visible frame renews its lease within thirty seconds of expiry`() {
        openRoot()
        send(Enter(4_000))
        respond(requested(), now = 4_100)
        val lease = 4_100 + PageSurfaceContract.LEASE_MS
        assertEquals(listOf(SendVisibility("maps:route", true, lease), ShowFrame), effects)

        send(Tick(lease - PageSurfaceContract.LEASE_RENEW_MS - 1))
        assertEquals(listOf(SessionEffect.None), effects)
        val renewAt = lease - PageSurfaceContract.LEASE_RENEW_MS
        send(Tick(renewAt))
        assertEquals(listOf(SendVisibility("maps:route", true, renewAt + PageSurfaceContract.LEASE_MS)), effects)
        assertEquals(renewAt + PageSurfaceContract.LEASE_MS, (top().status as FrameStatus.Shown).leaseUntilMs)
    }

    @Test
    fun `rule 10 live revisions replace only a higher revision on the visible frame`() {
        openRoot()
        val request = showFirst(4_000)

        send(PageResponse(page(request, revision = 2, text = "250 m"), 5_000))
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
        assertEquals(listOf(ShowFrame), effects)
        val current = model

        listOf(
            page(request, revision = 2, text = "dup"),
            page(request, revision = 1, text = "old"),
            page(request, revision = 3).put("requestId", "other"),
        ).forEach {
            send(PageResponse(it, 5_100))
            assertEquals(current, model)
        }
        send(PageResponse(page(request, revision = 9), 4_050 + PageSurfaceContract.LEASE_MS))
        assertEquals(current, model)
    }

    @Test
    fun `rule 10 live revisions need a live page and are dropped on a covered frame`() {
        openRoot()
        val still = showFirst(4_000, live = false, actions = listOf(action("detail", kind = "hub")))
        send(PageResponse(page(still, revision = 2), 4_500))
        assertEquals(1L, (top().status as FrameStatus.Shown).revision)

        send(Enter(5_000))
        respond(requested(), now = 5_100)
        send(PageResponse(page(still, revision = 3), 5_200))
        assertEquals(1L, (frames().first().status as FrameStatus.Shown).revision)
        assertEquals(listOf(SessionEffect.None), effects)
    }

    @Test
    fun `rule 11 an action sends a fresh invocation with the shown revision`() {
        openRoot()
        val request = showFirst(4_000, actions = listOf(action("mute")))
        send(PageResponse(page(request, revision = 4, actions = listOf(action("mute"))), 4_500))

        send(Enter(5_000))
        val first = effects.filterIsInstance<SendAction>().single().action
        assertEquals("mute", first.actionId)
        assertEquals(4L, first.revision)
        assertEquals(1, first.frameIndex)
        assertTrue(PageSurfaceContract.validateAction(first.toPayload()) is PageSurfaceValidationResult.Valid)

        send(Enter(5_100))
        assertEquals(listOf(SessionEffect.None), effects)

        send(PageResult(JSONObject().put("invocationId", first.invocationId).put("status", "done"), 5_200))
        assertNull(top().invocation)
        assertEquals(ActionOutcome("mute", "done"), top().outcome)

        send(Enter(5_300))
        val second = effects.filterIsInstance<SendAction>().single().action
        assertNotEquals(first.invocationId, second.invocationId)
    }

    @Test
    fun `rule 11 rejected and stale results keep the page and done applies a replacement`() {
        openRoot()
        val request = showFirst(4_000, actions = listOf(action("mute")))
        val snapshot = (top().status as FrameStatus.Shown).snapshot

        listOf("rejected", "stale").forEachIndexed { index, status ->
            send(Enter(5_000L + index * 100))
            val invocation = effects.filterIsInstance<SendAction>().single().action
            send(PageResult(JSONObject().put("invocationId", invocation.invocationId).put("status", status), 5_050))
            assertNull(top().invocation)
            assertEquals(status, top().outcome?.status)
            assertEquals(snapshot, (top().status as FrameStatus.Shown).snapshot)
        }

        send(Enter(6_000))
        val invocation = effects.filterIsInstance<SendAction>().single().action
        val replacement = page(request, revision = 5, actions = listOf(action("unmute")))
        send(
            PageResult(
                JSONObject().put("invocationId", invocation.invocationId).put("status", "done").put("replacement", replacement),
                6_100,
            ),
        )
        assertEquals(5L, (top().status as FrameStatus.Shown).revision)
    }

    @Test
    fun `rule 11 an unacknowledged action becomes unconfirmed and is never retried`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("mute")))
        send(Enter(5_000))
        val invocation = effects.filterIsInstance<SendAction>().single().action

        send(Tick(5_000 + PageSurfaceContract.PAGE_TIMEOUT_MS - 1))
        assertEquals(invocation.invocationId, top().invocation?.invocationId)
        send(Tick(5_000 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        assertNull(top().invocation)
        assertEquals(ActionOutcome("mute", "UNCONFIRMED_ACTION"), top().outcome)
        assertTrue(effects.none { it is SendAction })

        send(PageResult(JSONObject().put("invocationId", invocation.invocationId).put("status", "done"), 13_100))
        assertEquals("UNCONFIRMED_ACTION", top().outcome?.status)
    }

    @Test
    fun `rule 11 the default resolver keeps confirmation hub and immersion rows inert`() {
        val defaults = SessionReducer()
        openRoot(using = defaults)
        send(Enter(4_000), defaults)
        respond(
            requested(),
            now = 4_100,
            actions = listOf(
                action("delete", confirm = true), action("detail", kind = "hub"),
                action("full", kind = "immersion"), action("mute"),
            ),
            using = defaults,
        )

        repeat(3) { index ->
            send(Enter(5_000L + index * 100), defaults)
            assertEquals(listOf(SessionEffect.None), effects)
            send(Step(1, 5_050L + index * 100), defaults)
        }
        send(Enter(6_000), defaults)
        assertEquals("mute", effects.filterIsInstance<SendAction>().single().action.actionId)
    }

    @Test
    fun `rule 12 link loss makes every frame unavailable and back pops locally`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("detail", kind = "hub")))
        val first = (top().status as FrameStatus.Shown).snapshot
        send(Enter(5_000))
        respond(requested(), now = 5_100, actions = listOf(action("mute")))
        send(Enter(5_200))
        val invocation = effects.filterIsInstance<SendAction>().single().action

        send(SessionEvent.LinkLost)
        assertTrue(frames().all { (it.status as? FrameStatus.Unavailable)?.reason == "link_lost" })
        assertEquals(first, (frames().first().status as FrameStatus.Unavailable).lastSnapshot)
        assertTrue(frames().all { it.invocation == null })

        send(PageResult(JSONObject().put("invocationId", invocation.invocationId).put("status", "done"), 5_300))
        assertNull(top().outcome)

        send(Back(5_400))
        assertEquals(listOf(ShowFrame), effects)
        send(Back(5_500))
        assertEquals(listOf(ShowRoot), effects)
        assertTrue(model.state is SessionState.Root)
    }

    @Test
    fun `rule 12 link restoration replays nothing until retry`() {
        openRoot()
        showFirst(4_000, actions = listOf(action("mute")))
        send(SessionEvent.LinkLost)

        send(Enter(4_500))
        assertEquals(listOf(SessionEffect.None), effects)
        send(SessionEvent.LinkRestored)
        assertEquals(listOf(SessionEffect.None), effects)
        assertTrue(top().status is FrameStatus.Unavailable)

        send(Enter(5_000))
        assertEquals("retry", requested().reason)
    }

    @Test
    fun `rule 13 retained snapshots above the budget evict the oldest covered frame`() {
        assertTrue(
            (PageSurfaceContract.MAX_FRAMES - 1) * PageSurfaceContract.MAX_PAGE_BYTES <=
                PageSurfaceContract.MAX_SNAPSHOT_TOTAL_BYTES,
        )
        val body = "x".repeat(1_000)
        val pageBytes = PageSurfaceContract.serializedBytes(
            page(PageSurfaceRequest(PageCorrelation("s1-r0", 1, 1, "maps:route"), "open"), text = body)
                .put("actions", JSONArray().put(action("p2", kind = "hub"))),
        )
        val small = SessionReducer(testItems, maxSnapshotTotalBytes = pageBytes * 2 + 100)
        openRoot(using = small)
        send(Enter(4_000), small)
        respond(requested(), now = 4_100, text = body, actions = listOf(action("p2", kind = "hub")), using = small)
        send(Enter(5_000), small)
        respond(requested(), now = 5_100, text = body, actions = listOf(action("p3", kind = "hub")), using = small)
        send(Enter(6_000), small)
        respond(requested(), now = 6_100, text = body, using = small)

        assertEquals(FrameStatus.Unavailable(null, "evicted"), frames()[0].status)
        assertEquals("maps:route", frames()[0].pageId)
        assertTrue(frames()[1].status is FrameStatus.Shown)

        send(Back(7_000), small)
        send(Back(7_100), small)
        val refresh = requested()
        assertEquals("maps:route", refresh.correlation.pageId)
        assertEquals("refresh", refresh.reason)
        assertNull((top().status as FrameStatus.Loading).previous)
    }

    @Test
    fun `rule 14 closed ignores everything but triple tap and bookkeeping`() {
        val closed = model
        listOf(
            Contact(1), Enter(2), Back(3), Step(1, 4), Tick(5),
            PageResponse(JSONObject(), 6), PageResult(JSONObject(), 7),
            SessionEvent.LinkLost, SessionEvent.LinkRestored,
        ).forEach {
            send(it)
            assertEquals(closed, model)
            assertEquals(listOf(SessionEffect.None), effects)
        }
        assertFalse(model.linkLost)
    }

    @Test
    fun `rule 14 closed records activities notices and the editable flag`() {
        send(ActivityStarted(ActivityStop("timer", "timer:main", "4:00")))
        send(ActivityEnded("maps"))
        send(NoticeArrived(NoticePreview("relay:thread-1", "Ana: hi")))
        send(SessionEvent.NoticeArmed)
        send(EditableFocused(true))

        assertSame(SessionState.Closed, model.state)
        assertEquals(listOf(SessionEffect.None), effects)
        assertEquals(listOf("timer"), model.activities.map(ActivityStop::id))
        assertEquals("Ana: hi", model.preview?.text)
        assertEquals(1, model.notificationCount)
        assertTrue(model.noticeArmed)
        assertTrue(model.editableFocused)
    }

    private fun send(event: SessionEvent, using: SessionReducer = reducer): List<SessionEffect> {
        val transition = using.reduce(model, event)
        model = transition.model
        effects = transition.effects
        return effects
    }

    /** Opens a session with the pinned Navigation activity at the head of the root. */
    private fun openRoot(at: Long = 1_000, using: SessionReducer = reducer) {
        if (model.activities.none { it.id == "maps" }) {
            send(ActivityStarted(ActivityStop("maps", "maps:route", "300 m", pinned = true)), using)
        }
        send(TripleTap(at), using)
        send(Tick(at + PageSurfaceContract.GATE_MS), using)
        assertTrue(model.state is SessionState.Root)
    }

    private fun showFirst(
        at: Long,
        live: Boolean = true,
        actions: List<JSONObject> = emptyList(),
    ): PageSurfaceRequest {
        send(Enter(at))
        val request = requested()
        respond(request, now = at + 50, live = live, actions = actions)
        return request
    }

    private fun respond(
        request: PageSurfaceRequest,
        now: Long,
        revision: Long = 1,
        live: Boolean = true,
        actions: List<JSONObject> = emptyList(),
        text: String = "",
        using: SessionReducer = reducer,
    ) {
        send(PageResponse(page(request, revision, live, actions, text), now), using)
        assertTrue(top().status is FrameStatus.Shown)
    }

    private fun page(
        request: PageSurfaceRequest,
        revision: Long = 1,
        live: Boolean = true,
        actions: List<JSONObject> = emptyList(),
        text: String = "",
    ): JSONObject = PageSurfaceContract.correlationPayload(request.correlation)
        .put("revision", revision)
        .put("template", "summary")
        .put("title", "Page")
        .put("body", JSONObject().put("text", text))
        .put("actions", JSONArray(actions))
        .put("live", live)

    private fun action(id: String, kind: String = "plugin", confirm: Boolean = false) =
        JSONObject().put("id", id).put("label", id).put("kind", kind).put("confirm", confirm)

    private fun requested(): PageSurfaceRequest = effects.filterIsInstance<RequestPage>().single().request
    private fun root(): SessionState.Root = model.state as SessionState.Root
    private fun frames(): List<Frame> = (model.state as SessionState.InPage).frames
    private fun top(): Frame = frames().last()
}
