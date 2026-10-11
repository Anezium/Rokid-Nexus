package com.anezium.rokidbus.plugin.nav

import com.anezium.rokidbus.client.plugin.NexusPage
import com.anezium.rokidbus.shared.PageBody
import com.anezium.rokidbus.shared.PageSurfaceContract
import com.anezium.rokidbus.shared.PageSurfaceValidationResult
import com.anezium.rokidbus.shared.PageTemplateContract
import com.anezium.rokidbus.shared.RouteManeuver
import com.anezium.rokidbus.shared.RoutePositionQuality
import com.anezium.rokidbus.shared.RouteRibbonBetween
import com.anezium.rokidbus.shared.RouteStopState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** `nav:main` as a route page: what a journey draws, and what notification guidance honestly cannot. */
class NavRoutePageTest {
    private val text = NavPageText(noAccess = "Allow access", off = "Switched off", idle = "Start navigating")
    private val route = straight()

    private val walking = NavGuidance(
        source = NavSource.GOOGLE_MAPS,
        glyph = "turn_right",
        primary = "300 m",
        secondary = "Rue de Rivoli",
        eta = "12:41",
        stepKey = "step-1",
        instruction = "Turn right onto Rue de Rivoli",
    )

    private val transit = NavGuidance(
        source = NavSource.CITYMAPPER,
        glyph = "train",
        primary = "2 stops",
        secondary = "Luxembourg",
        badge = "RER B",
        track = NavTrack(count = 5, at = 2, target = 4, label = "Luxembourg"),
        stepKey = "ride",
    )

    @Test
    fun `without the launcher bundle nav main stays a base summary, journey or not`() {
        val state = journey(tracking(100.0)).copy(extended = false)
        val page = NavPages.page(NavPages.MAIN, 1, state, text) as NexusPage.Content
        assertTrue(page.body is PageBody.Summary)
        assertTrue(page.body.template in PageTemplateContract.supportedTemplates)
        val notification = NavPages.page(NavPages.MAIN, 2, notified(walking).copy(extended = false), text) as NexusPage.Content
        assertTrue(notification.body is PageBody.Summary)
    }

    @Test
    fun `a running journey carries its maneuver, facts and both map views with a live position`() {
        val page = content(journey(tracking(100.0)))
        val body = page.body as PageBody.Route
        assertEquals(RouteManeuver("turn-left", "100 m", "Test Street", "~${tracking(100.0).remainingMinutes} min"), body.maneuver)
        assertEquals(RoutePositionQuality.LIVE, body.position)
        assertNull(body.fixAgeS)
        assertEquals(RoutePositionQuality.LIVE, body.follow!!.position!!.quality)
        assertNotNull(body.overview)
        assertEquals(NavMapProjection.FOLLOW_SPAN_METERS, body.follow!!.spanMeters, 0.0)
        assertTrue(body.facts.size <= PageTemplateContract.MAX_ROUTE_FACTS)
        assertEquals("Walking route", body.facts.single { it.id == "source" }.value)
        assertTrue(page.actions.any { it.id == NavRoutePage.END_JOURNEY && it.confirm })
        assertNull(body.ribbon)
        assertValid(page)
    }

    @Test
    fun `a stale journey keeps its last place with its age, one without any fix has no position`() {
        val stale = content(journey(tracking(100.0).copy(quality = RouteQuality.STALE, positionAtMs = 0, atMs = 42_400)))
        val staleBody = stale.body as PageBody.Route
        assertEquals(RoutePositionQuality.STALE, staleBody.position)
        assertEquals(42, staleBody.fixAgeS)
        assertEquals(RoutePositionQuality.STALE, staleBody.follow!!.position!!.quality)
        assertEquals(42, staleBody.follow!!.position!!.fixAgeS)
        // The distance still comes from the confirmed progress; the hub marks it approximate.
        assertEquals("100 m", staleBody.maneuver!!.distance)
        assertValid(stale)

        val none = content(journey(NavRouteSnapshot(route, quality = RouteQuality.WAITING, atMs = 5_000)))
        val noneBody = none.body as PageBody.Route
        assertEquals(RoutePositionQuality.NONE, noneBody.position)
        assertNull(noneBody.fixAgeS)
        assertEquals(RoutePositionQuality.NONE, noneBody.follow!!.position!!.quality)
        assertNull(noneBody.follow!!.position!!.point)
        assertEquals("Waiting GPS", noneBody.maneuver!!.distance)
        assertValid(none)
    }

    @Test
    fun `a journey still planning says so and draws nothing`() {
        val state = NavPageState(
            guidance = null,
            listenerConnected = false,
            enabled = false,
            updatedAt = null,
            journeyActive = true,
            journeyDestination = "Gare de Lyon, Paris",
            journeyMessage = NavJourneyService.MESSAGE_PLANNING,
            extended = true,
        )
        val page = content(state)
        val body = page.body as PageBody.Route
        assertEquals(RoutePositionQuality.NONE, body.position)
        assertNull(body.maneuver)
        assertNull(body.follow)
        assertNull(body.overview)
        assertEquals(NavJourneyService.MESSAGE_PLANNING, body.facts.single { it.id == "status" }.value)
        assertEquals("Gare de Lyon, Paris", body.facts.single { it.id == "destination" }.value)
        assertValid(page)
    }

    @Test
    fun `another app's guidance is text only, with no position and no geometry`() {
        val page = content(notified(walking))
        val body = page.body as PageBody.Route
        assertEquals(RoutePositionQuality.NONE, body.position)
        assertNull(body.fixAgeS)
        assertNull(body.follow)
        assertNull(body.overview)
        assertNull(body.ribbon)
        // The app's own words; a glyph that is not a glyph name falls back to the neutral one.
        assertEquals(RouteManeuver(NavText.ROUTE_GLYPH, "300 m", "Rue de Rivoli", "12:41"), body.maneuver)
        assertEquals("Google Maps", body.facts.single { it.id == "source" }.value)
        assertEquals("12:24", body.facts.single { it.id == "updated" }.value)
        assertFalse(page.actions.any { it.id == NavRoutePage.END_JOURNEY })
        assertValid(page)
    }

    @Test
    fun `a ride names only the stop to get off at and flags where the vehicle is as an estimate`() {
        val page = content(notified(transit))
        val body = page.body as PageBody.Route
        assertEquals(RoutePositionQuality.NONE, body.position)
        assertNull(body.follow)
        val ribbon = body.ribbon!!
        assertEquals("RER B", ribbon.line)
        assertEquals(
            listOf(NavRoutePage.UNNAMED_STOP, NavRoutePage.UNNAMED_STOP, NavRoutePage.UNNAMED_STOP, NavRoutePage.UNNAMED_STOP, "Luxembourg"),
            ribbon.stops.map { it.name },
        )
        assertEquals(
            listOf(RouteStopState.PASSED, RouteStopState.PASSED, RouteStopState.PASSED, RouteStopState.UPCOMING, RouteStopState.ALIGHT),
            ribbon.stops.map { it.state },
        )
        assertTrue(ribbon.stops.all { it.time == null })
        assertEquals(RouteRibbonBetween(2, 0.5), ribbon.between)
        assertEquals(NavRoutePage.ESTIMATE_NOTE, ribbon.note)
        assertValid(page)

        // At the stop to get off at, nothing is between two stops and nothing is estimated.
        val alighting = transit.copy(track = NavTrack(count = 5, at = 4, target = 4, label = "Luxembourg"))
        val last = (content(notified(alighting)).body as PageBody.Route).ribbon!!
        assertNull(last.between)
        assertNull(last.note)
    }

    @Test
    fun `no access, switched off and no route each answer without a route`() {
        for ((state, title) in listOf(
            notified(null).copy(listenerConnected = false) to "No notification access",
            notified(null).copy(enabled = false) to "Navigation is off",
            notified(null) to "No active route",
        )) {
            val page = content(state)
            val body = page.body as PageBody.Route
            assertEquals(RoutePositionQuality.NONE, body.position)
            assertNull(body.maneuver)
            assertNull(body.follow)
            assertEquals(title, body.facts.single().label)
            assertValid(page)
        }
    }

    private fun content(state: NavPageState) = NavPages.page(NavPages.MAIN, 1, state, text) as NexusPage.Content

    private fun notified(guidance: NavGuidance?) =
        NavPageState(guidance, listenerConnected = true, enabled = true, updatedAt = "12:24", extended = true)

    private fun journey(snapshot: NavRouteSnapshot) = NavPageState(
        guidance = snapshot.guidance(),
        listenerConnected = true,
        enabled = true,
        updatedAt = "12:24",
        journeyActive = true,
        journey = snapshot,
        journeyDestination = snapshot.route.destination.name,
        extended = true,
    )

    private fun tracking(progress: Double) = NavRouteSnapshot(
        route,
        progressMeters = progress,
        position = route.line.pointAt(progress),
        positionAtMs = 0,
        quality = RouteQuality.TRACKING,
    )

    private fun assertValid(page: NexusPage.Content) {
        assertEquals(PageSurfaceContract.TEMPLATE_ROUTE, page.body.template)
        val json = PageTemplateContract.encode(page.body).toString()
        assertTrue(json.toByteArray(Charsets.UTF_8).size <= PageSurfaceContract.MAX_PAGE_BYTES)
        val decoded = PageTemplateContract.decode(
            PageSurfaceContract.TEMPLATE_ROUTE,
            json,
            page.actions.map { it.id }.toSet(),
            "nav",
            extended = true,
        )
        assertTrue(decoded.toString(), decoded is PageSurfaceValidationResult.Valid)
        assertEquals(page.body, (decoded as PageSurfaceValidationResult.Valid).value)
    }

    private fun straight(): NavRoute {
        val points = listOf(GeoPoint(48.0, 2.0), GeoPoint(48.004, 2.0))
        return NavRoute(
            NavDestination("Destination", points.last()),
            points,
            400,
            listOf(RouteStep(200.0, "LEFT", "Test Street"), RouteStep(RouteLine(points).lengthMeters, "ARRIVE", "Destination")),
        )
    }
}
