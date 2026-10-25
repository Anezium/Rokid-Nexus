package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.glasses.session.SessionEffect.SendClosed
import com.anezium.rokidbus.glasses.session.SessionEffect.SendVisibility
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSegment
import com.anezium.rokidbus.shared.PageSegmentKind
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageSurfaceResponse
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PR3a's session rules over the real typed resolver: stable rows, actions, documents, local pages. */
class SessionPagesTest {
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { it.startsWith("relay:") || it.startsWith("nav:") },
    )
    private var reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId)
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun openRoot(at: Long = 1_000) {
        send(SessionEvent.TripleTap(at))
        send(SessionEvent.Tick(at + PageSurfaceContract.GATE_MS))
    }

    private fun root() = model.state as SessionState.Root
    private fun page() = when (val state = model.state) {
        is SessionState.InPage -> state
        is SessionState.Launching -> state.previous as SessionState.InPage
        else -> error("not in a page: $state")
    }
    private fun top() = page().frames.last()
    private fun requested() = effects.filterIsInstance<RequestPage>().last()

    private fun body(row: List<PageRow>) = PageTemplateContract.encode(PageBody.SelectableList(row))

    private fun respond(
        request: PageSurfaceRequest,
        now: Long,
        revision: Long = 1,
        body: JSONObject = body(listOf(PageRow("a", "Ana"), PageRow("b", "Bo"))),
        template: String = "selectableList",
        actions: JSONArray = JSONArray(),
        live: Boolean = true,
        epoch: String? = null,
        pluginId: String? = null,
        requestId: String = request.correlation.requestId,
    ) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation.copy(requestId = requestId))
            .put("revision", revision).put("template", template).put("title", "Relay")
            .put("body", body).put("actions", actions).put("live", live)
        epoch?.let { payload.put(PageSurfaceContract.FIELD_PROVIDER_REGISTRATION_ID, it) }
        pluginId?.let { payload.put(PageSurfaceContract.FIELD_PLUGIN_ID, it) }
        send(SessionEvent.PageResponse(payload, now))
    }

    /** Root with one activity whose page is a provider page, then that page open and shown. */
    private fun openProviderPage(
        pageId: String = "relay:main",
        body: JSONObject? = null,
        actions: JSONArray = JSONArray(),
        epoch: String? = "e1",
        template: String = "selectableList",
    ) {
        send(SessionEvent.ActivityStarted(ActivityStop("relay:local", pageId, "Ana: hi", pinned = true)))
        openRoot()
        send(SessionEvent.Enter(2_000))
        respond(
            requested().request, 2_100, body = body ?: body(listOf(PageRow("a", "Ana"), PageRow("b", "Bo"))),
            actions = actions, epoch = epoch, template = template,
        )
    }

    private fun action(id: String, kind: String, label: String = id) =
        JSONObject().put("id", id).put("label", label).put("kind", kind).put("confirm", false)

    @Test
    fun `opening a provider detail requests it before releasing the list lease`() {
        openProviderPage(body = body(listOf(PageRow("mika", "Mika", target = PageTarget.Page("relay:thread")))))
        send(SessionEvent.Enter(2_200))

        val request = requested()
        val cover = effects.filterIsInstance<SendVisibility>().single()
        assertTrue(effects.indexOf(request) < effects.indexOf(cover))
        assertEquals("relay:thread", request.request.correlation.pageId)
        assertEquals(2, request.request.correlation.frameIndex)
        assertEquals("relay:main", cover.pageId)
        assertFalse(cover.visible)
        assertEquals(FrameIdentity(model.generation, 1), cover.frame)
        assertNull((page().frames.first().status as FrameStatus.Shown).leaseUntilMs)
        assertTrue(top().status is FrameStatus.Loading)

        respond(request.request, 2_300, epoch = "e1")
        assertTrue(top().status is FrameStatus.Shown)
        assertEquals(FrameIdentity(model.generation, 2), effects.filterIsInstance<SendVisibility>().single().frame)
    }

    @Test
    fun `back leases the list before closing either a loading or shown detail and then refreshes`() {
        for (answerDetail in listOf(false, true)) {
            model = SessionModel()
            openProviderPage(body = body(listOf(PageRow("mika", "Mika", target = PageTarget.Page("relay:thread")))))
            val snapshot = (top().status as FrameStatus.Shown).snapshot
            send(SessionEvent.Enter(2_200))
            if (answerDetail) respond(requested().request, 2_300, epoch = "e1")
            send(SessionEvent.Back(2_400))

            val lease = effects.filterIsInstance<SendVisibility>().single()
            val close = effects.filterIsInstance<SendClosed>().single()
            val refresh = requested()
            assertTrue(effects.indexOf(lease) < effects.indexOf(close))
            // A refresh prunes deeper broker frames, so the close must precede it.
            assertTrue(effects.indexOf(close) < effects.indexOf(refresh))
            assertEquals(FrameIdentity(model.generation, 1), lease.frame)
            assertTrue(lease.visible)
            assertEquals("relay:thread", close.pageId)
            assertEquals(FrameIdentity(model.generation, 2), close.frame)
            assertEquals("refresh", refresh.request.reason)
            assertEquals(1L, refresh.minimumRevision)
            assertEquals(snapshot, (top().status as FrameStatus.Loading).previous)
            assertNull(top().invocation)

            respond(refresh.request, 2_500, revision = 2, epoch = "e1")
            assertTrue(top().status is FrameStatus.Shown)
            assertEquals(1, page().frames.size)
        }
    }

    @Test
    fun `the selected row follows its key and a new row never steals focus`() {
        openProviderPage()
        send(SessionEvent.Step(1, 2_200))
        assertEquals("b", top().selectedKey)
        val request = (top().status as FrameStatus.Shown).requestId
        respond(
            PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"),
            2_300, revision = 2, body = body(listOf(PageRow("new", "New"), PageRow("a", "Ana"), PageRow("b", "Bo"))), epoch = "e1",
        )
        assertEquals(2, top().selected)
        assertEquals("b", top().selectedKey)
        assertNull(top().tombstone)
    }

    @Test
    fun `a removed selected row is an inert tombstone and never retargets the tap`() {
        openProviderPage(body = body(listOf(PageRow("a", "Ana", target = PageTarget.Page("relay:a")), PageRow("b", "Bo", target = PageTarget.Page("relay:b")), PageRow("c", "Cy", target = PageTarget.Page("relay:c")))))
        send(SessionEvent.Step(1, 2_200))
        val request = (top().status as FrameStatus.Shown).requestId
        respond(
            PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_300, revision = 2,
            body = body(listOf(PageRow("a", "Ana", target = PageTarget.Page("relay:a")), PageRow("c", "Cy", target = PageTarget.Page("relay:c")))),
            epoch = "e1",
        )
        assertEquals("Bo", top().tombstone)
        assertEquals(1, top().selected)
        send(SessionEvent.Enter(2_400))
        assertTrue(effects.none { it is RequestPage || it is SendAction })
        // Stepping forward lands on the row that slid into its place; nothing fires on the way.
        send(SessionEvent.Step(1, 2_500))
        assertNull(top().tombstone)
        assertEquals("c", top().selectedKey)
        send(SessionEvent.Enter(2_600))
        assertEquals("relay:c", requested().request.correlation.pageId)
    }

    @Test
    fun `stepping back from a tombstone selects the row before it`() {
        openProviderPage(body = body(listOf(PageRow("a", "Ana"), PageRow("b", "Bo"), PageRow("c", "Cy"))))
        send(SessionEvent.Step(1, 2_200))
        send(SessionEvent.Step(1, 2_210))
        val request = (top().status as FrameStatus.Shown).requestId
        respond(PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_300, revision = 2, body = body(listOf(PageRow("a", "Ana"), PageRow("b", "Bo"))), epoch = "e1")
        assertEquals(2, top().selected)
        send(SessionEvent.Step(1, 2_400))
        assertEquals("Cy", top().tombstone)
        send(SessionEvent.Step(-1, 2_500))
        assertEquals("b", top().selectedKey)
    }

    @Test
    fun `a plugin row tap sends exactly one confirmed action without a panel`() {
        openProviderPage(
            body = body(listOf(PageRow("mark", "Mark read", target = PageTarget.Action("mark")))),
            actions = JSONArray().put(action("mark", "plugin")),
        )
        send(SessionEvent.Enter(2_200))
        val sent = effects.filterIsInstance<SendAction>().single().action
        assertEquals("mark", sent.actionId)
        assertEquals(1L, sent.revision)
        assertEquals(PageSurfaceContract.MODE_CONFIRMED, sent.mode)
        assertNull(top().panel)
        // A pending invocation takes no second one.
        send(SessionEvent.Enter(2_700))
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `a page revision needs to be drawn before a new fresh press can invoke it`() {
        reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId, launcherActive = { true })
        openProviderPage(
            body = body(listOf(PageRow("mark", "Mark read", target = PageTarget.Action("mark")), PageRow("t", "Thread", target = PageTarget.Page("relay:t")))),
            actions = JSONArray().put(action("mark", "plugin")),
        )
        acknowledge(2_200)
        val request = (top().status as FrameStatus.Shown).requestId
        respond(
            PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_500, revision = 2,
            body = body(listOf(PageRow("mark", "Mark read", target = PageTarget.Action("mark")), PageRow("t", "Thread", target = PageTarget.Page("relay:t")))),
            actions = JSONArray().put(action("mark", "plugin")), epoch = "e1",
        )
        assertNull(top().panel)
        send(SessionEvent.Enter(2_600))
        assertTrue(effects.none { it is SendAction })
        acknowledge(2_700)
        send(SessionEvent.Enter(2_800, pressStartMs = 2_650))
        assertTrue(effects.none { it is SendAction })
        send(SessionEvent.Enter(2_900, pressStartMs = 2_750))
        assertEquals(2L, effects.filterIsInstance<SendAction>().single().action.revision)
        assertEquals("e1", top().providerEpoch)
    }

    @Test
    fun `an immersion row launches directly and a hub action from a provider does nothing`() {
        openProviderPage(
            pageId = "nav:main",
            body = body(listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open")), PageRow("ctl", "Hub control", target = PageTarget.Action("ctl")))),
            actions = JSONArray().put(action("open", "immersion")).put(action("ctl", "hub")),
        )
        send(SessionEvent.Enter(2_200))
        assertTrue(model.state is SessionState.Launching)
        assertEquals("nav", effects.filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)
        assertNull(top().panel)
        // BACK before the surface arrives cancels the open and returns to the page.
        send(SessionEvent.Back(2_700))
        assertNull(top().panel)
        assertTrue("nav" in model.cancelledOpen)

        send(SessionEvent.Step(1, 2_800))
        send(SessionEvent.Enter(2_900))
        assertNull(top().panel)
        assertTrue(effects.none { it is SendAction || it is SessionEffect.SendLauncherOpen })
    }

    @Test
    fun `a document pages through its measured layout and keeps its anchor across revisions`() {
        val doc = PageTemplateContract.encode(
            PageBody.Document(
                listOf(PageSegment("h", PageSegmentKind.HEADER, "Ana"), PageSegment("p", PageSegmentKind.PROSE, "long text")),
                items = listOf(PageRow("open", "Open", target = PageTarget.Page("relay:other"))),
            ),
        )
        openProviderPage(body = doc, template = "document")
        respondDocumentGuard()
        assertEquals(FrameMode.READING, top().mode)
        val frame = FrameIdentity(model.generation, 1)
        val starts = listOf(DocumentAnchor("h", 0), DocumentAnchor("p", 0), DocumentAnchor("p", 400))
        // A layout of another revision is ignored.
        send(SessionEvent.DocumentPaged(frame, "relay:main", 9, starts, 0))
        assertTrue(top().pageStarts.isEmpty())
        send(SessionEvent.DocumentPaged(frame, "relay:main", 1, starts, 0))
        send(SessionEvent.Step(1, 2_300))
        send(SessionEvent.Step(1, 2_310))
        send(SessionEvent.Step(1, 2_320))
        assertEquals(2, top().page)
        assertEquals(DocumentAnchor("p", 400), top().anchor)
        assertTrue(effects.none { it is RequestPage || it is SendAction })

        // A live revision keeps the anchor; the renderer then reports where it now falls.
        val request = (top().status as FrameStatus.Shown).requestId
        respond(PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_400, revision = 2, body = doc, epoch = "e1", template = "document")
        assertEquals(DocumentAnchor("p", 400), top().anchor)
        assertTrue(top().pageStarts.isEmpty())
        send(SessionEvent.DocumentPaged(frame, "relay:main", 2, starts + DocumentAnchor("p", 800), 2))
        assertEquals(2, top().page)

        // SELECT reaches the rows; BACK returns to reading before it pops.
        send(SessionEvent.Enter(2_500))
        assertEquals(FrameMode.ROWS, top().mode)
        send(SessionEvent.Back(2_600))
        assertEquals(FrameMode.READING, top().mode)
        assertEquals(1, page().frames.size)
        send(SessionEvent.Back(2_700))
        assertTrue(model.state is SessionState.Root)
    }

    private fun respondDocumentGuard() {
        assertTrue(top().status is FrameStatus.Shown)
    }

    @Test
    fun `local pages stay usable on link loss while provider frames lose everything`() {
        openRoot()
        // With no activity and no notice the root is Notifications, then Applications.
        send(SessionEvent.Enter(2_100))
        val notifications = requested().request
        assertEquals(RootStops.NOTIFICATIONS_PAGE_ID, notifications.correlation.pageId)
        send(SessionEvent.LinkLost)
        // The local answer still arrives, in-process, and is accepted.
        send(SessionEvent.PageResponse(local().answer(notifications), 2_200))
        assertTrue(top().status is FrameStatus.Shown)

        send(SessionEvent.Back(2_300))
        send(SessionEvent.Step(1, 2_400))
        send(SessionEvent.Enter(2_500))
        assertEquals(RootStops.APPLICATIONS_PAGE_ID, requested().request.correlation.pageId)
        send(SessionEvent.PageResponse(local().answer(requested().request), 2_600))
        assertTrue(top().status is FrameStatus.Shown)
    }

    @Test
    fun `a hub invalidation keeps an owner's covered snapshots inert and spares other pages`() {
        openProviderPage(body = body(listOf(PageRow("t", "Thread", target = PageTarget.Page("relay:thread")))))
        send(SessionEvent.Enter(2_200))
        val thread = requested().request
        respond(thread, 2_300, epoch = "e1")
        val coveredRequest = (page().frames.first().status as FrameStatus.Shown).requestId

        // An unrelated error and one for another owner are ignored.
        send(SessionEvent.PageResponse(errorFor("other", model.generation, 1, "relay:main", "relay"), 2_400))
        send(SessionEvent.PageResponse(errorFor(coveredRequest, model.generation, 1, "relay:main", "nav"), 2_400))
        assertTrue(page().frames.all { it.status is FrameStatus.Shown })

        send(SessionEvent.PageResponse(errorFor(coveredRequest, model.generation, 1, "relay:main", "relay"), 2_500))
        assertTrue(page().frames.all { (it.status as? FrameStatus.Unavailable)?.reason == PageSurfaceContract.ERROR_PAGE_UNAVAILABLE })
        assertTrue(page().frames.all { (it.status as FrameStatus.Unavailable).lastSnapshot != null })
        // Nothing more is sent for them, not even a refresh on uncover; BACK still pops locally.
        send(SessionEvent.Back(2_600))
        assertEquals(1, page().frames.size)
        assertTrue(effects.none { it is RequestPage || it is SendClosed || it is SendVisibility })
    }

    @Test
    fun `invalidation by event reaches frames under a pending launch too`() {
        openProviderPage(
            pageId = "nav:main",
            body = body(listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open")))),
            actions = JSONArray().put(action("open", "immersion")),
        )
        send(SessionEvent.Enter(2_200))
        send(SessionEvent.Enter(2_300))
        assertTrue(model.state is SessionState.Launching)
        send(SessionEvent.ProviderInvalidated("nav"))
        assertTrue((model.state as SessionState.Launching).previous.let { it as SessionState.InPage }.frames.single().status is FrameStatus.Unavailable)
    }

    @Test
    fun `the phone's stamped invalidation during a launch leaves the page inert after BACK`() {
        openProviderPage(
            pageId = "nav:main",
            body = body(listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open")))),
            actions = JSONArray().put(action("open", "immersion")),
        )
        val shown = top().status as FrameStatus.Shown
        send(SessionEvent.Enter(2_200))
        send(SessionEvent.Enter(2_300))
        assertTrue(model.state is SessionState.Launching)
        // An ordinary late answer naming another request is still ignored under the launch.
        send(SessionEvent.PageResponse(errorFor("elsewhere", model.generation, 1, "nav:main", "nav"), 2_400))
        assertTrue(((model.state as SessionState.Launching).previous as SessionState.InPage).frames.single().status is FrameStatus.Shown)
        // The phone revoked the provider while its full app was opening: what the bridge dispatches.
        send(SessionEvent.PageResponse(errorFor(shown.requestId, model.generation, 1, "nav:main", "nav"), 2_500))
        send(SessionEvent.Back(2_600))

        val frame = (model.state as SessionState.InPage).frames.single()
        val status = frame.status as FrameStatus.Unavailable
        assertEquals(shown.snapshot, status.lastSnapshot)
        assertEquals(null, frame.panel)
        send(SessionEvent.Enter(2_700))
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen || it is SendAction })
    }

    @Test
    fun `wire effects carry frame identity and a refused seventh frame sends nothing`() {
        openProviderPage(body = body(listOf(PageRow("n", "Next", target = PageTarget.Page("relay:p2")))))
        val generation = model.generation
        val granted = effects.filterIsInstance<SendVisibility>().single()
        assertEquals(FrameIdentity(generation, 1), granted.frame)
        for (index in 2..5) {
            send(SessionEvent.Enter(3_000L + index * 100))
            val request = requested()
            assertEquals(3_000L + index * 100 + PageSurfaceContract.PAGE_TIMEOUT_MS, request.deadlineMs)
            assertEquals(FrameIdentity(generation, index - 1), effects.filterIsInstance<SendVisibility>().single().frame)
            respond(request.request, 3_050L + index * 100, body = body(listOf(PageRow("n", "Next", target = PageTarget.Page("relay:p${index + 1}")))), epoch = "e1")
        }
        send(SessionEvent.Enter(4_000))
        val refused = effects.filterIsInstance<SendClosed>().single()
        assertNull(refused.frame)
        assertEquals(SessionEffect.ShowStatus(SessionStatus.FrameLimit), effects.first())
        send(SessionEvent.Back(4_100))
        assertEquals(FrameIdentity(generation, 5), effects.filterIsInstance<SendClosed>().single().frame)
        val refresh = requested()
        assertEquals("refresh", refresh.request.reason)
        assertEquals(1L, refresh.minimumRevision)
    }

    @Test
    fun `answers from another provider registration are dropped`() {
        openProviderPage(epoch = "e1")
        val request = (top().status as FrameStatus.Shown).requestId
        respond(PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_300, revision = 2, epoch = "e2")
        assertEquals(1L, (top().status as FrameStatus.Shown).revision)
        respond(PageSurfaceRequest(PageCorrelation(request, model.generation, 1, "relay:main"), "open"), 2_400, revision = 2, epoch = "e1")
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
    }

    @Test
    fun `a page this hub cannot draw is unavailable, never an immersion`() {
        send(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:main", "Ana", pinned = true)))
        openRoot()
        send(SessionEvent.Enter(2_000))
        respond(requested().request, 2_100, template = "conversation", body = JSONObject())
        assertEquals(SessionReducer.UNAVAILABLE_UNSUPPORTED, (top().status as FrameStatus.Unavailable).reason)
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })
    }

    @Test
    fun `the root counts the history and keeps it after the band goes away`() {
        assertEquals(0, RootStops.build(emptyList(), null, RootStops.retainedNotices(null)).single { it.kind == RootStopKind.NOTIFICATIONS }.count)
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, "Ana: hi")))
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, "Bo: here")))
        openRoot()
        assertEquals(2, root().stops.single { it.kind == RootStopKind.NOTIFICATIONS }.count)
        assertEquals("Bo: here", root().stops.single { it.kind == RootStopKind.NOTICE_PREVIEW }.text)
        assertEquals(2, model.notificationCount)
    }

    @Test
    fun `an ended activity stays in place and inert, values update in place, starts wait`() {
        send(SessionEvent.ActivityStarted(ActivityStop("nav:local", "nav:main", "300 m", pinned = true, detail = "Rue A")))
        send(SessionEvent.ActivityStarted(ActivityStop("transit:local", "nexus:activity:transit", "2 stops")))
        openRoot()
        send(SessionEvent.ActivityUpdated(ActivityStop("nav:local", "nav:main", "120 m", pinned = true, detail = "Rue B")))
        assertEquals("120 m", root().stops.first().text)
        assertEquals("Rue B", root().stops.first().detail)
        send(SessionEvent.ActivityUpdated(ActivityStop("timer:local", "nexus:activity:timer", "4:00")))
        assertEquals(listOf("timer:local"), model.queuedActivities.map(ActivityStop::id))
        val ids = root().stops.map(RootStop::id)
        send(SessionEvent.ActivityEnded("nav:local"))
        send(SessionEvent.ActivityUpdated(ActivityStop("nav:local", "nav:main", "Arrived", pinned = true)))
        assertEquals(ids, root().stops.map(RootStop::id))
        assertTrue(root().stops.first().ended)
        assertEquals("120 m", root().stops.first().text)
        send(SessionEvent.Enter(3_000))
        assertTrue(model.state is SessionState.Root)
        assertTrue(effects.none { it is RequestPage })
        // Normal bounded STEP moves past it.
        send(SessionEvent.Step(1, 3_100))
        assertEquals(1, root().selected)
    }

    @Test
    fun `more than two activities add an overflow stop and Applications stays five steps away`() {
        listOf("a", "b", "c", "d").forEachIndexed { index, id ->
            send(SessionEvent.ActivityStarted(ActivityStop("$id:local", "nexus:activity:$id", id, pinned = index == 2)))
        }
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, "Ana")))
        openRoot()
        val kinds = root().stops.map(RootStop::kind)
        assertEquals(PageSurfaceContract.MAX_ROOT_STOPS, kinds.size)
        assertEquals("c:local", root().stops.first().id)
        assertEquals(4, root().stops.single { it.kind == RootStopKind.ACTIVITIES }.count)
        repeat(5) { send(SessionEvent.Step(1, 3_000L + it)) }
        assertEquals(RootStopKind.APPLICATIONS, root().stops[root().selected].kind)
        send(SessionEvent.Step(1, 4_000))
        assertEquals(5, root().selected)
    }

    @Test
    fun `the root stays frozen while an immersion launch is pending`() {
        openProviderPage(
            pageId = "nav:main",
            body = body(listOf(PageRow("open", "Open the full app", target = PageTarget.Action("open")))),
            actions = JSONArray().put(action("open", "immersion")),
        )
        val stops = page().root.stops.map(RootStop::id)
        send(SessionEvent.Enter(2_200))
        send(SessionEvent.Enter(2_300))
        send(SessionEvent.ActivityStarted(ActivityStop("timer:local", "nexus:activity:timer", "4:00")))
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, "Bo")))
        assertEquals(stops, page().root.stops.map(RootStop::id))
    }

    @Test
    fun `a local launch closes the session on success and stays with a status on failure`() {
        openRoot()
        send(SessionEvent.Step(1, 2_000))
        send(SessionEvent.Step(1, 2_001))
        send(SessionEvent.Enter(2_100))
        val apps = LocalPages(
            history = { NotificationHistory() }, activities = { emptyList() },
            applications = { listOf(AppEntry("app:dev.tele", "Teleprompter", AppKind.NATIVE)) },
            providerPagesReady = { false }, formatTime = { "12:00" },
        )
        send(SessionEvent.PageResponse(apps.answer(requested().request), 2_200))
        send(SessionEvent.Enter(2_300))
        val launch = effects.filterIsInstance<SessionEffect.LaunchLocal>().single()
        assertEquals("app:dev.tele", launch.key)
        send(SessionEvent.LocalLaunchFinished(launch.key, launch.label, launched = false))
        assertEquals(SessionEffect.ShowStatus(SessionStatus.LaunchFailed("Teleprompter")), effects.single())
        assertTrue(model.state is SessionState.InPage)
        send(SessionEvent.LocalLaunchFinished(launch.key, launch.label, launched = true))
        assertTrue(model.state == SessionState.Closed)
        assertTrue(effects.any { it is SessionEffect.RestoreUnderneath })
    }

    @Test
    fun `local pages are valid typed pages that never invent content`() {
        val snapshot = NotificationHistory().add(NoticeEntry("relay", "Relay", "Ana", "on est devant", emptyList(), 1_000))
        val ready = LocalPages({ snapshot }, { emptyList() }, { emptyList() }, { it == "relay" }, { "14:02" })
        val notReady = LocalPages({ snapshot }, { emptyList() }, { emptyList() }, { false }, { "14:02" })
        val none = LocalPages({ NotificationHistory() }, { emptyList() }, { emptyList() }, { true }, { "14:02" })

        fun decoded(pages: LocalPages, pageId: String, params: String? = null): Pair<PageSurfaceResponse.Page, PageBody> {
            val payload = pages.answer(PageSurfaceRequest(PageCorrelation("r", 1, 1, pageId), "open", params))
            val page = (PageSurfaceContract.validateResponse(payload) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
            val body = (PageTemplateContract.decode(page, null) as PageSurfaceValidationResult.Valid).value
            return page to body
        }
        assertEquals(1, (decoded(ready, RootStops.NOTIFICATIONS_PAGE_ID).second as PageBody.SelectableList).items.size)
        assertEquals(0, (decoded(none, RootStops.NOTIFICATIONS_PAGE_ID).second as PageBody.SelectableList).items.size)
        val notice = decoded(ready, RootStops.NOTICE_PAGE_ID).second as PageBody.Document
        assertEquals(listOf("Relay · 14:02", "Ana", "on est devant"), notice.segments.map(PageSegment::text))
        assertEquals(PageTarget.Page("relay:main"), notice.items.single().target)
        assertTrue((decoded(notReady, RootStops.NOTICE_PAGE_ID).second as PageBody.Document).items.isEmpty())

        val many = (1..70).map { AppEntry("app:dev.app$it", "App $it", AppKind.NATIVE) } +
            AppEntry("plugin:nav", "Navigation", AppKind.PAGES, "nav") +
            AppEntry("plugin:lens", "Lens", AppKind.IMMERSION, "lens")
        val catalog = LocalPages({ NotificationHistory() }, { emptyList() }, { many }, { false }, { "" })
        val (firstPage, first) = decoded(catalog, RootStops.APPLICATIONS_PAGE_ID)
        val rows = (first as PageBody.SelectableList).items
        assertEquals(PageTemplateContract.MAX_ITEMS, rows.size)
        assertEquals("more", rows.last().id)
        assertEquals(63, firstPage.actions.size)
        val more = rows.last().target as PageTarget.Page
        val second = decoded(catalog, RootStops.APPLICATIONS_PAGE_ID, more.paramsJson).second as PageBody.SelectableList
        assertEquals(9, second.items.size)
        assertEquals(PageTarget.Page("nav:main"), second.items.first { it.label == "Navigation" }.target)
        val lens = second.items.first { it.label == "Lens" }
        assertNull(lens.sub)
        assertEquals(PageTarget.Action("launch:lens"), lens.target)
    }

    @Test
    fun `typed rows map local hub actions and keep provider ones in check`() {
        val local = LocalPages({ NotificationHistory() }, { emptyList() }, {
            listOf(
                AppEntry("plugin:lens", "Lens", AppKind.IMMERSION, "lens"),
                AppEntry("plugin:camera", "Camera", AppKind.CAMERA, "camera"),
                AppEntry("app:dev.tele", "Teleprompter", AppKind.NATIVE),
            )
        }, { false }, { "" })
        val payload = local.answer(PageSurfaceRequest(PageCorrelation("r", 1, 1, RootStops.APPLICATIONS_PAGE_ID), "open"))
        val page = (PageSurfaceContract.validateResponse(payload) as PageSurfaceValidationResult.Valid).value as PageSurfaceResponse.Page
        assertEquals(
            listOf(PageItem.Launch("lens", "Lens"), PageItem.LocalLaunch("plugin:camera", "Camera"), PageItem.LocalLaunch("app:dev.tele", "Teleprompter")),
            items.items(page),
        )
    }

    @Test
    fun `screens say why a page is unavailable and offer only its own controls`() {
        val screens = SessionScreens(items, { if (it == "relay") "Relay" else null }, { "12:21" }, { it })
        openRoot()
        send(SessionEvent.Enter(2_000))
        val loading = screens.screen(model.state, null, model.generation)
        assertEquals("NOTIFICATIONS", loading.header?.title)
        assertTrue(loading.blocks.none { it is ScreenBlock.Row })
        send(SessionEvent.Tick(2_000 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        val unavailable = screens.screen(model.state, null, model.generation)
        val texts = unavailable.blocks.filterIsInstance<ScreenBlock.Text>().map { it.text }
        assertTrue(SessionScreens.UNAVAILABLE_TITLE in texts)
        assertTrue("The plugin did not respond." in texts)
        assertFalse(texts.any { "PAGE_TIMEOUT" in it || "nexus:" in it })
        assertEquals(listOf("Retry", "Back"), unavailable.blocks.filterIsInstance<ScreenBlock.Row>().map { it.label })
        assertEquals("retry", unavailable.focusKey)
    }

    private fun local() = LocalPages({ NotificationHistory() }, { emptyList() }, { emptyList() }, { false }, { "" })

    @Test
    fun `a page from the plugin whose surface is underneath leaves that surface alone`() {
        // Navigation's own card is the base; the wearer consults nav:main over it, sees a live
        // revision, and leaves. Nothing is closed, switched or launched underneath.
        val base = Underneath.NexusSurface("nav:card")
        send(SessionEvent.ActivityStarted(ActivityStop("nav:local", "nav:main", "Turn left", pinned = true)))
        val seen = mutableListOf<SessionEffect>()
        val step = { event: SessionEvent ->
            send(event)
            seen += effects
        }
        step(SessionEvent.TripleTap(1_000, base))
        step(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        step(SessionEvent.Enter(2_000))
        val request = requested().request
        assertEquals("nav:main", request.correlation.pageId)
        respond(request, 2_100, pluginId = "nav", epoch = "e1")
        seen += effects
        respond(request, 2_600, revision = 2, pluginId = "nav", epoch = "e1")
        seen += effects
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
        step(SessionEvent.Back(3_000))
        step(SessionEvent.Back(3_100))

        assertEquals(SessionState.Closed, model.state)
        val consultation = setOf(
            SessionEffect.ShowGate::class, SessionEffect.ShowRoot::class, SessionEffect.ShowFrame::class,
            RequestPage::class, SendVisibility::class, SendClosed::class, SessionEffect.ShowStatus::class,
            SessionEffect.ScheduleDeadline::class, SessionEffect.CancelDeadline::class,
            SessionEffect.AttachHost::class, SessionEffect.DetachHost::class, SessionEffect.None::class,
        )
        val other = seen.filterNot { it::class in consultation || it is SessionEffect.RestoreUnderneath }
        assertTrue("$other", other.isEmpty())
        assertEquals(listOf(SessionEffect.RestoreUnderneath(base)), seen.filterIsInstance<SessionEffect.RestoreUnderneath>())
        assertTrue(seen.filterIsInstance<SendClosed>().all { it.pageId == "nav:main" })
    }

    @Test
    fun `a body row and an appended action sharing a wire id keep their own selection across a revision`() {
        // Valid: row ids are unique among the rows and action ids among the actions.
        val collision = body(listOf(PageRow("action:go", "Body go", target = PageTarget.Page("relay:thread:x"))))
        val actions = JSONArray().put(JSONObject().put("id", "go").put("label", "Go").put("kind", "plugin").put("confirm", true))
        send(SessionEvent.ActivityStarted(ActivityStop("relay:local", "relay:main", "Ana: hi", pinned = true)))
        openRoot()
        send(SessionEvent.Enter(2_000))
        val request = requested().request
        respond(request, 2_100, body = collision, actions = actions, epoch = "e1")
        val entries = items.entries((top().status as FrameStatus.Shown).snapshot.page)
        assertEquals(listOf("Body go", "Go"), entries.map { it.label })
        assertEquals(2, entries.map { it.key }.toSet().size)

        send(SessionEvent.Step(1, 2_200))
        assertEquals(1, top().selected)
        respond(request, 2_600, revision = 2, body = collision, actions = actions, epoch = "e1")
        assertEquals(2L, (top().status as FrameStatus.Shown).revision)
        assertEquals(1, top().selected)
        assertEquals(entries[1].key, top().selectedKey)
    }

    private var catalog: List<AppEntry> = emptyList()
    private val localPages = LocalPages(
        history = { NotificationHistory() },
        activities = { emptyList() },
        applications = { catalog },
        providerPagesReady = { false },
        formatTime = { "t$it" },
    )

    private fun app(name: String) = AppEntry("app:$name", name, AppKind.NATIVE)

    /** What the bridge does with a local request: answer it in-process. */
    private fun answerLocal(now: Long) {
        send(SessionEvent.PageResponse(localPages.answer(requested().request, live = true), now))
    }

    /** What the bridge does when a catalog changes under a visible local page. */
    private fun refreshLocal(now: Long) {
        val frame = top()
        val shown = frame.status as FrameStatus.Shown
        val request = PageSurfaceRequest(
            PageCorrelation(shown.requestId, model.generation, page().frames.size, frame.pageId),
            SessionReducer.REASON_REFRESH,
            frame.paramsJson,
        )
        send(SessionEvent.PageResponse(localPages.answer(request, live = true), now))
    }

    private fun shownLabels() = items.entries((top().status as FrameStatus.Shown).snapshot.page).map { it.label }

    /** The root's Applications stop, entered. */
    private fun enterApplications() {
        openRoot()
        send(SessionEvent.Step(1, 2_000))
        send(SessionEvent.Enter(2_050))
        assertEquals(RootStops.APPLICATIONS_PAGE_ID, requested().request.correlation.pageId)
        answerLocal(2_060)
    }

    @Test
    fun `Applications keeps the entries it was entered with, and a removed one stays inert in place`() {
        catalog = listOf(app("Alpha"), app("Bravo"), app("Charlie"), app("Delta"))
        enterApplications()
        send(SessionEvent.Step(1, 2_100))
        send(SessionEvent.Step(1, 2_110))
        assertEquals("Charlie", shownLabels()[top().selected])

        // An app installed before the selected row and one removed before it.
        catalog = listOf(app("Xray"), app("Alpha"), app("Charlie"), app("Delta"))
        refreshLocal(2_200)
        assertEquals(listOf("Alpha", "Bravo", "Charlie", "Delta"), shownLabels())
        assertEquals(2, top().selected)
        val entries = items.entries((top().status as FrameStatus.Shown).snapshot.page)
        assertEquals(PageItem.Inert, entries[1].item)

        // SELECT acts on the entry still there, and the removed one does nothing.
        send(SessionEvent.Enter(2_300))
        assertEquals(SessionEffect.LaunchLocal("app:Charlie", "Charlie"), effects.single())
        send(SessionEvent.Step(-1, 2_400))
        send(SessionEvent.Enter(2_410))
        assertTrue(effects.none { it is SessionEffect.LaunchLocal || it is RequestPage })

        // Entering again from the root takes the catalog as it is now.
        send(SessionEvent.Back(2_500))
        send(SessionEvent.Enter(2_600))
        answerLocal(2_610)
        assertEquals(listOf("Xray", "Alpha", "Charlie", "Delta"), shownLabels())
    }

    @Test
    fun `More pages page through the order Applications was entered with`() {
        val room = PageTemplateContract.MAX_ITEMS - 1
        catalog = (0 until room + 3).map { app("k$it") }
        enterApplications()
        assertEquals("More applications", shownLabels().last())
        repeat(room) { send(SessionEvent.Step(1, 2_100L + it)) }
        send(SessionEvent.Enter(2_110))
        answerLocal(2_120)
        val tail = listOf("k$room", "k${room + 1}", "k${room + 2}")
        assertEquals(tail, shownLabels())

        // An app installed before the More boundary does not shift what this page shows.
        catalog = listOf(app("new")) + (0 until room + 3).map { app("k$it") }
        refreshLocal(2_200)
        assertEquals(tail, shownLabels())
        // Nor does one removed before it.
        catalog = (0 until room + 3).filter { it != 1 }.map { app("k$it") }
        refreshLocal(2_300)
        assertEquals(tail, shownLabels())

        // Back on the first page, its own rows and its More count are those it was entered with.
        send(SessionEvent.Back(2_400))
        answerLocal(2_410)
        val first = shownLabels()
        assertEquals("k0", first.first())
        assertEquals("k1", first[1])
        assertEquals(room + 1, first.size)
    }

    @Test
    fun `Applications rows carry their catalog marks, an unknown one the grid, and the place itself none`() {
        catalog = listOf(
            AppEntry("plugin:nav", "Navigation", AppKind.PAGES, "nav", IconRef.Plugin("nav", "nav")),
            AppEntry("plugin:camera", "Camera", AppKind.CAMERA, "camera", IconRef.Plugin("camera", "lens")),
            AppEntry("app:dev.tele", "Teleprompter", AppKind.NATIVE, icon = IconRef.Native("dev.tele")),
            AppEntry("app:dev.mystery", "Mystery", AppKind.NATIVE),
        )
        val screens = SessionScreens(
            items, { if (it == "nav") "Navigation" else null }, { "12:00" }, { it },
            pluginIcon = { IconRef.Plugin(it, "key-$it") },
            localIcon = localPages::iconOf,
        )
        enterApplications()
        val applications = screens.screen(model.state, null, model.generation)
        assertNull(applications.header?.icon)
        assertEquals(
            listOf(IconRef.Plugin("nav", "nav"), IconRef.Plugin("camera", "lens"), IconRef.Native("dev.tele"), IconRef.Place(IconPlace.GRID)),
            applications.blocks.filterIsInstance<ScreenBlock.Row>().map { it.icon },
        )

        // The provider page it opens names its owner by its mark in the header.
        send(SessionEvent.Enter(2_100))
        assertEquals("nav:main", requested().request.correlation.pageId)
        respond(requested().request, 2_200, epoch = "e1")
        val page = screens.screen(model.state, null, model.generation)
        assertEquals(IconRef.Plugin("nav", "key-nav"), page.header?.icon)
        // Its rows are the plugin's own: no app mark is guessed for them.
        assertTrue(page.blocks.filterIsInstance<ScreenBlock.Row>().all { it.icon == null })
    }

    @Test
    fun `a pages plugin entered from Applications ends with one hub Open app that launches on a fresh tap`() {
        reducer = SessionReducer(
            items,
            isLocalPage = PageTemplateContract::isLocalPageId,
            launcherActive = { true },
            ownerApp = { owner -> OpenApp(owner, "Relay").takeIf { owner == "relay" } },
        )
        catalog = listOf(AppEntry("plugin:relay", "Relay", AppKind.PAGES, "relay"))
        enterApplications()
        send(SessionEvent.Enter(2_100))
        assertEquals("relay:main", requested().request.correlation.pageId)
        // The plugin offers its own full-app row too: the hub's replaces it, so there is one.
        respond(
            requested().request, 2_200, epoch = "e1",
            actions = JSONArray().put(action("mark", "plugin", "Mark read")).put(action("open-full", "immersion", "Open full app")),
        )
        val labels = items.entries((top().status as FrameStatus.Shown).snapshot.page).map { it.label }
        assertEquals(listOf("Ana", "Bo", "Mark read", "Open full app"), labels)
        val screens = SessionScreens(items, { "Relay" }, { "12:00" }, { it })
        val rows = screens.screen(model.state, null, model.generation).blocks.filterIsInstance<ScreenBlock.Row>()
        assertEquals(listOf("Ana", "Bo", "Mark read", ActivityCommands.OPEN_APP), rows.map { it.label })

        repeat(3) { send(SessionEvent.Step(1, 2_300L + it)) }
        assertEquals(ActivityCommands.OPEN_APP_KEY, top().selectedKey)
        acknowledge(2_400)
        send(SessionEvent.Enter(2_440, pressStartMs = 2_390))
        assertTrue(effects.none { it is SessionEffect.SendLauncherOpen })
        send(SessionEvent.Enter(2_460, pressStartMs = 2_420))
        assertTrue(model.state is SessionState.Launching)
        assertNull(top().panel)
        assertEquals("relay", effects.filterIsInstance<SessionEffect.SendLauncherOpen>().single().pluginId)
    }

    @Test
    fun `a page opened from a provider's own row, or for an owner with no full app, gets no hub Open app`() {
        reducer = SessionReducer(
            items,
            isLocalPage = PageTemplateContract::isLocalPageId,
            launcherActive = { true },
            ownerApp = { null },
        )
        catalog = listOf(AppEntry("plugin:relay", "Relay", AppKind.PAGES, "relay"))
        enterApplications()
        send(SessionEvent.Enter(2_100))
        respond(requested().request, 2_200, epoch = "e1", actions = JSONArray().put(action("open-full", "immersion", "Open full app")))
        assertNull(top().openApp)
        // The provider's own row is then the way to its full app.
        assertEquals(PageItem.Launch("relay", "Open full app"), items.entries((top().status as FrameStatus.Shown).snapshot.page).last().item)
    }

    @Test
    fun `the Notifications row says it opens the collection, which says what its tags and bounds mean`() {
        val screens = SessionScreens(items, { null }, { "12:00" }, { it })
        openRoot()
        assertEquals(RootStops.NOTIFICATIONS_PAGE_ID, root().stops[root().selected].id)
        assertEquals(ScreenHint("swipe next · tap open the collection", "back close"), screens.screen(model.state, null, model.generation).hint)
        send(SessionEvent.Enter(2_000))
        answerLocal(2_010)
        val footnote = screens.screen(model.state, null, model.generation).blocks.last() as ScreenBlock.Text
        assertEquals(SessionScreens.NOTIFICATIONS_FOOTNOTE, footnote.text)
        assertTrue("Seen in Nexus" in footnote.text && "30 entries, 24 h" in footnote.text)
    }

    /** The host's draw acknowledgement of what is focused now. */
    private fun acknowledge(atMs: Long) {
        send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing focused"), atMs))
    }

    private fun errorFor(requestId: String, generation: Long, frameIndex: Int, pageId: String, owner: String) =
        PageSurfaceContract.correlationPayload(PageCorrelation(requestId, generation, frameIndex, pageId))
            .put("error", PageSurfaceContract.ERROR_PAGE_UNAVAILABLE).put(PageSurfaceContract.FIELD_PLUGIN_ID, owner)
}
