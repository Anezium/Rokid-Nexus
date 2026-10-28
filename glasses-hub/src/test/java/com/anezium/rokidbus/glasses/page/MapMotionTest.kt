package com.anezium.rokidbus.glasses.page

import com.anezium.rokidbus.glasses.HudMotion
import com.anezium.rokidbus.shared.RouteAnchor
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MapMotionTest {
    private var now = 10_000L
    private val motion = MapMotion { now }

    @After
    fun restore() {
        HudMotion.enabled = true
    }

    /** A follow frame of 200 m centred on the wearer, [eastDm] decimetres east of the route's origin. */
    private fun follow(eastDm: Int, frame: Int = 1, span: Double = 200.0, point: RoutePoint = RoutePoint(500, 500)) = RouteGeometry(
        spanMeters = span,
        paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(0, 500), RoutePoint(1000, 500 + eastDm % 7)))),
        position = RoutePosition(RoutePositionQuality.LIVE, point),
        anchor = RouteAnchor(frame, eastDm, 0),
    )

    @Test
    fun `the wearer glides from one frame to the next over the time the last one took`() {
        motion.arrive(follow(0))
        now += 5_000
        val next = follow(70) // 7 m east, five seconds later
        motion.arrive(next)
        // Drawn where they were: 7 m behind the new centre, which is 35 units at 200 m a frame.
        assertEquals(465f, motion.pose(next)!!.x, 0.01f)
        assertTrue(motion.moving())
        now += 2_500
        assertEquals(482.5f, motion.pose(next)!!.x, 0.01f)
        now += 2_500
        assertEquals(500f, motion.pose(next)!!.x, 0.01f)
        assertEquals(500f, motion.pose(next)!!.y, 0.01f)
        assertFalse(motion.moving())
    }

    @Test
    fun `a glide in progress continues from where it is drawn, not from where it was going`() {
        motion.arrive(follow(0))
        now += 4_000
        motion.arrive(follow(40))
        now += 2_000 // halfway: 2 m east of the origin
        val third = follow(80)
        motion.arrive(third)
        // From 2 m to 8 m over the 2 s since the last arrival.
        assertEquals(500f - 6f * 1000f / 200f, motion.pose(third)!!.x, 0.01f)
        now += 1_000
        assertEquals(500f - 3f * 1000f / 200f, motion.pose(third)!!.x, 0.01f)
    }

    @Test
    fun `anything that is not the same ground is drawn where it says at once`() {
        fun snaps(first: RouteGeometry, second: RouteGeometry, gapMs: Long = 5_000) {
            val motion = MapMotion { now }
            motion.arrive(first)
            now += gapMs
            motion.arrive(second)
            val pose = motion.pose(second)
            if (second.anchor == null) assertNull(pose) else assertEquals(second.position!!.point!!.x.toFloat(), pose!!.x, 0.01f)
            assertFalse(motion.moving())
        }
        snaps(follow(0), follow(70, frame = 2))            // a new route
        snaps(follow(0), follow(70, span = 400.0))         // a new zoom
        snaps(follow(0), follow(700))                      // 70 m: past a third of the frame
        snaps(follow(0), follow(70).copy(anchor = null))   // a provider without anchors
        snaps(follow(0), follow(70), gapMs = 13_000)       // a long silence
        HudMotion.enabled = false
        snaps(follow(0), follow(70))                       // motion turned off
    }

    @Test
    fun `a rebuilt view binding the same geometry continues the glide`() {
        motion.arrive(follow(0))
        now += 5_000
        val next = follow(70)
        motion.arrive(next)
        now += 2_500
        motion.arrive(next.copy())
        assertEquals(482.5f, motion.pose(next)!!.x, 0.01f)
    }

    @Test
    fun `an overview marker glides inside a frame that stays put`() {
        val first = follow(0, point = RoutePoint(300, 600))
        motion.arrive(first)
        now += 4_000
        val second = follow(0, point = RoutePoint(310, 600)).copy(paths = first.paths)
        motion.arrive(second)
        assertEquals(300f, motion.pose(second)!!.x, 0.01f)
        now += 2_000
        assertEquals(305f, motion.pose(second)!!.x, 0.01f)
    }

    @Test
    fun `the heading eases toward each sample the short way round`() {
        motion.arrive(follow(0))
        motion.aim(350f)
        assertEquals(350f, motion.pose(follow(0))!!.headingDeg!!, 0.01f)
        motion.aim(10f)
        val start = motion.pose(follow(0))!!.headingDeg!!
        assertEquals(350f, start, 0.01f)
        now += MapMotion.HEADING_EASE_MS
        val eased = motion.pose(follow(0))!!.headingDeg!!
        // Through north, never back round through 180.
        assertTrue("eased to $eased", eased > 355f || eased < 10f)
        assertTrue(motion.moving())
        now += MapMotion.HEADING_EASE_MS * 10
        assertEquals(10f, motion.pose(follow(0))!!.headingDeg!!, 0.05f)
        assertFalse(motion.moving())
        motion.aim(null)
        assertNull(motion.pose(follow(0))!!.headingDeg)
    }
}
