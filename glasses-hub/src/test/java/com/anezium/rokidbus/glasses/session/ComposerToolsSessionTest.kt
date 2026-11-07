package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.glasses.session.SessionEffect.SendCompose
import com.anezium.rokidbus.glasses.session.SessionEffect.SendLauncherOpen
import com.anezium.rokidbus.shared.ComposeDictateFinal
import com.anezium.rokidbus.shared.ComposeDictationReason
import com.anezium.rokidbus.shared.ComposerSlot
import com.anezium.rokidbus.shared.ComposerTool
import com.anezium.rokidbus.shared.ComposerToolContext
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageCorrelation
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Launcher bundle v5 on the glasses: the composer bar a page configures, its tools, and message thumbnails. */
class ComposerToolsSessionTest {
    private var launcherVersion = 5
    private var wallNow = 50_000_000L
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { launcherVersion >= 1 },
        launcherVersion = { launcherVersion },
    )
    private val reducer = SessionReducer(
        items,
        isLocalPage = PageTemplateContract::isLocalPageId,
        launcherActive = { launcherVersion >= 1 },
        wallClock = { wallNow },
    )
    private val screens = SessionScreens(items, { if (it == "meta") "Meta" else null }, { "14:02" }, { it }, { wallNow })
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()
    private val thumb = LauncherArtwork(8, 8, Base64.getEncoder().encodeToString(ByteArray(8) { 0x55 }))

    private val photo = ComposerTool("photo", "Photo", "camera", glyph = "camera")
    private val like = ComposerTool("like", "Like", "like")

    @Test
    fun `the bar follows the page's layout labels and tools with the hub's glyphs where it knows them`() {
        openThread(composer(layout = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Type, ComposerSlot.Send, ComposerSlot.Tool("like")), typeLabel = "Write", sendLabel = "Go"))
        focus(at = 3_000)
        val buttons = (screen().footer as InlineFooter.Buttons).buttons
        assertEquals(listOf(ComposerRules.toolKey("photo"), ComposerRules.TYPE, ComposerRules.toolKey("like")), buttons.map { it.key })
        assertEquals(listOf("Photo", "Write", "Like"), buttons.map { it.label })
        assertEquals(listOf("camera", "keyboard", null), buttons.map { it.glyph })
        // Dictate is hidden, so the first tap lands on Type.
        assertEquals(ComposerFocus(ComposerRules.TYPE, 1), top().composerFocus)
        assertEquals("swipe choose · tap start", screen().hint?.action)

        send(SessionEvent.Step(-1, 3_100))
        assertEquals(ComposerFocus(ComposerRules.toolKey("photo"), 0), top().composerFocus)
        assertEquals("swipe choose · tap photo", screen().hint?.action)
        send(SessionEvent.Step(1, 3_200))
        send(SessionEvent.Step(1, 3_300))
        assertEquals(ComposerFocus(ComposerRules.toolKey("like"), 2), top().composerFocus)
        send(SessionEvent.Step(1, 3_400))
        assertEquals(2, top().composerFocus?.index)

        // A draft brings Send in at its own slot, with the page's label.
        send(SessionEvent.Back(3_500))
        dictateVia(ComposerRules.TYPE, "see you", at = 4_000)
        focus(at = 5_000)
        val withSend = (screen().footer as InlineFooter.Buttons).buttons
        assertEquals(listOf(ComposerRules.toolKey("photo"), ComposerRules.TYPE, ComposerRules.SEND, ComposerRules.toolKey("like")), withSend.map { it.key })
        assertEquals("Go", withSend[2].label)
        assertEquals(ComposerFocus(ComposerRules.SEND, 2), top().composerFocus)
    }

    @Test
    fun `without a layout the tools follow the default bar`() {
        openThread(composer(layout = null))
        focus(at = 3_000)
        val keys = (screen().footer as InlineFooter.Buttons).buttons.map { it.key }
        assertEquals(listOf(ComposerRules.DICTATE, ComposerRules.TYPE, ComposerRules.toolKey("photo"), ComposerRules.toolKey("like")), keys)
        assertEquals(ComposerFocus(ComposerRules.DICTATE, 0), top().composerFocus)
    }

    @Test
    fun `a plugin tool runs its action in confirmed mode on a fresh tap and leaves focus`() {
        openThread(composer(layout = listOf(ComposerSlot.Tool("like"), ComposerSlot.Dictate, ComposerSlot.Tool("photo"))))
        focus(at = 3_000)
        send(SessionEvent.Step(-1, 3_100))
        assertEquals(ComposerRules.toolKey("like"), top().composerFocus?.key)
        shown(3_200)
        press(3_190)
        assertTrue(effects.none { it is SendAction })
        press(3_210)
        val action = effects.filterIsInstance<SendAction>().single().action
        assertEquals("like", action.actionId)
        assertEquals(PageSurfaceContract.MODE_CONFIRMED, action.mode)
        assertNull(action.text)
        assertEquals("like", top().invocation?.actionId)
        assertNull(top().composerFocus)
        assertNull(model.pendingSend)
    }

    @Test
    fun `an immersion tool opens the provider with the page thread and tool it came from`() {
        openThread(composer(layout = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Dictate), tools = listOf(photo)))
        focus(at = 3_000)
        assertEquals(ComposerRules.DICTATE, top().composerFocus?.key)
        send(SessionEvent.Step(-1, 3_100))
        shown(3_200)
        press(3_210)
        val open = effects.filterIsInstance<SendLauncherOpen>().single()
        assertEquals("meta", open.pluginId)
        assertEquals(
            ComposerToolContext("meta:thread", JSONObject().put("thread", "t1").toString(), "t1", "photo", "camera"),
            open.context,
        )
        assertTrue(model.state is SessionState.Launching)
        assertTrue(effects.none { it is SendAction || it is SendCompose })
    }

    @Test
    fun `a pair below v5 draws the default bar and ignores the page's tools`() {
        launcherVersion = 4
        openThread(composer(layout = listOf(ComposerSlot.Tool("photo"), ComposerSlot.Type), typeLabel = "Write", tools = listOf(photo)))
        focus(at = 3_000)
        val buttons = (screen().footer as InlineFooter.Buttons).buttons
        assertEquals(listOf(ComposerRules.DICTATE, ComposerRules.TYPE), buttons.map { it.key })
        assertEquals(listOf("Dictate", "Type"), buttons.map { it.label })
        assertTrue(conversationBlock().bubbles.all { it.image == null })
    }

    @Test
    fun `a failed dictation falls back to Dictate when the page hides Type`() {
        openThread(composer(layout = listOf(ComposerSlot.Dictate, ComposerSlot.Tool("photo")), tools = listOf(photo)))
        focus(at = 3_000)
        shown(3_100)
        press(3_110)
        val id = model.capture!!.captureId
        send(SessionEvent.ComposeReceived(ComposeInbound.Final(ComposeDictateFinal(id, ComposeDictationReason.NO_SPEECH, null)), 0))
        assertNull(model.capture)
        assertEquals(ComposerFocus(ComposerRules.DICTATE, 0), top().composerFocus)
    }

    @Test
    fun `message thumbnails reach the bubbles`() {
        openThread(composer(layout = null))
        val bubbles = conversationBlock().bubbles
        assertEquals(thumb, bubbles.first { it.key == "m:m1" }.image)
        assertNull(bubbles.first { it.key == "m:m2" }.image)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun composer(
        layout: List<ComposerSlot>?,
        typeLabel: String? = null,
        sendLabel: String? = null,
        tools: List<ComposerTool> = listOf(photo, like),
    ) = ConversationComposer(
        "Ana", "send", 500, replyable = true,
        tools = tools, layout = layout, typeLabel = typeLabel, sendLabel = sendLabel,
    )

    private fun conversation(composer: ConversationComposer) = PageBody.Conversation(
        threadKey = "t1",
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "look", image = thumb),
            ConversationMessage("m2", ConversationRole.OURS, "nice"),
        ),
        caption = "captured 14:02",
        composer = composer,
    )

    private fun destination(thread: String) = PageDestination("meta:thread", JSONObject().put("thread", thread).toString())

    private fun conversationNotice(thread: String, sender: String, text: String) = NoticeEntry(
        ownerPluginId = "meta",
        sourceLabel = "Meta",
        title = sender,
        body = text,
        atWallMs = wallNow,
        destination = destination(thread),
        eventKey = thread,
        conversation = true,
    )

    private val actions: JSONArray
        get() = JSONArray()
            .put(
                JSONObject().put("id", "send").put("label", "Send").put("kind", "plugin").put("confirm", false)
                    .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(PageActionType.ComposeSend)),
            )
            .put(JSONObject().put("id", "camera").put("label", "Camera").put("kind", "immersion").put("confirm", false))
            .put(JSONObject().put("id", "like").put("label", "Like").put("kind", "plugin").put("confirm", false))

    /** A conversation notice, a new session, its card, and the thread's page shown with [composer]. */
    private fun openThread(composer: ConversationComposer, at: Long = 1_000) {
        send(SessionEvent.NoticeArrived(NoticePreview(RootStops.NOTICE_PAGE_ID, "on est devant"), conversationNotice("t1", "Ana", "on est devant")))
        send(SessionEvent.TripleTap(at))
        send(SessionEvent.Tick(at + PageSurfaceContract.GATE_MS))
        val root = model.state as SessionState.Root
        val card = root.stops.indexOfFirst { it.kind == RootStopKind.NOTICE_PREVIEW }
        val steps = card - root.selected
        repeat(kotlin.math.abs(steps)) { send(SessionEvent.Step(if (steps > 0) 1 else -1, at + 900L + it)) }
        send(SessionEvent.Enter(at + 1_000))
        val request = effects.filterIsInstance<RequestPage>().single().request
        assertEquals("meta:thread", request.correlation.pageId)
        respond(request, conversation(composer), now = at + 1_100)
    }

    private fun respond(request: PageSurfaceRequest, body: PageBody, now: Long) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1).put("template", body.template).put("title", "Ana")
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
        assertTrue(top().status is FrameStatus.Shown)
    }

    /** Reading to focus: the screen is drawn at [at], the press begins after it. */
    private fun focus(at: Long) {
        shown(at)
        press(at + 10)
    }

    /** A completed capture of [text] through the focused [key], from reading. */
    private fun dictateVia(key: String, text: String, at: Long) {
        focus(at)
        while (top().composerFocus?.key != key) send(SessionEvent.Step(1, at + 20))
        shown(at + 100)
        press(at + 110)
        val capture = model.capture!!
        val message = if (capture.kind == CaptureKind.TYPE) {
            ComposeInbound.Done(com.anezium.rokidbus.shared.ComposeTypeDone(capture.captureId, true, text))
        } else {
            ComposeInbound.Final(ComposeDictateFinal(capture.captureId, ComposeDictationReason.COMPLETED, text))
        }
        send(SessionEvent.ComposeReceived(message, 0))
        assertEquals(text, model.draft?.text)
    }

    private fun shown(atMs: Long) {
        send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    }

    private fun press(at: Long, start: Long = at) = send(SessionEvent.Enter(at + 40, pressStartMs = start))

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun screen(): SessionScreen = screens.screen(model.state, null, model.generation, CommandFacts.of(model))

    private fun conversationBlock(): ScreenBlock.Conversation = screen().blocks.filterIsInstance<ScreenBlock.Conversation>().single()

    private fun top() = (model.state as SessionState.InPage).frames.last()
}
