package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.glasses.session.SessionEffect.SendAction
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.LauncherArtwork
import com.anezium.rokidbus.shared.LauncherInteractiveContract
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Launcher bundle v6: an image page is drawn as its picture, caption and ordinary action rows. */
class ImagePageScreenTest {
    private var version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 6)
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
    private lateinit var request: PageSurfaceRequest

    private val preview = LauncherArtwork(128, 96, Base64.getEncoder().encodeToString(ByteArray(128 * 96 / 8) { 0x55 }))
    private val photo = PageBody.Image(preview, "Photo to Alice · WhatsApp")

    @Test
    fun `an image page draws the picture, its caption and selectable action rows`() {
        openImagePage()
        val screen = screen()
        val image = screen.blocks.filterIsInstance<ScreenBlock.Image>().single()
        assertEquals(preview, image.image)
        assertEquals("Photo to Alice · WhatsApp", image.caption)
        assertFalse(image.dimmed)
        assertEquals(0, screen.blocks.indexOf(image))
        val rows = screen.blocks.filterIsInstance<ScreenBlock.Row>()
        assertEquals(listOf("row:action send-photo", "row:action retake", "row:action cancel"), rows.map { it.key })
        assertEquals(listOf("Send", "Retake", "Cancel"), rows.map { it.label })
        assertTrue(rows.first().selected)
        assertEquals("row:action send-photo", screen.focusKey)
        assertEquals("tap run", screen.hint?.action)
        assertEquals("1/3", screen.header?.trail)
        assertNull(screen.fit)
    }

    @Test
    fun `swiping moves the selection along the actions and a tap runs the selected one`() {
        openImagePage()
        send(SessionEvent.Step(1, 2_300))
        assertEquals("row:action retake", screen().focusKey)
        send(SessionEvent.Step(1, 2_400))
        assertEquals("row:action cancel", screen().blocks.filterIsInstance<ScreenBlock.Row>().single { it.selected }.key)
        shown(2_500)
        press(2_600)
        val invocation = effects.filterIsInstance<SendAction>().single().action
        assertEquals("cancel", invocation.actionId)
        assertEquals("photo:main", invocation.pageId)
    }

    @Test
    fun `a live replacement without actions keeps the picture and offers only back`() {
        openImagePage()
        respond(request, PageBody.Image(preview, "Sending…"), JSONArray(), 2_300, revision = 2)
        val screen = screen()
        assertEquals("Sending…", screen.blocks.filterIsInstance<ScreenBlock.Image>().single().caption)
        // The removed selection leaves its inert tombstone, as on every page: a tap runs nothing.
        assertTrue(screen.blocks.filterIsInstance<ScreenBlock.Row>().all { it.tombstone })
        assertEquals("back root", screen.hint?.back)
        press(2_400)
        assertTrue(effects.none { it is SendAction })
    }

    @Test
    fun `an unavailable image page keeps its picture dimmed and acts on nothing`() {
        openImagePage()
        val shownStatus = top().status as FrameStatus.Shown
        val error = PageSurfaceContract.correlationPayload(request.correlation.copy(requestId = shownStatus.requestId))
            .put("error", PageSurfaceContract.ERROR_PAGE_UNAVAILABLE).put(PageSurfaceContract.FIELD_PLUGIN_ID, "photo")
        send(SessionEvent.PageResponse(error, 9_000))
        assertTrue(top().status is FrameStatus.Unavailable)
        val screen = screen()
        val image = screen.blocks.filterIsInstance<ScreenBlock.Image>().single()
        assertTrue(image.dimmed)
        assertEquals("last:image", image.key)
        assertEquals(listOf("retry", "back"), screen.blocks.filterIsInstance<ScreenBlock.Row>().map { it.key })
    }

    @Test
    fun `a pre-v6 pair never sees the picture`() {
        version = LauncherInteractiveContract.negotiated(LauncherInteractiveContract.VERSION, 5)
        openImagePage()
        assertTrue(screen().blocks.none { it is ScreenBlock.Image })
    }

    private fun openImagePage() {
        send(SessionEvent.ActivityStarted(ActivityStop(
            id = "photo:activity", pageId = LocalPages.activityPageId("photo"), text = "Alice", pinned = true,
            ownerPluginId = "photo", ownerLabel = "Photo", seq = 1, destination = PageDestination("photo:main"),
            activation = ActivityActivation.DESTINATION,
        )))
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        shown(1_900)
        press(2_000)
        request = effects.filterIsInstance<RequestPage>().single().request
        respond(request, photo, JSONArray().put(action("send-photo", "Send")).put(action("retake", "Retake")).put(action("cancel", "Cancel")), 2_100)
        shown(2_200)
    }

    private fun respond(request: PageSurfaceRequest, body: PageBody, actions: JSONArray, now: Long, revision: Long = 1) {
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", body.template).put("title", "Photo")
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
    }

    private fun action(id: String, label: String) = JSONObject().put("id", id).put("label", label).put("kind", "plugin").put("confirm", false)
    private fun screen() = screens.screen(model.state, null, model.generation, CommandFacts.of(model))
    private fun top() = (model.state as SessionState.InPage).frames.last()
    private fun shown(atMs: Long) = send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    private fun press(at: Long) = send(SessionEvent.Enter(at + 40, pressStartMs = at))
    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
    }
}
