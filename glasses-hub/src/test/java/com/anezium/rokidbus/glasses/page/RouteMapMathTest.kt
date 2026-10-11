package com.anezium.rokidbus.glasses.page

import com.anezium.rokidbus.glasses.session.MapView
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The route map's decisions, without a pixel: projection, rotation, clipping, scale bar, names. */
class RouteMapMathTest {
    private val route = RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 500), RoutePoint(500, 300), RoutePoint(800, 300)))

    private fun geometry(quality: RoutePositionQuality = RoutePositionQuality.LIVE, point: RoutePoint? = RoutePoint(500, 500)) = RouteGeometry(
        spanMeters = 500.0,
        paths = listOf(RoutePath(RoutePathRole.STREET, listOf(RoutePoint(0, 500), RoutePoint(1000, 500))), route),
        position = RoutePosition(quality, point, fixAgeS = if (quality == RoutePositionQuality.STALE) 40 else null),
        turn = RoutePoint(500, 300),
        destination = RoutePoint(800, 300),
    )

    @Test
    fun `a trusted position with a heading turns the map heading-up with an arrow`() {
        val style = RouteMapMath.styleOf(geometry(), MapView.FOLLOW, 90f, degraded = false)
        assertEquals(MapStyle(northUp = false, dashedRoute = false, position = PositionMark.ARROW), style)
        // Without a heading the wearer is a dot and north stays up.
        assertEquals(MapStyle(northUp = true, dashedRoute = false, position = PositionMark.DOT), RouteMapMath.styleOf(geometry(), MapView.FOLLOW, null, false))
        // The whole route is always north-up.
        assertTrue(RouteMapMath.styleOf(geometry(), MapView.OVERVIEW, 90f, false).northUp)
    }

    @Test
    fun `a degraded position is said, never hidden`() {
        val stale = RouteMapMath.styleOf(geometry(RoutePositionQuality.STALE), MapView.FOLLOW, 90f, false)
        assertEquals(MapStyle(northUp = true, dashedRoute = true, position = PositionMark.LOST), stale)
        val flagged = RouteMapMath.styleOf(geometry(), MapView.FOLLOW, 90f, degraded = true)
        assertEquals(stale, flagged)
        val none = RouteMapMath.styleOf(geometry(RoutePositionQuality.NONE, null), MapView.FOLLOW, 90f, false)
        assertEquals(MapStyle(northUp = true, dashedRoute = true, position = PositionMark.NONE), none)
    }

    @Test
    fun `follow puts the wearer on the lower third and turns the heading up`() {
        val g = geometry()
        val style = RouteMapMath.styleOf(g, MapView.FOLLOW, 90f, false)
        val t = RouteMapMath.transform(g, MapForm.COMPACT, MapView.FOLLOW, style, 90f, 441f, 262f)
        assertEquals(220.5f, t.x(500f, 500f), 0.01f)
        assertEquals(262f * MapForm.COMPACT.anchorY, t.y(500f, 500f), 0.01f)
        // Heading east: a point east of the wearer is straight above it on screen.
        assertEquals(220.5f, t.x(600f, 500f), 0.01f)
        assertTrue(t.y(600f, 500f) < t.y(500f, 500f))
        // North (smaller y in the frame) is then on the left.
        assertTrue(t.x(500f, 400f) < 220.5f)
        // The frame's half extent reaches the farthest corner, so no corner is ever left empty.
        val farthest = kotlin.math.hypot(220.5f, 262f * MapForm.COMPACT.anchorY)
        assertEquals(farthest, 500f * t.scale, 0.01f)
    }

    @Test
    fun `a lost position keeps the last fix higher and north-up`() {
        val g = geometry(RoutePositionQuality.STALE)
        val style = RouteMapMath.styleOf(g, MapView.FOLLOW, 90f, false)
        val t = RouteMapMath.transform(g, MapForm.MINIMAP, MapView.FOLLOW, style, 90f, 259f, 190f)
        assertEquals(0f, t.rotationDeg, 0f)
        assertEquals(190f * RouteMapMath.DEGRADED_ANCHOR_Y, t.y(500f, 500f), 0.01f)
    }

    @Test
    fun `the whole route fits inside the view's margins, north-up, under the insets`() {
        val g = geometry()
        val style = RouteMapMath.styleOf(g, MapView.OVERVIEW, 90f, false)
        val insets = MapInsets(top = 120f, bottom = 40f)
        val t = RouteMapMath.transform(g, MapForm.FULL, MapView.OVERVIEW, style, 90f, 480f, 640f, insets)
        assertEquals(0f, t.rotationDeg, 0f)
        route.points.forEach { point ->
            val x = t.x(point.x.toFloat(), point.y.toFloat())
            val y = t.y(point.x.toFloat(), point.y.toFloat())
            assertTrue("x $x", x >= RouteMapMath.FIT_MARGIN_PX - 0.01f && x <= 480f - RouteMapMath.FIT_MARGIN_PX + 0.01f)
            assertTrue("y $y", y >= insets.top + RouteMapMath.FIT_MARGIN_PX - 0.01f && y <= 640f - insets.bottom - RouteMapMath.FIT_MARGIN_PX + 0.01f)
        }
    }

    @Test
    fun `clipping keeps the inside ends exactly and the direction of a cut line`() {
        val box = MapBox(0f, 0f, 100f, 100f)
        assertArrayEquals(floatArrayOf(10f, 10f, 90f, 90f), RouteMapMath.clipSegment(10f, 10f, 90f, 90f, box), 0f)
        assertArrayEquals(floatArrayOf(50f, 50f, 100f, 50f), RouteMapMath.clipSegment(50f, 50f, 150f, 50f, box), 0.001f)
        assertNull(RouteMapMath.clipSegment(150f, 0f, 150f, 100f, box))
        // A diagonal entering through one side keeps its slope.
        val cut = requireNotNull(RouteMapMath.clipSegment(-50f, 0f, 50f, 100f, box))
        assertArrayEquals(floatArrayOf(0f, 50f, 50f, 100f), cut, 0.001f)
        assertEquals(1f, (cut[3] - cut[1]) / (cut[2] - cut[0]), 0.001f)

        // A polyline that leaves and comes back is two runs, nothing drawn outside.
        val runs = RouteMapMath.clipPolyline(floatArrayOf(10f, 10f, 50f, 10f, 150f, 10f, 150f, 90f, 50f, 90f), box)
        assertEquals(2, runs.size)
        assertArrayEquals(floatArrayOf(10f, 10f, 50f, 10f, 100f, 10f), runs[0], 0.001f)
        assertArrayEquals(floatArrayOf(100f, 90f, 50f, 90f), runs[1], 0.001f)
        runs.forEach { run -> run.forEach { assertTrue(it in 0f..100f) } }
    }

    @Test
    fun `clipping under any rotation never draws past the view's margin`() {
        val g = geometry()
        val box = RouteMapMath.clipBox(259f, 190f, 8f)
        for (heading in 0 until 360 step 15) {
            val style = RouteMapMath.styleOf(g, MapView.FOLLOW, heading.toFloat(), false)
            val t = RouteMapMath.transform(g, MapForm.MINIMAP, MapView.FOLLOW, style, heading.toFloat(), 259f, 190f)
            val street = floatArrayOf(t.x(0f, 500f), t.y(0f, 500f), t.x(1000f, 500f), t.y(1000f, 500f))
            RouteMapMath.clipPolyline(street, box).flatMap { it.toList() }.chunked(2).forEach { (x, y) ->
                assertTrue("heading $heading x $x", x >= box.left - 0.01f && x <= box.right + 0.01f)
                assertTrue("heading $heading y $y", y >= box.top - 0.01f && y <= box.bottom + 0.01f)
            }
        }
    }

    @Test
    fun `the scale bar is the largest round distance that fits`() {
        // 500 m over 1000 units at 0.56 px a unit: 0.89 m a pixel.
        val bar = requireNotNull(RouteMapMath.scaleBar(500.0, 0.56f, 441f * RouteMapMath.SCALE_BAR_FRACTION))
        assertEquals(50, bar.meters)
        assertEquals("50 m", bar.label)
        assertEquals(50 / (500.0 / 1000 / 0.56), bar.lengthPx.toDouble(), 0.01)
        assertTrue(bar.lengthPx <= 441f * RouteMapMath.SCALE_BAR_FRACTION)
        assertEquals("2 km", requireNotNull(RouteMapMath.scaleBar(20_000.0, 0.5f, 100f)).label)
        assertNull(RouteMapMath.scaleBar(0.0, 0.5f, 100f))
        assertNull(RouteMapMath.scaleBar(500.0, 0.5f, 0f))
    }

    @Test
    fun `street names are placed by priority, whole, clear of the furniture and of each other`() {
        val bounds = MapBox(0f, 0f, 371f, 300f)
        val compass = MapBox(330f, 0f, 371f, 50f)
        val labels = listOf(
            MapLabel("Bd Sébastopol", 1, 100f, 100f, 90f, 13f),
            MapLabel("Rue de Rivoli", 0, 120f, 104f, 90f, 13f),
            MapLabel("Rue Saint-Denis", 2, 200f, 200f, 100f, 13f),
            MapLabel("Quai", 0, 350f, 20f, 30f, 13f),
            MapLabel("Rue Edge", 0, 5f, 150f, 60f, 13f),
        )
        val placed = RouteMapMath.placeLabels(labels, MapForm.PANEL.maxLabels, bounds, listOf(compass))
        // Rivoli first; Sébastopol overlaps it; Quai sits on the compass; Edge is cut by the frame.
        assertEquals(listOf("Rue de Rivoli", "Rue Saint-Denis"), placed.map { it.text })
        assertTrue(RouteMapMath.placeLabels(labels, MapForm.MINIMAP.maxLabels, bounds, emptyList()).isEmpty())
        assertTrue(RouteMapMath.placeLabels(labels, MapForm.COMPACT.maxLabels, bounds, emptyList()).isEmpty())
        assertEquals(2, MapForm.PANEL.maxLabels)
        assertFalse(MapForm.FULL.maxLabels < MapForm.PANEL.maxLabels)
    }

    @Test
    fun `headings are kept in one turn`() {
        assertEquals(350f, RouteMapMath.normalized(-10f), 0f)
        assertEquals(10f, RouteMapMath.normalized(370f), 0f)
        assertEquals(0f, RouteMapMath.normalized(360f), 0f)
    }
}
