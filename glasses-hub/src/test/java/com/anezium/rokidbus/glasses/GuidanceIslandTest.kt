package com.anezium.rokidbus.glasses

import com.anezium.rokidbus.glasses.input.LauncherBackend
import com.anezium.rokidbus.glasses.page.HudGlyphs
import com.anezium.rokidbus.glasses.page.IslandForm
import com.anezium.rokidbus.glasses.page.MapForm
import com.anezium.rokidbus.shared.ActivitySurfaceContent
import com.anezium.rokidbus.shared.AmbientSize
import com.anezium.rokidbus.shared.PinSurfacePosition
import com.anezium.rokidbus.shared.RouteGeometry
import com.anezium.rokidbus.shared.RoutePath
import com.anezium.rokidbus.shared.RoutePathRole
import com.anezium.rokidbus.shared.RoutePoint
import com.anezium.rokidbus.shared.RoutePosition
import com.anezium.rokidbus.shared.RoutePositionQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guidance islands (plan 028a §8.1) and the launcher backend precedence (§8.3). */
class GuidanceIslandTest {
    @Test
    fun `an owner without a size or live geometry keeps the existing island`() {
        assertNull(GuidanceIslands.of(item(ActivityPresentation.PANEL, size = null), geometry = null, headingDeg = 30f))
    }

    @Test
    fun `an explicit size is drawn as is, an automatic one follows the collapse`() {
        val map = geometry(RoutePositionQuality.LIVE)
        assertEquals(IslandForm.PANEL, GuidanceIslands.of(item(ActivityPresentation.CHIP, AmbientSize.PANEL), map, 30f)?.form)
        assertEquals(IslandForm.TEXT, GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.TEXT), map, 30f)?.form)
        assertEquals(IslandForm.MINIMAP, GuidanceIslands.of(item(ActivityPresentation.PANEL, null), map, 30f)?.form)
        assertEquals(IslandForm.TEXT, GuidanceIslands.of(item(ActivityPresentation.CHIP, null), map, 30f)?.form)
        // A size without geometry is guidance text with no map.
        val noMap = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), null, null)!!
        assertNull(noMap.content.map)
    }

    @Test
    fun `a live fix draws heading-up, a lost one north-up with the lost glyph and approximate figures`() {
        val live = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.MINIMAP), geometry(RoutePositionQuality.LIVE), 30f)!!
        assertEquals(30f, live.content.map?.headingDeg)
        assertEquals(MapForm.MINIMAP, live.content.map?.form)
        assertFalse(live.content.approximate)
        assertEquals("straight", live.content.glyph)

        val lost = GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.PANEL), geometry(RoutePositionQuality.STALE), 30f)!!
        assertEquals(HudGlyphs.GPS_LOST, lost.content.glyph)
        assertTrue(lost.content.approximate)
        assertEquals(true, lost.content.map?.degraded)
        assertNull(lost.content.map?.headingDeg)
        assertEquals(MapForm.PANEL, lost.content.map?.form)

        // The text form draws no map at all.
        assertNull(GuidanceIslands.of(item(ActivityPresentation.PANEL, AmbientSize.TEXT), geometry(RoutePositionQuality.LIVE), 30f)!!.content.map)
    }

    @Test
    fun `the phone's explicit backend outranks the glasses' stored one, which outranks the default`() {
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = "session", stored = "LEGACY", bundleNegotiated = true))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = "legacy", stored = "SESSION", bundleNegotiated = true))
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = null, stored = "SESSION", bundleNegotiated = false))
        assertEquals(LauncherBackend.SESSION, LauncherBackend.resolve(phone = "unknown", stored = "SESSION", bundleNegotiated = true))
    }

    @Test
    fun `nobody's choice keeps the legacy default in this lane, negotiated or not`() {
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.DEFAULT)
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = null, bundleNegotiated = true))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = null, bundleNegotiated = false))
        assertEquals(LauncherBackend.LEGACY, LauncherBackend.resolve(phone = null, stored = "garbage", bundleNegotiated = true))
        assertNull(LauncherBackend.fromPhone(null))
    }

    private fun item(presentation: ActivityPresentation, size: AmbientSize?) = ActivityRenderItem(
        activity = NexusActivitySurface(
            surfaceId = "maps:activity",
            ownerPluginId = "maps",
            seq = 1,
            content = ActivitySurfaceContent(
                glyph = "straight",
                primary = "120 m",
                secondary = "Rue de Rivoli",
                progress = null,
                eta = "09:52",
                detail = listOf("then left"),
                actions = emptyList(),
                maxDurationMs = null,
            ),
            corner = PinSurfacePosition.TOP_LEFT,
            startedOrder = 1,
            lastUpdatedOrder = 1,
            lastSignificantOrder = null,
            collapseAtMs = 0,
            maxDurationDeadlineMs = null,
            selectedActionIndex = -1,
            motionToken = 1,
        ),
        primary = true,
        presentation = presentation,
        size = size,
    )

    private fun geometry(quality: RoutePositionQuality) = RouteGeometry(
        spanMeters = 400.0,
        paths = listOf(RoutePath(RoutePathRole.ROUTE, listOf(RoutePoint(500, 1000), RoutePoint(500, 0)))),
        position = RoutePosition(quality, RoutePoint(500, 660), fixAgeS = if (quality == RoutePositionQuality.LIVE) null else 9),
    )
}
