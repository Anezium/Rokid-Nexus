package com.anezium.rokidbus.plugin.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavRouteGuidanceTest {
    @Test
    fun `degraded destination drops geocoder area before fitting the HUD name`() {
        val guidance = NavRouteSnapshot(route(destination = "Gare de Goussainville, Sarcelles, Goussainville"), quality = RouteQuality.STALE).guidance()
        assertEquals("Gare de Goussainville", guidance.secondary)
    }

    @Test
    fun `fallback guidance publishes distance and instruction only once`() {
        val route = route()
        val guidance = NavRouteSnapshot(route, quality = RouteQuality.TRACKING).guidance()

        assertEquals(distanceLabel(route.lengthMeters), guidance.primary)
        assertNull(guidance.measure)
        assertEquals("Follow the route", guidance.secondary)
        assertTrue(guidance.detail.isEmpty())
        assertEquals("Follow the route", guidance.instruction)
        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
        assertFalse(guidance.arrived)
    }

    @Test
    fun `equal formatted distances do not repeat even when meters differ`() {
        val route = route(stepAtMeters = 2_223.0)
        val snapshot = NavRouteSnapshot(route, quality = RouteQuality.TRACKING)

        assertTrue(snapshot.remainingMeters > snapshot.stepDistanceMeters)
        assertEquals(distanceLabel(snapshot.remainingMeters), snapshot.guidance().primary)
        assertNull(snapshot.guidance().measure)
    }

    @Test
    fun `next turn and remaining distance retain their distinct street and instruction`() {
        val route = route(direction = "LEFT", street = "Test Street", stepAtMeters = 200.0)
        val guidance = NavRouteSnapshot(route, progressMeters = 100.0, quality = RouteQuality.TRACKING).guidance()

        assertEquals("100 m", guidance.primary)
        assertEquals(distanceLabel(route.lengthMeters - 100.0), guidance.measure)
        assertEquals("Test Street", guidance.secondary)
        assertEquals(listOf("Turn left onto Test Street"), guidance.detail)
        assertEquals("Turn left onto Test Street", guidance.instruction)
        assertEquals("turn-left", guidance.glyph)
        assertEquals("~27 min", guidance.eta)
    }

    @Test
    fun `identical text is compared before the different slot limits`() {
        val destination = "The very long synthetic destination name"
        val instruction = "Follow the route to $destination"
        val route = route(direction = "ARRIVE", street = instruction, destination = destination)
        val guidance = NavRouteSnapshot(route, quality = RouteQuality.TRACKING).guidance()

        assertNotEquals(NavText.fit(instruction, 28), NavText.fit(instruction, 32))
        assertEquals(NavText.fit(instruction, 28), guidance.secondary)
        assertTrue(guidance.detail.isEmpty())
        assertEquals(instruction, guidance.instruction)
    }

    @Test
    fun `distinct long street and instruction survive clipping`() {
        val street = "The very long synthetic street name"
        val instruction = "Turn left onto $street"
        val route = route(direction = "LEFT", street = street, stepAtMeters = 200.0)
        val guidance = NavRouteSnapshot(route, quality = RouteQuality.TRACKING).guidance()

        assertEquals(NavText.fit(street, 28), guidance.secondary)
        assertEquals(listOf(NavText.fit(instruction, 32)), guidance.detail)
        assertEquals(instruction, guidance.instruction)
    }

    @Test
    fun `route without maneuvers keeps destination guidance without claiming arrival`() {
        val route = route(direction = "ARRIVE", street = "Destination")
        val guidance = NavRouteSnapshot(route, quality = RouteQuality.TRACKING).guidance()

        assertNull(guidance.measure)
        assertEquals("Destination", guidance.secondary)
        assertEquals(listOf("Follow the route to Destination"), guidance.detail)
        assertEquals("Follow the route to Destination", guidance.instruction)
        assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
        assertFalse(guidance.imminent)
        assertFalse(guidance.arrived)
    }

    @Test
    fun `degraded guidance keeps status and remaining distance without a live turn or eta`() {
        val route = route(direction = "LEFT", street = "Test Street", stepAtMeters = 200.0)
        val statuses = mapOf(
            RouteQuality.WAITING to "Waiting GPS",
            RouteQuality.STALE to "GPS lost",
            RouteQuality.INACCURATE to "Low accuracy",
            RouteQuality.OFF_ROUTE to "Off route",
        )
        for ((quality, primary) in statuses) {
            val guidance = NavRouteSnapshot(route, progressMeters = 190.0, quality = quality).guidance()
            assertEquals(primary, guidance.primary)
            assertEquals("Destination", guidance.secondary)
            assertEquals(listOf(quality.message), guidance.detail)
            assertEquals(quality.message, guidance.instruction)
            assertEquals(distanceLabel(route.lengthMeters - 190.0), guidance.measure)
            assertEquals(NavText.ROUTE_GLYPH, guidance.glyph)
            assertNull(guidance.eta)
            assertFalse(guidance.imminent)
            assertFalse(guidance.arrived)
        }
    }

    @Test
    fun `confirmed arrival keeps arrival status and remaining distance`() {
        val route = route(direction = "ARRIVE", street = "Destination")
        val guidance = NavRouteSnapshot(route, progressMeters = route.lengthMeters, quality = RouteQuality.ARRIVED).guidance()

        assertEquals("Arrived", guidance.primary)
        assertEquals("Destination", guidance.secondary)
        assertEquals(listOf("Arrived"), guidance.detail)
        assertEquals("Arrived", guidance.instruction)
        assertEquals("0 m", guidance.measure)
        assertEquals("arrive", guidance.glyph)
        assertNull(guidance.eta)
        assertFalse(guidance.imminent)
        assertTrue(guidance.arrived)
    }

    private fun route(
        direction: String = "UNKNOWN",
        street: String = "",
        destination: String = "Destination",
        stepAtMeters: Double? = null,
    ): NavRoute {
        val points = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.02, 0.0))
        val lengthMeters = RouteLine(points).lengthMeters
        return NavRoute(
            NavDestination(destination, points.last()),
            points,
            1_680,
            listOf(RouteStep(stepAtMeters ?: lengthMeters, direction, street)),
        )
    }
}
