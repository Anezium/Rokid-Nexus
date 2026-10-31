package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.shared.ActivityGeometryUpdate
import com.anezium.rokidbus.shared.ActivityHeading
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Live route geometry and heading (plan 028a §1.7): latest-wins, smoothed, stale after 3 s. */
class RouteLiveTest {
    @Test
    fun `heading smoothing turns through north on the shortest arc`() {
        assertEquals(20.0, HeadingFilter.shortestArc(350.0, 10.0), 1e-9)
        assertEquals(-20.0, HeadingFilter.shortestArc(10.0, 350.0), 1e-9)
        assertEquals(180.0, HeadingFilter.shortestArc(0.0, 180.0), 1e-9)

        val filter = HeadingFilter()
        assertEquals(350.0, filter.sample(ActivityHeading(350.0, atWallMs = 1), nowMs = 1_000)!!, 1e-9)
        val next = filter.sample(ActivityHeading(10.0, atWallMs = 2), nowMs = 1_200)!!
        // Part of the way from 350 towards 10, through north: never back across south.
        assertTrue("got $next", next > 350.0 || next < 10.0)
        assertTrue("got $next", next !in 10.0..350.0)
    }

    @Test
    fun `a heading older than three seconds is absent and an out-of-order sample is dropped`() {
        val filter = HeadingFilter()
        filter.sample(ActivityHeading(90.0, atWallMs = 5_000), nowMs = 1_000)
        assertNotNull(filter.current(1_000 + HeadingFilter.STALE_MS))
        assertNull(filter.current(1_000 + HeadingFilter.STALE_MS + 1))
        assertNull(filter.sample(ActivityHeading(120.0, atWallMs = 4_000), nowMs = 1_100))
        // After staleness a new sample starts afresh, unsmoothed.
        assertEquals(200.0, filter.sample(ActivityHeading(200.0, atWallMs = 9_000), nowMs = 10_000)!!, 1e-9)
    }

    @Test
    fun `geometry is latest-wins by revision per owner`() {
        val state = RouteLiveState()
        assertTrue(state.onGeometry("maps", ActivityGeometryUpdate(2, geometry(1))))
        assertFalse(state.onGeometry("maps", ActivityGeometryUpdate(2, geometry(2))))
        assertFalse(state.onGeometry("maps", ActivityGeometryUpdate(1, geometry(3))))
        assertEquals(geometry(1), state.geometry("maps"))
        assertTrue(state.onGeometry("maps", ActivityGeometryUpdate(3, geometry(4))))
        assertEquals(3L, state.geometryRevision("maps"))
        assertNull(state.geometry("transit"))
    }

    @Test
    fun `a newer revision of the same geometry is accepted so its map acknowledges it`() {
        val state = RouteLiveState()
        assertTrue(state.onGeometry("maps", ActivityGeometryUpdate(1, geometry(1))))
        assertTrue(state.onGeometry("maps", ActivityGeometryUpdate(2, geometry(1))))
        assertEquals(2L, state.geometryRevision("maps"))
        assertFalse(state.onGeometry("maps", ActivityGeometryUpdate(2, geometry(1))))
    }

    @Test
    fun `small heading moves are kept but not redrawn, and a stale heading reads absent`() {
        val state = RouteLiveState()
        assertTrue(state.onHeading("maps", ActivityHeading(100.0, atWallMs = 1), nowMs = 1_000))
        assertFalse(state.onHeading("maps", ActivityHeading(100.5, atWallMs = 2), nowMs = 1_200))
        assertNotNull(state.heading("maps", 1_200))
        assertNull(state.heading("maps", 1_200 + HeadingFilter.STALE_MS + 1))
        // Coming back after being absent always redraws.
        assertTrue(state.onHeading("maps", ActivityHeading(100.5, atWallMs = 3), nowMs = 9_000))
    }

    @Test
    fun `an ended or offline activity loses its route and the next one starts from nothing`() {
        val state = RouteLiveState()
        state.onGeometry("maps", ActivityGeometryUpdate(5, geometry(1)))
        state.onGeometry("transit", ActivityGeometryUpdate(1, geometry(2)))
        state.onHeading("maps", ActivityHeading(10.0, atWallMs = 1), nowMs = 1_000)
        assertEquals(emptySet<String>(), state.retain(live = setOf("maps", "transit"), offline = emptySet()))

        assertEquals(setOf("transit"), state.retain(live = setOf("maps"), offline = setOf("transit")))
        assertNull(state.geometry("transit"))
        assertEquals(setOf("maps"), state.retain(live = emptySet(), offline = emptySet()))
        assertNull(state.geometry("maps"))
        assertNull(state.heading("maps", 1_000))
        // A new activity of the same owner may start its revisions over.
        assertTrue(state.onGeometry("maps", ActivityGeometryUpdate(1, geometry(3))))
    }

    @Test
    fun `an owner never seen live keeps what it sent until its start arrives`() {
        val state = RouteLiveState()
        state.onGeometry("maps", ActivityGeometryUpdate(1, geometry(1)))
        assertEquals(emptySet<String>(), state.retain(live = emptySet(), offline = emptySet()))
        assertNotNull(state.geometry("maps"))
        assertEquals(setOf("maps"), state.clear())
        assertNull(state.geometry("maps"))
    }

    private fun geometry(x: Int) = RouteGeometry(
        spanMeters = 500.0,
        paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(x, 0), RoutePoint(x, 1000)))),
    )
}
