package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageRow
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTarget
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Launcher bundle v9: a row's section is a caption above it, never a stop of the selection. */
class RowSectionScreenTest {
    private var version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 9)
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { true },
        launcherVersion = { version },
    )
    private val reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId, launcherActive = { true })
    private val screens = SessionScreens(items, pluginLabel = { null }, formatTime = { "12:00" }, wallTimeOf = { it })
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()

    private val chats = PageBody.SelectableList(
        listOf(
            PageRow("a", "Claire", target = PageTarget.Page("line:chat:a"), section = "PINNED"),
            PageRow("b", "Julie", target = PageTarget.Page("line:chat:b"), section = "RECENT"),
            PageRow("c", "Tom", target = PageTarget.Page("line:chat:c")),
        ),
    )

    @Test
    fun `each section is drawn above its row and the selection starts on the first contact`() {
        openList()
        val screen = screen()
        assertEquals(
            listOf("section:row:a", "row:a", "section:row:b", "row:b", "row:c"),
            screen.blocks.map { it.key },
        )
        val captions = screen.blocks.filterIsInstance<ScreenBlock.Text>()
        assertEquals(listOf("PINNED", "RECENT"), captions.map { it.text })
        assertTrue(captions.all { it.role == TextRole.SECTION })
        assertEquals("row:a", screen.focusKey)
        assertEquals("1/3", screen.header?.trail)
        assertEquals("tap open", screen.hint?.action)
    }

    @Test
    fun `a swipe moves from the last pinned row straight to the first recent one`() {
        openList()
        send(SessionEvent.Step(1, 2_300))
        assertEquals("row:b", screen().focusKey)
        assertEquals("2/3", screen().header?.trail)
    }

    @Test
    fun `a pair below v9 draws the same rows in the same order without captions`() {
        version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 8)
        openList()
        assertEquals(listOf("row:a", "row:b", "row:c"), screen().blocks.map { it.key })
    }

    private fun openList() {
        send(SessionEvent.ActivityStarted(ActivityStop(
            id = "line:activity", pageId = LocalPages.activityPageId("line"), text = "LINE", pinned = true,
            ownerPluginId = "line", ownerLabel = "LINE", seq = 1, destination = PageDestination("line:main"),
            activation = ActivityActivation.DESTINATION,
        )))
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        shown(1_900)
        press(2_000)
        respond(effects.filterIsInstance<RequestPage>().single().request, chats, 2_100)
        shown(2_200)
    }

    private fun respond(request: PageSurfaceRequest, body: PageBody, now: Long) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", 1L).put("template", body.template).put("title", "LINE")
            .put("body", PageTemplateContract.encode(body)).put("actions", JSONArray()).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
    }

    private fun screen() = screens.screen(model.state, null, model.generation, CommandFacts.of(model))
    private fun shown(atMs: Long) = send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    private fun press(at: Long) = send(SessionEvent.Enter(at + 40, pressStartMs = at))
    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }
}
