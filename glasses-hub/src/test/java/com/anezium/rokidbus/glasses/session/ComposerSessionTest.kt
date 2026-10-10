package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.glasses.session.SessionEffect.SendCompose
import com.anezium.rokidbus.shared.ComposeDictateFinal
import com.anezium.rokidbus.shared.ComposeDictatePartial
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ComposeTypeDone
import com.anezium.rokidbus.shared.ComposeTypeText
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plan 028a §5.3-5.4, §6 and §7 on the glasses: the history, the root card and the composer. */
class ComposerSessionTest {
    private var negotiated = true
    private var wallNow = 50_000_000L
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { negotiated },
    )
    private val reducer = SessionReducer(
        items,
        isLocalPage = PageTemplateContract::isLocalPageId,
        launcherActive = { negotiated },
        wallClock = { wallNow },
    )
    private val screens = SessionScreens(items, { if (it == "relay") "Relay" else null }, { "14:02" }, { it }, { wallNow })
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    // ---- the composer ------------------------------------------------------------------------

    @Test
    fun `a tap focuses Dictate first and Step walks the buttons without wrapping`() {
        openThread("t1", at = 1_000)
        focus(at = 3_000)
        assertEquals(ComposerFocus(ComposerRules.DICTATE, 0), top().composerFocus)
        assertTrue(effects.none { it is SendCompose || it is SendAction })

        send(SessionEvent.Step(-1, 3_100))
        assertEquals(0, top().composerFocus?.index)
        send(SessionEvent.Step(1, 3_200))
        assertEquals(ComposerFocus(ComposerRules.TYPE, 1), top().composerFocus)
        send(SessionEvent.Step(1, 3_300))
        assertEquals(1, top().composerFocus?.index)

        send(SessionEvent.Back(3_400))
        assertNull(top().composerFocus)
        assertTrue(model.state is SessionState.InPage)
    }

    @Test
    fun `focusing and starting a capture need a press begun after the screen was drawn`() {
        openThread("t1", at = 1_000)
        shown(3_000)
        press(2_990)
        assertNull(top().composerFocus)
        press(3_010)
        assertEquals(ComposerRules.DICTATE, top().composerFocus?.key)

        shown(3_100)
        press(3_090)
        assertNull(model.capture)
        assertTrue(effects.none { it is SendCompose })
        press(3_110)
        val start = effects.filterIsInstance<SendCompose>().single().message as ComposeOutbound.DictateStart
        assertEquals(model.capture?.captureId, start.captureId)
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `a tap finishes a dictation into a draft and sends nothing`() {
        openThread("t1", at = 1_000)
        startCapture(CaptureKind.DICTATE, at = 3_000)
        val id = model.capture!!.captureId
        compose(ComposeInbound.Partial(ComposeDictatePartial(id, "see you")))
        assertEquals("see you", model.capture?.liveText)

        send(SessionEvent.Enter(4_000, pressStartMs = 3_990))
        val stop = effects.filterIsInstance<SendCompose>().single().message as ComposeOutbound.DictateStop
        assertFalse(stop.stop.cancel)
        assertTrue(model.capture!!.finishing)
        assertNull(model.draft)

        compose(ComposeInbound.Final(ComposeDictateFinal(id, ComposeDictationReason.COMPLETED, "see you soon")))
        assertNull(model.capture)
        assertEquals(Draft("relay", "t1", destination("t1"), "Ana", "see you soon"), model.draft)
        assertNull(top().composerFocus)
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `Send exists only for this thread's ready draft and needs a fresh press on its own focus`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)

        shown(5_000)
        press(5_010)
        assertEquals(ComposerFocus(ComposerRules.SEND, 0), top().composerFocus)
        assertTrue(effects.none { it is SendAction })

        // The press that began before the Send focus was drawn sends nothing.
        shown(5_100)
        press(5_090)
        assertTrue(effects.none { it is SendAction })
        assertNull(model.pendingSend)

        press(5_200)
        val action = effects.filterIsInstance<SendAction>().single().action
        assertEquals("send", action.actionId)
        assertEquals(PageSurfaceContract.MODE_COMPOSE, action.mode)
        assertEquals("see you", action.text)
        assertEquals(action.invocationId, model.pendingSend?.invocationId)
        assertNull(top().composerFocus)

        // While it is in flight, nothing else sends.
        shown(5_300)
        press(5_310)
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `a handed-off reply clears the draft and resolves the thread's history and root card`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        val action = sendDraft(at = 5_000)

        send(SessionEvent.PageResult(JSONObject().put("invocationId", action.invocationId).put("status", "done"), 5_500))
        assertNull(model.draft)
        assertNull(model.pendingSend)
        assertEquals(Handoff("relay", "t1", "see you", 1), model.handoff)
        assertEquals(HistoryState.HANDED_OFF, model.history.entries.single().state)
        assertNull(top().outcome)
        val bubble = conversationBlock().bubbles.single { it.key == SessionScreens.HANDOFF_BUBBLE }
        assertEquals(SessionScreens.HANDED_TO_APP, bubble.meta)

        send(SessionEvent.Back(5_600))
        val card = root().stops.first { it.kind == RootStopKind.NOTICE_PREVIEW }
        assertEquals(HistoryState.HANDED_OFF, card.notice?.state)
        val drawn = screen().blocks.first() as ScreenBlock.Card
        assertEquals("RELAY · ${SessionScreens.REPLY_HANDED_OFF}", drawn.label)
        assertEquals(SessionScreens.HANDED_OFF_UNCONFIRMED, drawn.tertiary)
    }

    @Test
    fun `an unanswered send is unconfirmed, focuses Type next and is never sent again`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        sendDraft(at = 5_000)

        send(SessionEvent.Tick(5_200 + PageSurfaceContract.PAGE_TIMEOUT_MS))
        assertEquals(DraftState.UNCONFIRMED, model.draft?.state)
        assertNull(model.pendingSend)
        val draftBubble = conversationBlock().bubbles.single { it.key == SessionScreens.DRAFT_BUBBLE }
        assertEquals(SessionScreens.UNCONFIRMED_CHECK_PHONE, draftBubble.meta)

        // A reconnect or the passing of time resends nothing.
        send(SessionEvent.LinkLost)
        send(SessionEvent.LinkRestored)
        send(SessionEvent.Tick(30_000))
        assertTrue(effects.none { it is SendAction })

        // Type first, prefilled with the retained text; Send is not offered for it.
        reopenThread("t1", at = 40_000)
        focus(at = 43_000)
        assertEquals(ComposerFocus(ComposerRules.TYPE, 1), top().composerFocus)
        shown(43_100)
        press(43_110)
        val start = effects.filterIsInstance<SendCompose>().single().message as ComposeOutbound.TypeStart
        assertEquals("see you", start.start.initialText)
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `a failed dictation offers Type and never falls back to another microphone`() {
        openThread("t1", at = 1_000)
        startCapture(CaptureKind.DICTATE, at = 3_000)
        compose(ComposeInbound.Final(ComposeDictateFinal(model.capture!!.captureId, ComposeDictationReason.MIC_UNAVAILABLE)))
        assertNull(model.capture)
        assertTrue(model.micUnavailable)
        assertNull(model.draft)
        assertTrue(effects.none { it is SendCompose })
        val footer = screen().footer as InlineFooter.StatusLine
        assertEquals(SessionScreens.MIC_UNAVAILABLE, footer.text)
        assertEquals(SessionScreens.USE_TYPE, footer.sub)

        focus(at = 5_000)
        assertEquals(ComposerRules.TYPE, top().composerFocus?.key)
    }

    @Test
    fun `Back cancels a capture and the earlier draft stays, even when the phone answers late`() {
        openThread("t1", at = 1_000)
        dictate("first", at = 3_000)
        // Ready draft: Send, Dictate, Type.
        startCapture(CaptureKind.TYPE, at = 6_000, steps = 2)
        val id = model.capture!!.captureId
        compose(ComposeInbound.Text(ComposeTypeText(id, "first and more")))

        send(SessionEvent.Back(7_000))
        assertEquals(ComposeOutbound.TypeCancel(id), effects.filterIsInstance<SendCompose>().single().message)
        assertNull(model.capture)
        assertEquals("first", model.draft?.text)
        assertEquals(DraftState.READY, model.draft?.state)

        compose(ComposeInbound.Done(ComposeTypeDone(id, committed = true, text = "first and more")))
        assertEquals("first", model.draft?.text)
    }

    @Test
    fun `an empty committed typing is the one explicit discard`() {
        openThread("t1", at = 1_000)
        dictate("first", at = 3_000)
        startCapture(CaptureKind.TYPE, at = 6_000, steps = 2)
        compose(ComposeInbound.Done(ComposeTypeDone(model.capture!!.captureId, committed = true, text = "")))
        assertNull(model.draft)
        assertNull(model.capture)
    }

    @Test
    fun `the draft survives root, Back and the session closing`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        val draft = model.draft

        assertEquals("back root · ${SessionScreens.DRAFT_KEPT}", screen().hint?.back)
        send(SessionEvent.Back(5_000))
        assertTrue(model.state is SessionState.Root)
        assertEquals(draft, model.draft)
        send(SessionEvent.Back(5_100))
        assertEquals(SessionState.Closed, model.state)
        assertEquals(draft, model.draft)

        reopenThread("t1", at = 10_000)
        assertEquals(draft, model.draft)
        focus(at = 13_000)
        assertEquals(ComposerRules.SEND, top().composerFocus?.key)
    }

    @Test
    fun `another thread's capture replaces the draft only when it completes, and says so before`() {
        openThread("t1", at = 1_000)
        dictate("for Ana", at = 3_000)
        send(SessionEvent.Back(5_000))
        send(SessionEvent.Back(5_100))

        openThread("t2", at = 10_000, owner = "chat", recipient = "Bo")
        startCapture(CaptureKind.DICTATE, at = 13_000)
        val id = model.capture!!.captureId
        compose(ComposeInbound.Partial(ComposeDictatePartial(id, "for Bo")))
        val live = conversationBlock().bubbles.single { it.key == SessionScreens.LIVE_BUBBLE }
        assertEquals("replaces the draft for Ana", live.meta)
        assertEquals("for Ana", model.draft?.text)

        // Cancelled: Ana's draft stays.
        send(SessionEvent.Back(14_000))
        assertEquals("for Ana", model.draft?.text)

        dictate("for Bo", at = 15_000)
        assertEquals(Draft("chat", "t2", destination("t2", "chat"), "Bo", "for Bo"), model.draft)
    }

    @Test
    fun `triple tap is not recognised over a capture and the session never opens over one`() {
        openThread("t1", at = 1_000)
        startCapture(CaptureKind.DICTATE, at = 3_000)
        send(SessionEvent.TripleTap(4_000))
        assertTrue(model.state is SessionState.InPage)
        assertTrue(model.capture != null)
        assertEquals(listOf(SessionEffect.None), effects)

        // Taken down from elsewhere, the capture is cancelled with it; only then does a session open.
        val id = model.capture!!.captureId
        send(SessionEvent.Abort)
        assertEquals(SessionState.Closed, model.state)
        assertNull(model.capture)
        assertTrue(effects.contains(SendCompose(ComposeOutbound.DictateStop(com.anezium.rokidbus.shared.ComposeDictateStop(id, cancel = true)))))
        send(SessionEvent.TripleTap(6_000))
        assertTrue(model.state is SessionState.Opening)
    }

    @Test
    fun `link loss cancels a capture and keeps the draft, and the return replays nothing`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        startCapture(CaptureKind.DICTATE, at = 6_000, steps = 1)
        val id = model.capture!!.captureId

        send(SessionEvent.LinkLost)
        assertNull(model.capture)
        assertTrue(effects.contains(SendCompose(ComposeOutbound.DictateStop(com.anezium.rokidbus.shared.ComposeDictateStop(id, cancel = true)))))
        assertEquals(DraftState.READY, model.draft?.state)
        assertEquals("see you", model.draft?.text)

        send(SessionEvent.LinkRestored)
        assertTrue(effects.none { it is SendAction || it is SendCompose })
        // The page needs the wearer's Retry and a new deliberate Send.
        assertTrue(top().status is FrameStatus.Unavailable)
    }

    @Test
    fun `a withdrawn thread loses its draft's authority until a new notice for it reopens Type`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)

        send(SessionEvent.NoticeWithdrawn("relay", "t1"))
        assertEquals(DraftState.AUTHORITY_LOST, model.draft?.state)
        assertEquals("see you", model.draft?.text)
        assertEquals(HistoryState.WITHDRAWN, model.history.entries.single().state)
        val line = screen().footer as InlineFooter.StatusLine
        assertEquals(ComposerRules.REPLY_UNAVAILABLE, line.text)
        assertEquals(StatusLineStyle.OFF, line.style)
        assertEquals(SessionScreens.NOT_SENT_HERE, conversationBlock().bubbles.single { it.key == SessionScreens.DRAFT_BUBBLE }.meta)
        shown(5_000)
        press(5_010)
        assertNull(top().composerFocus)

        // Another thread's notice changes nothing; the same thread's reopens Type, prefilled.
        arrive(conversationNotice("t9", "Zoe", "hi"))
        assertFalse(model.draft!!.reopened)
        arrive(conversationNotice("t1", "Ana", "still there?"))
        assertTrue(model.draft!!.reopened)
        focus(at = 6_000)
        assertEquals(ComposerFocus(ComposerRules.TYPE, 1), top().composerFocus)
        shown(6_100)
        press(6_110)
        val start = effects.filterIsInstance<SendCompose>().single().message as ComposeOutbound.TypeStart
        assertEquals("see you", start.start.initialText)
        compose(ComposeInbound.Done(ComposeTypeDone(start.captureId, committed = true, text = "see you, sorry")))
        assertEquals(DraftState.READY, model.draft?.state)

        // Then OK, then a new Send.
        focus(at = 7_000)
        assertEquals(ComposerRules.SEND, top().composerFocus?.key)
    }

    @Test
    fun `authority_lost or a page that cannot reply keeps the text and withholds Send`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        val action = sendDraft(at = 5_000)
        send(
            SessionEvent.PageResult(
                JSONObject().put("invocationId", action.invocationId).put("status", "rejected").put("code", PageSurfaceContract.CODE_AUTHORITY_LOST),
                5_500,
            ),
        )
        assertEquals(DraftState.AUTHORITY_LOST, model.draft?.state)
        assertEquals("see you", model.draft?.text)
        assertNull(top().outcome)
        assertTrue(screen().footer is InlineFooter.StatusLine)

        // A page revision saying the thread cannot be replied to does the same for a fresh draft.
        reset()
        openThread("t1", at = 1_000)
        dictate("again", at = 3_000)
        val request = requestOf()
        respond(request, conversation("t1", replyable = false), revision = 2, now = 5_000)
        assertEquals(DraftState.AUTHORITY_LOST, model.draft?.state)
        assertEquals("again", model.draft?.text)
        shown(5_100)
        press(5_110)
        assertNull(top().composerFocus)
    }

    @Test
    fun `a stale send keeps the draft for a new Send on the new revision`() {
        openThread("t1", at = 1_000)
        dictate("see you", at = 3_000)
        val action = sendDraft(at = 5_000)
        send(SessionEvent.PageResult(JSONObject().put("invocationId", action.invocationId).put("status", "stale"), 5_500))
        assertEquals(DraftState.READY, model.draft?.state)
        assertNull(model.pendingSend)
        assertTrue(effects.none { it is SendAction })
        focus(at = 6_000)
        assertEquals(ComposerRules.SEND, top().composerFocus?.key)
    }

    @Test
    fun `bubbles take their side from the role alone`() {
        openThread("t1", at = 1_000)
        val sides = conversationBlock().bubbles.map { it.key to it.side }
        assertEquals(
            listOf("m:m1" to BubbleSide.LEFT, "m:m2" to BubbleSide.RIGHT, "m:m3" to BubbleSide.NONE),
            sides,
        )
        val header = screen().header
        assertEquals("RELAY · ANA", header?.title)
        assertEquals("tap reply", screen().hint?.action)
    }

    // ---- notifications ------------------------------------------------------------------------

    @Test
    fun `the root's conversation card keeps its entry and resolves in place`() {
        arrive(conversationNotice("t1", "Ana", "on est devant"))
        openRoot(at = 1_000)
        val card = root().stops.first()
        assertEquals(NoticeCardKind.CONVERSATION, card.notice?.kind)
        assertEquals("relay:thread", card.pageId)
        val entryId = card.notice?.entryId

        // A newer thread of the same owner neither swaps the card nor makes it an aggregate.
        arrive(conversationNotice("t2", "Bo", "on my way"))
        val after = root().stops.first()
        assertEquals(entryId, after.notice?.entryId)
        assertEquals(NoticeCardKind.CONVERSATION, after.notice?.kind)
        assertEquals("Ana", after.text)
        assertEquals(2, root().stops.single { it.kind == RootStopKind.NOTIFICATIONS }.count)

        send(SessionEvent.NoticeWithdrawn("relay", "t1"))
        assertEquals(HistoryState.WITHDRAWN, root().stops.first().notice?.state)
        val drawn = screen().blocks.first() as ScreenBlock.Card
        assertEquals("RELAY · ${SessionScreens.NOTIFICATION_REMOVED}", drawn.label)
        assertEquals(SessionScreens.REMOVED_NOTHING_PROVEN, drawn.tertiary)

        // It still opens its exact thread, never another one.
        send(SessionEvent.Enter(3_000))
        val request = effects.filterIsInstance<RequestPage>().single().request
        assertEquals("relay:thread", request.correlation.pageId)
        assertEquals(destination("t1").paramsJson, request.paramsJson)
    }

    @Test
    fun `several live threads of one owner make an aggregate opening its main page`() {
        arrive(conversationNotice("t1", "Ana", "hi"))
        arrive(conversationNotice("t2", "Bo", "hello"))
        openRoot(at = 1_000)
        val card = root().stops.first()
        assertEquals(NoticeCardKind.AGGREGATE, card.notice?.kind)
        assertEquals("2 conversations", card.text)
        assertEquals("Bo, Ana", card.notice?.secondary)
        send(SessionEvent.Enter(2_000))
        assertEquals("relay:main", effects.filterIsInstance<RequestPage>().single().request.correlation.pageId)
    }

    @Test
    fun `a Notifications row opens the exact thread and marks its entry seen`() {
        arrive(NoticeEntry("todo", "Todo", "Milk", null, atWallMs = wallNow))
        arrive(conversationNotice("t1", "Ana", "on est devant"))
        val local = LocalPages({ model.history }, { emptyList() }, { emptyList() }, { true }, { "14:02" }, { wallNow })
        openRoot(at = 1_000)
        val notifications = root().stops.indexOfFirst { it.kind == RootStopKind.NOTIFICATIONS }
        repeat(notifications) { send(SessionEvent.Step(1, 1_900L + it)) }
        send(SessionEvent.Enter(2_000))
        send(SessionEvent.PageResponse(local.answer(requestOf()), 2_100))
        assertEquals(2, items.rows((top().status as FrameStatus.Shown).snapshot.page).size)

        send(SessionEvent.Enter(2_200))
        val request = requestOf()
        assertEquals("relay:thread", request.correlation.pageId)
        assertEquals(destination("t1").paramsJson, request.paramsJson)
        assertEquals(HistoryState.SEEN, model.history.entries.first().state)
        assertEquals(HistoryState.NEW, model.history.entries.last().state)
    }

    @Test
    fun `without the launcher bundle a conversation page is never drawn nor composed`() {
        negotiated = false
        arrive(conversationNotice("t1", "Ana", "hi"))
        openRoot(at = 1_000)
        // The host keeps the bundle's fields out of the history then; even given one, the page stays inert.
        send(SessionEvent.Enter(2_000))
        respond(requestOf(), conversation("t1"), now = 2_100, expectShown = false)
        assertTrue(top().status is FrameStatus.Unavailable)
        assertNull(model.capture)
    }

    @Test
    fun `the history is bounded, aged, purged by owner and keeps the band's text`() {
        var history = NotificationHistory()
        repeat(31) { history = history.add(NoticeEntry("relay", "Relay", "Ana", "msg $it", atWallMs = it.toLong())) }
        assertEquals(maxHistory, history.entries.size)
        assertEquals("msg 30", history.entries.first().notice.body)

        val day = 24L * 60 * 60 * 1000
        val aged = NotificationHistory()
            .add(NoticeEntry("relay", "Relay", null, "old", atWallMs = 0))
            .add(NoticeEntry("relay", "Relay", null, "new", atWallMs = day))
        assertEquals(listOf("new"), aged.visible(day).map { it.notice.body })
        assertEquals(listOf("new", "old"), aged.visible(day - 1).map { it.notice.body })

        // The redacted text the band showed is kept as it was, never rewritten.
        val redacted = NotificationHistory().add(NoticeEntry("relay", "Relay", "Hidden sender", "New message", atWallMs = 0))
        assertEquals("Hidden sender", redacted.entries.single().notice.text)
        assertEquals("New message", redacted.entries.single().notice.body)

        val mixed = NotificationHistory()
            .add(NoticeEntry("relay", "Relay", null, "a", atWallMs = 0, eventKey = "k1"))
            .add(NoticeEntry("todo", "Todo", null, "b", atWallMs = 0, eventKey = "k1"))
        assertEquals(listOf("todo"), mixed.purge("relay").entries.map { it.notice.ownerPluginId })
        val withdrawn = mixed.withdraw("relay", "k1")
        assertEquals(listOf(HistoryState.NEW, HistoryState.WITHDRAWN), withdrawn.entries.map { it.state })
        // Withdrawn is never rolled back to seen.
        val id = withdrawn.entries.last().id
        assertEquals(HistoryState.WITHDRAWN, withdrawn.markSeen(id).entry(id)?.state)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private val maxHistory = com.anezium.rokidbus.shared.NoticeSurfaceContract.MAX_HISTORY_ENTRIES

    private fun reset() {
        model = SessionModel()
        effects = emptyList()
    }

    private fun destination(thread: String, owner: String = "relay") =
        PageDestination("$owner:thread", JSONObject().put("thread", thread).toString())

    private fun conversationNotice(thread: String, sender: String, text: String, owner: String = "relay") = NoticeEntry(
        ownerPluginId = owner,
        sourceLabel = if (owner == "relay") "Relay" else owner,
        title = sender,
        body = text,
        atWallMs = wallNow,
        destination = destination(thread, owner),
        eventKey = thread,
        conversation = true,
    )

    private fun arrive(entry: NoticeEntry) = send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, entry.text), entry))

    private fun conversation(thread: String, recipient: String = "Ana", replyable: Boolean = true) = PageBody.Conversation(
        threadKey = thread,
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "on est devant"),
            ConversationMessage("m2", ConversationRole.OURS, "j'arrive"),
            ConversationMessage("m3", ConversationRole.UNKNOWN, "?"),
        ),
        caption = "captured 14:02",
        composer = ConversationComposer(recipient, "send", 500, replyable),
    )

    private val sendAction: JSONObject
        get() = JSONObject().put("id", "send").put("label", "Send").put("kind", "plugin").put("confirm", false)
            .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(PageActionType.ComposeSend))
            .put(QuickActionPolicyContract.FIELD_HUB_QUICK, false)

    private fun respond(
        request: PageSurfaceRequest,
        body: PageBody,
        revision: Long = 1,
        now: Long,
        expectShown: Boolean = true,
    ) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", body.template).put("title", "Ana")
            .put("body", PageTemplateContract.encode(body)).put("actions", JSONArray().put(sendAction)).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
        if (expectShown) assertTrue(top().status is FrameStatus.Shown)
    }

    /** The request in flight for the top frame: the last one asked, or the one that showed it. */
    private fun requestOf(): PageSurfaceRequest {
        val top = top()
        val requestId = when (val status = top.status) {
            is FrameStatus.Loading -> status.requestId
            is FrameStatus.Shown -> status.requestId
            is FrameStatus.Unavailable -> error("no request")
        }
        val frameIndex = (model.state as SessionState.InPage).frames.size
        return PageSurfaceRequest(
            com.anezium.rokidbus.shared.PageCorrelation(requestId, model.generation, frameIndex, top.pageId),
            SessionReducer.REASON_OPEN,
            top.paramsJson,
        )
    }

    /** A conversation notice, a new session, its card, and the thread's page shown. */
    private fun openThread(thread: String, at: Long, owner: String = "relay", recipient: String = "Ana") {
        arrive(conversationNotice(thread, recipient, "on est devant", owner))
        reopenThread(thread, at, owner, recipient)
    }

    private fun reopenThread(thread: String, at: Long, owner: String = "relay", recipient: String = "Ana") {
        openRoot(at)
        val card = root().stops.indexOfFirst { it.kind == RootStopKind.NOTICE_PREVIEW }
        repeat(card) { send(SessionEvent.Step(1, at + 900L + it)) }
        send(SessionEvent.Enter(at + 1_000))
        val request = effects.filterIsInstance<RequestPage>().single().request
        assertEquals("$owner:thread", request.correlation.pageId)
        respond(request, conversation(thread, recipient), now = at + 1_100)
    }

    private fun openRoot(at: Long) {
        if (model.state != SessionState.Closed) {
            while (model.state != SessionState.Closed) send(SessionEvent.Back(at - 10))
        }
        send(SessionEvent.TripleTap(at))
        send(SessionEvent.Tick(at + PageSurfaceContract.GATE_MS))
        assertTrue(model.state is SessionState.Root)
    }

    /** Reading to focus: the screen is drawn at [at], the press begins after it. */
    private fun focus(at: Long) {
        shown(at)
        press(at + 10)
    }

    /** Focus, [steps] along the buttons, then a fresh press starts [kind]. */
    private fun startCapture(kind: CaptureKind, at: Long, steps: Int = if (kind == CaptureKind.TYPE) 1 else 0) {
        focus(at)
        repeat(steps) { send(SessionEvent.Step(1, at + 20 + it)) }
        shown(at + 100)
        press(at + 110)
        assertEquals(kind, model.capture?.kind)
    }

    /** A completed dictation of [text], from reading. */
    private fun dictate(text: String, at: Long) {
        val steps = if (model.draft?.isFor(model.capture?.ownerPluginId ?: currentOwner(), currentThread()) == true &&
            model.draft?.state == DraftState.READY
        ) {
            1
        } else {
            0
        }
        startCapture(CaptureKind.DICTATE, at, steps)
        val id = model.capture!!.captureId
        send(SessionEvent.Enter(at + 500, pressStartMs = at + 490))
        compose(ComposeInbound.Final(ComposeDictateFinal(id, ComposeDictationReason.COMPLETED, text)))
        assertNull(model.capture)
    }

    /** From reading with a ready draft: focus lands on Send, a fresh press sends it. */
    private fun sendDraft(at: Long): com.anezium.rokidbus.shared.PageSurfaceInvocation {
        focus(at)
        assertEquals(ComposerRules.SEND, top().composerFocus?.key)
        shown(at + 100)
        press(at + 110)
        return effects.filterIsInstance<SendAction>().single().action
    }

    private fun currentOwner(): String? = PageTemplateContract.namespaceOf(top().pageId)

    private fun currentThread(): String? =
        (top().status as? FrameStatus.Shown)?.let { items.conversation(it.snapshot.page)?.threadKey }

    private fun compose(message: ComposeInbound) = send(SessionEvent.ComposeReceived(message, 0))

    private fun shown(atMs: Long) {
        send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    }

    private fun press(at: Long) = send(SessionEvent.Enter(at + 40, pressStartMs = at))

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun screen(): SessionScreen = screens.screen(model.state, null, model.generation, CommandFacts.of(model))

    private fun conversationBlock(): ScreenBlock.Conversation = screen().blocks.filterIsInstance<ScreenBlock.Conversation>().single()

    private fun root() = model.state as SessionState.Root
    private fun top() = (model.state as SessionState.InPage).frames.last()
}
