package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.ConversationComposer
import com.anezium.rokidbus.shared.ConversationMessage
import com.anezium.rokidbus.shared.ConversationRole
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.PageActionType
import com.anezium.rokidbus.shared.PageActionTypeContract
import com.anezium.rokidbus.shared.QuickActionPolicyContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A retained page is read with the templates it was accepted under (plan 028a, BUSSPEC page
 * unavailable): the live negotiation decides what is accepted and acted on, never how a page
 * already accepted is read, in either direction.
 */
class RetainedProvenanceTest {
    private var bundle = true
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { bundle },
    )
    private val reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId, launcherActive = { bundle })
    private val screens = SessionScreens(items, pluginLabel = { "Relay" }, formatTime = { "09:41" }, wallTimeOf = { it })
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    @Test
    fun `a conversation accepted over the bundle stays readable once the bundle is gone, and nothing on it acts`() {
        openThread()
        val shown = (top().status as FrameStatus.Shown).snapshot
        assertTrue(shown.extended)

        bundle = false
        send(SessionEvent.LinkLost)
        val screen = screen()
        val conversation = screen.blocks.filterIsInstance<ScreenBlock.Conversation>().single()
        assertTrue(conversation.dimmed)
        assertEquals(listOf("on est devant", "j'arrive"), conversation.bubbles.map { it.text })
        assertEquals(listOf("retry", "back"), screen.blocks.filterIsInstance<ScreenBlock.Row>().map { it.key })
        assertNull(screen.footer)
        // The live reading of the same page is refused: rows and actions follow the live bundle.
        assertNull(items.body(shown.page))
        assertTrue(items.rows(shown.page).isEmpty())
    }

    @Test
    fun `a page accepted without the bundle is never read with it, whatever the live negotiation says`() {
        openThread()
        val page = (top().status as FrameStatus.Shown).snapshot.page
        assertNotNull(items.retainedBody(Snapshot(page, extended = true)))
        assertNull(items.retainedBody(Snapshot(page, extended = false)))
        bundle = false
        assertNotNull(items.retainedBody(Snapshot(page, extended = true)))
        assertNull(items.retainedBody(Snapshot(page, extended = false)))
    }

    @Test
    fun `an older phone's answer at the same revision replaces the retained rich page instead of keeping it`() {
        openThread()
        bundle = false
        send(SessionEvent.LinkLost)
        send(SessionEvent.LinkRestored)
        // The wearer's Retry, answered by a phone without the bundle at the same revision.
        press(5_000)
        val request = effects.filterIsInstance<RequestPage>().single().request
        respond(request, PageBody.Summary(primary = "Ana", secondary = null, text = "Open Relay on the phone", facts = emptyList()), now = 5_100)
        val snapshot = (top().status as FrameStatus.Shown).snapshot
        assertFalse(snapshot.extended)
        assertEquals(PageSurfaceContract.TEMPLATE_SUMMARY, snapshot.page.template)
        assertNull(items.conversation(snapshot.page))
    }

    private fun openThread() {
        val stop = ActivityStop(
            id = "relay:activity",
            pageId = LocalPages.activityPageId("relay"),
            text = "Ana",
            ownerPluginId = "relay",
            ownerLabel = "Relay",
            seq = 1,
            destination = PageDestination("relay:thread", """{"thread":"t1"}"""),
            activation = ActivityActivation.DESTINATION,
        )
        send(SessionEvent.ActivityStarted(stop))
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        press(2_000)
        val request = effects.filterIsInstance<RequestPage>().single().request
        respond(request, conversation(), now = 2_100)
        assertEquals("relay:thread", top().pageId)
    }

    private fun conversation() = PageBody.Conversation(
        threadKey = "t1",
        messages = listOf(
            ConversationMessage("m1", ConversationRole.RECEIVED, "on est devant"),
            ConversationMessage("m2", ConversationRole.OURS, "j'arrive"),
        ),
        caption = "captured 14:02",
        composer = ConversationComposer("Ana", "send", 500, true),
    )

    private fun respond(request: PageSurfaceRequest, body: PageBody, now: Long) {
        // A conversation's composer names its typed Send among the page's actions.
        val actions = if (body is PageBody.Conversation) JSONArray().put(sendAction()) else JSONArray()
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1).put("template", body.template).put("title", "Ana")
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
        assertTrue("${top().status}", top().status is FrameStatus.Shown)
    }

    private fun sendAction(): JSONObject = JSONObject().put("id", "send").put("label", "Send").put("kind", "plugin").put("confirm", false)
        .put(PageActionTypeContract.FIELD_TYPED, PageActionTypeContract.encode(PageActionType.ComposeSend))
        .put(QuickActionPolicyContract.FIELD_HUB_QUICK, false)

    /** A press begun after what it lands on was drawn. */
    private fun press(at: Long) {
        SessionFocus.tokenOf(model)?.let { send(SessionEvent.FocusShown(it, at - 100)) }
        send(SessionEvent.Enter(at + 40, pressStartMs = at))
    }

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }

    private fun screen() = screens.screen(model.state, null, model.generation, CommandFacts.of(model))

    private fun top() = (model.state as SessionState.InPage).frames.last()
}
