package com.anezium.rokidbus.glasses.session

import com.anezium.rokidbus.glasses.page.HudGlyphs
import com.anezium.rokidbus.glasses.session.SessionEffect.RequestPage
import com.anezium.rokidbus.shared.ActivityActivation
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageDestination
import com.anezium.rokidbus.shared.PageFact
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceRequest
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RouteManeuver
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Route pages, the full map and the display page (plan 028a §5.5, §8.1). */
class RouteNavigationTest {
    private var live: RouteGeometry? = null
    private var saved: AmbientSize? = null
    private val items = TypedPageItems(
        isLocalPage = PageTemplateContract::isLocalPageId,
        providerPageAllowed = { true },
        extended = { true },
        liveGeometry = { owner -> live.takeIf { owner == OWNER } },
        ambientSize = { owner -> saved.takeIf { owner == OWNER } },
    )
    private val reducer = SessionReducer(items, isLocalPage = PageTemplateContract::isLocalPageId, launcherActive = { true })
    private val screens = SessionScreens(
        items, pluginLabel = { "Maps" }, formatTime = { "09:41" }, wallTimeOf = { it },
        liveRoute = { owner -> LiveRoute(live.takeIf { owner == OWNER }, 90f) },
    )
    private var model = SessionModel()
    private var effects: List<SessionEffect> = emptyList()
    private val allEffects = mutableListOf<SessionEffect>()

    @Test
    fun `a route page lists the map, its views, full screen and display before the actions and Open app`() {
        openRoute(routeBody())

        assertEquals(
            listOf(
                TypedPageItems.MAP_KEY, TypedPageItems.VIEW_FOLLOW_KEY, TypedPageItems.VIEW_OVERVIEW_KEY,
                TypedPageItems.FULL_SCREEN_KEY, TypedPageItems.DISPLAY_KEY, TypedPageItems.ACTION_KEY_PREFIX + "reroute",
                ActivityCommands.OPEN_APP_KEY,
            ),
            rows().map { it.key },
        )
        assertEquals(0, top().selected)
        val screen = screen()
        val map = screen.blocks.filterIsInstance<ScreenBlock.Map>().single()
        assertEquals(MapSize.COMPACT, map.size)
        assertEquals("MAP · FOLLOW", map.label)
        assertTrue(map.selected)
        assertEquals(listOf("Maneuver", "Map", "Fact"), screen.blocks.take(3).map { it::class.simpleName })
        assertEquals("Display", screen.blocks.filterIsInstance<ScreenBlock.Row>().single { it.key == "row:${TypedPageItems.DISPLAY_KEY}" }.label)
    }

    @Test
    fun `Enter on the map opens the chrome-less full map and Step switches two views without wrapping`() {
        openRoute(routeBody())
        press(3_000)
        assertEquals(FullMapMode.MAP, top().fullMap)
        val full = screen()
        assertTrue(full.chromeless)
        assertNull(full.header)
        assertEquals(MapSize.FULL, full.blocks.filterIsInstance<ScreenBlock.Map>().single().size)

        send(SessionEvent.Step(-1, 3_100))
        assertEquals(listOf(SessionEffect.None), effects)
        assertNull(model.mapViews[OWNER])

        send(SessionEvent.Step(1, 3_200))
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])
        val overview = screen().blocks.filterIsInstance<ScreenBlock.Map>().single()
        assertEquals("MAP · WHOLE ROUTE", overview.label)
        // Whole route is north-up.
        assertNull(overview.headingDeg)

        send(SessionEvent.Step(1, 3_300))
        assertEquals(listOf(SessionEffect.None), effects)
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])

        send(SessionEvent.Step(-1, 3_400))
        assertEquals(MapView.FOLLOW, model.mapViews[OWNER])
        assertEquals(90f, screen().blocks.filterIsInstance<ScreenBlock.Map>().single().headingDeg)
    }

    @Test
    fun `Enter on the full map opens the hub commands and Back returns to the page with the map selected`() {
        openRoute(routeBody())
        press(3_000)
        send(SessionEvent.Step(1, 3_100))
        press(3_200)
        assertEquals(FullMapMode.COMMANDS, top().fullMap)
        assertEquals(TypedPageItems.VIEW_FOLLOW_KEY, top().selectedKey)
        val commands = screen()
        assertEquals("COMMANDS", commands.header?.title)
        assertTrue(commands.blocks.none { it is ScreenBlock.Map })

        // The commands never step onto the map.
        send(SessionEvent.Step(-1, 3_300))
        assertEquals(TypedPageItems.VIEW_FOLLOW_KEY, top().selectedKey)
        send(SessionEvent.Step(1, 3_400))
        assertEquals(TypedPageItems.VIEW_OVERVIEW_KEY, top().selectedKey)

        send(SessionEvent.Back(3_500))
        assertEquals(FullMapMode.MAP, top().fullMap)
        send(SessionEvent.Back(3_600))
        assertNull(top().fullMap)
        assertEquals(0, top().selected)
        assertEquals(TypedPageItems.MAP_KEY, top().selectedKey)
        assertEquals(1, (model.state as SessionState.InPage).frames.size)
        // The view chosen on the full map is kept for the session.
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])
        assertEquals("MAP · WHOLE ROUTE", screen().blocks.filterIsInstance<ScreenBlock.Map>().single().label)

        send(SessionEvent.Back(3_700))
        assertTrue(model.state is SessionState.Root)
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])
    }

    @Test
    fun `a view row picks the view and the session forgets it when it closes`() {
        openRoute(routeBody())
        send(SessionEvent.Step(2, 3_000))
        assertEquals(TypedPageItems.VIEW_OVERVIEW_KEY, top().selectedKey)
        press(3_100)
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])
        assertNull(top().fullMap)
        assertEquals(true, screen().blocks.filterIsInstance<ScreenBlock.Row>().single { it.key == "row:${TypedPageItems.VIEW_OVERVIEW_KEY}" }.checked)

        send(SessionEvent.Abort)
        assertEquals(emptyMap<String, MapView>(), model.mapViews)
    }

    @Test
    fun `the full map and its views never write the ambient size`() {
        openRoute(routeBody())
        press(3_000)
        send(SessionEvent.Step(1, 3_100))
        press(3_200)
        send(SessionEvent.Step(1, 3_300))
        press(3_400)
        assertEquals(FullMapMode.MAP, top().fullMap)
        assertEquals(MapView.OVERVIEW, model.mapViews[OWNER])
        send(SessionEvent.Back(3_500))
        assertTrue(allEffects.none { it is SessionEffect.SaveAmbientSize })
        assertTrue((model.state as SessionState.InPage).frames.none { it.panel is FramePanel.Display })
    }

    @Test
    fun `the display page previews on swipe, saves on tap and Back cancels`() {
        saved = AmbientSize.MINIMAP
        openRoute(routeBody())
        selectKey(TypedPageItems.DISPLAY_KEY)
        press(4_000)
        assertEquals(FramePanel.Display(OWNER, AmbientSize.MINIMAP, selected = 1), top().panel)
        assertEquals("Display · Minimap", rows().single { it.key == TypedPageItems.DISPLAY_KEY }.label)

        send(SessionEvent.Step(1, 4_100))
        val swiped = top().panel as FramePanel.Display
        assertEquals(AmbientSize.PANEL, swiped.size)
        assertTrue(swiped.moved)
        send(SessionEvent.Step(1, 4_200))
        assertEquals(listOf(SessionEffect.None), effects)

        send(SessionEvent.Back(4_300))
        assertNull(top().panel)
        assertTrue(allEffects.none { it is SessionEffect.SaveAmbientSize })

        press(4_400)
        send(SessionEvent.Step(-1, 4_500))
        press(4_600)
        assertEquals(SessionEffect.SaveAmbientSize(OWNER, AmbientSize.TEXT), effects.filterIsInstance<SessionEffect.SaveAmbientSize>().single())
        assertNull(top().panel)
    }

    @Test
    fun `a live revision keeps the display page open but cancels a confirmation`() {
        openRoute(routeBody())
        selectKey(TypedPageItems.DISPLAY_KEY)
        press(4_000)
        respond(request, routeBody(distance = "80 m"), revision = 2, now = 4_100)
        assertTrue(top().panel is FramePanel.Display)
    }

    @Test
    fun `a page without geometry draws the owner's live geometry and has no map without it`() {
        openRoute(routeBody(follow = null, overview = null))
        assertEquals(TypedPageItems.DISPLAY_KEY, rows().first().key)
        assertTrue(screen().blocks.none { it is ScreenBlock.Map })

        live = geometry()
        assertEquals(TypedPageItems.MAP_KEY, rows().first().key)
        assertEquals(live, screen().blocks.filterIsInstance<ScreenBlock.Map>().single().geometry)
    }

    @Test
    fun `a degraded position draws north-up, the lost glyph, approximate distances and the GPS line`() {
        openRoute(routeBody(position = RoutePositionQuality.STALE, fixAgeS = 12))
        val screen = screen()
        val maneuver = screen.blocks.filterIsInstance<ScreenBlock.Maneuver>().single()
        assertEquals(HudGlyphs.GPS_LOST, maneuver.glyph)
        assertTrue(maneuver.approximate)
        val map = screen.blocks.filterIsInstance<ScreenBlock.Map>().single()
        assertTrue(map.degraded)
        assertNull(map.headingDeg)
        assertEquals("GPS lost · last fix 12 s", screen.blocks.filterIsInstance<ScreenBlock.Text>().single { it.key == "gps" }.text)
    }

    @Test
    fun `route presentation rules`() {
        val body = routeBody()
        val follow = geometry(x = 100)
        val overview = geometry(x = 900)
        val both = body.copy(follow = follow, overview = overview)
        assertEquals(follow, RoutePresentation.geometry(both, MapView.FOLLOW, live = geometry(x = 5)))
        assertEquals(overview, RoutePresentation.geometry(both, MapView.OVERVIEW, live = null))
        // One geometry serves both views; none falls back to the live one.
        assertEquals(follow, RoutePresentation.geometry(both.copy(overview = null), MapView.OVERVIEW, live = null))
        val livePath = geometry(x = 5)
        assertEquals(livePath, RoutePresentation.geometry(both.copy(follow = null, overview = null), MapView.FOLLOW, livePath))

        assertFalse(RoutePresentation.degraded(body, follow))
        assertTrue(RoutePresentation.degraded(body.copy(position = RoutePositionQuality.NONE), follow))
        val staleFix = follow.copy(position = RoutePosition(RoutePositionQuality.STALE, RoutePoint(500, 500), fixAgeS = 4))
        assertTrue(RoutePresentation.degraded(body, staleFix))
        assertTrue(RoutePresentation.degraded(null, staleFix))
        // A stale heading is no heading: north-up, but the fix itself is still good.
        assertNull(RoutePresentation.heading(MapView.FOLLOW, degraded = false, headingDeg = null))
        assertEquals(45f, RoutePresentation.heading(MapView.FOLLOW, degraded = false, headingDeg = 45f))
        assertNull(RoutePresentation.heading(MapView.FOLLOW, degraded = true, headingDeg = 45f))
        assertNull(RoutePresentation.heading(MapView.OVERVIEW, degraded = false, headingDeg = 45f))
        assertEquals("~120 m", RoutePresentation.approximate("120 m", degraded = true))
        assertEquals("~120 m", RoutePresentation.approximate("~120 m", degraded = true))
        assertEquals("120 m", RoutePresentation.approximate("120 m", degraded = false))
        assertEquals(MapView.FOLLOW, RoutePresentation.stepView(MapView.FOLLOW, -1))
        assertEquals(MapView.OVERVIEW, RoutePresentation.stepView(MapView.OVERVIEW, 1))
    }

    @Test
    fun `the root keeps its bell and grid place marks`() {
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        val rows = screen().blocks.filterIsInstance<ScreenBlock.Row>()
        assertEquals(IconRef.Place(IconPlace.BELL), rows.single { it.key == RootStops.NOTIFICATIONS_PAGE_ID }.icon)
        assertEquals(IconRef.Place(IconPlace.GRID), rows.single { it.key == RootStops.APPLICATIONS_PAGE_ID }.icon)
    }

    private lateinit var request: PageSurfaceRequest

    private fun openRoute(body: PageBody.Route) {
        val stop = ActivityStop(
            id = "$OWNER:activity",
            pageId = LocalPages.activityPageId(OWNER),
            text = "120 m",
            ownerPluginId = OWNER,
            ownerLabel = "Maps",
            seq = 1,
            destination = PageDestination(PAGE_ID),
            activation = ActivityActivation.DESTINATION,
            launchable = true,
        )
        send(SessionEvent.ActivityStarted(stop))
        send(SessionEvent.TripleTap(1_000))
        send(SessionEvent.Tick(1_000 + PageSurfaceContract.GATE_MS))
        shown(1_900)
        press(2_000)
        request = effects.filterIsInstance<RequestPage>().single().request
        respond(request, body, revision = 1, now = 2_100)
        assertEquals(PAGE_ID, top().pageId)
    }

    private fun respond(request: PageSurfaceRequest, body: PageBody, revision: Long, now: Long) {
        val actions = JSONArray().put(
            JSONObject().put("id", "reroute").put("label", "Reroute").put("kind", "plugin").put("confirm", true),
        )
        val payload = PageSurfaceContract.correlationPayload(request.correlation)
            .put("revision", revision).put("template", body.template).put("title", "Route")
            .put("body", PageTemplateContract.encode(body)).put("actions", actions).put("live", true)
        send(SessionEvent.PageResponse(payload, now))
        assertTrue(top().status is FrameStatus.Shown)
    }

    private fun selectKey(key: String) {
        val index = rows().indexOfFirst { it.key == key }
        send(SessionEvent.Step(index - top().selected, 3_900))
        assertEquals(key, top().selectedKey)
    }

    private fun routeBody(
        distance: String = "120 m",
        follow: RouteGeometry? = geometry(),
        overview: RouteGeometry? = geometry(x = 800),
        position: RoutePositionQuality = RoutePositionQuality.LIVE,
        fixAgeS: Int? = null,
    ) = PageBody.Route(
        position = position,
        maneuver = RouteManeuver("turn-left", distance, street = "Rue de Rivoli", eta = "09:52"),
        facts = listOf(PageFact("eta", "Arrival", "09:52")),
        follow = follow,
        overview = overview,
        fixAgeS = fixAgeS,
    )

    private fun geometry(x: Int = 500) = RouteGeometry(
        spanMeters = 800.0,
        paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(x, 0), RoutePoint(x, 1000)))),
        position = RoutePosition(RoutePositionQuality.LIVE, RoutePoint(x, 500)),
    )

    private fun rows() = items.rows((top().status as FrameStatus.Shown).snapshot.page) +
        listOfNotNull(top().openApp?.let(ActivityCommands::openAppRow))

    private fun screen() = screens.screen(model.state, null, model.generation, CommandFacts.of(model))

    private fun shown(atMs: Long) {
        send(SessionEvent.FocusShown(SessionFocus.tokenOf(model) ?: error("nothing on screen"), atMs))
    }

    private fun press(at: Long) = send(SessionEvent.Enter(at + 40, pressStartMs = at))

    private fun send(event: SessionEvent) {
        val transition = reducer.reduce(model, event)
        model = transition.model
        effects = transition.effects
        allEffects += transition.effects
    }

    private fun top() = (model.state as SessionState.InPage).frames.last()

    private companion object {
        const val OWNER = "maps"
        const val PAGE_ID = "maps:route"
    }
}
